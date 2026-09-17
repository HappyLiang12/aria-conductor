# Qoder CLI Provider (Slices A-C) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `qoder` as a third selectable, governed ADK provider — real Qoder CLI inside OpenSandbox, driven through an in-sandbox ACP bridge, with per-tool HITL for every run, encrypted PAT storage, worker/operator authorization, and a mandatory local real-E2E regression suite pinned to the zero-credit `efficient` model.

**Architecture:** One sandbox per prepared agent (shared lifecycle extracted from the opencode manager) hosting a small ACP bridge; one bridge-owned `qodercli --acp` process per active run. The backend creates approvals from authenticated bridge permission events (host-side), delivers allow-once/deny decisions over the bridge, and enforces write authorization at the MCP execution boundary with one-use grants. See `docs/superpowers/specs/2026-09-17-qoder-cli-agent-core-design.md`.

**Tech Stack:** Java 21 / Spring Boot 3.3 / OpenSandbox SDK `com.alibaba.opensandbox:sandbox:1.0.18`; TypeScript Node 22 bridge + vitest; React 19 / Vite / TanStack Query / Playwright; Flyway (latest in-repo: `V58`, next new: `V59`).

**Spec:** `docs/superpowers/specs/2026-09-17-qoder-cli-agent-core-design.md` (approved 2026-09-17)
**Evidence base:** `docs/reviews/2026-09-17-qoder-cli-acp-spike.md` (partial Windows evidence, plus its Section 11 model/cost addendum)

## Global Constraints

- **Local real E2E is mandatory.** Every slice exit requires real-stack runs on this machine: real Qoder CLI, real OpenSandbox (podman), real backend/frontend. Unit tests and mocks never replace it (design Section 10).
- **All local E2E Qoder runs MUST use `modelId: efficient`** (0.00x Credit, verified 2026-09-17: `total_credits: 0`). The E2E harness fails closed if the configured model is not in `{efficient, lite}` unless `QODER_E2E_ALLOW_PAID=1` is explicitly set. Never assert zero cost as a guarantee — record reported usage per run.
- **CI skips credential-dependent cases with an explicit reason string** (no PAT in CI, no CI edits). Skips must name the missing credential, not hide failures.
- **Frozen CLI facts (2026-09-17):** `-m/--model <model>`; `session/set_model {sessionId, modelId}` accepted (`{}`); help exposes `--setting-sources`, `--plugin-dir`, `--strict-mcp-config`; models list includes `efficient` and `lite` at 0.00x. Effective-model observability inside ACP is NOT verified — A5 records what is observable.
- **PAT handling:** only from the runtime credential store (B7) or an injected env var for probes. Never in argv, logs, SSE events, fixtures, reports, or commits. Tests use synthetic tokens. The PAT is not rotated (recommended to the user); do not print or copy it.
- **`opencode` remains the default provider.** No seed/template/default flips, no CI changes, no removal of existing providers. Slice E is separately approved work.
- **TDD is mandatory** for Java and frontend changes: failing test first, then minimal implementation. Follow the existing repo test tiers (Surefire unit; `*IntegrationTest`/`*E2ETest` run only under `mvn verify`).
- **Build/test commands:** Maven is on PATH (installed 2026-09-12 at `C:\Users\User\tools`). From `agent-control-tower`: filtered run `mvn test -pl act-execution -Dtest="*X*" -Djacoco.skip=true`; full unit lane `mvn clean test -Dspring.profiles.active=h2`; integration lane `mvn verify`. Frontend from `agent-control-tower/act-dashboard`: `pnpm test`, `pnpm build`, `npx playwright test`.
- **`act-common` tests cannot use `act-test-support`** (cyclic): use a local `@DataJpaTest` slice with H2. No `TIMESTAMPTZ` in Flyway migrations. Every new run-scoped child table must be registered in the existing `purgeRuns` cleanup path.
- **No real credentials in fixtures.** Use placeholder tokens like `test-worker-token` in every test and script.
- **Dispatch rules:** implementation agents never run `git add/commit/branch/checkout/reset`; they report diffs and the coordinator commits serially per wave with exact paths. Writers must not run Maven concurrently in the same module; verification agents run builds after the wave. One writer per file per wave. Docs and review artifacts are coordinator-owned.
- Docs and code comments in English. Do not flip unrelated behavior; when touching shared code, existing tests must stay green unchanged.

---

## Frozen Contracts (Wave 0 — coordinator writes these, no dispatch)

These names and shapes are authoritative for all tasks below. If implementation discovers a mismatch, stop and fix the contract here first.

### C0.1 Model pinning

- Config key `qoder.model` (default `auto`; E2E sets `efficient`). Validated against the runtime's advertised model IDs at session creation; unknown ID → explicit provider error, no silent fallback.
- Bridge calls `session/set_model {sessionId, modelId}` right after `session/new`; failure is a provider error.
- The selected model is echoed in a `usage`/`session_started` bridge event so the E2E can assert it.

### C0.2 Bridge API v1 (new component, port 4097 inside the sandbox)

`Authorization: Bearer <BRIDGE_TOKEN>` required on every route except `/health`. Bodies > 1 MiB → 413. Unknown session/request → 404. Ids in URLs are not credentials.

| Route | Request | Response |
|---|---|---|
| `GET /health` | — | `200 {"status":"ok","cliVersion":"1.1.41"}` (bridge liveness only) |
| `POST /sessions` | `{runId, agentId, cwd, model, deadlineSeconds, mcpServers[]}` | `201 {"bridgeSessionId"}` after initialize + `session/new` + `session/set_model` succeed |
| `POST /sessions/{id}/prompt` | `{text}` | `202 {"accepted":true}` (completion arrives via events) |
| `GET /sessions/{id}/events?after=N` | — | `text/event-stream`, `data: {"sequence":N,"type":...}`; ring buffer 1000; too-old `after` → `409 {"error":"REPLAY_GAP"}` |
| `POST /sessions/{id}/permissions/{requestId}` | `{approved, reason?}` | `200 {"outcome":"delivered\|already_resolved\|expired\|unknown"}`; conflicting re-decision → `409 {"error":"ALREADY_RESOLVED"}`; approved with no `allow_once` option → `422 {"error":"UNSUPPORTED_OPTIONS"}` |
| `POST /sessions/{id}/cancel` | — | `202 {"terminated":true\|false}`; rejects pending requests, then SIGTERM→SIGKILL within 10s grace |

