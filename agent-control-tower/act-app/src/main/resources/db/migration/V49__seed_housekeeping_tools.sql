-- V49: Seed housekeeping orchestration tools for Aria (operator cleanup).
-- housekeeping_scan is read-only; housekeeping_execute is DESTRUCTIVE and its
-- handler blocks on the human ApprovalGate (agent can request, never self-approve).
-- Mirrors the V41 seed pattern: deterministic IDs, knowledge_items approval proxy,
-- handler_class dispatch (housekeepingToolHandler).

INSERT INTO knowledge_items (id, name, type, description, status, sensitivity, created_at, escalation_count) VALUES
('c0000001-0000-0000-0000-000000000007', 'Seed tool: housekeeping_scan', 'TOOL',
 'Read-only leftover scan for housekeeping.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0),
('c0000001-0000-0000-0000-000000000008', 'Seed tool: housekeeping_execute', 'TOOL',
 'Approval-gated cleanup execution for housekeeping.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0);

INSERT INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at, risk_tier, status, kind) VALUES
('seed-tool-housekeeping_scan', 'housekeeping_scan', 'housekeeping_scan',
 'Scan the system for leftovers: terminal runs older than 24h, stuck paused runs at the approval gate, DONE/CANCELLED kanban cards, leftover e2e-*/unhealthy agents, and expired pending approvals. Read-only; returns counts and samples so the operator can decide what to clean.',
 'TIER_1', 'GENERAL', 'housekeepingToolHandler',
 '{"type":"object","properties":{}}',
 'NONE', 60000, 'c0000001-0000-0000-0000-000000000007', TRUE, 1, CURRENT_TIMESTAMP, 'READ', 'APPROVED', 'HANDLER'),
('seed-tool-housekeeping_execute', 'housekeeping_execute', 'housekeeping_execute',
 'Execute a housekeeping cleanup for selected categories (runs, stuck, kanban, agents, approvals). Supports exclusions (runIds/kanbanItemIds/agentIds/approvalIds to keep) and includeStuck (opt-in). Destructive: pauses for human approval before anything is deleted or retired.',
 'TIER_1', 'GENERAL', 'housekeepingToolHandler',
 '{"type":"object","properties":{"categories":{"type":"array","items":{"type":"string","enum":["runs","stuck","kanban","agents","approvals"]},"description":"Categories to clean"},"includeStuck":{"type":"boolean","description":"Include stuck paused runs (opt-in, default false)"},"exclusions":{"type":"object","description":"Ids to keep: runIds / kanbanItemIds / agentIds / approvalIds arrays"}},"required":["categories"]}',
 'NONE', 300000, 'c0000001-0000-0000-0000-000000000008', TRUE, 1, CURRENT_TIMESTAMP, 'DESTRUCTIVE', 'APPROVED', 'HANDLER');
