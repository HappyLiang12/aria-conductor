# Aria turn resilience — live verification (2026-10-03 → 2026-10-04)

Branch `feat/aria-turn-resilience` at `95a7140f` (base `a74d6ede` = main after PR #102). Environment:
local stack started from this worktree (`scripts/start.ps1`, podman + OpenSandbox, H2, core
`opencode/SANDBOX`, `LLM_MODEL=deepseek-flash`, local admission cap=3 per the host's sandbox
ceiling). Evidence quoted from the stack's `.run/backend.log`, the drill scripts' captured output,
and API payloads (`/api/v1/runs`, `/api/v1/aria/conversations`, `/api/v1/aria/notifications`).

## Step 1 — regression (head 4c906787 → module suite on the pre-fix head; fix re-verified)

- `mvn -B test -pl act-execution,act-aria,act-agent`: **1229 + 381 (+4 pre-existing skips) + 259,
  0 failures, BUILD SUCCESS**.
- `mvn -B verify -pl act-app -am -Dskip.unit.tests=true -Dit.test="RunAdmissionIntegrationTest,
  KanbanAutoDispatchIntegrationTest,KanbanTransitionIntegrityIntegrationTest,
  RunDispatchGroupMigrationIntegrationTest"`: **11/11, BUILD SUCCESS**.
- Frontend: `npx vitest run` → **56 files / 450 tests passed**; `pnpm build` → built.

## Finding caught by this drill (R-ATR4) and its fix

The first Phase-1 drill (pre-rebuild) showed Aria dispatching 3 children that all COMPLETED with
**no batch notification** and a silent listener. Root cause (read-verified): the sandbox core's
dispatch surface is `act-mcp/.../RunTools.runAgent` — Task 8's stamping only covered
`act-aria/.../RunToolHandler` (the host/engine-pipeline surface), so sandbox children carried NULL
`dispatched_by_run_id` and the listener's guard returned silently. Fixed in `95a7140f`: `RunTools`
stamps `dispatchedByRunId` from the transport actor's runId (`McpActorContext.require`;
`ActorPrincipal` worker tokens are run-scoped), operator actors stay unset, no conversationId is
ever stamped; RunToolsTest 23/23 + act-mcp 172/172 + failsafe IT 18/18; plan/spec synced in the
same commit. Scoped re-review: ADDRESSED, no new breakage.

## Step 2 — live drill on the rebuilt stack (all evaluated criteria PASS)

**Phase 1 — dispatch → completion wake → synthesis** (conversation `599ab674-7907-41d3-98e8-ae6973375fac`):
- Aria dispatched 3 children in one turn (run `e8410c4e`); at **+94s (68s after dispatch)** the
  notification arrived:
  `{"title":"子任務批次完成（3 個：成功 3／失敗 0）","body":"Batch e8410c4e-…: Runs: 4a612d63-…:COMPLETED, 3b2bebcb-…:COMPLETED, 0c6b66a5-…:COMPLETED","resourceType":"CONVERSATION","resourceId":"599ab674-…"}`
  — title, per-group body marker, and conversation-scoped resource all as designed.
- `POST /conversations/599ab674-…/synthesize` → 200 with the exact composed template listing all
  three children; the composed prompt sent through the normal chat path produced a real synthesis
  reply (3/3 statistics + per-area hotel findings) in the same conversation.

**Phase 2 — failed-turn visibility** (same conversation; OpenSandbox container stopped to induce a
pre-iteration failure, then restarted):
- The induced turn failed at sandbox creation and the conversation timeline gained exactly one
  synthetic error entry:
  `{"role":"assistant","error":true,"runId":"938691ba-…","retryPrompt":"測試失敗可見性：請只回覆「OK」兩個字。","content":"回合執行失敗：OpenSandbox sandbox creation failed …"}`
  — the failed turn is now visible with its reason and its retry payload (A1), and the conversation
  no longer ends in silence.

**Pass-criteria evaluation:** Step-1 suites ✓ (counts above); dispatch + stamp + group notification ✓;
synthesize endpoint template ✓; end-to-end synthesis turn ✓; failed-turn timeline entry + retryPrompt ✓;
`grep -c "DOCKER::" .run/backend.log` = 0 ✓; no leftover sandbox containers ✓.

## Not evaluated live (recorded honestly)

- The relay-dead sandbox recreate (C2) was **not naturally exercised** during this drill
  (`grep -c "Relay never established\|Recreated sandbox"` = 0); it is covered by
  `OpenSandboxSdkTest`/`SandboxExecutionBackendTest` unit scenarios only.
- The dashboard 「彙整」click itself is unit-tested (NotificationBell/AriaPanel vitest); the live
  drill exercised the endpoint + event contract through the API instead.
- The stream-timeout path intentionally keeps the legacy error payload (per design); the timeline
  entry (exercised above) covers the visible outcome regardless.
