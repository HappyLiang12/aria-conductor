-- V19: Tool registry, agent-tool assignment, skill-tool dependencies, role templates

CREATE TABLE tool_definitions (
    id              VARCHAR(36)  PRIMARY KEY,
    name            VARCHAR(255) NOT NULL UNIQUE,
    display_name    VARCHAR(255),
    description     TEXT         NOT NULL,
    tier            VARCHAR(20)  NOT NULL DEFAULT 'TIER_1',
    category        VARCHAR(50)  NOT NULL,
    handler_class   VARCHAR(500),
    script_type     VARCHAR(20),
    script          TEXT,
    parameters      TEXT         NOT NULL,
    sandbox_mode    VARCHAR(20)  NOT NULL DEFAULT 'NONE',
    sandbox_config  TEXT,
    timeout_ms      INT          NOT NULL DEFAULT 30000,
    knowledge_item_id UUID,
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    version         INT          NOT NULL DEFAULT 1,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(255),
    CONSTRAINT fk_tool_ki FOREIGN KEY (knowledge_item_id) REFERENCES knowledge_items(id)
);

CREATE INDEX idx_tool_def_tier ON tool_definitions(tier);
CREATE INDEX idx_tool_def_category ON tool_definitions(category);
CREATE INDEX idx_tool_def_enabled ON tool_definitions(enabled);

CREATE TABLE agent_tools (
    agent_id    UUID NOT NULL,
    tool_id     VARCHAR(36) NOT NULL,
    assigned_by VARCHAR(50) NOT NULL DEFAULT 'USER',
    assigned_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (agent_id, tool_id),
    CONSTRAINT fk_at_agent FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE,
    CONSTRAINT fk_at_tool  FOREIGN KEY (tool_id)  REFERENCES tool_definitions(id) ON DELETE CASCADE
);

CREATE TABLE skill_tools (
    skill_id    VARCHAR(36) NOT NULL,
    tool_id     VARCHAR(36) NOT NULL,
    PRIMARY KEY (skill_id, tool_id),
    CONSTRAINT fk_st_skill FOREIGN KEY (skill_id) REFERENCES skill_definitions(id) ON DELETE CASCADE,
    CONSTRAINT fk_st_tool  FOREIGN KEY (tool_id)  REFERENCES tool_definitions(id) ON DELETE CASCADE
);

CREATE TABLE agent_skills (
    agent_id    UUID NOT NULL,
    skill_id    VARCHAR(36) NOT NULL,
    assigned_by VARCHAR(50) NOT NULL DEFAULT 'USER',
    assigned_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (agent_id, skill_id),
    CONSTRAINT fk_as_agent FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE,
    CONSTRAINT fk_as_skill FOREIGN KEY (skill_id) REFERENCES skill_definitions(id) ON DELETE CASCADE
);

CREATE TABLE role_tool_templates (
    role        VARCHAR(50)  NOT NULL,
    tool_id     VARCHAR(36)  NOT NULL,
    is_default  BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (role, tool_id),
    CONSTRAINT fk_rtt_tool FOREIGN KEY (tool_id) REFERENCES tool_definitions(id) ON DELETE CASCADE
);

-- Add columns to agents
ALTER TABLE agents ADD COLUMN IF NOT EXISTS adk_provider VARCHAR(50);
ALTER TABLE agents ADD COLUMN IF NOT EXISTS tool_ids TEXT;
ALTER TABLE agents ADD COLUMN IF NOT EXISTS skill_ids TEXT;

-- Add columns to skill_definitions
ALTER TABLE skill_definitions ADD COLUMN IF NOT EXISTS tier VARCHAR(20) DEFAULT 'TIER_2';
ALTER TABLE skill_definitions ADD COLUMN IF NOT EXISTS sandbox_mode VARCHAR(20) DEFAULT 'NONE';
ALTER TABLE skill_definitions ADD COLUMN IF NOT EXISTS enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE skill_definitions ADD COLUMN IF NOT EXISTS version INT DEFAULT 1;
ALTER TABLE skill_definitions ADD COLUMN IF NOT EXISTS created_by VARCHAR(255);
