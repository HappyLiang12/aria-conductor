-- V43: Fix SDD seed configs.
--   1. Give the seeded SDD role agents (ba/dev/qa) explicit task config.
--   2. Raise the development-workflow template per-step max_iterations to 15.
--   3. Extend the BA prompt with the required spec sections.
--   4. Raise the circuit-breaker per-run token budget to 300000.
--   5. Add a composite index for knowledge-version lookups by (item, version).

-- 1. SDD role agents: explicit task config so instantiated runs get
--    taskApprovalRequired=false and a sane tool-call budget (matches V42 config='{}' seed).
UPDATE agents
SET config = '{"taskApprovalRequired": false, "maxToolCallRounds": 15}'
WHERE role IN ('ba', 'dev', 'qa') AND config = '{}';

-- 2. development-workflow template: bump per-step max_iterations (6/10/6 -> 15/15/15).
--    H2 REPLACE is a plain (case-sensitive) string replace, so each statement targets a
--    unique substring. The BA step's "max_iterations: 6" is disambiguated by the following
--    "kind: dev" line; dev's "10" is already unique; after those two, the only remaining
--    "max_iterations: 6" is the QA step's trailing line.
UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    'max_iterations: 6
  - kind: dev',
    'max_iterations: 15
  - kind: dev')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);

UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    'max_iterations: 10',
    'max_iterations: 15')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);

UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    'max_iterations: 6',
    'max_iterations: 15')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);

-- 3. BA prompt: append the required spec sections.
UPDATE knowledge_versions
SET yaml_content = REPLACE(yaml_content,
    'Analyze issue {issueRef} and write a spec. End your output with SPEC_ID=<uuid> after approval.',
    'Analyze issue {issueRef} and write a spec with sections: Problem Statement, Proposed Solution, Acceptance Criteria, Error Handling. End your output with SPEC_ID=<uuid> after approval.')
WHERE knowledge_item_id = CAST('d0000001-0000-0000-0000-000000000001' AS UUID);

-- 4. Circuit-breaker per-run token budget: 100000 -> 300000.
UPDATE system_config
SET config_value = '300000'
WHERE config_key = 'circuit.breaker.max.tokens.per.run';

-- 5. Composite index for knowledge-version lookups by (item, version).
CREATE INDEX idx_kv_item_version ON knowledge_versions (knowledge_item_id, version);
