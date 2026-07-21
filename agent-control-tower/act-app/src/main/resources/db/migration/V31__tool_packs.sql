-- V31: Tool packs (plugin system) + pack credentials + governance columns on tool_definitions

CREATE TABLE tool_packs (
    id              VARCHAR(36)  PRIMARY KEY,
    name            VARCHAR(255) NOT NULL UNIQUE,
    kind            VARCHAR(20)  NOT NULL DEFAULT 'HANDLER',
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    sandbox_mode    VARCHAR(20)  NOT NULL DEFAULT 'NONE',
    config          TEXT,
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_tool_packs_status ON tool_packs(status);
CREATE INDEX idx_tool_packs_enabled ON tool_packs(enabled);

CREATE TABLE pack_credentials (
    id              VARCHAR(36)  PRIMARY KEY,
    pack_id         VARCHAR(36)  NOT NULL,
    agent_id        VARCHAR(36),
    cred_key        VARCHAR(100) NOT NULL,
    enc_value       TEXT         NOT NULL,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pc_pack FOREIGN KEY (pack_id) REFERENCES tool_packs(id) ON DELETE CASCADE
);

CREATE INDEX idx_pack_cred_pack ON pack_credentials(pack_id);
CREATE INDEX idx_pack_cred_pack_agent ON pack_credentials(pack_id, agent_id);

-- Governance columns on tool_definitions (backward-compatible defaults)
ALTER TABLE tool_definitions ADD COLUMN IF NOT EXISTS pack_id VARCHAR(36);
ALTER TABLE tool_definitions ADD COLUMN IF NOT EXISTS kind VARCHAR(20) DEFAULT 'HANDLER';
ALTER TABLE tool_definitions ADD COLUMN IF NOT EXISTS risk_tier VARCHAR(20) DEFAULT 'READ';
ALTER TABLE tool_definitions ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'APPROVED';

-- Backfill: all existing tools behave as implicit core pack, HANDLER kind, READ risk, APPROVED
UPDATE tool_definitions SET kind = 'HANDLER' WHERE kind IS NULL;
UPDATE tool_definitions SET risk_tier = 'READ' WHERE risk_tier IS NULL;
UPDATE tool_definitions SET status = 'APPROVED' WHERE status IS NULL;

CREATE INDEX idx_tool_def_pack ON tool_definitions(pack_id);
CREATE INDEX idx_tool_def_status ON tool_definitions(status);
