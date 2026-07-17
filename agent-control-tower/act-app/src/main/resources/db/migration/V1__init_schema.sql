-- V1: Initial schema for Aria Conductor
-- All tables use H2-compatible SQL syntax

CREATE TABLE agents (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    agent_type VARCHAR(50) NOT NULL,
    role VARCHAR(255),
    model VARCHAR(255),
    provider VARCHAR(255),
    config TEXT,
    health_status VARCHAR(50) NOT NULL DEFAULT 'HEALTHY',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    retired_at TIMESTAMP
);
CREATE INDEX idx_agents_type ON agents(agent_type);
CREATE INDEX idx_agents_health ON agents(health_status);

CREATE TABLE runs (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    prompt_seed TEXT,
    max_iterations INT DEFAULT 50,
    total_tokens_used BIGINT DEFAULT 0,
    iteration_count INT DEFAULT 0,
    error_message VARCHAR(2000),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT fk_runs_agent FOREIGN KEY (agent_id) REFERENCES agents(id)
);
CREATE INDEX idx_runs_agent ON runs(agent_id);
CREATE INDEX idx_runs_status ON runs(status);

CREATE TABLE agent_sessions (
    run_id UUID PRIMARY KEY,
    agent_id UUID NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    memory TEXT,
    context TEXT,
    turn_count INT DEFAULT 0,
    total_input_tokens BIGINT DEFAULT 0,
    total_output_tokens BIGINT DEFAULT 0,
    version INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT fk_sessions_agent FOREIGN KEY (agent_id) REFERENCES agents(id)
);
CREATE INDEX idx_sessions_agent ON agent_sessions(agent_id);
CREATE INDEX idx_sessions_status ON agent_sessions(status);

CREATE TABLE session_trajectory (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    turn_number INT NOT NULL,
    role VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    tool_calls TEXT,
    input_tokens INT DEFAULT 0,
    output_tokens INT DEFAULT 0,
    latency_ms INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_trajectory_run FOREIGN KEY (run_id) REFERENCES runs(id)
);
CREATE INDEX idx_trajectory_run ON session_trajectory(run_id);
CREATE INDEX idx_trajectory_turn ON session_trajectory(run_id, turn_number);

CREATE TABLE tool_calls (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    arguments TEXT,
    result TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    latency_ms INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_tool_calls_run FOREIGN KEY (run_id) REFERENCES runs(id)
);
CREATE INDEX idx_tool_calls_run ON tool_calls(run_id);
CREATE INDEX idx_tool_calls_status ON tool_calls(status);

CREATE TABLE approvals (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    tool_call_id UUID,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    reason VARCHAR(1000),
    requested_at TIMESTAMP NOT NULL,
    decided_at TIMESTAMP,
    expires_at TIMESTAMP,
    CONSTRAINT fk_approvals_run FOREIGN KEY (run_id) REFERENCES runs(id)
);
CREATE INDEX idx_approvals_run ON approvals(run_id);
CREATE INDEX idx_approvals_status ON approvals(status);

CREATE TABLE knowledge_items (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL,
    description VARCHAR(1000),
    current_version VARCHAR(50),
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    sensitivity VARCHAR(50) NOT NULL DEFAULT 'INTERNAL',
    file_path VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    retired_at TIMESTAMP
);
CREATE INDEX idx_knowledge_type ON knowledge_items(type);
CREATE INDEX idx_knowledge_status ON knowledge_items(status);

CREATE TABLE knowledge_versions (
    id UUID PRIMARY KEY,
    knowledge_item_id UUID NOT NULL,
    version VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    content TEXT,
    created_at TIMESTAMP NOT NULL,
    approved_at TIMESTAMP,
    CONSTRAINT fk_kv_item FOREIGN KEY (knowledge_item_id) REFERENCES knowledge_items(id)
);
CREATE INDEX idx_kv_item ON knowledge_versions(knowledge_item_id);

CREATE TABLE prompt_calls (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id UUID NOT NULL,
    agent_id UUID NOT NULL,
    provider VARCHAR(255),
    model VARCHAR(255),
    input_tokens INT DEFAULT 0,
    output_tokens INT DEFAULT 0,
    latency_ms INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_prompt_calls_run FOREIGN KEY (run_id) REFERENCES runs(id)
);
CREATE INDEX idx_prompt_calls_run ON prompt_calls(run_id);
CREATE INDEX idx_prompt_calls_agent ON prompt_calls(agent_id);

CREATE TABLE audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    resource_type VARCHAR(100) NOT NULL,
    resource_id VARCHAR(255),
    action VARCHAR(100) NOT NULL,
    details TEXT,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_audit_resource ON audit_log(resource_type, resource_id);
CREATE INDEX idx_audit_event_type ON audit_log(event_type);

CREATE TABLE llm_providers (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    type VARCHAR(50) NOT NULL,
    base_url VARCHAR(500),
    api_key_ref VARCHAR(255),
    default_model VARCHAR(255),
    default_max_tokens INT DEFAULT 4096,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE TABLE system_config (
    config_key VARCHAR(255) PRIMARY KEY,
    config_value TEXT,
    description VARCHAR(500),
    updated_at TIMESTAMP
);