Event types: `session_started {model}`, `agent_message {text}`, `tool_call {toolCallId, toolName, kind, status}`, `tool_call_update {toolCallId, status}`, `permission_request {requestId, toolCallId, toolName, title, redactedPreview, inputDigest, options[{optionId, kind, name}], expiresAt}`, `mode_changed {currentModeId}`, `usage {credits, inputTokens, outputTokens}`, `completed {stopReason}`, `failed {reason}`.

### C0.3 ACP call sequence (bridge internals)

1. spawn `qodercli --acp` (argv exactly `["--acp"]`, env allowlist, cwd `/workspace`, no shell).
2. `initialize {protocolVersion:1}` → wait matching response.
3. `session/new {cwd, mcpServers:[{type:"http",name,url,headers:[{name:"Authorization",value:"Bearer …"}]}]}` → wait.
4. `session/set_model {sessionId, modelId}` → wait.
5. prompt: `session/prompt {sessionId, prompt:[{type:"text",text}]}`.
6. permission: reply `{"outcome":"selected","optionId":…}` or `{"outcome":"cancelled"}` on the original JSON-RPC id.
7. cancel: use ACP `session/cancel` only if task A4 observes it; otherwise terminate the child. The chosen path is recorded in the A4 evidence file and in `acp-client.ts` as a constant.

### C0.4 Permission option selection (bridge and Java coordinator share this rule)

- approved → option with `kind == "allow_once"`. Absent → fail closed (`UNSUPPORTED_OPTIONS`); never fall back to the first option or `allow_always`.
- denied → option with `kind == "reject_once"`; else `{"outcome":"cancelled"}`.
- Never select `allow_always`. Mode escalation observed → stop the run with a governance error.

### C0.5 Shared sandbox lifecycle (new class, B1)

```java
package io.aria.conductor.execution.sandbox;

public class SandboxLifecycle {
    public SandboxLifecycle(String serverUrl, String apiKey)
    public String createSandbox(UUID ownerKey, String image, Map<String, String> env) // retrying, see manager
    public void uploadWorkspace(UUID ownerKey, Path workspaceDir)                     // depth 3 / 4 MiB caps kept
    public String getSandboxUrl(String sandboxId, int port)
    public void renewSandbox(String sandboxId, Duration extension)
    public void killSandbox(String sandboxId)
    public String runCommand(String sandboxId, String command)                        // blocking
    public void runBackgroundCommand(String sandboxId, String command, Map<String, String> env)
    public boolean isServerHealthy()
    public Sandbox sandbox(String sandboxId)                                          // escape hatch: raw SDK handle for provider-specific reads (opencode diagnostics)
}
```

Escape-hatch amendment (recorded during B1): `diagnose` stays in the opencode adapter (its log paths are opencode-specific) but its metrics section needs the raw SDK handle, so `sandbox(String)` is part of C0.5 as delivered; providers should use the lifecycle operations for everything else.

`OpenCodeSandboxManager` keeps its exact public API (`createSandbox` 2-arg/3-arg, `uploadWorkspace`, `getSandboxUrl`, `renewSandbox`, `killSandbox`, `runCommand`, `runServeCommand`, `isServerHealthy`) and delegates; `runServeCommand(sandboxId, port, env)` still builds the opencode serve command and delegates to `runBackgroundCommand`. Its existing tests must pass without edits to their expectations.

### C0.6 Provider-resolved task constraints (B2)

```java
public record TaskExecutionConstraints(Duration maxTaskDuration) {}
// AdkProvider:
default TaskExecutionConstraints taskConstraints() { return null; } // null = engine keeps today's opencode fallback
```

`AgentLoopEngine` replaces the direct `openCodeProperties.getMaxTaskMinutes()` read (`AgentLoopEngine.java:721`) with provider-resolved resolution; `OpenCodeAdkProvider` returns the property value (behavior preserved), `QoderAdkProvider` returns `qoder.max-task-minutes`.

### C0.7 Database additions (next free migration numbers; expect V59, V60)

```sql
-- V59__runtime_credential.sql  (B7)
CREATE TABLE runtime_credential (
  provider_id  VARCHAR(64) PRIMARY KEY,
  ciphertext   TEXT        NOT NULL,
  masked_suffix VARCHAR(8) NOT NULL,
  created_at   TIMESTAMP   NOT NULL,
  updated_at   TIMESTAMP   NOT NULL
);

-- V60__approval_source_and_acp_permission.sql  (C1)
ALTER TABLE approval ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'LEGACY_GATE';
CREATE TABLE acp_permission_request (
  approval_id       UUID PRIMARY KEY REFERENCES approval(id),
  run_id            UUID NOT NULL,
  agent_id          UUID,
  bridge_session_id VARCHAR(128) NOT NULL,
  bridge_request_id VARCHAR(128) NOT NULL,
  tool_call_id      VARCHAR(128),
  tool_name         VARCHAR(256) NOT NULL,
  options_json      TEXT,
  request_digest    VARCHAR(128) NOT NULL,
  display_json      TEXT,
  expires_at        TIMESTAMP NOT NULL,
  delivery_state    VARCHAR(32) NOT NULL,
  selected_option_id VARCHAR(64),
  delivered_at      TIMESTAMP,
  CONSTRAINT uq_acp_permission_correlation UNIQUE (bridge_session_id, bridge_request_id)
);
```

`acp_permission_request` is run-scoped: register it in the `purgeRuns` cleanup path with a test.

### C0.8 E2E scenario matrix (C6 — mandatory, real Qoder on `efficient`)

