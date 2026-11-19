package com.lbaby.orders;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
class IncidentCases {
    record Scope(String orderId, String storeId, String channel) {}
    record Incident(UUID id, String channel, String orderId, String storeId, String createdBy,
                    String goal, Instant createdAt, RuleBaseline.Outcome outcome,
                    List<RuleBaseline.Observation> observations, List<CaseEvidence.Item> evidence) {
        public java.util.Map<String,String> getActions() {
            String path = "/cases/" + id;
            return java.util.Map.of("checkStatus",path + "/repair-eligibility",
                    "investigate",path + "/investigations","requestRepair",path + "/repair-attempts");
        }
    }

    private final OrderSources sources;
    private final RuleBaseline baseline;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    private final CaseEvidence evidence;

    IncidentCases(OrderSources sources, RuleBaseline baseline, JdbcTemplate jdbc, ObjectMapper mapper,
                  CaseEvidence evidence) {
        this.sources = sources;
        this.baseline = baseline;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.evidence = evidence;
    }

    @Transactional
    public Incident create(String orderId, DemoIdentity actor) {
        checkGrant(actor);
        String store = sources.storeOf(orderId).filter(actor.storeId()::equals)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var result = baseline.evaluate(orderId, store);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO incident_case
                (id, channel, order_id, store_id, created_by, goal, outcome, observations, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, "MEITUAN", orderId, store, actor.name(), "CHECK_POS_STATUS",
                result.outcome().name(), encode(result.observations()), Instant.now().truncatedTo(ChronoUnit.MICROS));
        return read(id, actor);
    }

    @Transactional
    public Incident read(UUID id, DemoIdentity actor) {
        checkGrant(actor);
        return jdbc.query("SELECT * FROM incident_case WHERE id = ? AND store_id = ?", (row, i) -> {
                var observations = decode(row.getString("observations"));
                return new Incident(id, row.getString("channel"), row.getString("order_id"), row.getString("store_id"),
                        row.getString("created_by"), row.getString("goal"), row.getTimestamp("created_at").toInstant(),
                        RuleBaseline.Outcome.valueOf(row.getString("outcome")), observations, timeline(id, observations));
                },
                id, actor.storeId()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    Scope scope(UUID id, DemoIdentity actor) {
        checkGrant(actor);
        return jdbc.query("SELECT order_id,store_id,channel FROM incident_case WHERE id=? AND store_id=?",
                (row, i) -> new Scope(row.getString("order_id"), row.getString("store_id"), row.getString("channel")), id, actor.storeId())
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void checkGrant(DemoIdentity actor) {
        Boolean active = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM demo_operator_grant WHERE operator_name=? AND store_id=? AND enabled)
                """, Boolean.class,actor.name(),actor.storeId());
        if (!Boolean.TRUE.equals(active)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    // V1.1 retained the original snapshots but had no evidence table.
    private List<CaseEvidence.Item> timeline(UUID caseId, List<RuleBaseline.Observation> observations) {
        var items = evidence.timeline(caseId);
        if (items.stream().anyMatch(item -> item.kind().equals("SNAPSHOT"))) return items;
        for (var obs : observations) {
            evidence.save(caseId, "initial", obs.source(), "SNAPSHOT", null, null, obs.observedAt(), obs.status());
        }
        return evidence.timeline(caseId);
    }

    private String encode(List<RuleBaseline.Observation> observations) {
        try {
            return mapper.writeValueAsString(observations);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot save observations", e);
        }
    }

    private List<RuleBaseline.Observation> decode(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read observations", e);
        }
    }
}
