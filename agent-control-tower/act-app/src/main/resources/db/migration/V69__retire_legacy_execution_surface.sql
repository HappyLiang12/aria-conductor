-- V69: Retire the legacy NATIVE/ADK execution surface.
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
-- The legacy denormalized assignment columns are no longer read by any code.
UPDATE agents SET tool_ids = NULL, skill_ids = NULL
WHERE tool_ids IS NOT NULL OR skill_ids IS NOT NULL;

-- 4. Retire the TOOL knowledge entries that backed the legacy catalogue.
UPDATE knowledge_items
SET status = 'RETIRED', retired_at = CURRENT_TIMESTAMP
WHERE type = 'TOOL' AND status <> 'RETIRED';

-- 5. Expire pending legacy tool approvals: the in-JVM executor that would have
--    run them is gone. Only the removed gate's own asks qualify: a pending
--    REVIEW_REQUEST or CLARIFICATION ask is live operator state, and ACP asks
--    keep their own lifecycle, so all three must survive the upgrade.
UPDATE approvals
SET status = 'EXPIRED', reason = 'Legacy tool approval retired', decided_at = CURRENT_TIMESTAMP
WHERE status = 'PENDING'
  AND approval_type = 'TOOL_CALL'
  AND ask_type = 'APPROVAL'
  AND source = 'LEGACY_GATE';

-- 6. Remove configuration that only tuned removed machinery: harness profiles
--    shaped the in-JVM loop, and the circuit breaker / ADK runtime knobs have no
--    reader in the run-owned core runtime (V69 is the retirement point for both).
DELETE FROM system_config
WHERE config_key LIKE 'harness.%'
   OR config_key LIKE 'circuit.breaker.%'
   OR config_key LIKE 'adk.%';
