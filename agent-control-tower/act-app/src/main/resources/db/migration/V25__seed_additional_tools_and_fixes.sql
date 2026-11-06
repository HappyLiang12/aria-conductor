-- V25: Add 3 new tools + fix search_knowledge/init_dod required fields.
-- Uses WHERE NOT EXISTS for idempotency (ANSI SQL, H2 + MariaDB compatible).
-- No ON DUPLICATE KEY UPDATE / INSERT IGNORE (MySQL-specific syntax avoided).

-- KnowledgeItems for 3 new tools (idempotent)
INSERT INTO knowledge_items (id, name, type, description, status, sensitivity, created_at, escalation_count)
SELECT 'a0000001-0000-0000-0000-000000000027', 'Seed tool: list_running_runs', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM knowledge_items WHERE id = 'a0000001-0000-0000-0000-000000000027');

INSERT INTO knowledge_items (id, name, type, description, status, sensitivity, created_at, escalation_count)
SELECT 'a0000001-0000-0000-0000-000000000028', 'Seed tool: store_knowledge', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM knowledge_items WHERE id = 'a0000001-0000-0000-0000-000000000028');

INSERT INTO knowledge_items (id, name, type, description, status, sensitivity, created_at, escalation_count)
SELECT 'a0000001-0000-0000-0000-000000000029', 'Seed tool: delete_agent', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM knowledge_items WHERE id = 'a0000001-0000-0000-0000-000000000029');

-- 3 new tool definitions (idempotent via WHERE NOT EXISTS)
INSERT INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at)
SELECT 'seed-tool-list_running_runs','list_running_runs', 'list_running_runs', 'List currently running and pending runs', 'TIER_2', 'PLATFORM', 'runToolHandler', '{"type":"object","properties":{}}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000027', TRUE, 1, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM tool_definitions WHERE name = 'list_running_runs');

INSERT INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at)
SELECT 'seed-tool-store_knowledge','store_knowledge', 'store_knowledge', 'Store knowledge (alias for create)', 'TIER_2', 'PLATFORM', 'knowledgeToolHandler', '{"type":"object","properties":{"name":{"type":"string"},"content":{"type":"string"},"type":{"type":"string"}},"required":["name","content","type"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000028', TRUE, 1, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM tool_definitions WHERE name = 'store_knowledge');

INSERT INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at)
SELECT 'seed-tool-delete_agent','delete_agent', 'delete_agent', 'Delete agent (alias for retire)', 'TIER_2', 'PLATFORM', 'agentToolHandler', '{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}', 'NONE', 30000, 'a0000001-0000-0000-0000-000000000029', TRUE, 1, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM tool_definitions WHERE name = 'delete_agent');

-- Fix search_knowledge: required[] → required["keyword"] (for environments that ran original V22)
UPDATE tool_definitions
SET parameters = '{"type":"object","properties":{"keyword":{"type":"string"}},"required":["keyword"]}'
WHERE name = 'search_knowledge'
  AND parameters = '{"type":"object","properties":{"keyword":{"type":"string"}},"required":[]}';

-- Fix init_dod: required["taskId","taskType"] → required["taskId"] (taskType is optional per handler)
UPDATE tool_definitions
SET parameters = '{"type":"object","properties":{"taskId":{"type":"string"},"taskType":{"type":"string"}},"required":["taskId"]}'
WHERE name = 'init_dod'
  AND parameters = '{"type":"object","properties":{"taskId":{"type":"string"},"taskType":{"type":"string"}},"required":["taskId","taskType"]}';

-- Assign new tools to ARIA role template (idempotent)
INSERT INTO role_tool_templates (role, tool_id, is_default)
SELECT 'ARIA', id, TRUE FROM tool_definitions
WHERE name IN ('list_running_runs', 'store_knowledge', 'delete_agent')
  AND NOT EXISTS (
    SELECT 1 FROM role_tool_templates rtt
    WHERE rtt.role = 'ARIA' AND rtt.tool_id = tool_definitions.id
  );
