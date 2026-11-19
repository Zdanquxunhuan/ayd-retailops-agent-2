package com.lbaby.orders;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
class OrderLogInvestigation {
    record Search(String source, Instant from, Instant until, Integer limit) {}
    enum Status { MATCHES, NO_MATCH, EXPIRED, TIMEOUT }
    record Result(Status status, boolean expired, boolean truncated, CaseEvidence.Item searchEvidence,
                  List<CaseEvidence.Item> events) {}

    private final IncidentCases cases;
    private final LogScenario scenario;
    private final OrderLogSource logs;
    private final CaseEvidence evidence;

    OrderLogInvestigation(IncidentCases cases, LogScenario scenario, OrderLogSource logs, CaseEvidence evidence) {
        this.cases = cases;
        this.scenario = scenario;
        this.logs = logs;
        this.evidence = evidence;
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Result search(UUID caseId, DemoIdentity actor, Search search, UUID parent) {
        var incident = cases.scope(caseId, actor);
        if (search.source() == null || !scenario.sources().contains(search.source())
                || search.from() == null || search.until() == null || !search.from().isBefore(search.until())
                || Duration.between(search.from(), search.until()).compareTo(scenario.maxWindow()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid source or time window");
        }
        int limit = search.limit() == null ? scenario.maxResults() : search.limit();
        if (limit < 1 || limit > scenario.maxResults()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String ref = null;
        if (parent != null) {
            if (!scenario.followEnabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            var anchor = evidence.read(caseId, parent);
            if (!anchor.kind().equals("LOG") || anchor.requestRef() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No request reference");
            }
            ref = anchor.requestRef();
        }

        var cutoff = scenario.retainedFrom();
        boolean expired = search.from().isBefore(cutoff);
        var events = new ArrayList<CaseEvidence.Item>();
        boolean truncated = false;
        var status = Status.EXPIRED;
        if (search.until().isAfter(cutoff)) {
            var page = logs.search(incident.orderId(), incident.storeId(), search.source(),
                    expired ? cutoff : search.from(), search.until(), ref, limit);
            truncated = page.truncated();
            if (page.timedOut()) {
                status = Status.TIMEOUT;
            } else {
                status = page.events().isEmpty() ? Status.NO_MATCH : Status.MATCHES;
                Instant collected = Instant.now();
                for (var event : page.events()) {
                    events.add(evidence.save(caseId, event.id(), search.source(), "LOG", event.requestRef(),
                            event.time(), collected, event.safeMessage()));
                }
            }
        }
        String summary = "%s [%s,%s) limit=%d expired=%s truncated=%s parent=%s".formatted(
                status, search.from(), search.until(), limit, expired, truncated, parent);
        var receipt = evidence.save(caseId, UUID.randomUUID().toString(), search.source(), "SEARCH", ref,
                null, Instant.now(), summary);
        return new Result(status, expired, truncated, receipt, List.copyOf(events));
    }
}