| # | Scenario | Entry point | Core assertions |
|---|---|---|---|
| S1 | Agent create with `qoder` + ordinary run | UI + API | run COMPLETED; selected model == `efficient`; credits reported as observed (no zero-cost claim) |
| S2 | Write attempt → ask → approve once → executes | UI Review card | file created once; second write asks again; option selected is `allow_once` |
| S3 | Deny → no side effect | UI | file absent; CLI session continues; run not cancelled by the denial |
| S4 | Expiry → reject delivered | API (short timeout) | approval EXPIRED; bridge got reject/cancel; run outcome honest; no stale allow delivered later |
| S5 | Cancel during pending permission | UI/API | no run-owned CLI process remains; asks expired with cancel reason; sandbox killed only by fallback |
| S6 | Credential set/mask/remove/test | UI Providers + API | masked responses; missing `PACK_CREDENTIAL_KEY` rejected; test endpoint uses `efficient` |
| S7 | Worker self-approval denied | MCP with run-scoped token | `decide_approval` → 403; no status change |
| S8 | One-use write grant | MCP replay | second identical write call without a new approval fails; no duplicate side effect |
| S9 | Kanban-dispatched run | UI board | card moves; Review card shows the live ask; decision does not mark card DONE |
| S10 | Restart interrupt | kill backend mid-pending | restart reports interruption; pending asks expired; no replay |
| S11 | Read auto-allow vs write gate on platform MCP | run with MCP | read tool runs without ask; write stays unexecuted until approved; audit shows allowed execution with run correlation |
| S12 | Regression: opencode + langchain + UI smoke | existing suites | all pre-existing suites green; no console/network errors on touched routes |

Local invocation example: `pwsh -NoProfile -File scripts/start.ps1 -Provider qoder` then `cd agent-control-tower/act-dashboard && QODER_E2E=1 npx playwright test e2e/qoder-*.spec.ts`. Evidence captured to `docs/reviews/2026-09-17-qoder-slice-<x>-evidence.md` (coordinator-written, raw command output only).

---

## File Structure

**New (Java, act-execution):** `execution/sandbox/SandboxLifecycle.java`; `execution/adk/qoder/{QoderAdkProvider,QoderBridgeClient,QoderProperties,QoderProgressPump}.java`; `execution/credential/RuntimeCredentialService.java`; `execution/approval/{ApprovalDecisionService,AcpPermissionCoordinator,AcpPermissionRequestRepository,WriteGrantService,RunScopedCredentialService}.java`.

**New (Java, act-common / act-app):** `common/model/{RuntimeCredential,ApprovalSource,AcpPermissionRequest}.java` + repositories; migrations `V59__runtime_credential.sql`, `V60__approval_source_and_acp_permission.sql`.

**New (Java, act-mcp):** `mcp/WorkerGovernanceAspect.java`, `mcp/WorkerScopeResolver.java`, `mcp/ToolPolicyRegistry.java`.

**New (Java, act-execution):** `execution/controller/QoderCredentialController.java` + DTOs, beside the existing `AdkProviderController` which already serves `/api/v1/adk/providers` from `execution/controller/` (the provider surface lives in act-execution, not act-dashboard-api).

**New (bridge + image):** `agent-control-tower/qoder-sandbox/{Dockerfile,plugin/…,bridge/{package.json,tsconfig.json,src/{acp-client.ts,server.ts,main.ts,sse.ts},test/*.test.ts}}`.

**New (frontend):** `src/api/qoderCredential.ts`, `src/components/QoderCredentialCard.tsx`, extended `src/components/ReviewPanels.tsx`, `src/types/index.ts` additions, `e2e/qoder-*.spec.ts`.

**New (harness):** `e2e/qoder/{lib,slice-a,slice-c}/*.{mjs,sh}`.

**Modified:** `OpenCodeSandboxManager.java` (delegate), `AdkProvider.java` + `AgentLoopEngine.java` (constraints), `ApprovalController.java` (ApprovalDetail DTO + delegate to decision service), `Approval.java` (source), `ApprovalExpiryChecker.java` (ACP expiry path), `execution/kanban/**` unchanged unless a source-awareness test forces it, `application.yml` (`qoder.*`), `ProvidersPage.tsx`/`CrewPage.tsx` selector, `ReviewPanels.tsx`/`ReviewWorkspace.tsx`/`TaskDrawer.tsx`/`pages/OpsPage.tsx`/`KanbanBoard.tsx` (ACP ask rendering), `scripts/lib/container-runtime.ps1` + `scripts/start.ps1` + `scripts/start-backend.sh` (qoder mode), `docker-compose.yml` only if a new mount is required (avoid by default).

---

## Dependency and Parallelism Map

Rule: one writer per file per wave; implementation agents never touch git; the coordinator commits each wave serially after review; a separate verifier agent re-runs the wave's commands and reports raw output. Writers do not run Maven in the same module concurrently; the verifier does build runs.

| Wave | Parallel tasks | Depends on | Wave exit check |
|---|---|---|---|
| 0 | — (coordinator) | — | contracts above recorded; spike report addendum committed |
| 1 | **A1** | Wave 0 | image builds; real OpenSandbox boot + `qodercli --version` inside |
| 2 | **A2**, **A3**, **A4**, **A5** | A1 (image) | four gate logs recorded; cancel method decision written |
| 3 | **B1**, **B2**, **B3a**, **B7** | Slice A gates pass | unit tests for each; opencode suites untouched-green |
| 4 | **B3b**, **B4**, **B5**, **B8** | B3a (bridge core), A1, B7 | bridge vitest green; image with bridge builds; client tests green |
| 5 | **B6**, **B9**, **B10** | B4, B5, B8 | provider unit tests green; real-sandbox smoke run (no-permission prompt) on `efficient` |
| 6 | verifier + fixes | wave 5 | B-slice real smoke evidence committed |
| 7 | **C1**, **C4** | B green | migrations + auth tests green |
| 8 | **C2**, **C5** | C1 (both); C2 also needs B6 | coordinator unit + UI component tests green |
| 9 | **C3** | C2 | decision dispatch + race tests green |
| 10 | **C6** | C3 | S1-S12 executed locally; evidence committed |
| 11 | **C7** | C6 | acceptance sweep vs design Section 10 with PASS/NOT VERIFIED per item |

