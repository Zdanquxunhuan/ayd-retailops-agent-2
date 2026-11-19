package com.lbaby.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
class RepairRequests {
    static final String ACTION = "REPUSH_FINISHED_ORDER_TO_POS";
    enum Status { AWAITING_CONFIRMATION, INVALIDATED, PENDING_EXECUTION, EXECUTING, NEEDS_VERIFICATION, VERIFIED, BLOCKED }
    record Target(String channel, String orderId, String action) {}
    record Attempt(UUID id, UUID caseId, String environment, String storeId, Target target, String requestedBy,
                   Instant createdAt, Instant expiresAt, Status status, String reason, String confirmedBy,
                   Instant confirmedAt, List<CaseEvidence.Item> applicationEvidence,
                   List<CaseEvidence.Item> confirmationEvidence, RepairExecution.Result execution) {
        public String getHistoricalInvestigations() { return "/cases/" + caseId + "/investigations"; }
    }

    static class Ineligible extends RuntimeException {
        final RepairEligibility.Assessment assessment;
        Ineligible(RepairEligibility.Assessment assessment) { this.assessment = assessment; }
    }

    static class PermissionRevoked extends ResponseStatusException {
        PermissionRevoked() { super(HttpStatus.FORBIDDEN); }
    }

    private final JdbcTemplate db;
    private final IncidentCases cases;
    private final RepairEligibility eligibility;
    private final CaseEvidence evidence;
    private final String environment;
    private final Duration ttl;

    RepairRequests(JdbcTemplate db, IncidentCases cases, RepairEligibility eligibility, CaseEvidence evidence,
                   @Value("${repair.fixture-environment:local}") String fixtureEnvironment,
                   @Value("${repair.confirmation-ttl:PT10M}") Duration ttl, SandboxPos sandbox) {
        if (!fixtureEnvironment.matches("[a-zA-Z0-9_-]{1,48}") || ttl.isZero() || ttl.isNegative())
            throw new IllegalArgumentException("Invalid repair environment or confirmation TTL");
        this.db = db;
        this.cases = cases;
        this.eligibility = eligibility;
        this.evidence = evidence;
        this.environment = sandbox.enabled() ? sandbox.environment() : "fixture:" + fixtureEnvironment;
        this.ttl = ttl;
    }

    boolean permitted(DemoIdentity actor) {
        // Serialize a confirmation against changes to this operator's grant.
        return db.query("""
                SELECT enabled,can_request_repair FROM demo_operator_grant
                WHERE operator_name=? AND store_id=? FOR UPDATE
                """, (r,i) -> r.getBoolean(1) && r.getBoolean(2), actor.name(), actor.storeId())
                .stream().findFirst().orElse(false);
    }

