# Governed Plugin / Tool-Pack System — Design Spec & Phase-1 Implementation Plan

## 1. Summary

Aria Conductor gains a governed plugin/tool-pack system. A `ToolPack` groups related tools under a namespace with a declared `kind`, shared credentials, and an approval lifecycle. It EXTENDS the existing DB-backed tool registry and `ToolExecutionEngine`; it does not replace them. First deliverable: a git pack enabling read -> modify -> commit -> push -> PR (satisfies GitHub Issue #12). External AI-coding agents are defined as a future `AGENT` kind but deferred.

Design decisions confirmed in brainstorming:
- Plugin kind = unified multi-kind registry: `HANDLER | SCRIPT | MCP | AGENT` (HANDLER/AGENT in-tree; SCRIPT/MCP runtime-addable). Everything routes through `ToolExecutionEngine`.
- Per-run isolated workspace under a writable root; file/shell tools path-jailed; real source stays `/project:ro`.
- Encrypted per-pack credential store; per-agent override of global; env-injected at call time; never logged or written to workspace; host-env fallback.
- Three distinct governance layers: (A) registration approval `PENDING->APPROVED`; (B) grant via existing tables, user-controlled UI, Aria assists; (C) runtime risk gate by `riskTier` (`READ|WRITE_LOCAL|PUSH|DESTRUCTIVE`).
- HITL via a core `request_approval` worker tool (approve/deny only), reusing the existing Approval model + `RunStatus.PAUSED`.
- Sandbox A+B strategy: default `sandboxMode=NONE` with graceful fallback to handler.

Critical feasibility finding (verified): most HITL machinery ALREADY EXISTS.
- `ApprovalGate` (`act-execution/.../approval/ApprovalGate.java:161-258`) — `requestApproval`/`requestTurnApproval` block a virtual thread on a `CompletableFuture`, 30-min TTL -> EXPIRED, publish `ApprovalRequestedEvent`; `decideApproval` unblocks; `cancelAllPendingForRun`.
- `RunContext` (`engine/RunContext.java:101-137`) — `pause/resume/awaitResume`, `getCurrentToolCallId`, `getRunId`.
- `ActionExecutionPipeline` Stage 4 (`pipeline/ActionExecutionPipeline.java:78-98`) already gates on `classification.requiresApproval()`.
- `AgentLoopEngine.executeIteration` (`engine/AgentLoopEngine.java:517-643`) already creates the `ToolCall`, sets `currentToolCallId`, routes through the pipeline; loop pause-check at `:438-443`; `completeRun` at `:490`.
- Approval REST + UI + WS already shipped (`ApprovalController POST /{id}/decide`, `ApprovalsPage.tsx`, `EventBroadcastListener`).

What is genuinely missing (must be built): `ToolPack` + `PackCredential` entities; `riskTier`/`packId`/`kind`/`status` on `ToolDefinition`; `VersionStatus` applied to tools/packs; `WorkspaceManager`; credential encryption; threading `RunContext` into `ToolExecutionEngine`; git pack seed + Docker/compose infra.

## 2. Data Model & Schema

New enums (`act-common/.../model/`): `PackKind {HANDLER, SCRIPT, MCP, AGENT}`; `RiskTier {READ, WRITE_LOCAL, PUSH, DESTRUCTIVE}`. Reuse existing `VersionStatus {PENDING, APPROVED, REJECTED}`.

New entities + repos (`act-common/.../model/` + `repository/`):
- `ToolPack`: id, name (unique namespace), kind, status (VersionStatus), sandboxMode, config (JSON, nullable), createdAt/updatedAt.
- `PackCredential`: id, packId, agentId (nullable = global; non-null = per-agent override), credKey, encValue, updatedAt.

Extend `ToolDefinition` (`act-common/.../model/ToolDefinition.java`) — additive columns: `packId` (nullable = implicit core pack), `kind` (default HANDLER), `riskTier` (default READ), `status` (VersionStatus, default APPROVED). Keep existing `tier`/`category` (capability taxonomy) untouched; `riskTier` is the new governance dimension.

Migration `act-app/src/main/resources/db/migration/V31__tool_packs.sql`:
- `CREATE TABLE tool_packs (...)`, `CREATE TABLE pack_credentials (...)`.
- `ALTER TABLE tool_definitions ADD COLUMN pack_id VARCHAR(36), ADD COLUMN kind VARCHAR(20) DEFAULT 'HANDLER', ADD COLUMN risk_tier VARCHAR(20) DEFAULT 'READ', ADD COLUMN status VARCHAR(20) DEFAULT 'APPROVED'`.
- Backfill so all existing tools are unchanged (implicit core pack, HANDLER, READ, APPROVED).
- Entities MUST match schema exactly — `application.yml` uses `ddl-auto: validate`.

## 3. Execution Flow: Context Threading + Kind Dispatch

Seam (single most important change):
- Add overload `ToolExecutionEngine.execute(String toolName, Map args, RunContext ctx)`; keep the 2-arg method delegating with `ctx=null` (all existing callers/tests unchanged). (`tool/ToolExecutionEngine.java:23`)
- `ActionExecutor.execute` passes `ctx` (`pipeline/ActionExecutor.java:37`).
- Engine injects `workspaceDir`, `runId`, `toolCallId` into handler args (existing `toolName`-injection precedent at `ToolExecutionEngine.java:51-52`) — no `ToolHandler` interface change.

Kind dispatch (thin top-level switch on owning pack kind; null pack = legacy core, behavior unchanged):
- HANDLER -> existing Spring-bean handler path.
- SCRIPT -> existing sandbox path (graceful fallback already at `:31-35`).
- MCP / AGENT -> reserved branches returning "not yet supported" (Phase 2/3).

Registration pre-check (Layer A) in the engine: refuse execution unless tool/pack `status == APPROVED` (defense-in-depth; legacy backfilled APPROVED).

Performance (from Plan B): add Caffeine caches (`common/config/CacheConfig.java` already has `@EnableCaching`) for `findByName` + riskTier + grant resolution; evict on approval/grant writes. Avoids per-call/per-iteration DB reads on the hot path (`AgentLoopEngine.java:532-535`).

## 4. Governance — Three Layers (distinct)

Layer A — Registration approval: `AgentToolResolver.resolveForAgent` (`tool/AgentToolResolver.java:21-34`) and `ToolRegistry.buildToolsPayloadForIds` filter `status==APPROVED`. Minimal pack-management endpoints (register pack/tool as PENDING; approve -> APPROVED). Aria proposes; user confirms via UI (per user prefs: Aria-assist + UI-control; Tool/Skill PENDING->APPROVED).

Layer B — Grant: reuse `AgentTool` / `RoleToolTemplate` / `SkillTool` (migration V21). Granting a pack = granting its tool rows. Final assignment user-controlled via existing agent-tool UI.

Layer C — Runtime risk gate (smallest possible change):
- New `ToolRiskResolver` (cached): `action.name()` -> `ToolDefinition.riskTier`.
- In `ActionClassifier.classify` (`pipeline/ActionClassifier.java:13-32`), consult riskTier: `PUSH`/`DESTRUCTIVE` -> `requiresApproval=true`; `READ`/`WRITE_LOCAL` -> no approval. This feeds the EXISTING Stage-4 `ApprovalGate.requestApproval` (block -> WS -> approve/deny -> TTL EXPIRED). No new state machine.
- Cosmetic dashboard accuracy: set `Run.status=PAUSED` around the blocking gate (reuse `updateRunStatusDirect`), restore `RUNNING` after decision. Resume = future completing (NOT persist-and-restart).

## 5. HITL: request_approval Tool

New `@Component("requestApprovalHandler")` (`tool/handlers/RequestApprovalHandler.java`) implements `ToolHandler`; reads `RunContext` from injected args; builds an `Action`; delegates to `approvalGate.requestApproval(action, ctx)`; returns approve/deny text. Core, always-available worker tool `request_approval(summary, reason)`.

Seed in V32 as a TIER_1 tool granted to all roles via `role_tool_templates` (mirror `V22__seed_standard_tools.sql:48-70`).

## 6. Per-Run Isolated Workspace

New `tool/WorkspaceManager.java`:
- Root from `tools.file.workspace-dir` (env `TOOLS_FILE_WORKSPACE_DIR`, default `/workspaces` in Docker, `./data/workspaces` local) in `application.yml`.
- Lazy `provision(runId)` -> `{root}/{runId}` on first file/shell/git use (avoid overhead for no-IO runs).
- `resolve(runId, relPath)` path-jail: canonicalize + `startsWith` prefix check, reject `..`/absolute escape (same technique as `FileWriteHandler.java:27-30`).
- `cleanup(runId)` on terminal state — hook in `AgentLoopEngine.completeRun` (`:490`); plus `@Scheduled` sweeper for orphan dirs (mirror `ApprovalExpiryChecker`).
- Add `workspaceDir` to `RunContext`.

Jail handlers (guard with `if (ctx != null && workspace exists)`; legacy null-ctx path unchanged): `FileReadHandler` (currently reads arbitrary absolute paths — security fix), `FileWriteHandler`, `FileListHandler` resolve under workspace; `ShellExecHandler` sets `pb.directory(workspace)`.

## 7. Credentials (encrypted per-pack store)

- `PackCredentialCipher`: AES-GCM, key from env `PACK_CREDENTIAL_KEY`; encrypt at rest via JPA `AttributeConverter` or service. (Deliberate hardening BEYOND the current plaintext LLM-key pattern — documented deviation honoring the user's explicit "encrypted" choice.)
- `PackCredentialService.resolve(packId, agentId, credKey)`: per-agent override -> pack-global -> host-env fallback (mirror `LangChainAdkProvider` env fallback).
- Inject into `ProcessBuilder.environment()` at call time; NEVER log (redact `***`), NEVER write to workspace or trajectory.

## 8. Git Pack — First Concrete Pack (satisfies Issue #12)

- `tool_packs` row `git`, `kind=SCRIPT`, `sandboxMode=NONE` (A+B default; sandbox hardening Phase 2).
- New `GitPackHandler` (single dispatcher by `toolName`) wrapping git/gh via `ProcessBuilder`, cwd = run workspace, command whitelist, `GITHUB_TOKEN` env-injected, `Semaphore` to bound concurrent git processes, `timeoutMs` + `destroyForcibly`.
- Seed tools + risk tiers (V32): `git_status`/`git_diff`/`git_log` = READ; `git_add`/`git_commit`/`git_checkout` = WRITE_LOCAL; `git_push`/`git_create_pr` = PUSH; `git_reset_hard`/`force_push` = DESTRUCTIVE.

## 9. Infrastructure (Issue #12 acceptance)

- `agent-control-tower/Dockerfile`: `apt-get install -y git gh` in runtime stage BEFORE `USER aria` (`:25-27`); ensure workspace volume writable by `aria`.
- `docker-compose.yml` backend: add `workspaces` named volume at `/workspaces`; `TOOLS_FILE_WORKSPACE_DIR=/workspaces`; `GITHUB_TOKEN: ${GITHUB_TOKEN:-}`; `PACK_CREDENTIAL_KEY` env.

## 10. Rollout Phasing

- Phase 1 (this plan): pack model + engine ctx threading + kind dispatch + 3-layer governance + `request_approval` + `WorkspaceManager` + credential store + git pack + Issue #12 infra. Backward compatible (legacy tools = implicit core pack).
- Phase 2: GitLab + Playwright packs; MCP-consumer kind; Docker-sandbox git hardening (per-run container reuse, not per-command).
- Phase 3: AGENT kind — sandboxed external coding-agent adapter returning a diff for approval before any commit/push.

## 11. Testing Plan

- Golden/backward-compat: all seeded tools still resolve, classify as non-approval, execute identically (legacy null-ctx path).
- Unit: `ToolRiskResolver` (PUSH/DESTRUCTIVE -> approval), `WorkspaceManager` jail escape rejection, `PackCredentialService` resolution + redaction, `GitPackHandler` command whitelist.
- Integration (`act-test-support` `IntegrationTestBase`, `MockAdkRuntime`): register -> approve -> grant -> execute; runtime gate pause/approve/resume (extend `TurnLevelApprovalTest` pattern); `request_approval` round-trip; git read -> commit in workspace.
- E2E Playwright (`act-dashboard/e2e/`): git-pack lifecycle — agent hits `git_push` -> approval page -> approve -> resume (reuse `ApprovalsPage.tsx`; mirror `workflow-governance.spec.ts`).
- CI: existing `.github/workflows/ci.yml` (Java tests, E2E smoke, Playwright).

## 12. Risks & Mitigations

- `ddl-auto: validate` boot failure if entity != migration -> add columns + V31 in same PR; run `mvn verify` booting context.
- Legacy behavior drift -> behavior-preserving defaults + golden tests.
- Engine signature break -> overload; old 2-arg delegates `ctx=null`; `ActionExecutor` is the only prod caller needing ctx.
- Handler null-ctx regression -> guard all workspace logic behind `ctx != null`.
- PAUSED/resume complexity -> reuse proven blocking-future `ApprovalGate`; cosmetic `Run.status=PAUSED` only; NO persist-and-restart in Phase 1.
- Credential encryption net-new -> AES-GCM with env key; never log/write tokens; document deviation from plaintext LLM pattern.
- Sandbox git push needs network + writable vol -> git pack default `sandboxMode=NONE` in-process; Docker hardening Phase 2.
- Non-root container git -> install before `USER aria`; writable volume ownership.
- In-memory pause/approval maps lost on restart (single-instance) -> startup/`@Scheduled` reaper marks stale PENDING approvals EXPIRED and stale PAUSED runs FAILED; DB-backed signaling flagged as multi-instance prerequisite (Phase 2/3).
- Hot-path DB load -> Caffeine caching with evict-on-write.

## 13. Rejected Alternatives

- Persist-PAUSED-and-restart resume: highest-regression path requiring loop re-entry from DB; the blocking-future `ApprovalGate` already works end-to-end (TTL + WS + UI). Rejected for Phase 1.
- Git pack as per-tool SCRIPT sandbox rows: per-command `docker run` cold start (~300ms-1s x >=5 commands) and current `--network=none --read-only` sandbox can't push; a single in-process `GitPackHandler` (cwd=workspace, whitelist) is faster, simpler, more secure. Sandbox hardening deferred to Phase 2 with per-run container reuse.
- Plaintext credentials (parity with current LLM keys): user explicitly chose an encrypted per-pack store; AES-GCM adopted as a deliberate upgrade.
- MCP-consumer / external-agent now: deferred to Phase 2/3 to bound Phase 1 scope; contracts defined (kinds reserved in dispatch).
- Separate parallel plugin subsystem: rejected in favor of extending `ToolExecutionEngine` to keep a single governed choke point.
- Reusing existing `tier` (TIER_1/2/3) for risk: `tier` is a capability taxonomy; new orthogonal `riskTier` avoids conflating capability with governance risk.