Cross-check protocol (each wave): coordinator reads `git diff -- <owned paths>`; verifies no out-of-scope files; dispatches a fresh verifier for raw command output; maps tasks to acceptance criteria; then commits with the task's message.

---

## Slice A — protocol/environment gates (real sandbox, real CLI)

Any failed required gate stops the project and is reported with raw evidence; do not paper over it.

### Task A1: Linux artifact pin + image boot inside OpenSandbox

**Files:**
- Create: `agent-control-tower/qoder-sandbox/Dockerfile`, `e2e/qoder/slice-a/01-boot.md` (findings), `act-execution/src/test/java/io/aria/conductor/execution/qoder/QoderImageBootE2ETest.java`
- Test: the new `*E2ETest` (Failsafe lane)

**Interfaces:**
- Consumes: `OpenCodeSandboxManager(int,String)` — `createSandbox(UUID,String)`, `runCommand(String,String)`, `killSandbox(String)`
- Produces: pinned image tag `aria-conductor/qoder-sandbox:0.1`, CLI version + checksum record for B4

- [ ] **Step 1: Establish the official Linux distribution method** from current official Qoder documentation (install script/package/artifact). Record URL, version `1.1.41+`, and a checksum when the source provides one. If no verifiable distribution exists, STOP and report.
- [ ] **Step 2: Write the Dockerfile** (FROM `node:22-slim`; install the CLI via the verified method pinned to a full version; `WORKDIR /workspace`; `CMD ["qodercli","--version"]` initially).
- [ ] **Step 3: Build** — `docker build -t aria-conductor/qoder-sandbox:0.1 agent-control-tower/qoder-sandbox` (or podman; record which runtime). Capture output.
- [ ] **Step 4: Write the failing test** `QoderImageBootE2ETest` (env-gated: `@EnabledIfSystemProperty(named="qoder.e2e.enabled", matches="true")`), which creates a sandbox from the new image, runs `qodercli --version`, asserts a `1.1.41`-matching semver, then kills the sandbox.
- [ ] **Step 5: Run it red** — `mvn verify -pl act-execution -Dit.test=QoderImageBootE2ETest -Dqoder.e2e.enabled=true -Djacoco.skip=true`; expect failure before the image/pin exists, then green after.
- [ ] **Step 6: Record evidence** to `e2e/qoder/slice-a/01-boot.md` (raw command + output, image digest).
- [ ] **Step 7: Coordinator commit** — `feat(qoder): pin sandbox image and verify OpenSandbox boot`

### Task A2: Config isolation and plugin loading

**Files:**
- Create: `e2e/qoder/slice-a/02-isolation.sh`, `e2e/qoder/slice-a/02-isolation.md`, `agent-control-tower/qoder-sandbox/plugin/manifest.json` (skeleton from verified format)

**Interfaces:** Consumes the A1 image; produces the plugin bundle format + isolation flags for B4.

- [ ] **Step 1:** Discover the plugin manifest format from the CLI itself (`qodercli plugins --help`, `plugins list` with a planted directory) — do not infer from other products.
- [ ] **Step 2:** Script the negative cases: plant a hostile workspace `.mcp.json` (extra server) and hostile user settings; run `qodercli mcp list` and `--setting-sources` variants; assert the hostile server never appears and permission mode cannot be relaxed by project config. Also assert sandbox-level isolation: no container-runtime socket and no host credential mounts are reachable inside the sandbox.
- [ ] **Step 3:** Positive case: `--plugin-dir` loads only the pinned bundle; `--strict-mcp-config` rejects everything not passed on argv.
- [ ] **Step 4:** Run inside the sandbox (via `runCommand`), capture logs. Exit non-zero on any leaked server/plugin.
- [ ] **Step 5:** Record evidence (raw output, chosen flags). **Coordinator commit** — `test(qoder): prove config isolation and plugin loading in sandbox`

### Task A3: Authenticated MCP round-trip with real headers

**Files:**
- Create: `e2e/qoder/slice-a/03-mcp-auth.mjs`, `e2e/qoder/slice-a/03-mcp-auth.md`

**Interfaces:** Extends the spike recipe (`docs/reviews/2026-09-17-qoder-cli-acp-spike.md` Section 8) with a header-validating stub and negative case.

- [ ] **Step 1:** Stub asserts `Authorization: Bearer <synthetic>`; missing/wrong header → 401 and records it.
- [ ] **Step 2:** Positive: session/new with the header entry → tool call succeeds; permission `allow_once` selected by kind.
- [ ] **Step 3:** Negative: header omitted → tool call fails inside the CLI; script asserts the 401 was observed (proves the header path is load-bearing).
- [ ] **Step 4:** Run inside the sandbox with `efficient`; the PAT is injected into the run environment only (never argv) and the script prints no token material. Capture JSON summary (`{pingCalls, permissionGrants, authFailures}`). **Coordinator commit** — `test(qoder): verify authenticated MCP round-trip in sandbox`

### Task A4: Permission semantics — allow-once, deny, cancel, no escalation

**Files:**
- Create: `e2e/qoder/slice-a/04-permissions.mjs`, `e2e/qoder/slice-a/04-permissions.md`

**Interfaces:** Produces the cancel-method decision consumed by C0.3/B3a; produces option-kind evidence for C0.4.

- [ ] **Step 1:** Allow-once: write request → select `kind=allow_once` → file created; assert no `mode_changed` to `acceptEdits`; second write asks again.
- [ ] **Step 2:** Deny: select `reject_once` (or cancelled) → assert the file is absent afterwards.
- [ ] **Step 3:** Cancel while a request is pending: probe whether `session/cancel` exists; if not, terminate and assert no orphan (`ps` inside sandbox empty). Record which path worked.
- [ ] **Step 4:** Capture raw event logs with request IDs (shortened) and the reported `usage`.
- [ ] **Step 5:** Record the cancel decision + option IDs in the evidence file. **Coordinator commit** — `test(qoder): pin allow-once, deny and cancel semantics`

### Task A5: Long wait, renewal, and effective-model observability

