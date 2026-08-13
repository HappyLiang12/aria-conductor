-- V42: Seed SDD role agents (ba/dev/qa) for the development-workflow template.
-- The V40 seed template resolves steps by agent_role (ba/dev/qa); without these
-- agents the template cannot be instantiated on a fresh deployment.
--
-- Column set matches the agents table as built by V1 + V15 (adk_provider) +
-- V21 (tool_ids/skill_ids) + V28 (role TEXT). There is NO `status` column on
-- the agents table (health_status plays that role); NULLable columns are
-- omitted so the NOT NULL defaults apply. agents.id is a native UUID column
-- (V1), so ids are cast explicitly (H2 rejects implicit string->UUID coercion).
-- NOTE: a `qa` prefix is NOT a valid UUID (q is not hex), so the QA agent uses
-- the aa prefix; ba/de prefixes are hex-valid.

INSERT INTO agents (id, name, role, agent_type, adk_provider, model, config, health_status, created_at, updated_at) VALUES
(CAST('ba000000-0000-0000-0000-000000000001' AS UUID), 'SDD BA Agent', 'ba', 'NATIVE', 'langchain', 'mock', '{}', 'HEALTHY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(CAST('de000000-0000-0000-0000-000000000002' AS UUID), 'SDD DEV Agent', 'dev', 'NATIVE', 'langchain', 'mock', '{}', 'HEALTHY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(CAST('aa000000-0000-0000-0000-000000000003' AS UUID), 'SDD QA Agent', 'qa', 'NATIVE', 'langchain', 'mock', '{}', 'HEALTHY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
