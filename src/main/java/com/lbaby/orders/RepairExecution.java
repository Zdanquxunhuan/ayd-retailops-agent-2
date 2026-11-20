package com.lbaby.orders;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@org.springframework.context.annotation.Profile("!pos-shadow")
class RepairExecution {
    record Result(RepairRequests.Status phase, String precondition, String callResult, String posResult,
                  OffsetDateTime checkedAt, String checkedBy) {}
    private record Job(UUID caseId, UUID id, DemoIdentity actor) {}
    private record Binding(String order, String channel, Instant expires, String action) {}

    private final RepairRequests repairs;
    private final IncidentCases cases;
    private final RepairEligibility eligibility;
    private final OrderSources orders;
    private final SynchronousRepair writer;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final CaseEvidence evidence;

    RepairExecution(RepairRequests repairs, IncidentCases cases, RepairEligibility eligibility, OrderSources orders,
                    SynchronousRepair writer, JdbcTemplate db, TransactionTemplate tx,
                    CaseEvidence evidence) {
        this.repairs=repairs; this.cases=cases; this.eligibility=eligibility; this.orders=orders;
        this.writer=writer; this.db=db; this.tx=tx;
        this.evidence=evidence;
    }

    RepairRequests.Attempt afterConfirmation(RepairRequests.Attempt attempt, DemoIdentity actor) {
        if (!writer.enabled() || attempt.status()!=RepairRequests.Status.PENDING_EXECUTION) return attempt;
        dispatch(new Job(attempt.caseId(),attempt.id(),actor));
        return repairs.read(attempt.caseId(),attempt.id(),actor);
    }

    private void dispatch(Job job) {
        String order;
        try {
            order=tx.execute(s -> reserve(job,false));
        } catch (DuplicateKeyException competingOrder) {
            // The unique active order is held by another case. This application cannot be replayed later.
            tx.executeWithoutResult(s -> reserve(job,true));
            return;
        }
        if (order==null) return;
        SynchronousRepair.Reply reply;
        try { reply=writer.pushConfirmed(job.id(),order); }
        catch (RuntimeException uncertain) { reply=SynchronousRepair.Reply.UNKNOWN; }
        db.update("UPDATE repair_execution SET call_result=? WHERE attempt_id=?",reply.name(),job.id());
        tx.executeWithoutResult(s -> reconcile(job,job.actor().name()));
    }

    private String reserve(Job job, boolean competing) {
        boolean allowed=repairs.permitted(job.actor());
        var rows=db.query("""
                SELECT * FROM repair_attempt WHERE id=? AND case_id=? AND environment=? AND confirmed_by=?
                AND store_id=? AND status='PENDING_EXECUTION' FOR UPDATE
                """,(r,i) -> new Binding(r.getString("order_id"),r.getString("channel"),
                        r.getTimestamp("expires_at").toInstant(),r.getString("action")),job.id(),job.caseId(),
                writer.environment(),job.actor().name(),job.actor().storeId());
        if (rows.isEmpty() || hasExecution(job.id())) return null;
        var data=rows.getFirst();
        String order=data.order(), channel=data.channel(), reason;
        if (!allowed) reason="PERMISSION_REVOKED";
        else if (competing) reason="ANOTHER_ACTIVE_REPAIR";
        else if (!Instant.now().isBefore(data.expires())) reason="EXPIRED";
        else {
            var scope=cases.scope(job.caseId(),job.actor());
            if (!order.equals(scope.orderId()) || !channel.equals(scope.channel()) || !RepairRequests.ACTION.equals(data.action()))
                reason="ORDER_SCOPE_CHANGED";
            else {
                var check=eligibility.check(scope);
                eligibility.save(job.caseId(),job.id()+"/before-execution",check);
                reason=check.reason();
            }
        }
        boolean invoke=reason.equals("MISSING_CANDIDATE"), found=reason.equals("POS_PRESENT");
        String phase=invoke ? "EXECUTING" : found ? "VERIFIED" : "BLOCKED";
        db.update("""
                INSERT INTO repair_execution(attempt_id,environment,channel,active_order,phase,precondition,
                call_result,pos_result,checked_at,checked_by) VALUES(?,?,?,?,?,?,?,?,?,?)
                """,job.id(),writer.environment(),channel,invoke ? order : null,phase,reason,
                invoke ? "UNKNOWN" : "SKIPPED",found ? "FOUND" : null,Instant.now(),job.actor().name());
        return invoke ? order : null;
    }

    private boolean hasExecution(UUID id) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM repair_execution WHERE attempt_id=?)",
                Boolean.class,id));
    }

    RepairRequests.Attempt verify(UUID caseId, UUID id, DemoIdentity actor) {
        var record=repairs.read(caseId,id,actor);
        if (record.execution()==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"No execution to verify");
        tx.executeWithoutResult(s -> reconcile(new Job(caseId,id,actor),actor.name()));
        return repairs.read(caseId,id,actor);
    }

    private void reconcile(Job job, String reviewer) {
        var active=db.query("""
                SELECT active_order FROM repair_execution WHERE attempt_id=? AND environment=?
                AND active_order IS NOT NULL FOR UPDATE
                """,(r,i) -> r.getString(1),job.id(),writer.environment());
        if (active.isEmpty()) return;
        String order=active.getFirst();
        String pos;
        try {
            pos=orders.storeOf(order).filter(job.actor().storeId()::equals).isPresent()
                    ? orders.queryPos(order).name() : "ORDER_SCOPE_CHANGED";
        } catch (RuntimeException unavailable) { pos="UNAVAILABLE"; }
        boolean found=pos.equals("FOUND");
        var now=Instant.now();
        evidence.save(job.caseId(),UUID.randomUUID().toString(),writer.evidenceSource(),"VERIFICATION",
                job.id().toString(),null,now,reviewer+" / "+pos);
        db.update("""
                UPDATE repair_execution SET phase=?,active_order=?,pos_result=?,checked_at=?,checked_by=?
                WHERE attempt_id=?
                """,found ? "VERIFIED" : "NEEDS_VERIFICATION",found ? null : order,pos,now,reviewer,job.id());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        if (!writer.enabled()) return;
        var jobs=db.query("""
                SELECT a.* FROM repair_attempt a LEFT JOIN repair_execution e ON a.id=e.attempt_id
                WHERE a.environment=? AND a.status='PENDING_EXECUTION' AND
                (e.attempt_id IS NULL OR e.active_order IS NOT NULL)
                """,(r,i) -> new Job(r.getObject("case_id",UUID.class),r.getObject("id",UUID.class),
                        new DemoIdentity(r.getString("confirmed_by"),r.getString("store_id"))),writer.environment());
        for (var job : jobs) {
            if (hasExecution(job.id())) tx.executeWithoutResult(s -> reconcile(job,writer.environment().split(":",2)[0]+"-recovery"));
            else dispatch(job);
        }
    }
}
