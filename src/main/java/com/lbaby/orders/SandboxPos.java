package com.lbaby.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Synchronous domain call. Returning SUCCESS means the sandbox POS transaction has committed. */
interface SynchronousRepair {
    enum Reply { SUCCESS, DUPLICATE, TIMEOUT, UNKNOWN, ACKNOWLEDGED }
    boolean enabled();
    String environment();
    String evidenceSource();
    Reply pushConfirmed(java.util.UUID attempt, String orderId);
}

@Component
class SandboxPos implements SynchronousRepair {
    enum Scenario { NORMAL, LOST_REPLY, DUPLICATE_WITHOUT_POS, TIMEOUT_WITHOUT_WRITE }
    private final JdbcTemplate db;
    private final String environment;
    private final boolean enabled;
    private final Scenario scenario;

    SandboxPos(JdbcTemplate db, @Value("${repair.fixture-environment:local}") String name,
               @Value("${repair.sandbox-enabled:true}") boolean enabled,
               @Value("${repair.sandbox-response:NORMAL}") Scenario scenario) {
        this.db = db;
        this.environment = "sandbox:" + name;
        this.enabled = enabled;
        this.scenario = scenario;
    }

    public boolean enabled() { return enabled; }
    public String environment() { return environment; }

    boolean contains(String orderId) {
        return enabled && Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM sandbox_pos WHERE environment=? AND order_id=?)",
                Boolean.class,environment,orderId));
    }

    public String evidenceSource() { return "SANDBOX_POS"; }
    public Reply pushConfirmed(java.util.UUID attempt, String orderId) { return push(orderId); }

    public Reply push(String orderId) {
        if (!enabled) throw new IllegalStateException("Sandbox disabled");
        if (scenario == Scenario.DUPLICATE_WITHOUT_POS) return Reply.DUPLICATE;
        if (scenario == Scenario.TIMEOUT_WITHOUT_WRITE) return Reply.TIMEOUT;
        try {
            db.update("INSERT INTO sandbox_pos(environment,order_id) VALUES(?,?)",environment,orderId);
        } catch (DuplicateKeyException alreadyWritten) {
            return Reply.DUPLICATE;
        }
        return scenario == Scenario.LOST_REPLY ? Reply.TIMEOUT : Reply.SUCCESS;
    }
}