**Files:**
- Create: `e2e/qoder/slice-a/05-wait-renewal.mjs`, `e2e/qoder/slice-a/05-wait-renewal.md`

**Interfaces:** Consumes A4's harness; produces renewal/deadline facts for B6/C2.

- [ ] **Step 1:** Create a sandbox, call `renewSandbox(id, 30m)`, assert `expiresAt` moves forward.
- [ ] **Step 2:** Hold a permission request pending for a bounded long wait (e.g. 5 min), answer it, and assert the CLI still executes — proving the wait survives across a renewal window.
- [ ] **Step 3:** Attempt to observe the effective model in ACP (any usage/`session_started` field); record exactly what is observable and what is not — do not infer.
- [ ] **Step 4:** Verify the hard-deadline behavior we will implement is expressible: record wall-clock of prompt start/end. **Coordinator commit** — `test(qoder): record wait, renewal and model observability facts`

---

## Slice B — runtime, credentials, lifecycle (internal; not yet a usable provider)

### Task B1: Extract `SandboxLifecycle`

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/sandbox/SandboxLifecycle.java`
- Modify: `.../adk/opencode/OpenCodeSandboxManager.java` (delegate; public API unchanged)
- Test: `.../execution/sandbox/SandboxLifecycleTest.java` (new), `OpenCodeSandboxManagerTest.java` (unchanged, must stay green)

**Interfaces:** Produces the C0.5 contract.

- [ ] **Step 1:** Write failing tests for the new class covering: retrying create on transient start errors, env forwarding, endpoint scheme completion, renewal, kill, blocking command, background command (virtual thread), server health probe.
- [ ] **Step 2:** Run red — `mvn test -pl act-execution -Dtest="SandboxLifecycleTest" -Djacoco.skip=true`.
- [ ] **Step 3:** Move the logic (do not rewrite semantics: 30-min TTL, 3 attempts, 2s/4s backoff, depth-3 upload, 4 MiB cap, skipHealthCheck=true) and make the manager delegate.
- [ ] **Step 4:** Run green — the new test plus `mvn test -pl act-execution -Dtest="*OpenCode*" -Djacoco.skip=true` (existing expectations untouched).
- [ ] **Step 5: Coordinator commit** — `refactor(execution): extract shared sandbox lifecycle from opencode manager`

### Task B2: Provider-resolved task constraints

**Files:**
- Modify: `.../adk/AdkProvider.java`, `.../adk/opencode/OpenCodeAdkProvider.java`, `.../engine/AgentLoopEngine.java:721`
- Test: `.../engine/AgentLoopEngineConstraintsTest.java` (new), existing provider tests green

**Interfaces:** Produces C0.6; consumed by B6.

- [ ] **Step 1:** Failing test: engine passes `provider.taskConstraints().maxTaskDuration()` into `TaskContext`; null constraints keep today's opencode-properties fallback.
- [ ] **Step 2:** Run red — `mvn test -pl act-execution -Dtest="AgentLoopEngineConstraintsTest" -Djacoco.skip=true`.
- [ ] **Step 3:** Implement the record + default + opencode override; adjust the engine call site.
- [ ] **Step 4:** Green + full module suite (`mvn test -pl act-execution -Dspring.profiles.active=h2`).
- [ ] **Step 5: Coordinator commit** — `feat(execution): resolve task deadline from the provider`

### Task B3a: Bridge ACP client (TypeScript)

**Files:**
- Create: `agent-control-tower/qoder-sandbox/bridge/{package.json,tsconfig.json,src/acp-client.ts,src/env.ts}`, `test/acp-client.test.ts` (vitest, mirror `packages/mcp-server` conventions)

**Interfaces:** Implements C0.3; consumed by B3b. Exposes `createSession(spec): Promise<{sessionId}>`, `prompt(sessionId,text): void`, `onEvent(handler)`, `decide(requestId, approved)`, `cancel(sessionId)`, `close()`.

- [ ] **Step 1:** Failing vitest: spawn a fake CLI (a committed fixture script that emits scripted NDJSON) and assert the exact sequence in C0.3, including `session/set_model`, permission reply shape, rejection of `allow_always`-only offers, and that an unsupported client-method request from the CLI (e.g. an `fs/read_text_file`-class call) receives an explicit JSON-RPC error, never a fabricated success.
- [ ] **Step 2:** Run red — `cd agent-control-tower/qoder-sandbox/bridge && npx vitest run`.
- [ ] **Step 3:** Implement with `node:child_process` + NDJSON codec; env allowlist constants; no shell; argv exactly `["--acp"]`; the env allowlist excludes other providers' LLM credentials (`DEEPSEEK_API_KEY`, `LLM_API_KEY`, DB-managed keys) — a test asserts their absence from the spawned child env.
- [ ] **Step 4:** Green. **Coordinator commit** — `feat(qoder): add ACP client to the sandbox bridge`

### Task B3b: Bridge HTTP/SSE server

**Files:**
- Create: `.../bridge/src/{server.ts,sse.ts,main.ts}`, `test/server.test.ts`

**Interfaces:** Implements C0.2 exactly; consumed by B5.

- [ ] **Step 1:** Failing tests for: bearer 401; session create/prompt; SSE sequencing + `after` replay + `REPLAY_GAP`; permission decision outcomes (`delivered|already_resolved|expired|unknown`), conflicting re-decision 409, `UNSUPPORTED_OPTIONS` 422; cancel semantics; 413 oversized body.
- [ ] **Step 2:** Run red; implement minimal server with a 1000-event ring buffer and pending-permission map with deadlines.
- [ ] **Step 3:** Green. **Coordinator commit** — `feat(qoder): add bridge HTTP/SSE control surface`

### Task B4: Final qoder-sandbox image (CLI + bridge + plugin)

**Files:**
- Modify: `agent-control-tower/qoder-sandbox/Dockerfile` (copy bridge, build `dist`, non-root user, `EXPOSE 4097`, `CMD ["node","/opt/qoder/bridge/main.js"]`), `agent-control-tower/qoder-sandbox/plugin/*` (from A2 format)
- Modify: `scripts/lib/container-runtime.ps1` (add `Ensure-QoderSandboxImage`, mirroring `Ensure-OpencodeSandboxImage`), `act-app/src/main/resources/application.yml` (qoder image tag)

**Interfaces:** Consumes A1 pin + B3b build; produces the runtime image for B6/B10.

- [ ] **Step 1:** Build image; `docker run --rm` smoke: bridge `/health` responds and refuses requests without the bearer.
- [ ] **Step 2:** Extend the container-runtime scenario test (`e2e/container-runtime-e2e.sh/.ps1`) with the qoder image case.
- [ ] **Step 3:** Record evidence; **coordinator commit** — `build(qoder): ship CLI, bridge and plugin bundle in the sandbox image`

### Task B5: `QoderBridgeClient` (Java)

**Files:**
- Create: `.../adk/qoder/QoderBridgeClient.java`, `.../adk/qoder/QoderProperties.java`
- Test: `.../adk/qoder/QoderBridgeClientTest.java` (JDK `com.sun.net.httpserver.HttpServer` stub)

**Interfaces:** Implements the C0.2 client side; consumed by B6.

- [ ] **Step 1:** Failing tests: create/prompt/events(SSE with `after` resume)/decide/cancel/health; 401/404/409/422 mapping to typed errors; SSE reconnect uses last sequence.
- [ ] **Step 2:** Run red; implement with `java.net.http.HttpClient` (mirror `OpenCodeHttpClient` style); no retry of completed mutations.
- [ ] **Step 3:** Green. **Coordinator commit** — `feat(qoder): add Java bridge client and properties`

### Task B6: `QoderAdkProvider`

**Files:**
- Create: `.../adk/qoder/{QoderAdkProvider,QoderProgressPump}.java`
- Test: `.../adk/qoder/QoderAdkProviderTest.java` (unit, mocked client/lifecycle) + `.../qoder/QoderSandboxSmokeE2ETest.java` (real sandbox, env-gated)

**Interfaces:**
- Consumes: `SandboxLifecycle` (C0.5), `QoderBridgeClient` (C0.2), constraints (C0.6), A4 cancel finding.
- Produces: `providerId()=="qoder"`, `supportsTaskExecution()==true`, `executeTask` / `abortTask`; progress events via the same publisher pattern as `OpenCodeAdkProvider.java:189-197`; session mcpServers empty in slice B (C4 wires the scoped token).

- [ ] **Step 1:** Failing unit tests: busy rejection (second concurrent run for one prepared agent fails with a typed error); pending-abort during session creation honored; deadline abort path calls cancel then kill; a caller-provided `TaskContext.maxDuration` is honored (never silently ignored) and a null duration falls back to `qoder.max-task-minutes`; progress events carry sequence + `qoder.model`.
- [ ] **Step 2:** Red → implement (one sandbox per agent, one bridge session per run, renewal heartbeat like `startRenewHeartbeat`, `finally` cleanup).
- [ ] **Step 3:** Green + module suite.
- [ ] **Step 4:** Real-sandbox smoke (env-gated): prompt `Reply with exactly: ok` on `efficient` in a real sandbox → `TaskResult` with output `ok`; assert the bridge reported model `efficient`. Capture raw output.
- [ ] **Step 5: Coordinator commit** — `feat(qoder): add ACP-backed qoder provider with session isolation`

### Task B7: Runtime credential entity + service

**Files:**
- Create: `common/model/RuntimeCredential.java` + repository (act-common), `execution/credential/RuntimeCredentialService.java`, `V59__runtime_credential.sql`
- Test: `RuntimeCredentialRepositoryTest` (local `@DataJpaTest` slice, H2, no `act-test-support`), `RuntimeCredentialServiceTest` (encryption required; masked DTO; delete), `MigrationSmokeTest` green under `mvn verify -pl act-app`

**Interfaces:** Produces `configured()`, `save(pat)`, `read()`, `delete()`, `maskedStatus()`; enforces `PACK_CREDENTIAL_KEY` presence (no Base64 fallback).

- [ ] **Step 1:** Failing tests including: missing `PACK_CREDENTIAL_KEY` → typed rejection on save and on read-for-launch; ciphertext never returned by DTO; suffix masking exact.
- [ ] **Step 2:** Red → implement reusing `PackCredentialCipher` with encryption enforced (guard added in the service, existing pack/LLM behavior untouched).
- [ ] **Step 3:** Green. **Coordinator commit** — `feat(security): add encrypted runtime credential store for qoder`

### Task B8: Credential API + audit exclusion

**Files:**
- Create: `act-execution/src/main/java/io/aria/conductor/execution/controller/QoderCredentialController.java`, `.../controller/QoderCredentialDtos.java`
- Modify: audit/argument-logging path to exclude credential payloads
- Test: controller tests (MockMvc) + audit-exclusion test

**Interfaces:** Endpoints `GET|PUT|DELETE /api/v1/adk/providers/qoder/credential`, `POST .../credential/test` (bounded, discloses cost, uses the configured model; in tests stubbed).

- [ ] **Step 1:** Failing tests: masked GET; PUT never echoes the secret; DELETE revokes; test endpoint maps failures; audit log/event payloads exclude the PAT (synthetic token asserted absent from serialized records).
- [ ] **Step 2:** Red → implement. **Step 3:** Green.
- [ ] **Step 4: Coordinator commit** — `feat(api): expose qoder credential management with masked responses`

### Task B9: Provider metadata + credential UI

**Files:**
- Modify: `act-dashboard/src/pages/ProvidersPage.tsx`, agent selector (`CrewPage.tsx` usage), `src/types/index.ts`
- Create: `src/api/qoderCredential.ts`, `src/components/QoderCredentialCard.tsx`
- Test: vitest component tests + `pnpm build`

**Interfaces:** Consumes B8 API; renders separate states: credential configured, sandbox service, bridge readiness (from existing provider health API).

- [ ] **Step 1:** Failing tests: card shows masked/default/error states; save posts once; remove confirms; `qoder` appears in the selector without changing defaults.
- [ ] **Step 2:** Red → implement → green. **Step 3: Coordinator commit** — `feat(ui): add qoder provider card and credential editor`

### Task B10: Startup/local mode for qoder

**Files:**
- Modify: `scripts/start.ps1`, `scripts/start-backend.sh` (`--provider qoder`), `README.md` mode note
- Test: existing startup script tests (`e2e/startup-e2e.ps1`, `e2e/container-runtime-e2e.*`) extended

**Interfaces:** `-Provider qoder` selects image + provider; never changes the default. qoder mode pins `ARIA_MCP_AUTH_MODE=token` with a locally generated token file so sandboxes cannot reach unauthenticated operator APIs; an explicit override to `none` is refused (design Section 6.2).

- [ ] **Step 1:** Extend script tests red (dry-run assertions), including the refusal case: qoder mode with an explicit `auth-mode: none` override exits with an explicit error, while the default qoder mode pins token auth automatically.
- [ ] **Step 2:** Implement; run the startup e2e script (stub-only, no live sandbox).
- [ ] **Step 3: Coordinator commit** — `feat(startup): add explicit qoder local mode`

---

## Slice C — governance vertical slice (first usable delivery)

### Task C1: Approval source + ACP companion record

**Files:**
- Create: `common/model/{ApprovalSource.java,AcpPermissionRequest.java}` (+repository), `V60__approval_source_and_acp_permission.sql`
- Modify: `common/model/Approval.java` (add `source`, default LEGACY_GATE), purge path registration (`HousekeepingService`)
- Modify: `execution/controller/ApprovalController.java` (`ApprovalDetail` DTO + mapper: add `source` and ACP display fields)
- Test: act-common slice tests; `mvn verify -pl act-app` migration lane; purge test

**Interfaces:** Produces `ApprovalSource.{LEGACY_GATE, ACP_PERMISSION}`; unique correlation constraint; `expiresAt`/`deliveryState` as in C0.7.

- [ ] **Step 1:** Failing tests: legacy records read as LEGACY_GATE; duplicate correlation rejected; purge removes companion rows with the run.
- [ ] **Step 2:** Red → implement migrations + entities (H2-compatible types; no TIMESTAMPTZ).
- [ ] **Step 3:** Green; **coordinator commit** — `feat(approval): persist approval source and ACP permission correlation`

### Task C4: Worker/operator authorization + one-use write grants

**Files:**
- Create: `mcp/{WorkerScopeResolver,WorkerGovernanceAspect,ToolPolicyRegistry}.java`, `execution/approval/{RunScopedCredentialService,WriteGrantService}.java`
- Modify: MCP request path to resolve worker scope; `McpTokenFilter` untouched for operator tokens
- Test: registry-coverage reflection test (every `@Tool` name classified), denial tests per category, grant consumption tests, `RuntimeCredential`/provider endpoints have no worker access

**Interfaces:**
- `ToolPolicyRegistry`: explicit reviewed map; every `@Tool` in `act-mcp/.../tools/*` classified `WORKER_READ | WORKER_WRITE | OPERATOR_ONLY`; unknown → denied.
- `RunScopedCredentialService.issue(runId, expiry)` → token; `resolve(token)` → run scope; revoked on run end/restart.
- `WriteGrantService.grant(runId, toolName, argsDigest)`; `consume(runId, toolName, argsDigest)` single-use; TTL bounded.
- Worker by policy must be denied `decide_approval`, question answering as human, credential/policy changes, agent create/update/run-other, and cross-run impersonation. A worker write call without a consumed grant fails closed.
- MCP coverage: inventory the changed operations across the three tool surfaces (embedded `act-mcp` tools, `packages/mcp-server` TS tools with the PR #89 parity, Aria tool handlers). Credential and bridge-control operations deliberately get no agent-facing tool; `decide_approval` keeps its name and gets the worker denial; if the TS package mirrors embedded names, keep them aligned.

- [ ] **Step 1:** Failing tests (unit, no real MCP): each denial path returns 403-class error and leaves state unchanged; direct MCP write without grant fails even with a valid worker token; identical replay consumes once.
- [ ] **Step 2:** Record the MCP-surface inventory in the task evidence (surface, name, changed or unchanged, test that covers it; TS package suite updated only if mirrored names moved).
- [ ] **Step 3:** Red → implement. **Step 4:** Green + module suites. **Step 5: Coordinator commit** — `feat(mcp): enforce worker scope and one-use write grants`

### Task C2: Host-side ACP permission coordinator

**Files:**
- Create: `execution/approval/{AcpPermissionCoordinator.java,AcpPermissionRequestRepository.java}` (repository may live in act-common per repo convention)
- Modify: `QoderAdkProvider` (wire coordinator callbacks; publish `ApprovalRequestedEvent` with source; pass `mcpServers` — platform MCP URL plus the run-scoped token issued by C4 — into `session/new` at run start, replacing B6's empty list in slice C), `execution/approval/ApprovalExpiryChecker.java` (ACP rows route through the coordinator expiry path instead of only unblocking a gate future)
- Test: unit tests with a fake bridge event source; integration test (H2) creating/deduping/expiring; restart-recovery test

**Interfaces:** Consumes bridge `permission_request` events; validates run/session identity; host-side sanitization recomputes previews/digests before persistence (sandbox input is untrusted; unknown option kinds or malformed events are rejected, never coerced into an allow); creates `Approval(source=ACP_PERMISSION)` + companion atomically; dedupe by unique correlation; changed payload for same correlation → rejected + governance error; expiry = `min(approvals.timeout-ms, run deadline)`, enforced on reads/decisions and by the existing 60s `@Scheduled` checker, whose ACP branch delivers the reject/cancel through the same idempotent `deliverDecision(approvalId, …)` primitive C3 uses. On startup, pending ACP asks whose session cannot be resumed are expired with a restart-interruption reason and no replay (design Section 5.3).

- [ ] **Step 1:** Failing tests: duplicate event → one ask; changed digest → rejected; malformed/unknown-option event → rejected, not coerced into an allow; secret-looking strings are redacted in stored display content; expired-before-decision → EXPIRED and reject delivered to the bridge (short expiry, fake clock); run deadline earlier than approval timeout wins; restart recovery expires pending ACP asks without replay.
- [ ] **Step 2:** Red → implement → green. **Step 3: Coordinator commit** — `feat(approval): create host-side approvals from ACP permission events`

### Task C3: Decision dispatch (legacy vs ACP) and delivery

**Files:**
- Create: `execution/approval/ApprovalDecisionService.java`
- Modify: `ApprovalController.decide` to delegate; `ApprovalGate.decideApproval` unchanged for legacy
- Test: service tests (both sources), race tests (approve vs expire vs cancel → exactly one terminal outcome), delivery idempotency tests

**Interfaces:** Keeps the HTTP contract `POST /api/v1/approvals/{id}/decide {approved, reason}`. ACP path: atomic PENDING→terminal with expiry check; option selected by kind (C0.4); persist decision + delivery state before ack; physical delivery is C2's idempotent `deliverDecision`, retried only while the same run/session/request remains live; approve-after-expiry/cancel rejected; delivered-after-deadline retains APPROVED with an expired delivery outcome (never rewrites the decision). ACP decisions publish `ApprovalDecidedEvent` as today; SDD/kanban routing must ignore them — pinned by test.

- [ ] **Step 1:** Failing tests incl. concurrency (two threads), stale delivery, bridge 409 mapping, approve without `allow_once` → governance error surfaced to the operator, run continues per design (deny ≠ cancel); an ACP decision publishes `ApprovalDecidedEvent` but does not advance a workflow chain or kanban card (`SpecReviewCoordinator` filters `SPEC_REVIEW` only — `SpecReviewCoordinator.java:138`).
- [ ] **Step 2:** Red → implement → green. **Step 3: Coordinator commit** — `feat(approval): dispatch decisions by source with idempotent ACP delivery`

### Task C5: Review-surface UI for ACP asks

**Files:**
- Modify: `src/components/ReviewPanels.tsx` (`DecisionPanel`/`ShortApprovalView`), `src/components/ReviewWorkspace.tsx`, `src/components/TaskDrawer.tsx`, `src/pages/OpsPage.tsx` approval queue, `src/components/KanbanBoard.tsx` ask rendering, `src/types/index.ts`
- Test: `src/components/__tests__/ReviewPanels.acp.test.tsx`, Playwright `e2e/qoder-review-ask.spec.ts` (API-mocked states)

**Interfaces:** Renders source badge, resolved tool identity, sanitized preview/diff, expiry, `Allow once` / `Deny`; excludes ACP asks from `Approve all`; shows decision vs delivery status; stale/expired/concurrently-decided handled without a false success. The Ops queue keeps routing approve/reject through `/decide` (no separate path) and renders the same source-aware states.

- [ ] **Step 1:** Failing component tests for each state (pending, delivered, expired, already resolved elsewhere), including the Ops queue rendering of an ACP ask.
- [ ] **Step 2:** Red → implement → green + `pnpm build`.
- [ ] **Step 3: Coordinator commit** — `feat(ui): render ACP permission asks in the Review surface`

### Task C6: Full regression E2E suite (mandatory, real Qoder on `efficient`)

**Files:**
- Create: `act-dashboard/e2e/qoder-adk-e2e.spec.ts`, `act-dashboard/e2e/qoder-governance-e2e.spec.ts`, `e2e/qoder/slice-c/run-all.sh`, `e2e/qoder/lib/*`
- Create: `docs/reviews/2026-09-17-qoder-slice-c-evidence.md` (coordinator)
- Modify: none of the product code

**Interfaces:** Implements the C0.8 matrix S1-S12. Guard: reads `QODER_E2E_MODEL` (default `efficient`); fails if not in `{efficient, lite}` unless `QODER_E2E_ALLOW_PAID=1`. Skip reason when PAT absent must name the credential and the design section.

- [ ] **Step 1:** Write specs red (they will fail until the stack is up).
- [ ] **Step 2:** Precondition: load the PAT into the runtime credential store via the B8 API (read from the local file; never echoed). Bring up the real stack (`pwsh -NoProfile -File scripts/start.ps1 -Provider qoder`, which pins token auth) and run S1-S12; each scenario records raw output (command + observed result) into the evidence file. Reuse the harness patterns from `e2e/kanban-pickup-e2e.ps1` for S9.
- [ ] **Step 3:** Run the regression set: `mvn clean test -Dspring.profiles.active=h2`, `mvn verify`, `pnpm test`, `pnpm build`, existing Playwright suites, `e2e/container-runtime-e2e.*`.
- [ ] **Step 4:** Any NOT VERIFIED criterion is reported as such — never upgraded to PASS.
- [ ] **Step 5: Coordinator commit** — `test(qoder): add mandatory local E2E regression suite for the governed provider`

### Task C7: Acceptance sweep

**Files:**
- Create: `docs/reviews/2026-09-17-qoder-acceptance.md`

- [ ] **Step 1:** Map design Section 10 items 1-12 to the executed evidence (S1-S12 + suites); state PASS or NOT VERIFIED per item with the raw artifact path.
- [ ] **Step 2:** List remaining risks and the deferred items (hooks, resume, accounting fidelity, default flip = Slice D/E).
- [ ] **Step 3: Coordinator commit** — `docs(qoder): record slice C acceptance against the approved design`

---

## Out of scope (explicitly deferred)

- Slice D: hooks enforcement, reconnect refinements, model/accounting features, cross-process resume.
- Slice E: default-provider replacement, seed/template changes, opencode removal.
- The unrelated Overview/IA revamp; persistent (`allow_always`) grants; generic MCQ rendering; tenant/RBAC redesign.

## Definition of done per slice

- Slice A: A1-A5 evidence files committed; any failed gate stops the project with a written report.
- Slice B: unit + module suites green; the env-gated real-sandbox smoke passes locally; no default/provider regressions.
- Slice C: S1-S12 executed locally with real Qoder on `efficient`; acceptance sweep published; existing suites green.
