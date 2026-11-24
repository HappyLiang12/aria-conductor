-- V83: drop the retired legacy tool-assignment storage and the shadow-copy table.
--
-- V69 retired the in-JVM tool surface and emptied these tables; no entity maps them
-- any more (the legacy assignment columns on agents were nulled there too), so the
-- empty storage is dropped rather than carried forward. action_shadow_copies backed
-- the same removed action pipeline and has no reader at all.

DROP TABLE IF EXISTS agent_tools;
DROP TABLE IF EXISTS skill_tools;
DROP TABLE IF EXISTS role_tool_templates;
DROP TABLE IF EXISTS action_shadow_copies;

-- Seeded by V14 for the removed per-request token clamp; the run-owned core runtime
-- has no reader for it.
DELETE FROM system_config WHERE config_key = 'llm.max.tokens.ceiling';
