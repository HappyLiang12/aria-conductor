-- V22: Seed standard tool catalog — full AriaTools migration (Tier 1-2, all 38 tools)
-- Uses deterministic UUIDs for knowledge_items.id (H2 + MariaDB compatible).
-- tool_definitions.name remains UNIQUE per V21 constraint.
-- handler_class maps to @Component bean name in ToolHandler SPI.

-- KnowledgeItems for tool approval proxy (auto-approved seed)
INSERT INTO knowledge_items (id, name, type, description, status, sensitivity, created_at, escalation_count) VALUES
('a0000001-0000-0000-0000-000000000001', 'Seed tool: web_search', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000002', 'Seed tool: web_fetch', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000003', 'Seed tool: read_file', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000004', 'Seed tool: write_file', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000005', 'Seed tool: list_files', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000006', 'Seed tool: http_request', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000007', 'Seed tool: shell_exec', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000008', 'Seed tool: list_agents', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000009', 'Seed tool: get_agent', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-00000000000a', 'Seed tool: create_agent', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-00000000000b', 'Seed tool: update_agent', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-00000000000c', 'Seed tool: retire_agent', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-00000000000d', 'Seed tool: run_agent', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-00000000000e', 'Seed tool: get_run_status', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-00000000000f', 'Seed tool: list_runs', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000010', 'Seed tool: cancel_run', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000011', 'Seed tool: create_knowledge', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000012', 'Seed tool: search_knowledge', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000013', 'Seed tool: list_knowledge', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000014', 'Seed tool: review_knowledge', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000015', 'Seed tool: pause_run', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000016', 'Seed tool: resume_run', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000017', 'Seed tool: get_run', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000018', 'Seed tool: query_knowledge', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000019', 'Seed tool: retire_knowledge', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-00000000001a', 'Seed tool: create_kanban_item', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-00000000001b', 'Seed tool: list_kanban_items', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-00000000001c', 'Seed tool: update_kanban_item', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-00000000001d', 'Seed tool: transition_kanban_item', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-00000000001e', 'Seed tool: init_dod', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-00000000001f', 'Seed tool: submit_dod_review', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000020', 'Seed tool: get_dod_status', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000021', 'Seed tool: generate_report', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000022', 'Seed tool: list_reports', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000023', 'Seed tool: amend_report', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000024', 'Seed tool: get_dashboard_summary', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000025', 'Seed tool: list_pending_approvals', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('a0000001-0000-0000-0000-000000000026', 'Seed tool: decide_approval', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0);

-- Tool definitions with handler_class for ToolExecutionEngine dispatch
INSERT INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at) VALUES
('seed-tool-web_search',  'web_search', 'web_search', 'Search the web (feature pending full API integration)', 'TIER_1', 'GENERAL', 'webSearchHandler',   '{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000001', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-web_fetch',   'web_fetch', 'web_fetch', 'Fetch content from a URL', 'TIER_1', 'GENERAL', 'webFetchHandler',    '{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000002', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-read_file',   'read_file', 'read_file', 'Read file contents', 'TIER_1', 'GENERAL', 'fileReadHandler',    '{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000003', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-write_file',  'write_file', 'write_file', 'Write content to a file on the local filesystem', 'TIER_1', 'GENERAL', 'fileWriteHandler',   '{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000004', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-list_files',  'list_files', 'list_files', 'List files in a directory', 'TIER_1', 'GENERAL', 'fileListHandler',    '{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000005', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-http_request','http_request', 'http_request', 'Make an HTTP request', 'TIER_1', 'GENERAL', 'httpRequestHandler', '{"type":"object","properties":{"method":{"type":"string","enum":["GET","POST","PUT","DELETE"]},"url":{"type":"string"}},"required":["method","url"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000006', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-shell_exec',  'shell_exec', 'shell_exec', 'Execute a shell command on the local system', 'TIER_1', 'GENERAL', 'shellExecHandler',   '{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000007', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-list_agents', 'list_agents', 'list_agents', 'List all agents', 'TIER_2', 'PLATFORM', 'agentToolHandler',   '{"type":"object","properties":{}}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000008', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-get_agent',   'get_agent', 'get_agent', 'Get agent details', 'TIER_2', 'PLATFORM', 'agentToolHandler',   '{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000009', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-create_agent','create_agent', 'create_agent', 'Create new agent', 'TIER_2', 'PLATFORM', 'agentToolHandler',   '{"type":"object","properties":{"name":{"type":"string"},"role":{"type":"string"}},"required":["name","role"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-00000000000a', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-update_agent','update_agent', 'update_agent', 'Update agent', 'TIER_2', 'PLATFORM', 'agentToolHandler',   '{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-00000000000b', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-retire_agent','retire_agent', 'retire_agent', 'Retire agent', 'TIER_2', 'PLATFORM', 'agentToolHandler',   '{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-00000000000c', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-run_agent',   'run_agent', 'run_agent', 'Start agent run', 'TIER_2', 'PLATFORM', 'runToolHandler',     '{"type":"object","properties":{"agentId":{"type":"string"},"prompt":{"type":"string"}},"required":["agentId","prompt"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-00000000000d', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-get_run_status','get_run_status', 'get_run_status', 'Get run status', 'TIER_2', 'PLATFORM', 'runToolHandler',     '{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-00000000000e', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-list_runs',   'list_runs', 'list_runs', 'List all runs', 'TIER_2', 'PLATFORM', 'runToolHandler',     '{"type":"object","properties":{}}', 'NONE', 30000, 'a0000001-0000-0000-0000-00000000000f', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-cancel_run',  'cancel_run', 'cancel_run', 'Cancel run', 'TIER_2', 'PLATFORM', 'runToolHandler',     '{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000010', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-create_knowledge','create_knowledge', 'create_knowledge', 'Store knowledge', 'TIER_2', 'PLATFORM', 'knowledgeToolHandler', '{"type":"object","properties":{"name":{"type":"string"},"content":{"type":"string"},"type":{"type":"string"}},"required":["name","content","type"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000011', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-search_knowledge','search_knowledge', 'search_knowledge', 'Search knowledge', 'TIER_2', 'PLATFORM', 'knowledgeToolHandler', '{"type":"object","properties":{"keyword":{"type":"string"}},"required":[]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000012', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-list_knowledge','list_knowledge', 'list_knowledge', 'List knowledge', 'TIER_2', 'PLATFORM', 'knowledgeToolHandler', '{"type":"object","properties":{}}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000013', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-review_knowledge','review_knowledge', 'review_knowledge', 'Review knowledge', 'TIER_2', 'PLATFORM', 'knowledgeToolHandler', '{"type":"object","properties":{"id":{"type":"string"},"decision":{"type":"string"}},"required":["id","decision"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000014', TRUE, 1, CURRENT_TIMESTAMP),
-- Tier 2: Platform — Run (extended)
('seed-tool-pause_run',   'pause_run', 'pause_run', 'Pause a running run', 'TIER_2', 'PLATFORM', 'runToolHandler',     '{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000015', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-resume_run',  'resume_run', 'resume_run', 'Resume a paused run', 'TIER_2', 'PLATFORM', 'runToolHandler',     '{"type":"object","properties":{"id":{"type":"string"},"instruction":{"type":"string"}},"required":["id"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000016', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-get_run',     'get_run', 'get_run', 'Get run details', 'TIER_2', 'PLATFORM', 'runToolHandler',     '{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000017', TRUE, 1, CURRENT_TIMESTAMP),
-- Tier 2: Platform — Knowledge (extended)
('seed-tool-query_knowledge','query_knowledge', 'query_knowledge', 'Query knowledge by keyword', 'TIER_2', 'PLATFORM', 'knowledgeToolHandler', '{"type":"object","properties":{"keyword":{"type":"string"}},"required":["keyword"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000018', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-retire_knowledge','retire_knowledge', 'retire_knowledge', 'Retire knowledge item', 'TIER_2', 'PLATFORM', 'knowledgeToolHandler', '{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000019', TRUE, 1, CURRENT_TIMESTAMP),
-- Tier 2: Platform — Approval
('seed-tool-list_pending_approvals','list_pending_approvals', 'list_pending_approvals', 'List pending approvals', 'TIER_2', 'PLATFORM', 'approvalToolHandler', '{"type":"object","properties":{}}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000025', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-decide_approval','decide_approval', 'decide_approval', 'Approve or deny a pending approval', 'TIER_2', 'PLATFORM', 'approvalToolHandler', '{"type":"object","properties":{"id":{"type":"string"},"decision":{"type":"string"},"reason":{"type":"string"}},"required":["id","decision"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000026', TRUE, 1, CURRENT_TIMESTAMP),
-- Tier 2: Platform — Kanban
('seed-tool-create_kanban_item','create_kanban_item', 'create_kanban_item', 'Create a kanban item', 'TIER_2', 'PLATFORM', 'kanbanToolHandler', '{"type":"object","properties":{"title":{"type":"string"},"description":{"type":"string"},"priority":{"type":"string"}},"required":["title"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-00000000001a', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-list_kanban_items','list_kanban_items', 'list_kanban_items', 'List kanban items', 'TIER_2', 'PLATFORM', 'kanbanToolHandler', '{"type":"object","properties":{"status":{"type":"string"}}}', 'NONE', 30000, 'a0000001-0000-0000-0000-00000000001b', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-update_kanban_item','update_kanban_item', 'update_kanban_item', 'Update a kanban item', 'TIER_2', 'PLATFORM', 'kanbanToolHandler', '{"type":"object","properties":{"id":{"type":"string"},"title":{"type":"string"},"description":{"type":"string"}},"required":["id"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-00000000001c', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-transition_kanban_item','transition_kanban_item', 'transition_kanban_item', 'Move kanban item to new status', 'TIER_2', 'PLATFORM', 'kanbanToolHandler', '{"type":"object","properties":{"id":{"type":"string"},"newStatus":{"type":"string"}},"required":["id","newStatus"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-00000000001d', TRUE, 1, CURRENT_TIMESTAMP),
-- Tier 2: Platform — DoD
('seed-tool-init_dod','init_dod', 'init_dod', 'Initialize Definition of Done record', 'TIER_2', 'PLATFORM', 'dodToolHandler', '{"type":"object","properties":{"taskId":{"type":"string"},"taskType":{"type":"string"}},"required":["taskId","taskType"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-00000000001e', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-submit_dod_review','submit_dod_review', 'submit_dod_review', 'Submit DoD for review', 'TIER_2', 'PLATFORM', 'dodToolHandler', '{"type":"object","properties":{"taskId":{"type":"string"}},"required":["taskId"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-00000000001f', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-get_dod_status','get_dod_status', 'get_dod_status', 'Get DoD status', 'TIER_2', 'PLATFORM', 'dodToolHandler', '{"type":"object","properties":{"taskId":{"type":"string"}},"required":["taskId"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000020', TRUE, 1, CURRENT_TIMESTAMP),
-- Tier 2: Platform — Report
('seed-tool-generate_report','generate_report', 'generate_report', 'Generate a report', 'TIER_2', 'PLATFORM', 'reportToolHandler', '{"type":"object","properties":{"title":{"type":"string"},"owner":{"type":"string"},"sourceRunId":{"type":"string"}},"required":["title"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000021', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-list_reports','list_reports', 'list_reports', 'List all reports', 'TIER_2', 'PLATFORM', 'reportToolHandler', '{"type":"object","properties":{}}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000022', TRUE, 1, CURRENT_TIMESTAMP),
('seed-tool-amend_report','amend_report', 'amend_report', 'Amend an existing report', 'TIER_2', 'PLATFORM', 'reportToolHandler', '{"type":"object","properties":{"id":{"type":"string"},"amendment":{"type":"string"}},"required":["id"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000023', TRUE, 1, CURRENT_TIMESTAMP),
-- Tier 2: Platform — Dashboard
('seed-tool-get_dashboard_summary','get_dashboard_summary', 'get_dashboard_summary', 'Get dashboard KPI summary', 'TIER_2', 'PLATFORM', 'dashboardToolHandler', '{"type":"object","properties":{}}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000024', TRUE, 1, CURRENT_TIMESTAMP);

-- Role templates: ARIA gets all tools, WORKER gets Tier 1
INSERT INTO role_tool_templates (role, tool_id, is_default)
SELECT 'ARIA', id, TRUE FROM tool_definitions;

INSERT INTO role_tool_templates (role, tool_id, is_default)
SELECT 'WORKER', id, TRUE FROM tool_definitions WHERE tier = 'TIER_1';
