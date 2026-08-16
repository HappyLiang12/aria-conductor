-- V40: Seed find_knowledge lookup tool (#38).
-- Mirrors the V33/V38 seed pattern: deterministic IDs, knowledge_items approval proxy,
-- handler_class dispatch. find_knowledge resolves a knowledge item name to its UUID/status,
-- giving Aria (and operators) an explicit name -> UUID path for UUID-based knowledge tools.

-- Knowledge item for approval proxy
INSERT INTO knowledge_items (id, name, type, description, status, sensitivity, created_at, escalation_count) VALUES
('d0000001-0000-0000-0000-000000000001', 'Seed tool: find_knowledge', 'TOOL', 'Auto-seeded standard tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0);

-- find_knowledge tool (handler = knowledgeToolHandler). Read-only lookup; no approval gate.
INSERT INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at, risk_tier, status, kind) VALUES
('seed-tool-find_knowledge', 'find_knowledge', 'find_knowledge', 'Look up a knowledge item''s ID and status by exact name (requires name)', 'TIER_2', 'PLATFORM', 'knowledgeToolHandler', '{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}', 'NONE', 30000, 'd0000001-0000-0000-0000-000000000001', TRUE, 1, CURRENT_TIMESTAMP, 'READ', 'APPROVED', 'HANDLER');

-- Grant find_knowledge to the ARIA role template (mirrors the ARIA-grants in V22)
INSERT INTO role_tool_templates (role, tool_id, is_default)
SELECT 'ARIA', id, TRUE FROM tool_definitions WHERE name = 'find_knowledge';
