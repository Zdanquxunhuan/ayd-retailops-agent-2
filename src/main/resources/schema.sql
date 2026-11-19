CREATE TABLE IF NOT EXISTS incident_case (
    id UUID PRIMARY KEY,
    channel VARCHAR(32) NOT NULL,
    order_id VARCHAR(64) NOT NULL,
    store_id VARCHAR(32) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    goal VARCHAR(48) NOT NULL,
    outcome VARCHAR(48) NOT NULL,
    observations CLOB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS evidence (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES incident_case(id),
    source VARCHAR(40) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    request_ref VARCHAR(80),
    event_time TIMESTAMP(9) WITH TIME ZONE,
    collected_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    excerpt VARCHAR(600) NOT NULL
);
CREATE INDEX IF NOT EXISTS evidence_by_case ON evidence(case_id, event_time, collected_at);

CREATE TABLE IF NOT EXISTS investigation (
    id UUID PRIMARY KEY, case_id UUID NOT NULL REFERENCES incident_case(id),
    question VARCHAR(1600) NOT NULL, status VARCHAR(32) NOT NULL,
    answer VARCHAR(2400) NOT NULL, citations CLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS agent_step (
    run_id UUID NOT NULL REFERENCES investigation(id), step_no INT NOT NULL,
    details CLOB NOT NULL, PRIMARY KEY(run_id,step_no)
);
ALTER TABLE investigation ADD COLUMN IF NOT EXISTS parent_id UUID REFERENCES investigation(id);
ALTER TABLE investigation ADD COLUMN IF NOT EXISTS last_operator VARCHAR(64);
CREATE TABLE IF NOT EXISTS investigation_checkpoint (
    run_id UUID PRIMARY KEY REFERENCES investigation(id),
    version INT NOT NULL DEFAULT 0,
    remaining_ms BIGINT NOT NULL DEFAULT 90000,
    lease_until TIMESTAMP WITH TIME ZONE,
    memory CLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS demo_operator_grant (
    operator_name VARCHAR(64) PRIMARY KEY,
    store_id VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);
INSERT INTO demo_operator_grant(operator_name,store_id)
SELECT sample.operator_name,sample.store_id
FROM (VALUES ('duty-a','033101'),('next-shift-a','033101'),('duty-b','033102')) sample(operator_name,store_id)
WHERE NOT EXISTS (SELECT 1 FROM demo_operator_grant g WHERE g.operator_name=sample.operator_name);

ALTER TABLE demo_operator_grant ADD COLUMN IF NOT EXISTS can_request_repair BOOLEAN NOT NULL DEFAULT TRUE;
CREATE TABLE IF NOT EXISTS repair_attempt (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES incident_case(id),
    environment VARCHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    order_id VARCHAR(64) NOT NULL,
    store_id VARCHAR(32) NOT NULL,
    action VARCHAR(48) NOT NULL,
    requested_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('AWAITING_CONFIRMATION','PENDING_EXECUTION','INVALIDATED')),
    reason VARCHAR(48),
    confirmed_by VARCHAR(64),
    confirmed_at TIMESTAMP(9) WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS repair_by_case ON repair_attempt(environment,case_id,created_at);

CREATE TABLE IF NOT EXISTS sandbox_pos (
    environment VARCHAR(64) NOT NULL, order_id VARCHAR(64) NOT NULL,
    PRIMARY KEY(environment, order_id)
);
CREATE TABLE IF NOT EXISTS repair_execution (
    attempt_id UUID PRIMARY KEY REFERENCES repair_attempt(id),
    environment VARCHAR(64) NOT NULL, channel VARCHAR(32) NOT NULL,
    active_order VARCHAR(64), phase VARCHAR(32) NOT NULL,
    precondition VARCHAR(48) NOT NULL, call_result VARCHAR(32) NOT NULL,
    pos_result VARCHAR(32), checked_at TIMESTAMP WITH TIME ZONE, checked_by VARCHAR(64),
    UNIQUE(environment,channel,active_order)
);

CREATE TABLE IF NOT EXISTS shadow_work_order (
    id UUID PRIMARY KEY, order_id VARCHAR(64) NOT NULL, version INT NOT NULL
);
CREATE TABLE IF NOT EXISTS shadow_work_turn (
    case_id UUID NOT NULL REFERENCES shadow_work_order(id), turn_no INT NOT NULL,
    details CLOB NOT NULL, PRIMARY KEY(case_id,turn_no)
);
