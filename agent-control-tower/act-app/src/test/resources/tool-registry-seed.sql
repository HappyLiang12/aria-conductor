-- Minimal tool definitions seed for ToolRegistrySeedTest
-- (without FK constraints to avoid knowledge_items dependency)
CREATE TABLE IF NOT EXISTS tool_definitions (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(255),
    description TEXT NOT NULL,
    tier VARCHAR(20) NOT NULL DEFAULT 'TIER_1',
    category VARCHAR(50) NOT NULL,
    handler_class VARCHAR(500),
    script_type VARCHAR(20),
    script TEXT,
    parameters TEXT NOT NULL,
    sandbox_mode VARCHAR(20) NOT NULL DEFAULT 'NONE',
    sandbox_config TEXT,
    timeout_ms INT NOT NULL DEFAULT 30000,
    knowledge_item_id VARCHAR(36),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255)
);

-- Seed data matching V22__seed_standard_tools.sql
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-web_search', 'web_search', 'web_search', 'Search the web (feature pending full API integration)', 'TIER_1', 'GENERAL', 'webSearchHandler', NULL, '{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-web_fetch', 'web_fetch', 'web_fetch', 'Fetch content from a URL', 'TIER_1', 'GENERAL', 'webFetchHandler', NULL, '{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-read_file', 'read_file', 'read_file', 'Read file contents', 'TIER_1', 'GENERAL', 'fileReadHandler', NULL, '{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-write_file', 'write_file', 'write_file', 'Write content to a file on the local filesystem', 'TIER_1', 'GENERAL', 'fileWriteHandler', NULL, '{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-list_files', 'list_files', 'list_files', 'List files in a directory', 'TIER_1', 'GENERAL', 'fileListHandler', NULL, '{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-http_request', 'http_request', 'http_request', 'Make an HTTP request', 'TIER_1', 'GENERAL', 'httpRequestHandler', NULL, '{"type":"object","properties":{"method":{"type":"string"},"url":{"type":"string"}},"required":["method","url"]}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-shell_exec', 'shell_exec', 'shell_exec', 'Execute a shell command on the local system', 'TIER_1', 'GENERAL', 'shellExecHandler', NULL, '{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-list_agents', 'list_agents', 'list_agents', 'List all agents', 'TIER_2', 'PLATFORM', 'agentToolHandler', NULL, '{"type":"object","properties":{}}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-get_agent', 'get_agent', 'get_agent', 'Get agent details', 'TIER_2', 'PLATFORM', 'agentToolHandler', NULL, '{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-create_agent', 'create_agent', 'create_agent', 'Create new agent', 'TIER_2', 'PLATFORM', 'agentToolHandler', NULL, '{"type":"object","properties":{"name":{"type":"string"},"role":{"type":"string"}},"required":["name","role"]}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-update_agent', 'update_agent', 'update_agent', 'Update agent', 'TIER_2', 'PLATFORM', 'agentToolHandler', NULL, '{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-retire_agent', 'retire_agent', 'retire_agent', 'Retire agent', 'TIER_2', 'PLATFORM', 'agentToolHandler', NULL, '{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-run_agent', 'run_agent', 'run_agent', 'Start agent run', 'TIER_2', 'PLATFORM', 'runToolHandler', NULL, '{"type":"object","properties":{"agentId":{"type":"string"},"prompt":{"type":"string"}},"required":["agentId","prompt"]}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-get_run_status', 'get_run_status', 'get_run_status', 'Get run status', 'TIER_2', 'PLATFORM', 'runToolHandler', NULL, '{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-list_runs', 'list_runs', 'list_runs', 'List all runs', 'TIER_2', 'PLATFORM', 'runToolHandler', NULL, '{"type":"object","properties":{}}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-cancel_run', 'cancel_run', 'cancel_run', 'Cancel run', 'TIER_2', 'PLATFORM', 'runToolHandler', NULL, '{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-create_knowledge', 'create_knowledge', 'create_knowledge', 'Store knowledge', 'TIER_2', 'PLATFORM', 'knowledgeToolHandler', NULL, '{"type":"object","properties":{"name":{"type":"string"},"content":{"type":"string"},"type":{"type":"string"}},"required":["name","content","type"]}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-search_knowledge', 'search_knowledge', 'search_knowledge', 'Search knowledge', 'TIER_2', 'PLATFORM', 'knowledgeToolHandler', NULL, '{"type":"object","properties":{"keyword":{"type":"string"}},"required":["keyword"]}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-list_knowledge', 'list_knowledge', 'list_knowledge', 'List knowledge', 'TIER_2', 'PLATFORM', 'knowledgeToolHandler', NULL, '{"type":"object","properties":{}}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-review_knowledge', 'review_knowledge', 'review_knowledge', 'Review knowledge', 'TIER_2', 'PLATFORM', 'knowledgeToolHandler', NULL, '{"type":"object","properties":{"id":{"type":"string"},"decision":{"type":"string"}},"required":["id","decision"]}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-list_running_runs', 'list_running_runs', 'list_running_runs', 'List currently running and pending runs', 'TIER_2', 'PLATFORM', 'runToolHandler', NULL, '{"type":"object","properties":{}}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-store_knowledge', 'store_knowledge', 'store_knowledge', 'Store knowledge (alias for create)', 'TIER_2', 'PLATFORM', 'knowledgeToolHandler', NULL, '{"type":"object","properties":{"name":{"type":"string"},"content":{"type":"string"},"type":{"type":"string"}},"required":["name","content","type"]}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-delete_agent', 'delete_agent', 'delete_agent', 'Delete agent (alias for retire)', 'TIER_2', 'PLATFORM', 'agentToolHandler', NULL, '{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
MERGE INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, script_type, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) KEY(name) VALUES
('seed-tool-find_knowledge', 'find_knowledge', 'find_knowledge', 'Look up a knowledge item''s ID and status by exact name (requires name)', 'TIER_2', 'PLATFORM', 'knowledgeToolHandler', NULL, '{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}', 'NONE', 30000, NULL, TRUE, 1, CURRENT_TIMESTAMP);
