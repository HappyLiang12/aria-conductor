-- V41: Seed instantiate_template orchestration tool for Aria (SDD workflow).
-- Lets Aria start the governed 'development-workflow' template (BA -> spec approval ->
-- Dev -> QA) by template id, instead of rebuilding a bare chain via create_workflow.
-- Mirrors the V38 seed pattern: deterministic IDs, knowledge_items approval proxy,
-- handler_class dispatch (workflowHandler).

INSERT INTO knowledge_items (id, name, type, description, status, sensitivity, created_at, escalation_count) VALUES
('c0000001-0000-0000-0000-000000000006', 'Seed tool: instantiate_template', 'TOOL',
 'Workflow orchestration tool.', 'APPROVED', 'INTERNAL', CURRENT_TIMESTAMP, 0);

INSERT INTO tool_definitions (id, name, display_name, description, tier, category, handler_class, parameters, sandbox_mode, timeout_ms, knowledge_item_id, enabled, version, created_at, risk_tier, status, kind) VALUES
('seed-tool-instantiate_template', 'instantiate_template', 'instantiate_template',
 'Instantiate a governed workflow template (e.g. the "development-workflow" SDD loop) by template id. Provide templateId and optional parameters such as issueRef. The loop pauses for human spec approval (SPEC_REVIEW) and then routes on the QA verdict.',
 'TIER_1', 'GENERAL', 'workflowHandler',
 '{"type":"object","properties":{"templateId":{"type":"string","description":"Knowledge item id of the APPROVED WORKFLOW template"},"parameters":{"type":"object","description":"Template parameter values, e.g. {\"issueRef\":\"#12\"}"}},"required":["templateId"]}',
 'NONE', 60000, 'c0000001-0000-0000-0000-000000000006', TRUE, 1, CURRENT_TIMESTAMP, 'READ', 'APPROVED', 'HANDLER');
