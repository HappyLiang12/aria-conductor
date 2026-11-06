-- V37: Seed reusable harness profiles.
-- Profiles tune the agent execution loop (tool steering, LLM self-verification with HITL
-- escalation, and budgets) so even a weak model stays usable and safe. An agent references a
-- profile by name via its config JSON ({"harnessProfile":"weak-model-safe"}); resolution is
-- runtime-configurable via the system_config table (same pattern as circuit.breaker.*).

-- Global fallback profile name used when an agent does not reference one explicitly.
INSERT INTO system_config (config_key, config_value, description, updated_at) VALUES
('harness.default.profile', 'default', 'Harness profile applied to agents that do not reference one (must match a harness.profile.<name> key)', CURRENT_TIMESTAMP);

-- The "default" profile is a pure no-op that reproduces historical behaviour: no tool denylist,
-- no shell->git steering, self-verify enabled with NO escalation (identical to today), and
-- unset rounds (falls back to run/agent config).
INSERT INTO system_config (config_key, config_value, description, updated_at) VALUES
('harness.profile.default', '{"name":"default","toolDenylist":[],"steering":{"shellExecToGitPack":false},"selfVerify":{"enabled":true,"escalateTiers":[],"maxResponseTokens":200,"promptOverride":null},"maxToolCallRounds":0,"maxToolOutputChars":16000}', 'Default harness profile (no-op; preserves pre-profile behaviour)', CURRENT_TIMESTAMP);

-- The "weak-model-safe" profile hardens the loop for weak models: removes shell_exec from the
-- effective tool set, steers shell-wrapped git to the governed git pack, lets the LLM reviewer
-- ESCALATE risky WRITE_LOCAL/EXECUTE/PUSH/DESTRUCTIVE actions to a human gate, and caps rounds.
INSERT INTO system_config (config_key, config_value, description, updated_at) VALUES
('harness.profile.weak-model-safe', '{"name":"weak-model-safe","toolDenylist":["shell_exec"],"steering":{"shellExecToGitPack":true},"selfVerify":{"enabled":true,"escalateTiers":["WRITE_LOCAL","EXECUTE","PUSH","DESTRUCTIVE"],"maxResponseTokens":200,"promptOverride":null},"maxToolCallRounds":25,"maxToolOutputChars":16000}', 'Weak-model-safe harness profile (shell de-default, git steering, HITL escalation, tighter budgets)', CURRENT_TIMESTAMP);
