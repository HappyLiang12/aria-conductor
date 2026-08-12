-- V40: Spec-Driven Development workflow support.
-- Adds step-kind routing fields, DoD custom stages, verdict reviews,
-- SPEC_REVIEW approval content, and the seeded development-workflow template.

ALTER TABLE dod_stage_reviews ADD COLUMN verdict VARCHAR(20);
ALTER TABLE dod_records ADD COLUMN stages_json TEXT;
ALTER TABLE approvals ADD COLUMN approval_type VARCHAR(20) NOT NULL DEFAULT 'TOOL_CALL';
ALTER TABLE approvals ADD COLUMN content TEXT;
ALTER TABLE approvals ADD COLUMN content_kind VARCHAR(20);
ALTER TABLE approvals ADD COLUMN knowledge_item_id UUID;
ALTER TABLE workflow_chains ADD COLUMN report_artifact_id UUID;

-- Seed the development-workflow template (WORKFLOW knowledge item + version with YAML).
INSERT INTO knowledge_items (id, name, type, description, status, sensitivity, current_version, created_at, escalation_count) VALUES
('d0000001-0000-0000-0000-000000000001', 'development-workflow', 'WORKFLOW',
 'Spec-driven development loop: BA -> spec approval -> Dev -> QA.', 'APPROVED', 'INTERNAL', 'v1.0.0', CURRENT_TIMESTAMP, 0);

INSERT INTO knowledge_versions (id, knowledge_item_id, version, status, content, yaml_content, created_at, approved_at) VALUES
('d0000002-0000-0000-0000-000000000001', 'd0000001-0000-0000-0000-000000000001', 'v1.0.0', 'APPROVED',
 'Spec-driven development loop template.',
'steps:
  - kind: ba
    agent_role: ba
    prompt_template: "Analyze issue {issueRef} and write a spec. End your output with SPEC_ID=<uuid> after approval."
    max_iterations: 6
  - kind: dev
    agent_role: dev
    prompt_template: "Implement per approved spec {specRef}; run unit + integration tests and verify CI before finishing."
    max_iterations: 10
  - kind: qa
    agent_role: qa
    prompt_template: "Verify against spec {specRef}; generate a QA report via generate_report and submit the DoD verdict. End your output with REPORT_ID=<uuid>."
    max_iterations: 6', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);