    @Transactional
    public Attempt create(UUID caseId, DemoIdentity actor) {
        var scope = cases.scope(caseId, actor);
        if (!permitted(actor)) throw new PermissionRevoked();
        var assessment = eligibility.check(scope);
        if (!assessment.isEligible()) throw new Ineligible(assessment);
        var now = Instant.now();
        var existing = db.query("""
                SELECT id FROM repair_attempt WHERE environment=? AND case_id=? AND requested_by=?
                AND channel=? AND order_id=? AND action=? AND
                ((status='PENDING_EXECUTION' AND NOT EXISTS (SELECT 1 FROM repair_execution e
                WHERE e.attempt_id=repair_attempt.id AND e.phase IN ('BLOCKED','VERIFIED')))
                OR (status='AWAITING_CONFIRMATION' AND expires_at>?))
                ORDER BY created_at DESC FETCH FIRST 1 ROW ONLY
                """, (r,i) -> r.getObject(1,UUID.class), environment,caseId,actor.name(),scope.channel(),
                scope.orderId(),ACTION,now);
        if (!existing.isEmpty()) return load(caseId,existing.getFirst(),actor);
        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO repair_attempt(id,case_id,environment,channel,order_id,store_id,action,requested_by,
                created_at,expires_at,status) VALUES(?,?,?,?,?,?,?,?,?,?,'AWAITING_CONFIRMATION')
                """, id,caseId,environment,scope.channel(),scope.orderId(),scope.storeId(),ACTION,actor.name(),
                now,now.plus(ttl));
        eligibility.save(caseId, id + "/apply", assessment);
        return load(caseId,id,actor);
    }

    @Transactional(noRollbackFor = PermissionRevoked.class)
    public Attempt confirm(UUID caseId, UUID id, DemoIdentity actor, Target reviewed) {
        if (!permitted(actor)) {
            db.update("""
                    UPDATE repair_attempt SET status='INVALIDATED',reason='PERMISSION_REVOKED'
                    WHERE environment=? AND case_id=? AND id=? AND store_id=? AND requested_by=?
                    AND status='AWAITING_CONFIRMATION'
                    """, environment,caseId,id,actor.storeId(),actor.name());
            throw new PermissionRevoked();
        }
        var scope = cases.scope(caseId, actor);
        var attempt = load(caseId,id,actor);
        if (!attempt.requestedBy().equals(actor.name())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (!attempt.target().equals(reviewed)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Target mismatch");
        if (attempt.status() != Status.AWAITING_CONFIRMATION) return attempt;
        if (!reviewed.orderId().equals(scope.orderId()) || !reviewed.channel().equals(scope.channel())) {
            invalidate(id,"ORDER_SCOPE_CHANGED");
        } else {
            var assessment = eligibility.check(scope);
            eligibility.save(caseId, id + "/confirm", assessment);
            var now = Instant.now();
            if (!now.isBefore(attempt.expiresAt())) invalidate(id,"EXPIRED");
            else if (!assessment.isEligible()) invalidate(id,assessment.reason());
            else db.update("""
                    UPDATE repair_attempt SET status='PENDING_EXECUTION',confirmed_by=?,confirmed_at=?
                    WHERE id=?
                    """, actor.name(),now,id);
        }
        return load(caseId,id,actor);
    }

    private void invalidate(UUID id, String reason) {
        db.update("UPDATE repair_attempt SET status='INVALIDATED',reason=? WHERE id=?",reason,id);
    }

    @Transactional
    public Attempt read(UUID caseId, UUID id, DemoIdentity actor) {
        cases.scope(caseId,actor);
        return load(caseId,id,actor);
    }

    @Transactional
    public List<Attempt> list(UUID caseId, DemoIdentity actor) {
        cases.scope(caseId,actor);
        var ids = db.query("SELECT id FROM repair_attempt WHERE environment=? AND case_id=? ORDER BY created_at,id",
                (r,i) -> r.getObject(1,UUID.class),environment,caseId);
        return ids.stream().map(id -> load(caseId,id,actor)).toList();
    }

    private Attempt load(UUID caseId, UUID id, DemoIdentity actor) {
        var now = Instant.now();
        db.update("""
                UPDATE repair_attempt SET status='INVALIDATED',
                reason=CASE WHEN expires_at<=? THEN 'EXPIRED' ELSE 'PERMISSION_REVOKED' END
                WHERE environment=? AND case_id=? AND id=? AND store_id=? AND status='AWAITING_CONFIRMATION'
                AND (expires_at<=? OR NOT EXISTS (SELECT 1 FROM demo_operator_grant g
                    WHERE g.operator_name=repair_attempt.requested_by AND g.store_id=repair_attempt.store_id
                    AND g.enabled AND g.can_request_repair))
                """,now,environment,caseId,id,actor.storeId(),now);
        return db.query("""
                SELECT * FROM repair_attempt WHERE environment=? AND case_id=? AND id=? AND store_id=? FOR UPDATE
                """, this::map,environment,caseId,id,actor.storeId()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Attempt map(ResultSet row, int ignored) throws SQLException {
        var id = row.getObject("id",UUID.class);
        var caseId = row.getObject("case_id",UUID.class);
        var confirmed = row.getTimestamp("confirmed_at");
        var execution = db.query("SELECT * FROM repair_execution WHERE attempt_id=?", (r,i) ->
                new RepairExecution.Result(Status.valueOf(r.getString("phase")),r.getString("precondition"),
                        r.getString("call_result"),r.getString("pos_result"),r.getObject("checked_at",java.time.OffsetDateTime.class),
                        r.getString("checked_by")),id).stream().findFirst().orElse(null);
        return new Attempt(id,caseId,row.getString("environment"),row.getString("store_id"),
                new Target(row.getString("channel"),row.getString("order_id"),row.getString("action")),
                row.getString("requested_by"),row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("expires_at").toInstant(),execution == null ? Status.valueOf(row.getString("status")) : execution.phase(),
                row.getString("reason"),row.getString("confirmed_by"),confirmed == null ? null : confirmed.toInstant(),
                evidence.snapshots(caseId,id + "/apply"),evidence.snapshots(caseId,id + "/confirm"),execution);
    }
}
