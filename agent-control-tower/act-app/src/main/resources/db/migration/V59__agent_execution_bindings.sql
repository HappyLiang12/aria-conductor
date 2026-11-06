-- Cross-core Host/Sandbox execution (spec 3.1-3.2), migration 1 of the reserved
-- V59-V62 set. Additive only; no data purge and no applied migration is touched.
--
-- Agent execution selection: every column is nullable so legacy rows keep
-- explicitly unknown metadata instead of an inferred default. The single
-- deliberate backfill assigns the pre-existing OpenCode agents the mode they
-- already ran in (Sandbox); no other row is changed and no historical run core
-- is invented. adk_provider stays a plain string so an unapproved legacy
-- cleanup cannot block this upgrade.
ALTER TABLE agents ADD COLUMN execution_mode VARCHAR(16);
ALTER TABLE agents ADD COLUMN workspace_mode VARCHAR(16);
ALTER TABLE agents ADD COLUMN workspace_path TEXT;
ALTER TABLE agents ADD COLUMN workspace_base_ref VARCHAR(255);
UPDATE agents SET execution_mode = 'SANDBOX' WHERE adk_provider = 'opencode';

-- One immutable binding per run, frozen before launch and read by the running
-- task instead of re-resolving the agent's mutable settings. Only non-secret
-- data is stored: credential_ref is a reference resolved by the credential
-- service, and secret environments stay memory-only in the runtime carriers.
-- Unknown usage stays NULL (unknown is not zero), legacy runs get no row.
CREATE TABLE run_execution_bindings (
    run_id                     UUID          NOT NULL PRIMARY KEY,
    agent_id                   UUID          NOT NULL,
    core_id                    VARCHAR(64)   NOT NULL,
    execution_mode             VARCHAR(16)   NOT NULL,
    settings_json              TEXT          NOT NULL,
    workspace_kind             VARCHAR(32),
    workspace_lease_id         UUID,
    workspace_root             TEXT,
    workspace_source_root      TEXT,
    workspace_base_commit      VARCHAR(255),
    credential_ref             VARCHAR(255),
    configuration_revision     VARCHAR(128),
    deadline                   TIMESTAMP,
    runtime_environment_id     VARCHAR(255),
    runtime_ownership_identity VARCHAR(255),
    runtime_endpoint           VARCHAR(1024),
    runtime_version            VARCHAR(128),
    runtime_state              VARCHAR(32),
    usage_input_tokens         BIGINT,
    usage_output_tokens        BIGINT,
    usage_credits              DECIMAL(18,6),
    observed_model             VARCHAR(128),
    version                    BIGINT        NOT NULL DEFAULT 0,
    created_at                 TIMESTAMP     NOT NULL,
    updated_at                 TIMESTAMP
);
CREATE INDEX idx_run_bindings_agent ON run_execution_bindings (agent_id);
