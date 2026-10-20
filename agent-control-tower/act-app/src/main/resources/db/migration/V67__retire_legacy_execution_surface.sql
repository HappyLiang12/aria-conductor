-- V67: Retire the legacy NATIVE/ADK execution surface.
--
-- The run-owned core runtime is the only execution path: the in-JVM agent loop,
-- its ToolHandler SPI, harness profiles and tool assignment UI/API have been
-- removed. This forward migration normalizes persisted state without editing
-- applied migrations (V22/V33/V34/V35/V37 stay immutable).

-- 1. The NATIVE agent type no longer exists; every persisted agent runs on a core.
UPDATE agents SET agent_type = 'ADK' WHERE agent_type = 'NATIVE';

-- 2. Retire every legacy tool definition. Historical rows are kept for audit;
--    they are no longer dispatchable or listed by any production API.
UPDATE tool_definitions SET enabled = FALSE WHERE enabled = TRUE;

-- 3. Drop every assignment that pointed at the legacy tool surface.
DELETE FROM agent_tools;
DELETE FROM role_tool_templates;
DELETE FROM skill_tools;

-- 4. Retire the TOOL knowledge entries that backed the legacy catalogue.
UPDATE knowledge_items SET status = 'RETIRED' WHERE type = 'TOOL';

-- 5. Remove harness profile configuration; it only tuned the removed in-JVM loop.
DELETE FROM system_config WHERE config_key LIKE 'harness.%';
