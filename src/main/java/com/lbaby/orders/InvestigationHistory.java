package com.lbaby.orders;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
class InvestigationHistory {
    record Step(int number, String action, String argumentHash, String result, List<UUID> evidenceIds,
                long elapsedMs, int tokens, String operator) {}
    record Report(UUID id, UUID caseId, String status, String answer, List<UUID> citations, List<Step> steps) {}

    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    InvestigationHistory(JdbcTemplate db, ObjectMapper mapper) { this.db = db; this.mapper = mapper; }

    record Summary(UUID id, UUID parentId, String question, String status, String lastOperator, Integer version) {}
    record Claim(int turn, long allowance, RunMemory memory, String question) {}
    private record Stored(int version, long remaining, Instant lease, String json) {}

    @Transactional
    public UUID start(UUID caseId, String question, UUID parent, String operator, RunMemory memory) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO investigation(id,case_id,question,parent_id,last_operator,status,answer,citations) VALUES(?,?,?,?,?,'PAUSED','','[]')",
                id,caseId,question,parent,operator);
        db.update("INSERT INTO investigation_checkpoint(run_id,memory) VALUES(?,?)",id,encode(memory));
        return id;
    }

    List<Summary> list(UUID caseId) {
        return db.query("""
                SELECT i.*,c.version FROM investigation i LEFT JOIN investigation_checkpoint c ON c.run_id=i.id
                WHERE i.case_id=? ORDER BY i.id
                """,(r,i) -> new Summary(r.getObject("id",UUID.class),r.getObject("parent_id",UUID.class),
                r.getString("question"),r.getString("status"),r.getString("last_operator"),r.getObject("version",Integer.class)),caseId);
    }

    String question(UUID id) {
        return db.queryForObject("SELECT question FROM investigation WHERE id=?",String.class,id);
    }

    // Reserve a model turn before network I/O. A lost worker consumes its reservation.
    @Transactional
    public Claim claim(UUID caseId, UUID run, String operator, Integer expectedVersion) {
        var rows = db.query("""
                SELECT c.* FROM investigation_checkpoint c JOIN investigation i ON i.id=c.run_id
                WHERE i.case_id=? AND c.run_id=? FOR UPDATE
                """,(r,i) -> {
                    var lease=r.getTimestamp("lease_until");
                    return new Stored(r.getInt("version"),r.getLong("remaining_ms"),
                            lease==null ? null : lease.toInstant(),r.getString("memory"));
                },caseId,run);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"No V1.4 checkpoint");
        var row=rows.getFirst();
        int version=row.version();
        long remaining=row.remaining();
        var status=db.queryForObject("SELECT status FROM investigation WHERE id=?",String.class,run);
        if (!active(status)) return null;
        var now=Instant.now();
        if ((row.lease()!=null && row.lease().isAfter(now)) || (expectedVersion!=null && version!=expectedVersion))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Another worker owns this turn");
        RunMemory memory=decode(row.json(),RunMemory.class);
        if (version>=AgentRunner.MAX_TURNS || remaining<=0) {
            end(run,"HANDOFF","调查预算已用尽，请复核已提交的证据。",List.copyOf(memory.seen().keySet()));
            return null;
        }
        long allowance=Math.min(30000,remaining);
        db.update("UPDATE investigation_checkpoint SET version=?,remaining_ms=?,lease_until=? WHERE run_id=?",
                version+1,remaining-allowance,now.plusMillis(allowance+5000),run);
        db.update("UPDATE investigation SET last_operator=?,status='RUNNING' WHERE id=?",operator,run);
        return new Claim(version+1,allowance,memory,question(run));
    }

    static boolean active(String status) { return "PAUSED".equals(status) || "RUNNING".equals(status); }

    void fence(UUID run, int turn) {
        int version=db.queryForObject("SELECT version FROM investigation_checkpoint WHERE run_id=? FOR UPDATE",Integer.class,run);
        if (version!=turn) throw new ResponseStatusException(HttpStatus.CONFLICT,"Turn superseded");
    }

    void checkpoint(UUID run, Claim claim, long elapsed) {
        db.update("UPDATE investigation_checkpoint SET memory=?,lease_until=NULL,remaining_ms=remaining_ms+? WHERE run_id=?",
                encode(claim.memory()),Math.max(0,claim.allowance()-elapsed),run);
    }

    void step(UUID id, Step s) {
        db.update("INSERT INTO agent_step(run_id,step_no,details) VALUES(?,?,?)",id,s.number(),encode(s));
    }

    void end(UUID id,String status,String answer,List<UUID> citations) {
        db.update("UPDATE investigation SET status=?,answer=?,citations=? WHERE id=?",status,answer,encode(citations),id);
    }

    Report read(UUID caseId, UUID id) {
        return db.query("SELECT * FROM investigation WHERE case_id=? AND id=?", (r,i) ->
                new Report(id,caseId,r.getString("status"),r.getString("answer"),
                        List.of(decode(r.getString("citations"),UUID[].class)),
                        db.query("SELECT details FROM agent_step WHERE run_id=? ORDER BY step_no",
                                (s,n) -> decode(s.getString(1),Step.class),id)),caseId,id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private String encode(Object data) {
        try {return mapper.writeValueAsString(data);}
        catch(JsonProcessingException e) {throw new IllegalStateException(e);}
    }

    private <T> T decode(String json, Class<T> type) {
        try {return mapper.readValue(json,type);}
        catch(JsonProcessingException e) {throw new IllegalStateException(e);}
    }
}
