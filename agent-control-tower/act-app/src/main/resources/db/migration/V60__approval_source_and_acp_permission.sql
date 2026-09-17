-- V60: approval provenance plus the ACP permission companion record. A permission ask raised by
-- the Qoder CLI over ACP becomes a first-class governance row (approvals.source = ACP_PERMISSION)
-- so the existing review/decision surfaces can govern it; this migration only adds the storage.
-- Deliberate DDL choices:
-- 1. ALTER TABLE approvals ADD COLUMN source NOT NULL DEFAULT 'LEGACY_GATE': every row written
--    before this migration (and every legacy gate path) must read back as LEGACY_GATE, so the
--    default is enforced by the database, not only by the entity.
-- 2. FK (approval_id) REFERENCES approvals(id): the companion shares the approval's primary key
--    and lifecycle; the FK makes an orphaned companion impossible. It also forces the housekeeping
--    run purge to delete companion rows before the approvals they reference.
-- 3. UNIQUE (bridge_session_id, bridge_request_id): the ACP bridge correlates a permission ask by
--    session + request id. The constraint makes duplicate delivery of the same ask impossible at
--    the database level (a losing concurrent insert fails with a DataIntegrityViolationException
--    instead of creating a second approval).
-- 4. TIMESTAMP (not TIMESTAMPTZ): matches the rest of the schema; H2 MODE=MySQL (h2 profile / CI)
--    and MariaDB both reject TIMESTAMPTZ.
-- 5. Table name is singular by design contract (acp_permission_request); other tables in this
--    schema are mostly plural.
ALTER TABLE approvals ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'LEGACY_GATE';

CREATE TABLE acp_permission_request (
    approval_id        UUID PRIMARY KEY,
    run_id             UUID NOT NULL,
    agent_id           UUID,
    bridge_session_id  VARCHAR(128) NOT NULL,
    bridge_request_id  VARCHAR(128) NOT NULL,
    tool_call_id       VARCHAR(128),
    tool_name          VARCHAR(256) NOT NULL,
    options_json       TEXT,
    request_digest     VARCHAR(128) NOT NULL,
    display_json       TEXT,
    expires_at         TIMESTAMP NOT NULL,
    delivery_state     VARCHAR(32) NOT NULL,
    selected_option_id VARCHAR(64),
    delivered_at       TIMESTAMP,
    CONSTRAINT fk_acp_permission_approval FOREIGN KEY (approval_id) REFERENCES approvals(id),
    CONSTRAINT uq_acp_permission_correlation UNIQUE (bridge_session_id, bridge_request_id)
);
