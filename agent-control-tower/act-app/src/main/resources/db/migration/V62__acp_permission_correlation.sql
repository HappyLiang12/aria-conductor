-- Cross-core Host/Sandbox execution, migration 4 of the reserved V59-V62 set
-- (spec 3.2, 6.3 "permission normalization"): the run-scoped permission ledger.
-- Additive only; no applied migration is touched and no data is purged here.
--
-- One table holds both ledger row kinds so every run-scoped permission record
-- has exactly one purge registration:
--   * NATIVE_PERMISSION - a normalized permission request raised by a core
--     session or the platform boundary. Its correlation (run, session, request)
--     is unique and persisted BEFORE the ask is displayed; the offered options
--     and the normalized target are stored, and the argument document is
--     bound by the digest column (never by an option's position).
--   * WRITE_GRANT - a one-use authorization for exactly one matching platform
--     MCP call. It uses the platform boundary's own correlation key
--     ('platform-mcp' / 'grant:<arguments digest>'), so the same unique
--     constraint also makes a second outstanding authorization for the
--     identical call impossible; consumed_at is set exactly once.
--
-- target/delivery_state are plain names because those enums are execution-module
-- types and act-common never depends downstream. Unknown usage is not invented;
-- expiry is enforced on every read/decision, not only by the scheduled sweep.
--
-- No FK to runs(id): the ledger is a run-scoped child table and follows the
-- deliberate pattern of V51/V59 (run_progress_events, run_execution_bindings) —
-- HousekeepingService.purgeRuns deletes runs children first with a
-- hand-maintained list, so the registered purge path (deleteByRunIdInBulk) is
-- the deletion route; a FK would only break purge chunks, never remove rows.
CREATE TABLE acp_permission_request (
    id                 UUID          NOT NULL PRIMARY KEY,
    approval_id        UUID,
    kind               VARCHAR(24)   NOT NULL,
    run_id             UUID          NOT NULL,
    session_id         VARCHAR(128)  NOT NULL,
    request_id         VARCHAR(128)  NOT NULL,
    tool_name          VARCHAR(256)  NOT NULL,
    target             VARCHAR(32)   NOT NULL,
    arguments_digest   VARCHAR(128)  NOT NULL,
    arguments_json     TEXT,
    options_json       TEXT,
    expires_at         TIMESTAMP     NOT NULL,
    delivery_state     VARCHAR(32)   NOT NULL,
    selected_option_id VARCHAR(64),
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         TIMESTAMP     NOT NULL,
    decided_at         TIMESTAMP,
    delivered_at       TIMESTAMP,
    consumed_at        TIMESTAMP,
    CONSTRAINT uq_acp_permission_correlation UNIQUE (run_id, session_id, request_id)
);
CREATE INDEX idx_acp_permission_run ON acp_permission_request (run_id);
CREATE INDEX idx_acp_permission_approval ON acp_permission_request (approval_id);
