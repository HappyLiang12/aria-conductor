-- Workflow chain governance fields
ALTER TABLE workflow_chains ADD COLUMN is_template BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE workflow_chains ADD COLUMN template_params TEXT;
ALTER TABLE workflow_chains ADD COLUMN source_knowledge_item_id UUID;
ALTER TABLE workflow_chains ADD COLUMN knowledge_item_id UUID;
ALTER TABLE workflow_chains ADD COLUMN description TEXT;
CREATE INDEX idx_wf_is_template ON workflow_chains(is_template);
CREATE INDEX idx_wf_knowledge_item ON workflow_chains(knowledge_item_id);

-- YAML content for workflow templates (DB-first, future: sync to git)
ALTER TABLE knowledge_versions ADD COLUMN yaml_content TEXT;
