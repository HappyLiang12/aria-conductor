-- V44: SDD template prompt guidance for sandbox-based agents.
--   BA: fetch the issue via gh, emit Questions section instead of interactive asks.
--   DEV: clone the project independently before implementing.

-- BA prompt: replace the V43-extended prompt with the gh + Questions version.
UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    'Analyze issue {issueRef} and write a spec with sections: Problem Statement, Proposed Solution, Acceptance Criteria, Error Handling. End your output with SPEC_ID=<uuid> after approval.',
    'Analyze issue {issueRef} and write a spec with sections: Problem Statement, Proposed Solution, Acceptance Criteria, Error Handling. If the issue body is not already in your prompt, fetch it first with: gh issue view {issueRef} -R {issueRepo} --json title,body,labels (GH_TOKEN is already configured). If anything is ambiguous and requires the user to decide, end the spec with a ## Questions section listing each question on its own line; omit the section when nothing is ambiguous. NEVER ask interactive questions - put everything into the spec. If the task message contains rejection feedback (Spec was rejected: ...), incorporate the reviewer answers into the revised spec and drop the Questions section for answered items. End your output with SPEC_ID=<uuid> after approval.')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);

-- DEV prompt: clone guidance.
UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    'Implement per approved spec {specRef}; run unit + integration tests and verify CI before finishing.',
    'Check out the project first: git clone {repoUrl} /workspace/repo (GH_TOKEN is configured for private repos). Implement per approved spec {specRef}; run unit + integration tests and verify CI before finishing.')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);
