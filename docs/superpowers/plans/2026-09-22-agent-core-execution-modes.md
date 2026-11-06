# Cross-Core Host/Sandbox Execution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver Qoder and OpenCode in Host and Sandbox modes, preserve governed application/E2E behavior, and retire LangChain through an explicit bounded cleanup operation.

**Architecture:** Keep `adkProvider` and the existing provider inventory as the public core selection seam. Separate protocol adapters, run-owned execution backends, workspace ownership, credentials, and Aria governance; do not add a competing core registry. A distinct test distribution runs the production application against deterministic protocol peers, while native acceptance still exercises all four core/mode combinations.

**Tech Stack:** Java 21, Spring Boot 3.3, existing JPA/Flyway/Maven reactor, OpenSandbox SDK, Node/TypeScript bridge with Vitest, React 19/Vite/Playwright, existing Spring AI 1.0.9 and MCP Java SDK 0.18.3.

**Spec:** [Approved Host/Sandbox and retirement specification](../specs/2026-09-22-agent-core-execution-modes-design.md).

## Global Constraints

- Baseline is `0e3286a`; the current worktree also contains the approved, uncommitted design documents. Preserve them. Planning approval is not implementation, commit, push, deployment, or real-data cleanup authorization.
- One dependency-connected plan is used: replacement execution, its CI coverage, and legacy retirement must agree on contracts and bootstrap. The cleanup command is independently testable but cannot safely be operationally executed before replacement validation.
- First delivery includes **Qoder/HOST, Qoder/SANDBOX, OpenCode/HOST, OpenCode/SANDBOX**. New agents default to **OpenCode + Sandbox**; Host is explicitly selected and means the backend machine.
- Keep `adkProvider` as the core identifier. Add `executionMode`, `workspaceMode`, `workspacePath`, and `workspaceBaseRef`; do not create four composite provider IDs or a second alias for core ID.
- Host is for one operator's trusted repositories. Worktrees are not OS isolation. User repositories are never housekeeping targets; Direct leases cover overlapping canonical directory trees.
- Aria-managed credentials in both modes; no personal-login/config inheritance; no automatic CLI installation. New runtime secrets use authenticated encryption with a required key and no plaintext/Base64 fallback.
- Each run owns its runtime/session. Freeze core/mode/workspace/configuration/credential references before launch. Preserve the shared **45-minute** hard-deadline default; pause and human waiting do not extend it.
- Finalization order is **stop all owned writers -> verify stopped -> export/capture -> destroy environment -> release locks**. A prompt-completion event is not a stop proof.
- Keep truthful pause/resume, cancel, expiry and restart behavior. Native control APIs and OS process guarantees are capability-gated; do not invent an ACP pause method or claim a PID snapshot alone proves all descendants stopped.
- Both cores require approve-once/deny for writes, shell and side-effecting MCP operations. Workers cannot make operator decisions through REST, either Java MCP transport, or the TypeScript MCP proxy.
- **Never obtain green results by deleting/skipping scenarios, disabling tests, lowering thresholds, changing expected behavior to match a bug, or accepting arbitrary outcomes.** Any obsolete LangChain-only test removal requires a recorded replacement/retirement rationale, not merely a failing test.
- Assertions and mock verifications use concrete expected states, bytes, arguments, captured IDs and side-effect counts. Do not use `any()`, `expect.any(...)`, `anything()`, non-null-only assertions, or an alternative-terminal-state list as a substitute for the expected result.
- Required CI/E2E has no real model/account dependency. Mock the external LLM/core protocol boundary, not controllers, approval persistence, business outcomes or the database result state.
- After deterministic regression, run real Aria E2E with Qoder model **`efficient`** in both modes; no `auto`/paid-tier fallback and no CLI echo substitute. Also retain the approved OpenCode live matrix. Unknown usage is not zero.
- Hard-delete only the previewed current LangChain agents and their owned run/approval/audit records. Preserve other-core data, shared business records except approved link removal, settings, credentials and user repositories. No ordinary-startup purge or applied-migration rewrite.
- `act-common` must not depend on `act-test-support`; `act-test-support` already depends on common. Do not introduce execution-to-MCP or agent-to-execution dependency cycles.
- New components may be developed/tested before their production cutover, but do not ship a half-integrated mode, add permissive feature flags, or retain compatibility shims as the final design. Tasks 13-19 form a coordinated integration batch; individual unit success is not an application-readiness verdict.
- Writers own exact files and do not run concurrent Maven builds. The controller runs Java verification serially per wave; Node/frontend lanes may run independently when they do not touch the live stack.
- All repository commands below run from this worktree root; no `cd` to the original repository. Run the existing-worktree check before execution rather than creating another worktree blindly.
- Implementation workers never stage, commit, amend, reset, or push. Each task has a review/commit checkpoint, but an actual new commit requires separate user authorization and controller-owned exact-path staging. Never skip hooks or include unrelated staged work.

## 1. Execution preflight and command discipline

Before an execution session, record `git status --short --branch`, the exact commit and the existing design diff. The current detached HEAD is acceptable for planning, not a reason to discard work. Resolve the execution branch/checkpoint with the operator before committing. Preserve modified documents without a broad stash/reset.

Install/build reactor dependencies once at a wave boundary when needed:

```bash
mvn -f agent-control-tower/pom.xml install -DskipTests
```

Task commands name exact test classes. Surefire runs ordinary `*Test`; Failsafe runs `*IntegrationTest`, `*E2ETest`, and `*ContractTest`. For every targeted invocation, inspect its report and require the expected class/methods to execute with zero failures and zero skipped required cases. A zero-test success is not evidence. Do not mask a wrong selector with `failIfNoTests=false`.

Real probes require explicitly configured credentials, an operator-admitted disposable repository, and approved installed binaries/images. Do not inherit this assistant's SDK environment or search for private login material. Stop at a missing prerequisite and report BLOCKED; do not install a guessed package or downgrade the acceptance requirement.

## 2. File/responsibility map

All new names below are proposed. Existing path anchors are from the inspected baseline.

| Area | Files and responsibility |
|---|---|
| Shared settings/data | `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/Agent.java`, `Run.java`; new common/runtime value types and execution-binding entity |
| Public admission | `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/service/AgentService.java`, `RunService.java`; existing agent DTO/controller files; common admission port prevents a module cycle |
| Runtime kernel | New `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/` contracts, workspace leases, backend registry, lifecycle/finalization and readiness |
| Host backend | New runtime/host supervision; no shell-interpolated launch string and no global process-name killing |
| Sandbox backend | Extract SDK operations from `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeSandboxManager.java`; keep export access until writers stop and results are captured |
| Core protocols | Existing OpenCode HTTP client plus new Qoder bridge client/session adapters; mode-neutral `packages/qoder-acp-bridge/` |
| Governance | Existing approval/controller/repository paths plus normalized permission correlation, one-use grants, actor authentication and expiry delivery |
| MCP identity | `agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/McpServerConfig.java`, `McpTokenFilter.java`, tool callbacks; `packages/mcp-server/src/http-client.ts` and tools |
| Engine/health | `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java`, `health/AgentHealthReconciler.java`, `listener/WorkflowAutoChainer.java`; Aria initializer no longer prewarms a permanent runtime |
| Retirement/setup | New explicit preview/execute/setup services; existing repositories, `HousekeepingService`, Kanban links and workflow JSON are selectively handled |
| UI | Existing Crew/Providers/Review pages, API clients and types; new small runtime selection and operator-access components |
| Test distribution | `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/e2e/`, selected fixtures, and `src/assembly/backend-e2e-harness.xml`; no test controls in production artifacts |
| CI | Existing CI/start-stack/nightly files, preserved Playwright shards and unit/integration groups; separate real-sandbox lifecycle lane |

### 2.1 Frozen type and interface ledger

These are the cross-task contracts. Put each public type in its named Java file. Imports are the corresponding JDK/common/runtime types. Interface declarations are intentional contracts, not stub implementations; implementing tasks are named below.

Common types, owned by Task 2:

```java
public enum ExecutionMode { HOST, SANDBOX }
public enum WorkspaceMode { WORKTREE, DIRECT }
public enum WorkspaceKind { SCRATCH, WORKTREE, DIRECT, SANDBOX_SNAPSHOT }
public record AgentExecutionSettings(String coreId, ExecutionMode executionMode,
        WorkspaceMode workspaceMode, String workspacePath, String workspaceBaseRef) {}
public interface AgentExecutionPolicy {
    AgentExecutionSettings normalize(AgentExecutionSettings requested);
}
```

Runtime types, owned by Task 2, with behavior supplied by Tasks 5-13:

```java
public record ExecutionSpec(java.util.UUID runId, java.util.UUID agentId,
        String coreId, ExecutionMode mode, AgentExecutionSettings settings,
        String credentialRef, String configurationRevision, java.time.Instant deadline) {}
public record CoreTask(String systemPrompt,
        java.util.List<io.aria.conductor.execution.llm.LlmMessage> history,
        String userPrompt) {
    public CoreTask { history = java.util.List.copyOf(history); }
}
public record WorkspaceLease(java.util.UUID leaseId, java.util.UUID runId,
        WorkspaceKind kind, java.nio.file.Path localRoot,
        java.nio.file.Path sourceRoot, String runtimeRoot, String baseCommit) {}
public record PreparedEnvironment(java.util.UUID runId, ExecutionMode mode,
        String environmentId, String workingDirectory, String configurationDirectory,
        java.net.URI endpoint) {}
public record RuntimeHandle(java.util.UUID runId, ExecutionMode mode,
        String environmentId, String ownershipIdentity, java.net.URI endpoint) {}
public record StopProof(java.util.UUID runId, boolean allWritersStopped) {}
public enum ControlState { RUNNING, PAUSED, STOPPED }
public enum ControlStrategy { NATIVE_CHECKPOINT, BACKEND_SUSPEND, UNVERIFIED }
public record ControlAck(ControlState state, boolean verified) {}
public record CoreCapabilities(ControlStrategy pauseStrategy, boolean nativeCancel,
        boolean enforcedRoundLimit, boolean usageObservable) {}
public record UsageSnapshot(Long inputTokens, Long outputTokens,
        java.math.BigDecimal credits, String observedModel) {}
public record CoreResult(String sessionId, String finalOutput,
        UsageSnapshot usage, boolean cancelled) {}
public record CoreEvent(String type, java.util.UUID runId, String sessionId,
        String requestId, String payloadJson) {}
public record ArtifactBundle(java.nio.file.Path directory,
        String manifestSha256, boolean complete) {}
```

`SecretBundle` and `LaunchProfile` must defensively copy collections and override `toString()` with redacted output. They are memory-only and never serialized into execution bindings:

```java
public record SecretBundle(String reference, java.util.Map<String, String> environment) {
    public SecretBundle { environment = java.util.Map.copyOf(environment); }
    @Override public String toString() { return "SecretBundle[redacted]"; }
}
public record LaunchProfile(java.util.List<String> argv, java.util.Map<String, String> env,
        String workingDirectory) {
    public LaunchProfile {
        argv = java.util.List.copyOf(argv);
        env = java.util.Map.copyOf(env);
    }
    @Override public String toString() { return "LaunchProfile[redacted]"; }
}
```

Protocol/backends, implemented by Tasks 8-11:

```java
public interface CoreSession {
    String sessionId();
    java.util.concurrent.CompletionStage<CoreResult> prompt(CoreTask task,
            java.util.function.Consumer<CoreEvent> events);
    java.util.concurrent.CompletionStage<ControlAck> pause(java.time.Instant deadline);
    java.util.concurrent.CompletionStage<ControlAck> resume(java.time.Instant deadline);
    java.util.concurrent.CompletionStage<ControlAck> cancel(java.time.Instant deadline);
    java.util.concurrent.CompletionStage<Void> decide(PermissionReply reply);
}
public interface CoreAdapter {
    String coreId();
    CoreCapabilities capabilities(ExecutionMode mode);
    LaunchProfile launchProfile(ExecutionSpec spec, PreparedEnvironment environment,
            SecretBundle credentials);
    CoreSession open(RuntimeHandle handle, ExecutionSpec spec, SecretBundle credentials);
}
public interface ExecutionBackend {
    ExecutionMode mode();
    PreparedEnvironment prepare(ExecutionSpec spec, WorkspaceLease workspace);
    RuntimeHandle launch(PreparedEnvironment environment, LaunchProfile profile);
    java.util.concurrent.CompletionStage<ControlAck> pauseWriters(RuntimeHandle handle,
            java.time.Instant deadline);
    java.util.concurrent.CompletionStage<ControlAck> resumeWriters(RuntimeHandle handle,
            java.time.Instant deadline);
    StopProof stopWriters(RuntimeHandle handle, java.time.Instant deadline);
    void exportWorkspace(RuntimeHandle handle, java.nio.file.Path destination, StopProof proof);
    void destroy(RuntimeHandle handle);
}
```

Workspace/runtime services:

```java
public interface RuntimeActivity {
    boolean writersStopped(java.util.UUID runId);
    java.util.Set<java.util.UUID> activeRuns(java.util.UUID agentId);
}
public interface WorkspaceService {
    WorkspaceLease acquire(ExecutionSpec spec);
    ArtifactBundle capture(WorkspaceLease lease, ExecutionBackend backend,
            RuntimeHandle handle, StopProof proof);
    void release(WorkspaceLease lease, StopProof proof);
}
```

`ExecutionBackendRegistry.require(ExecutionMode)` returns the one selected backend. `CoreExecutionService.execute(ExecutionSpec, CoreAdapter, CoreTask)` returns `CoreResult`; its `pause(UUID)`, `resume(UUID)` and `cancel(UUID)` return `CompletionStage<ControlAck>`. It implements `RuntimeActivity` and retains the run-owned handle/session rather than resolving mutable agent settings again. `RunFinalizer.finish(ExecutionBackend, RuntimeHandle, WorkspaceLease, Instant)` returns `ArtifactBundle` and owns stop/capture/destroy/release ordering. `RunFinalizer(WorkspaceService)` is its constructor.

Task 4 owns the common security record and Task 12 owns permission types:

```java
public record ActorPrincipal(Role role, java.util.UUID runId, java.time.Instant expiresAt) {
    public enum Role { OPERATOR, WORKER }
    public void requireOperator() {
        if (role != Role.OPERATOR) throw new SecurityException("Operator authority required");
    }
}
public enum PermissionChoice { ALLOW_ONCE, DENY }
public enum PermissionTarget { NATIVE_TOOL, PLATFORM_MCP }
public record PermissionOption(String optionId, PermissionChoice choice) {}
public record NativePermission(java.util.UUID runId, String sessionId, String requestId,
        String toolName, PermissionTarget target, String argumentsJson,
        java.util.List<PermissionOption> options, java.time.Instant expiresAt) {
    public NativePermission { options = java.util.List.copyOf(options); }
}
public record PermissionReply(java.util.UUID runId, String sessionId,
        String requestId, String optionId) {}
```

`PermissionCoordinator.register(NativePermission)` returns the persisted approval UUID. `decide(UUID, PermissionChoice, ActorPrincipal)` returns `PermissionReply` only for a still-valid, correctly bound decision. Delivery is separately tracked and held during manual pause. `WriteGrantService.consume(UUID runId, String toolName, String argumentsDigest)` returns true exactly once for a matching unexpired grant. Neither accepts caller-supplied actor identity.

Task 14 owns `RetirementManifest` (preview UUID, SHA-256 digest, exact agent/run/approval/audit/child ID sets and expiry), `RetirementReceipt` (exact deleted/unlinked counts), and `LegacyRetirementService.preview(ActorPrincipal)` / `execute(UUID previewId, String expectedDigest, ActorPrincipal)`. `LegacySetupService.initializeMissingBuiltins(ActorPrincipal)` runs only after explicit scoped cleanup, never as a silent replacement for it.

### 2.2 Layering and public endpoints

Keep one `AdkProviderRegistry`. The production cutover adapts its existing provider facade to the shared runtime kernel and removes obsolete turn-only methods with their callers. `CoreAdapter` is a protocol port, not a second application provider registry. Existing provider facades may delegate to the common executor; do not duplicate lifecycle in Qoder and OpenCode facades.

Retain `/api/v1/adk/providers`; add mode/capability/readiness data. Its health route accepts an explicit `mode` and returns that mode, configuration readiness, runtime state and reason codes. Missing mode on this read-only route uses the documented default; runtime creation never silently changes an explicit mode.

New operator surfaces planned here:

| Route | Contract |
|---|---|
| `POST /api/v1/operator/session` | Authenticate separately configured operator bearer credential; issue HttpOnly session cookie and CSRF token |
| `DELETE /api/v1/operator/session` | Revoke that session; no core credential deletion |
| `GET/PUT/DELETE /api/v1/adk/providers/qoder/credential` | Masked metadata / encrypted replacement / future-launch revocation; operator only |
| `POST /api/v1/adk/providers/qoder/credential/test` | Explicit bounded credential test, never a health poll |
| `POST /api/v1/maintenance/langchain/preview` | Read-only exact target preview, operator only |
| `POST /api/v1/maintenance/langchain/execute` | `{previewId, expectedDigest}`; explicit unchanged-manifest hard deletion |
| `POST /api/v1/maintenance/initialize-builtins` | Explicit setup after cleanup, idempotent, creates only missing built-ins |

All names in this ledger are defined by the tasks below. Native wire method names are deliberately not guessed: Task 1 produces recorded version-specific protocol evidence and a reviewed control-driver choice. If it cannot demonstrate required control semantics, stop before enabling that combination and return to the design decision; a mocked control is not a substitute.

## 3. Dependencies and parallel waves

The controller verifies file disjointness before dispatch. One Java-reactor verifier runs Maven after a wave; Java writers do not race on shared `target/` or local repository outputs. Native capability probes use disposable sources, not a changing application stack. No writers/builds run during the final live application matrix.

| Wave | Tasks | Parallelism / dependency | Exit evidence |
|---|---|---|---|
| W0 | 1, 2 | Native/protocol probe lane and common-contract lane can be independent; enabling runtime waits for Task 1 | Version/control evidence; typed/schema tests |
| W1 | 3, 4 | Admission and auth files disjoint; one Java verification lane | Exact default/error and actor-boundary tests |
| W2 | 5, 6, 7 | Credentials, workspace, Node peers; distinct files/migrations | Secret redaction, leases, deterministic peer cases |
| W3 | 8, 9 | Node bridge and Java Host backend | Framing/permission and owned-process tests |
| W4 | 10, 11, 12 | Sandbox backend, native clients, approval coordination; serialize Maven | Backend lifecycle, adapter protocol, one-use grants |
| W5 | 13, 14 | Runtime coordinator and explicit retirement/setup; shared interfaces already frozen | Control/finalization and mixed-core purge tests |
| W6 | 15, 16 | UI/API integration and test distribution; separate frontend/Maven lanes | Browser component checks; isolated harness packaging/start |
| W7 | 17 | Port existing E2E using the harness | Concrete outcome assertions and coverage map |
| W8 | 18 | Production cutover/removal; sole owner of old runtime/default/startup files | Production artifact and retained-scenario regression |
| W9 | 19 | CI producers/consumers/path filters after replacement exists | Secret-free CI plus separate real-sandbox lifecycle lane |
| W10 | 20 | Freeze code; serial/bounded real model calls, no parallel writers | Full regression and live four-combination evidence |
| W11 | 21 | Separate operator authorization and exact target environment | Scoped cleanup receipt and protected-data check |

Task dependencies are explicit in each heading. W5-W9 is a coordinated integration batch: do not publish a partly cut-over stack or claim full CI readiness from a component-only test. An unexpected failure blocks its gate; classify environment/flake/regression before changing expectations.

## 4. Implementation tasks

### Task 1: Establish native capability and test-preservation gates

**Depends on:** Execution authorization and approved installed CLI/image candidates; no product code prerequisite.

**Files:**
- Create: `e2e/agent-core/capability-gate.mjs`
- Create: `e2e/agent-core/capability-gate.test.mjs`
- Create: `e2e/agent-core/probe-native.mjs`
- Create: `e2e/agent-core/coverage-map.json`
- Create: `e2e/agent-core/fixtures/` versioned sanitized protocol recordings
- Create: `docs/reviews/2026-09-22-agent-core-capability-evidence.md` when probes actually run

**Interfaces:** Produces the recorded core/OS/version/control matrix and exact protocol fixtures consumed by Tasks 7-11; `requireCapabilities(rows)` fails unless all four required combinations have observed handshake, managed auth, isolated config, permission, pause/resume, cancel and writer-stop/export evidence.

- [ ] **Step 1: Write the failing gate test**

```js
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { requireCapabilities } from './capability-gate.mjs';
test('missing pause evidence blocks a claimed combination', () => {
  const rows = [{ core: 'qoder', mode: 'HOST', checks: { pauseResume: false } }];
  assert.throws(() => requireCapabilities(rows), /qoder\/HOST.*pauseResume/);
});
```

- [ ] **Step 2: Verify red** — `node --test e2e/agent-core/capability-gate.test.mjs`; expect the missing gate implementation, not a credential/network failure.
- [ ] **Step 3: Implement the gate and native probe drivers**

```js
const pairs = ['qoder/HOST', 'qoder/SANDBOX', 'opencode/HOST', 'opencode/SANDBOX'];
const checks = ['pauseResume', 'handshake', 'managedAuth', 'isolatedConfig',
  'allowOnce', 'denyWithoutWrite', 'cancel', 'writersStopped', 'stableExport'];
export function requireCapabilities(rows) {
  for (const pair of pairs) {
    const row = rows.find(r => `${r.core}/${r.mode}` === pair);
    for (const check of checks) {
      if (row?.checks?.[check] !== true) throw new Error(`${pair}: ${check} not verified`);
    }
  }
}
```

Implement `probe-native.mjs` as an operator-invoked harness with explicit `--core`, `--mode`, `--workspace`, `--output` and installed binary/image configuration. Clear ambient SDK/auth/config sources. Qoder probes pin `efficient`; reject other requested model IDs. Record actual argv without secret values, version/hash/license source, framing, model acknowledgment, offered permission kinds, expiry/cancel and pause behavior. Use independent file bytes/process checks rather than native success text. Verify the OS-owned process group/job control technique; do not use an unverified pause RPC or a process-name kill. Freeze the successful control technique and sanitized wire recordings before adapter implementation. If native or supervised checkpoint/resume cannot satisfy the spec, stop this gate and seek a design decision; do not manufacture a positive capability row.

Inventory existing Java/Playwright test cases into `coverage-map.json` with original case identity, purpose, replacement case and rationale. List existing disabled/credential-gated cases explicitly; retained business purposes must get runnable deterministic replacements.

- [ ] **Step 4: Verify green and probe evidence** — rerun the Node gate test; then run the explicit native matrix against disposable workspaces. Check that every capability boolean has captured supporting output. Missing credentials/capability is BLOCKED, not a skipped PASS.
- [ ] **Step 5: Review checkpoint** — freeze evidence/fixtures and coverage mapping. Suggested authorized controller commit: `test(runtime): establish native capability gates`.

### Task 2: Add immutable settings/binding contracts and additive schema

**Depends on:** None for pure types; native support claims remain blocked by Task 1.

**Files:**
- Modify: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/Agent.java:36-48`
- Modify: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/Run.java:19-57`
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/runtime/ExecutionMode.java`
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/runtime/WorkspaceMode.java`
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/runtime/WorkspaceKind.java`
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/runtime/AgentExecutionSettings.java`
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/runtime/AgentExecutionPolicy.java`
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/RunExecutionBinding.java`
- Create the runtime ledger types under `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/` as individually named files from Section 2.1
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/repository/RunExecutionBindingRepository.java`
- Create: `agent-control-tower/act-app/src/main/resources/db/migration/V59__agent_execution_bindings.sql`
- Test: `agent-control-tower/act-common/src/test/java/io/aria/conductor/common/runtime/ExecutionSettingsTest.java`
- Test: `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/ExecutionBindingMigrationIntegrationTest.java`

**Interfaces:** Produces the Section 2.1 types. Binding repository key is run UUID; store core, mode, serialized non-secret settings, workspace ownership, credential reference, revision, deadline, runtime identity/state and known/unknown usage. Do not store secret environments.

- [ ] **Step 1: Write exact snapshot/default tests**

```java
@Test
void explicitHostSettingsRemainDistinctFromSandbox() {
    var settings = new AgentExecutionSettings("qoder", ExecutionMode.HOST,
            WorkspaceMode.WORKTREE, "C:/projects/example", "main");
    assertThat(settings.coreId()).isEqualTo("qoder");
    assertThat(settings.executionMode()).isEqualTo(ExecutionMode.HOST);
    assertThat(settings.workspaceMode()).isEqualTo(WorkspaceMode.WORKTREE);
    assertThat(settings.workspaceBaseRef()).isEqualTo("main");
}
```

- [ ] **Step 2: Verify red** — `mvn -f agent-control-tower/pom.xml -pl act-common -Dtest=ExecutionSettingsTest test`.
- [ ] **Step 3: Implement the ledger types and migration** — use the exact signatures in Section 2.1. Add agent columns and a run-owned binding table. Preserve nullable unknown legacy execution metadata; backfill only existing OpenCode agent mode to SANDBOX, not historical run core. Keep `adk_provider` as a string so an unapproved legacy cleanup does not prevent schema upgrade.

```sql
ALTER TABLE agents ADD COLUMN execution_mode VARCHAR(16);
ALTER TABLE agents ADD COLUMN workspace_mode VARCHAR(16);
ALTER TABLE agents ADD COLUMN workspace_path TEXT;
ALTER TABLE agents ADD COLUMN workspace_base_ref VARCHAR(255);
UPDATE agents SET execution_mode = 'SANDBOX' WHERE adk_provider = 'opencode';
```

Complete the binding table from the fields above with a UUID run key and optimistic-lock version; add repository tests for insert-once, unchanged snapshots after agent edits, and secret-free serialization. New schema is additive; no data purge is placed in V59. Reserve later migration numbers centrally; if another branch occupies these names, renumber the entire plan's future migration set before execution, not an applied migration.

- [ ] **Step 4: Verify** — run `ExecutionSettingsTest`; after the reactor dependency build, run `mvn -f agent-control-tower/pom.xml -pl act-app -Dskip.unit.tests=true -Dit.test=ExecutionBindingMigrationIntegrationTest verify`. Assert legacy binding absent, OpenCode agent mode SANDBOX, and exact unchanged unrelated rows.
- [ ] **Step 5: Review checkpoint** — suggested authorized controller commit: `feat(runtime): record immutable execution bindings`.

### Task 3: Normalize admission without provider or workspace fallback

**Depends on:** Task 2.

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreCatalog.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/DefaultAgentExecutionPolicy.java`
- Modify: `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/dto/CreateAgentRequest.java`
- Modify: `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/dto/UpdateAgentRequest.java`
- Modify: `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/dto/AgentResponse.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/AgentExecutionPolicyTest.java`

**Interfaces:** `CoreCatalog.supports(String, ExecutionMode)` reflects registered production core descriptors, not another execution registry. `DefaultAgentExecutionPolicy(CoreCatalog)` implements the common `AgentExecutionPolicy` port; agent-module consumers never import execution-module classes. Production consumer wiring is completed in Task 18.

- [ ] **Step 1: Write rejection/default tests**

```java
@Test
void anExplicitRemovedCoreDoesNotBecomeTheDefault() {
    var catalog = new CoreCatalog(java.util.Map.of(
            "qoder", java.util.Set.of(ExecutionMode.HOST, ExecutionMode.SANDBOX),
            "opencode", java.util.Set.of(ExecutionMode.HOST, ExecutionMode.SANDBOX)));
    var policy = new DefaultAgentExecutionPolicy(catalog);
    var request = new AgentExecutionSettings("langchain", ExecutionMode.HOST,
            WorkspaceMode.DIRECT, "C:/repo", null);
    assertThatThrownBy(() -> policy.normalize(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Unsupported agent core: langchain");
}
```

- [ ] **Step 2: Verify red** — `mvn -f agent-control-tower/pom.xml -pl act-execution -Dtest=AgentExecutionPolicyTest test`.
- [ ] **Step 3: Implement pure normalization**

```java
String core = requested.coreId() == null ? "opencode" : requested.coreId();
ExecutionMode mode = requested.executionMode() == null
        ? ExecutionMode.SANDBOX : requested.executionMode();
if (!catalog.coreIds().contains(core))
    throw new IllegalArgumentException("Unsupported agent core: " + core);
if (!catalog.supports(core, mode))
    throw new IllegalArgumentException("Unsupported execution mode: " + core + "/" + mode);
WorkspaceMode workspaceMode = requested.workspacePath() == null ? null
        : requested.workspaceMode() == null ? WorkspaceMode.WORKTREE : requested.workspaceMode();
return new AgentExecutionSettings(core, mode, workspaceMode,
        requested.workspacePath(), requested.workspaceBaseRef());
```

Implement `CoreCatalog.coreIds()` and the constructor used above with immutable collections. Validate contradictory workspace fields, explicit empty/unknown IDs and Host path admission separately from credential readiness. No missing credential causes a core/mode substitution. Extend DTOs consistently; preserve `adkProvider` rather than introduce a competing field name.

- [ ] **Step 4: Verify** — exact cases for omitted new settings, explicit Qoder/HOST, removed core, unsupported mode, invalid worktree/direct fields; every invalid case asserts its exact error and no launch call.
- [ ] **Step 5: Review checkpoint** — suggested authorized controller commit: `feat(runtime): validate explicit core and placement`.

### Task 4: Establish operator and run-scoped worker identity

**Depends on:** Task 2.

**Files:**
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/security/ActorPrincipal.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/security/ActorTokenService.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/security/ActorAuthenticationFilter.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/controller/OperatorSessionController.java`
- Create: `agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/McpActorContext.java`
- Modify: `agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/McpServerConfig.java`
- Modify: `agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/McpTokenFilter.java`
- Modify: `packages/mcp-server/src/http-client.ts`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/security/ActorTokenServiceTest.java`
- Test: `agent-control-tower/act-mcp/src/test/java/io/aria/conductor/mcp/McpActorTransportIntegrationTest.java`

**Interfaces:** `ActorTokenService.authenticateBearer(String)` returns a verified `ActorPrincipal`; `issueWorker(UUID, Instant)` returns a random opaque run-bound token; `revokeRun(UUID)` invalidates it. Operator session creation/revocation use the endpoints in Section 2.2 and a separately configured operator credential. `McpActorContext.require(ToolContext)` returns transport-owned identity, never input JSON identity.

- [ ] **Step 1: Write expiry and authority tests**

```java
@Test
void aWorkerCannotBecomeAnOperator() {
    UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000101");
    var actor = new ActorPrincipal(ActorPrincipal.Role.WORKER, runId,
            Instant.parse("2026-09-22T12:30:00Z"));
    assertThat(actor.runId()).isEqualTo(runId);
    assertThatThrownBy(actor::requireOperator).isInstanceOf(SecurityException.class)
            .hasMessage("Operator authority required");
}
```

- [ ] **Step 2: Verify red** — run `ActorTokenServiceTest` in act-execution and `McpActorTransportIntegrationTest` through Failsafe in act-mcp. Include actual HTTP requests over both MCP transports; a direct callback-only test is insufficient.
- [ ] **Step 3: Implement authentication and SDK context propagation** — use random opaque tokens with hashed server-side lookup, explicit expiry and constant-time operator credential comparison. Session cookies are HttpOnly/SameSite; validate Origin/CSRF on cookie-authenticated mutations. Do not put operator/core secrets in frontend assets, URLs or worker environments.

The installed SDK signatures were inspected with `javap`: both transport builders have `contextExtractor`, `McpSyncServerExchange.transportContext()` exists, and Spring AI exposes `McpToolUtils.getMcpExchange(ToolContext)`. Use these real APIs:

```java
.contextExtractor(request -> io.modelcontextprotocol.common.McpTransportContext.create(
        java.util.Map.of("aria.actor", actors.authenticateBearer(
                request.headers().firstHeader("Authorization")))))
```

```java
public static ActorPrincipal require(org.springframework.ai.chat.model.ToolContext context) {
    var exchange = org.springframework.ai.mcp.McpToolUtils.getMcpExchange(context)
            .orElseThrow(() -> new SecurityException("Missing MCP exchange"));
    Object value = exchange.transportContext().get("aria.actor");
    if (!(value instanceof ActorPrincipal actor))
        throw new SecurityException("Missing transport actor");
    return actor;
}
```

Wire an explicit SSE transport with the same extractor, not just the manually configured streamable transport. Bind MCP sessions to their authenticated actor/run and reject cross-actor session reuse. Guard callbacks before invocation; add `ToolContext` only as framework-owned context, not a caller argument. Propagate the configured actor token through the TypeScript MCP HTTP client. Expiry/cancel system actions use dedicated internal service methods, not a forgeable external SYSTEM token. Complete UI session consumers in Task 15 and production filter registration at cutover; do not enable an unauthenticated test override.

- [ ] **Step 4: Verify** — exact 401 for absent/invalid identity; exact 403 for worker operator-actions; exact run scope in both transports; no actor leakage across concurrent sessions; expired/revoked tokens rejected. Verify supplied `_actor`, `runId`, and transport session spoofing cannot replace authenticated identity.
- [ ] **Step 5: Review checkpoint** — suggested authorized controller commit: `feat(security): separate operator and run authority`.

### Task 5: Resolve managed secrets and controlled launch configuration

**Depends on:** Tasks 2, 4; native variable/flag facts from Task 1.

**Files:**
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/RuntimeCredential.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/credential/RuntimeCredentialService.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreConfigurationService.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/controller/QoderCredentialController.java`
- Create: `agent-control-tower/act-app/src/main/resources/db/migration/V60__runtime_credentials.sql`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/credential/RuntimeCredentialServiceTest.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/CoreConfigurationServiceTest.java`

**Interfaces:** `RuntimeCredentialService.resolve(String credentialRef)` returns a `SecretBundle`; `putQoder(String, ActorPrincipal)` returns masked metadata, never the secret; `deleteQoder(ActorPrincipal)` prevents future resolution. `CoreConfigurationService.prepare(ExecutionSpec, PreparedEnvironment, SecretBundle)` returns the immutable core-specific launch configuration, with generated files outside a Direct user repository.

- [ ] **Step 1: Write exact redaction/key-boundary tests**

```java
@Test
void launchObjectsNeverRevealTheirSecretEnvironmentInToString() {
    var bundle = new SecretBundle("qoder:test", java.util.Map.of("RUNTIME_SECRET", "synthetic-secret-42"));
    assertThat(bundle.toString()).isEqualTo("SecretBundle[redacted]");
    assertThat(bundle.environment().get("RUNTIME_SECRET")).isEqualTo("synthetic-secret-42");
}
```

- [ ] **Step 2: Verify red** — run `RuntimeCredentialServiceTest,CoreConfigurationServiceTest` in act-execution. Use synthetic secrets and a fixed clock.
- [ ] **Step 3: Implement encryption and controlled environment** — AES-GCM with a fresh nonce, required configured key, core/reference binding as authenticated data, and masked responses. Missing key fails, not Base64 fallback. Reuse existing model-provider resolution for OpenCode rather than duplicate/delete its keys. Keep runtime secret persistence distinct from existing protected settings.

```java
Map<String, String> environment = new java.util.LinkedHashMap<>();
for (String name : allowedSystemVariables) {
    String value = System.getenv(name);
    if (value != null) environment.put(name, value);
}
environment.putAll(credentials.environment());
return new LaunchProfile(argv, environment, preparedWorkingDirectory);
```

`allowedSystemVariables`, `argv`, and `preparedWorkingDirectory` are local values produced by the verified core profile in `prepare`; they are not copied from a worker request. Freeze exact OS/core allowlists from Task 1. Exclude personal HOME/config, SDK-entrypoint payloads, arbitrary NODE_OPTIONS and ambient MCP configuration. Generate only approved plugin/settings/MCP sources, inject run-scoped worker credentials, and scrub known secrets from diagnostic/progress/artifact boundaries. A credential test is explicit and bounded; ordinary readiness does not call a model.

- [ ] **Step 4: Verify** — encryption round trip and tamper rejection; missing key; exact masked fields; deletion prevents new resolution; log/HTTP/event payloads omit the exact synthetic secret; source repo configuration bytes remain unchanged; hostile ambient config does not alter the generated profile.
- [ ] **Step 5: Review checkpoint** — suggested authorized controller commit: `feat(runtime): isolate managed launch credentials`.

### Task 6: Implement workspace leases, worktrees and retained artifacts

**Depends on:** Tasks 2, 3.

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/WorkspacePaths.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/RunWorkspaceService.java`
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/RunWorkspaceLease.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/repository/RunWorkspaceLeaseRepository.java`
- Create: `agent-control-tower/act-app/src/main/resources/db/migration/V61__run_workspace_leases.sql`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/tool/WorkspaceManager.java:54-125`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/RunWorkspaceServiceTest.java`

**Interfaces:** `WorkspacePaths.overlaps(Path, Path)` resolves real paths and checks directory-tree overlap. `RunWorkspaceService` implements `WorkspaceService`; persist lease ownership/kind/state. `release` requires a matching verified `StopProof`; retention is separate from unlocking.

- [ ] **Step 1: Write an exact overlap test using real temporary directories**

```java
@Test
void parentAndChildDirectoriesConflict() throws Exception {
    Path repo = Files.createDirectories(temp.resolve("repo"));
    Path child = Files.createDirectories(repo.resolve("src"));
    Path other = Files.createDirectories(temp.resolve("other"));
    assertThat(WorkspacePaths.overlaps(repo, child)).isEqualTo(true);
    assertThat(WorkspacePaths.overlaps(repo, other)).isEqualTo(false);
}
```

Declare `@TempDir Path temp` in the test class. Add a two-writer test that asserts the first lease ID is retained and the second acquisition returns the exact conflict error, not an arbitrary failure.

- [ ] **Step 2: Verify red** — `mvn -f agent-control-tower/pom.xml -pl act-execution -Dtest=RunWorkspaceServiceTest test`.
- [ ] **Step 3: Implement canonical leases and ownership-aware lifecycle**

```java
public static boolean overlaps(Path left, Path right) throws IOException {
    Path a = left.toRealPath();
    Path b = right.toRealPath();
    return a.startsWith(b) || b.startsWith(a);
}
```

Acquire/check leases atomically under a process-wide lock and persist their run IDs and canonical roots. Rehydrate unresolved leases before admitting new Direct writers after restart. Worktree creation uses a resolved commit and a unique managed directory/ref; preserve the source index/dirty files and serialize platform-owned common-git-directory mutations. Run git with explicit argv, not an interpolated shell command. Store platform config outside the source/worktree and never merge/commit/push automatically.

For capture, require stopped writers, export into an Aria-owned result directory, reject unsafe links/path traversal, exclude generated secret-bearing files and compare against the pre-run baseline. Keep Direct directories and retained worktrees out of the existing age-only sweeper; scratch cleanup requires positive ownership and a stopped run.

- [ ] **Step 4: Verify** — exact bytes for dirty-source preservation, output diff, Direct modifications, and sibling/ancestor/junction cases on the relevant OS; failed worktree creation does not fall back; wrong-run or false stop proof does not release the lease; repository contents survive cleanup. Run integration tests with disposable git repositories, not the operator's checkout.
- [ ] **Step 5: Review checkpoint** — suggested authorized controller commit: `feat(runtime): retain and protect run workspaces`.

### Task 7: Build deterministic executable protocol peers

**Depends on:** Task 1 protocol recordings; Task 2 event/control contracts.

**Files:**
- Create: `agent-control-tower/act-app/src/test/resources/e2e/peers/mock-qoder.mjs`
- Create: `agent-control-tower/act-app/src/test/resources/e2e/peers/mock-opencode.mjs`
- Create: `agent-control-tower/act-app/src/test/resources/e2e/peers/peer-actions.mjs`
- Create: `agent-control-tower/act-app/src/test/resources/e2e/peers/peer-actions.test.mjs`
- Create: `agent-control-tower/act-app/src/test/resources/e2e/scenarios.json`

**Interfaces:** Each peer is a real Node process with an operator/harness-selected scenario and a disposable workspace. Qoder speaks recorded ACP framing; OpenCode exposes the verified native HTTP/event subset. Neither writes run outcome rows. `applyDecision(optionId, options, target, contents)` performs only a granted fixture write.

- [ ] **Step 1: Write denial without side-effect assertions**

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { applyDecision } from './peer-actions.mjs';
test('denial preserves the original bytes', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'aria-peer-'));
  const target = join(dir, 'result.txt');
  await writeFile(target, 'before\n');
  const result = await applyDecision('deny-7', [
    { optionId: 'allow-7', kind: 'allow_once' },
    { optionId: 'deny-7', kind: 'reject_once' },
  ], target, 'after\n');
  assert.deepEqual(result, { status: 'denied', writes: 0 });
  assert.equal(await readFile(target, 'utf8'), 'before\n');
});
```

- [ ] **Step 2: Verify red** — `node --test agent-control-tower/act-app/src/test/resources/e2e/peers/peer-actions.test.mjs`.
- [ ] **Step 3: Implement peers using the recorded protocol shapes**

```js
import { writeFile } from 'node:fs/promises';
export async function applyDecision(optionId, options, target, contents) {
  const selected = options.find(option => option.optionId === optionId);
  if (selected?.kind === 'reject_once') return { status: 'denied', writes: 0 };
  if (selected?.kind !== 'allow_once') throw new Error('Unsupported fixture decision');
  await writeFile(target, contents, 'utf8');
  return { status: 'written', writes: 1 };
}
```

Implement initialization/session ordering, model acknowledgment, streaming, repeated requests with distinct IDs, native denial/cancel and recorded control behavior. Where Task 1 selects backend suspension, test real process suspension rather than inventing a mock-only ACP pause method. Add scenarios `complete`, `write-twice`, `deny-write`, `permission-expiry`, `cancel-pending`, `pause-resume`, `malformed-frame`, `disconnect`, `invalid-auth`, `unsupported-model`, and `background-writer`. The last child keeps attempting writes after prompt completion so finalization ordering can be proven. All fixture paths are confined to the admitted temporary workspace. Scenario setup is authenticated and test-only.

- [ ] **Step 4: Verify** — peer tests assert exact frames/IDs/bytes/counts; wrong option/allow-always rejected; cancelled descendants are absent; no external model requests. These are simulated-peer results, never native-core acceptance.
- [ ] **Step 5: Review checkpoint** — suggested authorized controller commit: `test(runtime): add deterministic protocol processes`.

### Task 8: Implement the mode-neutral Qoder ACP bridge

**Depends on:** Tasks 1, 2, 5, 7.

**Files:**
- Create: `packages/qoder-acp-bridge/package.json`
- Create: `packages/qoder-acp-bridge/pnpm-lock.yaml`
- Create: `packages/qoder-acp-bridge/tsconfig.json`
- Create: `packages/qoder-acp-bridge/src/main.ts`
- Create: `packages/qoder-acp-bridge/src/acp-client.ts`
- Create: `packages/qoder-acp-bridge/src/server.ts`
- Create: `packages/qoder-acp-bridge/src/permissions.ts`
- Create: `packages/qoder-acp-bridge/src/events.ts`
- Test: `packages/qoder-acp-bridge/test/permissions.test.ts`
- Test: `packages/qoder-acp-bridge/test/bridge.test.ts`

**Interfaces:** A single run-bound bridge exposes authenticated `/health`, `/session`, `/prompt`, `/events`, `/permission-response`, and `/control` routes. `/session` accepts only the pre-bound run/workspace/model, not executable/argv overrides. `/control` implements the Task 1 verified control strategy, not a guessed vendor method. Build output is `dist/main.js` plus its imported modules; copy the same complete output tree into Host assets and the Sandbox image.

- [ ] **Step 1: Write exact permission selection tests**

```ts
import { expect, test } from 'vitest';
import { selectOption } from '../src/permissions.js';
test('allow-once never selects the first allow-always option', () => {
  const options = [
    { optionId: 'persistent', kind: 'allow_always' },
    { optionId: 'once-42', kind: 'allow_once' },
    { optionId: 'reject-42', kind: 'reject_once' },
  ];
  expect(selectOption(options, 'ALLOW_ONCE')).toBe('once-42');
  expect(selectOption(options, 'DENY')).toBe('reject-42');
});
```

- [ ] **Step 2: Verify red** — after explicit package dependency setup, `pnpm --dir packages/qoder-acp-bridge test -- permissions.test.ts`.
- [ ] **Step 3: Implement bounded framing and run-bound routes**

```ts
export function selectOption(
  options: Array<{ optionId: string; kind: string }>,
  choice: 'ALLOW_ONCE' | 'DENY',
): string {
  const kind = choice === 'ALLOW_ONCE' ? 'allow_once' : 'reject_once';
  const matches = options.filter(option => option.kind === kind);
  if (matches.length !== 1) throw new Error(`Expected one ${kind} option`);
  return matches[0].optionId;
}
```

Validate the HTTP choice before calling this function. Use child-process spawn with `shell:false`, explicit cwd/env and a pinned executable. Frame JSON-RPC by lines with a maximum accepted frame/body size and fail explicitly on malformed/oversized input. Wait for initialize and session creation before prompting. Keep stdout protocol separate from stderr diagnostics. Native replies use the exact captured schema and offered option IDs; pending request IDs are checked before a response is sent.

Protect every bridge route with a per-run control secret; that secret is not included in the Qoder child environment. Model/PAT and worker MCP tokens have separate destinations. Bound event buffering and sequence IDs; deduplicate control delivery and fail explicitly on unrecoverable replay gaps rather than re-executing tools. The parent supervisor remains responsible if the bridge dies.

- [ ] **Step 4: Verify** — `pnpm --dir packages/qoder-acp-bridge test` and `pnpm --dir packages/qoder-acp-bridge build`; run against the real mock CLI process and assert exact initialization order, model `efficient`, permission payloads, rejection codes, stream events and child exit. Check no secret appears in output or artifacts.
- [ ] **Step 5: Review checkpoint** — suggested authorized controller commit: `feat(qoder): add governed run-owned ACP bridge`.

### Task 9: Implement the Host backend and verified process ownership

**Depends on:** Tasks 1, 2, 5, 6, 7.

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/host/HostExecutionBackend.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/host/OwnedProcessController.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/host/OwnedProcess.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/host/WindowsProcessController.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/host/PosixProcessController.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/host/HostExecutionBackendIntegrationTest.java`

**Interfaces:** `HostExecutionBackend` implements `ExecutionBackend`. `OwnedProcess` records root PID, creation identity, run/ownership nonce and verified OS supervisor identity. `OwnedProcessController.start(UUID, LaunchProfile)` returns it; `stop(OwnedProcess, Instant)` returns `StopProof`; `pause`/`resume` return `CompletionStage<ControlAck>`.

- [ ] **Step 1: Write a real-child ownership test** — launch the Task 7 background-writer peer in a temporary directory; capture the exact child PID and output before cancellation. Assert the returned stop proof has the same run UUID and `allWritersStopped == true`, both owned PIDs are dead, an unrelated sentinel process is still alive, and file bytes remain unchanged after the stop acknowledgment.

```java
StopProof proof = backend.stopWriters(handle, deadline);
assertThat(proof.runId()).isEqualTo(handle.runId());
assertThat(proof.allWritersStopped()).isEqualTo(true);
assertThat(ProcessHandle.of(ownedChildPid).map(ProcessHandle::isAlive).orElse(false)).isEqualTo(false);
assertThat(sentinel.isAlive()).isEqualTo(true);
```

The test initializes `backend`, `handle`, `deadline`, `ownedChildPid`, and the sentinel by its actual launch setup; do not replace process liveness with a mocked boolean.

- [ ] **Step 2: Verify red** — `mvn -f agent-control-tower/pom.xml -pl act-execution -Dskip.unit.tests=true -Dit.test=HostExecutionBackendIntegrationTest verify`.
- [ ] **Step 3: Implement explicit launch and Task 1's proven OS controller**

```java
ProcessBuilder builder = new ProcessBuilder(profile.argv());
builder.directory(Path.of(profile.workingDirectory()).toFile());
builder.environment().clear();
builder.environment().putAll(profile.env());
```

The verified OS controller must attach ownership/supervision before the core can spawn unmanaged work, and return a durable identity, not merely a PID. Use the Task 1 validated Windows job/process technique and POSIX group/control technique with their recorded limitations; a snapshot of `ProcessHandle.descendants()` is not sufficient proof against escaping descendants. Any required native dependency is explicitly pinned/reviewed, never silently installed. Reject unverified control strategies.

Host preparation must not instantiate an OpenSandbox client or require Docker/Podman. Allocate loopback endpoints, authenticate the expected runtime, and handle a bind failure explicitly without connecting to an unrelated service. Stop writers separately from deleting temporary configuration. Resume uses the same owned runtime/checkpoint and original deadline.

- [ ] **Step 4: Verify** — real mock processes on supported Windows/POSIX test environments: spawn/child races, pending permission, ignored cooperative stop, pause acknowledgment/no later writes, resume, deadline and supervisor restart. Assert exact ownership identities and unchanged unrelated processes. Unverified OS behavior blocks support.
- [ ] **Step 5: Review checkpoint** — suggested authorized controller commit: `feat(runtime): supervise Host execution by run`.

### Task 10: Extract a run-owned Sandbox backend with export-safe shutdown

**Depends on:** Tasks 1, 2, 5, 6, 8.

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeSandboxManager.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/sandbox/SandboxLifecycle.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/sandbox/SandboxExecutionBackend.java`
- Modify: `agent-control-tower/opencode-sandbox/Dockerfile`
- Create: `agent-control-tower/qoder-sandbox/Dockerfile`
- Create: `agent-control-tower/runtime-sandbox/launch.mjs`
- Create: `agent-control-tower/runtime-sandbox/stop-writers.mjs`
- Create: `agent-control-tower/runtime-sandbox/test.Dockerfile`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/sandbox/SandboxExecutionBackendTest.java`
- Test: `e2e/agent-core/sandbox-lifecycle.test.mjs`

**Interfaces:** `SandboxLifecycle` owns SDK create/upload/endpoint/renew/export/kill operations, keyed by run UUID rather than agent UUID. `SandboxExecutionBackend` implements the common backend; stop proof does not mean the filesystem/export facility has been destroyed.

- [ ] **Step 1: Write exact stop/export/kill ordering tests**

```java
backend.exportWorkspace(handle, destination, new StopProof(handle.runId(), false));
```

Wrap that call with `assertThatThrownBy` expecting `IllegalStateException` and message `Writers are not stopped`; verify zero calls to the exact destination export operation. Add a successful recorded sequence assertion:

```java
assertThat(recordedOperations).containsExactly("create", "upload", "launch",
        "stop-writers", "export", "kill");
```

Use a small recording SDK test double whose methods append these literal operation names; the real-container test below independently validates the boundary.

- [ ] **Step 2: Verify red** — `mvn -f agent-control-tower/pom.xml -pl act-execution -Dtest=SandboxExecutionBackendTest test`.
- [ ] **Step 3: Extract SDK ownership and image launch** — preserve current SDK endpoint/upload semantics while removing OpenCode-specific command construction from lifecycle. Pass a trusted launch manifest to the fixed image launcher rather than interpolate user argv into a shell. Qoder image consumes the same bridge dist tree as Host and the Task 1 verified Linux CLI artifact/version/license. Both cores run unprivileged, without host credential mounts or a container-runtime socket.

```js
import { spawn } from 'node:child_process';
export function launchRun(profile) {
  return spawn(profile.argv[0], profile.argv.slice(1), {
    cwd: profile.workingDirectory,
    env: profile.env,
    shell: false,
    stdio: ['pipe', 'pipe', 'pipe'],
  });
}
```

Validate the trusted profile before launch. Track writer ownership so the image supervisor can stop core/bridge descendants while leaving SDK export access alive. Renew during execution and human/manual waits, stop renewal for teardown. If forced environment destruction loses export access, return an incomplete artifact result, not a stable final diff.

- [ ] **Step 4: Verify** — unit tests plus `node --test e2e/agent-core/sandbox-lifecycle.test.mjs` against actual OpenSandbox and the mock-peer image: endpoint authentication, upload, pause, renewal, background writer termination, stable export then kill, and unrelated sandbox preservation. A process-only transport fake is not this result.
- [ ] **Step 5: Review checkpoint** — suggested authorized controller commit: `refactor(runtime): share run-owned sandbox lifecycle`.

### Task 11: Implement native core sessions behind the shared ports

**Depends on:** Tasks 1, 2, 5, 7, 8.

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeHttpClient.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/core/OpenCodeCoreAdapter.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/core/OpenCodeCoreSession.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/core/QoderCoreAdapter.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/core/QoderBridgeClient.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/core/QoderCoreSession.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/core/CoreAdapterContractTest.java`

**Interfaces:** Both adapters implement `CoreAdapter`, return `CoreSession`, and translate native protocol into `CoreEvent`/`CoreResult`/`UsageSnapshot`. Capabilities come from the reviewed version/profile; snapshot the chosen control strategy with the run. Native-unavailable pause is explicit; orchestration may use only the separately verified BACKEND_SUSPEND strategy, never an unplanned runtime fallback.

- [ ] **Step 1: Write exact contract assertions against the recorded peers**

```java
assertThat(result.sessionId()).isEqualTo(recordedSessionId);
assertThat(result.finalOutput()).isEqualTo("fixture-complete");
assertThat(result.usage().inputTokens()).isEqualTo(12L);
assertThat(result.usage().outputTokens()).isEqualTo(7L);
assertThat(result.usage().observedModel()).isEqualTo("efficient");
```

`recordedSessionId` is captured from the peer's actual session response. In a separate unknown-usage scenario assert the relevant fields are null, not zero. Use a two-turn nonce scenario to assert the exact prior user/assistant messages are passed as context.

- [ ] **Step 2: Verify red** — `mvn -f agent-control-tower/pom.xml -pl act-execution -Dskip.unit.tests=true -Dit.test=CoreAdapterContractTest verify`.
- [ ] **Step 3: Implement session translation and usage semantics**

```java
public UsageSnapshot unknownUsage() {
    return new UsageSnapshot(null, null, null, null);
}
```

Do not replace unknown native values with this helper when values are actually reported; preserve exact source values and distinguish requested from observed model. Build the Qoder HTTP/SSE client for Task 8's bridge contract and extend the OpenCode client with Task 1's verified event/permission/control shapes. Preserve system input, ordered conversation history and the current user request without executing earlier tool actions again. Native session methods never allocate containers or user workspaces. Default OpenCode allow-all configuration must not survive into the governed profile.

- [ ] **Step 4: Verify** — both adapters against real protocol-peer processes: exact request bodies/headers, native option IDs, session ordering, streamed payloads, refusal, unsupported model, disconnect, cancellation and the chosen control strategy. Also run existing OpenCode HTTP retry/progress tests with their exact original behavioral assertions preserved.
- [ ] **Step 5: Review checkpoint** — suggested authorized controller commit: `feat(runtime): normalize Qoder and OpenCode sessions`.

### Task 12: Coordinate approvals, one-use grants and the MCP execution boundary

**Depends on:** Tasks 2, 4, 11.

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/approval/PermissionCoordinator.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/approval/WriteGrantService.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/approval/PermissionDeliveryState.java`
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/AcpPermissionRequest.java`
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/repository/AcpPermissionRequestRepository.java`
- Create: `agent-control-tower/act-app/src/main/resources/db/migration/V62__acp_permission_correlation.sql`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/approval/ApprovalGate.java:240-299`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/controller/ApprovalController.java:118-171`
- Modify: `agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/tools/ApprovalTools.java:36-50`
- Modify: `agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/tools/ToolPolicyRegistry.java` (create if absent)
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/approval/PermissionCoordinatorTest.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/approval/WriteGrantServiceTest.java`

**Interfaces:** Section 2.1 permission ledger. `PermissionCoordinator` persists `(runId, sessionId, requestId)` uniquely before display and exposes `deliveryState(UUID)`; `deliverPending(UUID)` is separate from `decide`. `WriteGrantService` consumes exactly once against `(runId, toolName, argumentsDigest)`. Both require an `ActorPrincipal`.

- [ ] **Step 1: Write exact one-use and identity tests**

```java
@Test
void aSecondIdenticalWriteWithoutANewApprovalIsRefused() {
    UUID runId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000201");
    var grants = new WriteGrantService(repository, clock);
    grants.grant(runId, "write_file", "sha256:abc", Instant.parse("2026-09-22T12:05:00Z"));
    assertThat(grants.consume(runId, "write_file", "sha256:abc")).isEqualTo(true);
    assertThat(grants.consume(runId, "write_file", "sha256:abc")).isEqualTo(false);
    assertThat(grants.consume(runId, "write_file", "sha256:def")).isEqualTo(false);
}
```

Add a decision test asserting an `ActorPrincipal(Role.WORKER, otherRun)` decision throws `SecurityException` with message `Operator authority required`, and that the approval row remains `PENDING`.

- [ ] **Step 2: Verify red** — `mvn -f agent-control-tower/pom.xml -pl act-execution -Dtest=PermissionCoordinatorTest,WriteGrantServiceTest test`.
- [ ] **Step 3: Implement correlation, delivery and authorization** — reuse `ApprovalGate.decideApproval` internals but require the authenticated operator, reject already-decided and expired requests, and keep the existing reason/idempotency behavior. Persist offered options and the normalized target; never select by position. `NATIVE_TOOL` replies go to the owning session only when not manually paused and the request is still valid; `PLATFORM_MCP` replies authorize one matching tool call.

Replace the unrestricted `decide_approval` path: both Java MCP transports resolve `McpActorContext.require(ToolContext)` and call the coordinator, not `ApprovalGate` directly. `ApprovalController` resolves the operator session and returns 401/403 explicitly. `/answer` keeps its QUESTION-only semantics. Denials produce zero execution; race/duplicate/expiry paths re-check state inside one transaction.

Route automatic read-only allowance through `ToolPolicyRegistry` classification; unknown tools remain gated. Register `acp_permission_request` in the run-purge path (Task 14 uses this) and cover it with a test.

- [ ] **Step 4: Verify** — exact rows/statuses/IDs for allow-once, re-ask, deny, expiry, late/duplicate/cross-run decision, worker REST/MCP self-approval, and a native write not repeated through MCP. Run `ApprovalGateConcurrencyTest` and `ApprovalExpiryCheckerSddTest` unchanged.
- [ ] **Step 5: Review checkpoint** — suggested authorized controller commit: `feat(approval): bind decisions to operator and run`.

### Task 13: Integrate the run coordinator, finalizer and truthful control

**Depends on:** Tasks 5-12.

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreExecutionService.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/ExecutionBackendRegistry.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/RunFinalizer.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/RunRuntimeRegistry.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/RuntimeRecoveryCoordinator.java`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java:258-289,596-603,719-727,822-857`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/health/AgentHealthReconciler.java:48-78`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/listener/WorkflowAutoChainer.java:313-328`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/AdkProvider.java:54-106`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/TaskDeadlineProperties.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/CoreExecutionServiceTest.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/RunFinalizerTest.java`

**Interfaces:** Section 2.1 runtime services. `RunFinalizer.finish` owns stop -> capture -> destroy -> release and is the only writer of terminal artifact state. `AgentLoopEngine` keeps business state transitions but delegates runtime control to `CoreExecutionService`, and reads its deadline from `TaskDeadlineProperties` (default 45 minutes) instead of `OpenCodeProperties`.

- [ ] **Step 1: Write exact ordering and truthfulness tests**

```java
assertThat(recordedSteps).containsExactly("stop-writers", "capture", "destroy", "release");
```

Add a pause test: before `ControlAck(PAUSED, true)` the service must not persist `PAUSED`; a false/unverified ack leaves the run `RUNNING` with a pending control request and returns the exact failure reason. Add a cancel test asserting `ABORTED`/`CANCELLED` is persisted only after the matching `StopProof(runId, true)`.

- [ ] **Step 2: Verify red** — `mvn -f agent-control-tower/pom.xml -pl act-execution -Dtest=CoreExecutionServiceTest,RunFinalizerTest test`.
- [ ] **Step 3: Implement the coordinator and integrate the engine** — resolve binding -> prepare workspace -> prepare environment -> launch -> open session -> prompt with full history -> stream/permissions -> finish. Manual pause and permission waiting are separate reasons; a decision received while paused is recorded but not delivered until resume re-validates it. Resume reuses the same handle/binding and never replays writes. On restart, adopt or reap only run-owned, ownership-verified runtimes; keep unresolved Direct leases held.

Replace `OpenCodeAdkProvider.resetAgent` coupling in `WorkflowAutoChainer` with a mode-neutral runtime reset through the registry. Replace pre-warm/permanent-instance semantics in `AriaDefaultAgentInitializer` (Tasks 18) and keep `AgentHealthReconciler` reporting configuration/readiness without creating runtimes: an idle configured agent is not UNHEALTHY merely because no runtime exists.

- [ ] **Step 4: Verify** — targeted unit tests plus `AgentHealthReconcilerTest`, `AriaDefaultAgentInitializerTest`, and Kanban dispatch integration tests updated only where behavior is intentionally replaced (never to loosen expectations). Confirm no test asserts `NOT_STARTED` as an error state.
- [ ] **Step 5: Review checkpoint** — suggested authorized controller commit: `feat(runtime): coordinate run-owned execution end to end`.

### Task 14: Implement preview-first retirement and explicit setup

**Depends on:** Tasks 2, 4, 12, 13.

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/maintenance/RetirementManifest.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/maintenance/RetirementReceipt.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/maintenance/LegacyRetirementService.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/maintenance/LegacySetupService.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/controller/MaintenanceController.java`
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/repository/AuditEventBulkRepository.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/maintenance/LegacyRetirementServiceIntegrationTest.java`

**Interfaces:** Section 2.1 maintenance types and endpoints. Preview computes the exact ID sets, a SHA-256 digest over them, counts and an expiry; execute requires the same digest and re-verifies quiescence. `LegacySetupService.initializeMissingBuiltins` creates only missing built-ins with `opencode` + `SANDBOX`.

- [ ] **Step 1: Write a mixed-core scoped-delete test with exact before/after state**

```java
var before = snapshot.protectedRecords();
var preview = service.preview(operator);
assertThat(preview.agentIds()).containsExactly(langChainAgentId);
assertThat(preview.agentIds()).doesNotContain(openCodeAgentId);
var receipt = service.execute(preview.previewId(), preview.digest(), operator);
assertThat(receipt.deletedAgents()).isEqualTo(1);
assertThat(receipt.deletedRuns()).isEqualTo(2);
assertThat(snapshot.protectedRecords()).isEqualTo(before);
assertThat(kanban.findById(cardId).orElseThrow().getLinkedAgentId()).isNull();
assertThat(workflowService.stepAt(chain, 0).getAgentId()).isNull();
assertThat(auditEventsFor(openCodeAgentId)).isNotEmpty();
```

Seed a disposable H2 database with one LangChain agent, one OpenCode agent, their runs/approvals/children/audit rows, one shared Kanban card and one two-step workflow step referencing each. Two runs are the LangChain agent's fixture runs; assert those exact UUIDs are gone.

- [ ] **Step 2: Verify red** — `mvn -f agent-control-tower/pom.xml -pl act-execution -Dskip.unit.tests=true -Dit.test=LegacyRetirementServiceIntegrationTest verify`.
- [ ] **Step 3: Implement bounded, transactional deletion** — select agents whose current `adk_provider` is exactly `langchain`; never widen by name/role/substring/default. Refuse when any target run is active, any pending approval exists for a target run, or a runtime is still owned; require quiescence instead of force-killing. Delete children before parents using bulk repositories, include the `acp_permission_request` rows registered in Task 12, delete direct agent-owned prompt rows, then agent tool/skill bindings, then agents. Remove only target links from Kanban/workflow JSON via `WorkflowService.serializeSteps` round-trip, preserving other steps and outputs. Delete audit rows only by verified `resourceType` in {`Agent`, `Run`, `Action`} with resource IDs in the frozen target sets; never by conversation ID or free text.

Enforce operator authority and single-flight; report exact counts and failures. A rerun with no targets is a no-op. Do not include a startup hook, scheduler or health-path call to execute. `LegacySetupService` replaces the repointing block in `AriaDefaultAgentInitializer` and runs only via the explicit endpoint or the CI setup step.

- [ ] **Step 4: Verify** — digest mismatch, expired preview, changed selection, active run, pending approval, protected-data equality, dangling-link absence, rerun no-op, and fresh-install bootstrap (V42 rows cleared then three built-ins created with `opencode`/`SANDBOX` and matching role names).
- [ ] **Step 5: Review checkpoint** — suggested authorized controller commit: `feat(maintenance): add preview-first legacy retirement`.

### Task 15: Expose selection, credentials, approvals and operator session in the UI

**Depends on:** Tasks 3, 4, 5, 12, 14.

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/types/index.ts:23,143,429,447`
- Modify: `agent-control-tower/act-dashboard/src/api/agents.ts`
- Create: `agent-control-tower/act-dashboard/src/api/operatorSession.ts`
- Create: `agent-control-tower/act-dashboard/src/api/runtimeCredentials.ts`
- Create: `agent-control-tower/act-dashboard/src/components/OperatorAccessPanel.tsx`
- Create: `agent-control-tower/act-dashboard/src/components/RuntimeCredentialsCard.tsx`
- Modify: `agent-control-tower/act-dashboard/src/pages/ProvidersPage.tsx:39,111`
- Modify: `agent-control-tower/act-dashboard/src/pages/CrewPage.tsx:42,89-95,262,422`
- Modify: `agent-control-tower/act-dashboard/src/components/ReviewPanels.tsx`
- Test: `agent-control-tower/act-dashboard/src/pages/__tests__/CrewPage.test.tsx`
- Test: `agent-control-tower/act-dashboard/src/components/__tests__/RuntimeCredentialsCard.test.tsx`

**Interfaces:** `agents.ts` accepts `adkProvider: 'qoder' | 'opencode'` and `executionMode: 'HOST' | 'SANDBOX'` plus Host workspace fields; `operatorSession.ts` establishes the local operator session; runtime credential API returns masked metadata only.

- [ ] **Step 1: Write exact UI assertions**

```tsx
expect(await screen.findByText('OpenCode')).toBeInTheDocument();
expect(screen.getByLabelText('Execution mode').textContent).toBe('Sandbox');
expect(screen.queryByText('LangChain ADK')).not.toBeInTheDocument();
expect(await screen.findByText('••••••••')).toBeInTheDocument();
expect(screen.queryByText('fixture-secret-value')).not.toBeInTheDocument();
```

- [ ] **Step 2: Verify red** — `pnpm --dir agent-control-tower/act-dashboard test -- CrewPage RuntimeCredentialsCard`.
- [ ] **Step 3: Implement the surfaces** — core selector, explicit mode selector (Host never preselected), Host workspace inputs (worktree default, base ref, Direct directory), mode-aware readiness/errors, credential card with test/mask/remove, operator session establishment, and Review rendering for normalized asks including permission kind and expiry. Removed-core values render as an explicit unsupported state rather than silently falling back. Batch approvals must not create persistent native escalation. Keep the existing Review routes and Kanban card linkage.

- [ ] **Step 4: Verify** — vitest components plus `pnpm --dir agent-control-tower/act-dashboard build`; browser check of the golden path and error states after replacement is running (Task 20), including console/network inspection.
- [ ] **Step 5: Review checkpoint** — suggested authorized controller commit: `feat(ui): expose core, mode and managed credentials`.

### Task 16: Package and launch the deterministic test distribution

**Depends on:** Tasks 4, 5, 7, 8, 11, 12, 13, 14.

**Files:**
- Create: `agent-control-tower/act-app/src/assembly/backend-e2e-harness.xml`
- Modify: `agent-control-tower/act-app/pom.xml:23-127`
- Modify: `agent-control-tower/pom.xml` (add the `core-e2e-harness` profile; keep existing surefire/failsafe tiering unchanged)
- Create: `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/e2e/CoreE2eApplication.java`
- Create: `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/e2e/CoreE2eConfiguration.java`
- Create: `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/e2e/DeterministicLlmClient.java`
- Create: `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/e2e/CoreE2eSetup.java`
- Create: `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/CoreE2ePackagingIntegrationTest.java`

**Interfaces:** Production `backend-jar` stays fixture-free. `backend-e2e-harness` ZIP contains `app/` (production classes/resources), `harness/` (only `io/aria/conductor/app/e2e/**` plus selected support classes), `lib/` (runtime dependency jars), `peers/` and `bridge/`. Launcher main class is `io.aria.conductor.app.e2e.CoreE2eApplication`.

- [ ] **Step 1: Write the production-boundary packaging test**

```java
try (var jar = new JarFile(System.getProperty("e2e.productionJar"))) {
    assertThat(jar.stream().map(ZipEntry::getName))
            .noneMatch(name -> name.contains("/e2e/")
                    || name.contains("act-test-support")
                    || name.contains("mock-qoder"));
}
```

Add ZIP-entry assertions for `app/`, `harness/io/aria/conductor/app/e2e/CoreE2eApplication.class`, `lib/`, `peers/mock-qoder.mjs`, and `bridge/main.js`.

- [ ] **Step 2: Verify red** — `mvn -f agent-control-tower/pom.xml -Pcore-e2e-harness -pl act-app verify -Dskip.unit.tests=true -Dit.test=CoreE2ePackagingIntegrationTest`; expect failure because the assembly/profile does not exist yet. Provide `e2e.productionJar` through the profile's Failsafe system properties.
- [ ] **Step 3: Implement packaging and launcher** — use `maven-dependency-plugin` `copy-dependencies` with `includeScope=runtime` into `target/e2e-lib`, unpack only the needed support classes, and `maven-assembly-plugin` ZIP (`includeBaseDirectory=false`). Add **test-scope** `act-test-support` to act-app; never add the reverse dependency.

```java
public static void main(String[] args) {
    new SpringApplicationBuilder(ActApplication.class, CoreE2eConfiguration.class)
            .profiles("h2", "core-e2e").run(args);
}
```

`CoreE2eConfiguration` replaces both `rawLlmClient` and `resilientLlmClient` with `DeterministicLlmClient`, which records requests, matches scenario responses exactly, and fails on unmatched input or unexpected external traffic. Harness settings are `e2e.assets`, `e2e.work-root`, `e2e.sandbox-transport=process|real`. `CoreE2eSetup.main` runs after HTTP availability with `--confirm-retire-historical-seeds` and an environment-supplied synthetic operator credential: it calls the production preview/execute endpoints, initializes missing built-ins, and fails loudly instead of bypassing retirement.

- [ ] **Step 4: Verify** — build the distribution and run the packaging test plus a real harness start: health endpoint exact `{"status":"UP"}` (or the exact current body), setup receipt counts, then one seeded scenario. Confirm the production jar still lacks fixture entries and Node/peer assets resolve from the ZIP.
- [ ] **Step 5: Review checkpoint** — suggested authorized controller commit: `build(test): package deterministic core E2E harness`.

### Task 17: Port existing E2E scenarios without losing coverage

**Depends on:** Task 16.

**Files:**
- Modify: `agent-control-tower/act-dashboard/e2e/fixtures.ts:36-46,128-143,206-250`
- Create: `agent-control-tower/act-dashboard/e2e/core-execution-modes.spec.ts`
- Create: `agent-control-tower/act-dashboard/e2e/core-permissions.spec.ts`
- Create: `agent-control-tower/act-dashboard/e2e/core-workspaces.spec.ts`
- Modify: `agent-control-tower/act-dashboard/e2e/opencode-adk-e2e.spec.ts`
- Modify: `agent-control-tower/act-dashboard/e2e/aria-conversation-id-regression.spec.ts`
- Modify: `agent-control-tower/act-dashboard/e2e/kanban-hitl.spec.ts`
- Modify: `agent-control-tower/act-dashboard/e2e/sdd-workflow.spec.ts`
- Delete at cutover: `agent-control-tower/act-dashboard/e2e/langchain-adk-e2e.spec.ts`
- Test: the specs above.

**Interfaces:** `seedAdkAgent(request, { adkProvider, executionMode, workspaceMode, workspacePath, workspaceBaseRef })`; `setScenario(request, agentId, scenario)` (harness-only, operator-authenticated) controls peers, never database outcomes.

- [ ] **Step 1: Write the four-combination completion spec with exact expectations**

```ts
for (const { core, mode } of [
  { core: 'qoder', mode: 'HOST' },
  { core: 'qoder', mode: 'SANDBOX' },
  { core: 'opencode', mode: 'HOST' },
  { core: 'opencode', mode: 'SANDBOX' },
] as const) {
  test(`${core}/${mode} completes a coding task with exact output`, async ({ request }) => {
    const agent = await seedAdkAgent(request, { adkProvider: core, executionMode: mode });
    await setScenario(request, agent.id, 'complete');
    const run = await seedRun(request, agent.id);
    await approveRunApproval(request, run.id);
    const done = await pollRunTerminal(request, run.id);
    expect(done.status).toBe('COMPLETED');
    expect(done.finalOutput).toBe('fixture-complete');
  });
}
```

- [ ] **Step 2: Verify red** — `CI=true pnpm --dir agent-control-tower/act-dashboard exec playwright test e2e/core-execution-modes.spec.ts --workers=1`; expect failure while the harness path is not yet wired in CI.
- [ ] **Step 3: Port scenarios per the coverage map** — preserve and strengthen: agent create/configure/run/verify, core switching and inventory, Aria SSE conversation reuse with exact history content, Kanban pickup/Review ask/cancel/truthful pause, SDD spec approval (`WAITING_APPROVAL`) and expiry, authenticated MCP parity with real HTTP calls, governed git push to a disposable bare remote, knowledge promotion with a completed trajectory, plus permissions (approve-once re-ask, deny no side effect, expiry, worker self-approval refused at REST and both MCP transports), workspaces (dirty source preserved, worktree invalid request, concurrent Direct rejected), and control (cancel/acknowledged pause/no writes after pause/stable export).

Do not convert expected terminal states into lists of acceptable outcomes, and do not add skips. Missing-dependency scenarios remain explicit FAILED-expectation cases. Remove the LangChain spec only when its business purposes are covered by the mapped replacements, and record the mapping in `e2e/agent-core/coverage-map.json`.

- [ ] **Step 4: Verify** — run the ported specs against the harness; every assertion uses captured IDs or literal expectations. Report coverage-map rows with their replacement spec name and status.
- [ ] **Step 5: Review checkpoint** — suggested authorized controller commit: `test(e2e): port governed scenarios to core harness`.

### Task 18: Cut production over and remove the LangChain runtime

**Depends on:** Tasks 13, 15, 16, 17 (replacement must exist before removal).

**Files:**
- Delete: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/LangChainAdkProvider.java`
- Delete: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/LangChainAdkProperties.java`
- Delete: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/AdkHttpClient.java`, `AdkInstance.java`, `AdkProcessReaper.java`, `AdkRunRequest.java`, `AdkRunResponse.java` and legacy-only tests
- Delete: `langchain-adk/` (Python runtime, image, dependencies, tests)
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/AdkProviderRegistry.java:39-82` (defaults and explicit-failure resolution)
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/AdkSystemProperties.java:19` (default `opencode`)
- Modify: `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/service/AgentService.java:91`, `AgentTemplateService.java:26,36,46`
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/tools/handlers/AgentToolHandler.java:119`
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/init/AriaDefaultAgentInitializer.java:301-326,401-436`
- Modify: `agent-control-tower/act-app/src/main/resources/application-h2.yml:51-64`, `application-mariadb.yml:41-50`
- Modify: `packages/mcp-server/src/tools/agents.ts:26,39`, `agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/tools/AgentTools.java:54-75`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/controller/AdkProviderController.java:116-122`
- Modify: `scripts/start.ps1:36,199`, `scripts/start-backend.ps1:17-23`, `scripts/start-backend.sh:29-36`, `docker-compose.yml:49-52,111-131`, `.env.example:31`
- Modify: `README.md`, `AGENTS.md`, `docs/architecture.md`, `CONTRIBUTING.md`, `docs/testing-baseline.md`, `agent-control-tower/README.md`

**Interfaces:** Supported production cores are exactly `qoder` and `opencode`; there is no LangChain provider bean, default, seed, template, script path or MCP enum value. `AdkProviderRegistry.resolve` throws an explicit error for unknown/unconfigured values instead of falling back.

- [ ] **Step 1: Write the removal assertions**

```java
@Test
void anUnknownConfiguredDefaultFailsClosed() {
    AdkSystemProperties props = new AdkSystemProperties();
    props.setDefaultProvider("langchain");
    assertThatThrownBy(() -> new AdkProviderRegistry(List.of(opencodeProvider), props))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unsupported ADK provider");
}
```

`opencodeProvider` is the real production provider instance or the existing registry test's existing test double; do not add a new mocking helper for this case.

Add a script test or documented check asserting `scripts/start-backend.ps1 -SkipSandbox` selects a Host-capable core and does not require a container runtime, and an MCP schema test asserting `adkProvider` accepts `qoder`/`opencode` and rejects `langchain`.

- [ ] **Step 2: Verify red** — `mvn -f agent-control-tower/pom.xml -pl act-execution -Dtest=AdkProviderRegistryTest test`.
- [ ] **Step 3: Cut over and remove** — adapt the provider facades to the shared runtime kernel, wire `DefaultAgentExecutionPolicy`/`CoreCatalog` production consumers, and remove obsolete turn-level methods (`call`, `parseActionsFromResponse`, `prepareAgent`, permanent `isHealthy(UUID)` semantics) with their callers. Remove the Aria permanent pre-warm and legacy repointing; Aria itself is created with `opencode`+`SANDBOX` when missing. Preserve generic LLM/tool/knowledge/workflow/progress behavior. Update scripts so Host mode needs no Docker/Podman and remove the LangChain-only compose path; keep explicit sandbox checks for sandbox modes. Append schema changes only; never rewrite applied migrations. Remove the LangChain-only CI setup and Python jobs in Task 19, not here.

- [ ] **Step 4: Verify** — full Java unit tier, targeted integration tier, `pnpm --dir packages/mcp-server test`, frontend vitest/build, and the ported Playwright smoke subset. Confirm no production reference to `langchain` remains except historical migrations/docs explicitly marked historical, and that unrelated tests keep their original assertions.
- [ ] **Step 5: Review checkpoint** — suggested authorized controller commit: `refactor!: retire LangChain ADK runtime`.

### Task 19: Rewire CI around the harness and real sandbox lifecycle

**Depends on:** Task 18.

**Files:**
- Modify: `.github/actions/start-stack/action.yml:25-38`
- Modify: `.github/workflows/ci.yml:27-36,39-60,87-99,120-128,159-192,193-215,243-291`
- Modify: `.github/workflows/nightly.yml:20-27,49-75`
- Modify: `.github/workflows/nightly-sdd-llm-smoke.yml:31-58`
- Create: `.github/workflows/sandbox-lifecycle.yml`
- Delete: Python-only jobs/files identified in Task 18

**Interfaces:** `backend-jar` remains the production artifact; `backend-e2e-harness` is a second artifact. `start-stack` extracts the harness, provisions Java and Node even for `frontend-mode: none`, runs the explicit setup, and starts the launcher on an ordinary classpath.

- [ ] **Step 1: Write the pipeline assertions as a checkable script** — add `e2e/agent-core/ci-contract.test.mjs` asserting: no job or action references `langchain-adk`, the required `core-e2e-harness` artifact is built and downloaded before smoke/E2E, path filters cover the bridge/fixtures/scripts/images, and no required PR job requires `DEEPSEEK_API_KEY` or a Qoder PAT.
- [ ] **Step 2: Verify red** — `node --test e2e/agent-core/ci-contract.test.mjs`.
- [ ] **Step 3: Rewire jobs** — build Node bridge assets before Maven; upload both artifacts; run smoke and Playwright from the harness with the exact command below; add `act-mcp` to the Java unit grouping; keep unit/Failsafe separation, frontend build/Vitest, MCP tests and the four Playwright shards; add explicit `workflow_dispatch` for branch validation. Add `sandbox-lifecycle.yml` as a requirement-triggered lane (backends, bridge/images, workspace transfer, startup paths) running the real OpenSandbox test image; results are reported separately and never substituted by the process-transport UI job. Retarget the nightly SDD smoke to OpenCode while keeping its `WAITING_APPROVAL` assertion, and gate live tiers on explicit live execution plus credentials.

```bash
mvn -f "$GITHUB_WORKSPACE/agent-control-tower/pom.xml" -Pcore-e2e-harness install -DskipTests
mvn -f "$GITHUB_WORKSPACE/agent-control-tower/pom.xml" -Pcore-e2e-harness -pl act-app verify -Dskip.unit.tests=true -Dit.test=CoreE2ePackagingIntegrationTest
java --enable-preview -cp "$HARNESS/app:$HARNESS/harness:$HARNESS/lib/*" io.aria.conductor.app.e2e.CoreE2eApplication --e2e.assets="$HARNESS"
CI=true pnpm --dir "$GITHUB_WORKSPACE/agent-control-tower/act-dashboard" exec playwright test --shard=1/4 --workers=1
```

- [ ] **Step 4: Verify** — run `node --test e2e/agent-core/ci-contract.test.mjs`; validate workflow YAML parses; if a container runtime is available locally, run the sandbox lifecycle lane against the mock-peer image and record the exact result. A cloud CI result can only be claimed after an actual run; otherwise report NOT VERIFIED. Never lower coverage thresholds or remove shared scenarios.
- [ ] **Step 5: Review checkpoint** — suggested authorized controller commit: `ci: run deterministic core harness and sandbox lane`.

### Task 20: Full regression and real four-combination acceptance

**Depends on:** Tasks 13-19; operator-provided environment.

**Files:**
- Create: `e2e/agent-core/live-matrix.ps1`
- Create: `e2e/agent-core/live-matrix.sh`
- Create: `docs/reviews/2026-09-22-agent-core-live-evidence.md` only from actual captured output
- Test: existing suites plus `e2e/agent-core/live-matrix.*`

**Interfaces:** `live-matrix` takes `--core`, `--mode`, `--model`, `--workspace`, `--repo`, `--evidence` and fails closed when the environment is incomplete. For Qoder it rejects any model other than `efficient` unless an explicit paid opt-in is set.

- [ ] **Step 1: Write the runner assertions** — the runner must fail before launching when a required credential/CLI/image/base-repo prerequisite is missing, must record `core`, `mode`, `os`, CLI/bridge version, requested and observed model, and must assert independently observed file bytes and tool/test outcome rather than the agent's self-report.
- [ ] **Step 2: Verify red** — run the runner without credentials; expect the exact missing-prerequisite failure, not a skip.
- [ ] **Step 3: Run the full regression** — full Java unit and integration tiers, `pnpm --dir agent-control-tower/act-dashboard test`, `pnpm build`, `npx playwright test` against the harness, and `pnpm --dir packages/mcp-server test`. Record raw outputs.
- [ ] **Step 4: Run the real matrix** — Qoder/HOST, Qoder/SANDBOX, OpenCode/HOST, OpenCode/SANDBOX, each completing a coding task with independent file/diff and outcome verification, plus the permission (approve-once re-ask, deny, expiry), cancellation, pause/resume, workspace (worktree and Direct), and error/no-fallback cases. **Qoder runs use `efficient`**; capture model acknowledgment and available usage evidence, and never assert zero cost.
- [ ] **Step 5: Report** — write the evidence file from captured command output only, marking any unevaluated criterion as `NOT VERIFIED`. A pass verdict requires every applicable criterion to have been evaluated. Record the exact matrix table.
- [ ] **Step 6: Review checkpoint** — no commit of evidence without a controller check; suggested `test(e2e): record live core matrix evidence`.

### Task 21: Operator-authorized scoped LangChain cleanup

**Depends on:** Tasks 14, 18, 20; explicit operator authorization.

**Files:**
- None created by the plan. Execution uses `POST /api/v1/maintenance/langchain/preview` and `/execute` against the targeted environment and records a receipt under `docs/reviews/`.

**Interfaces:** Preview first against the real target; execute only with the unchanged digest; verify protected data and re-run to confirm the no-op.

This task has no code deliverable and therefore no TDD red step: it is an operator-run maintenance operation whose verification is the preview/digest match, the receipt counts, and the independent protected-data comparison. Task 14 provides the tested implementation; this task exercises it against a real target only after explicit authorization.

- [ ] **Step 1: Obtain explicit authorization for the target environment and maintenance window.** Confirm this task is not required for a fresh/disposable environment where Task 14 setup already handled the seed rows.
- [ ] **Step 2: Preview and review** — capture exact agent/run/approval/audit counts and IDs; confirm no target run is active and no runtime is owned. Stop and report if the count differs from the reviewed expectation.
- [ ] **Step 3: Execute with the previewed digest** — record the receipt.
- [ ] **Step 4: Independent verification** — query the other-core records, shared Kanban/workflow, settings, credentials and user repositories and confirm equality with the pre-execution snapshot; confirm no dangling links; confirm a second execute is a no-op.
- [ ] **Step 5: Report and review checkpoint** — write the receipt and verification into a dated review artifact. Any mismatch blocks and reverts to design discussion; do not widen the deletion set.

## 5. Self-review

**Spec coverage**

| Spec area | Task(s) |
|---|---|
| Selection/defaults/no-fallback (3.1) | 2, 3, 15, 18 |
| Immutable binding (3.2) | 2, 13 |
| Component boundaries/matrix (4) | 8-13, 18 |
| Execution sequence and ordering (5.1) | 6, 9, 10, 13 |
| Workspace ownership (5.2) | 6, 9, 10, 13 |
| Deadlines/cancel/restart (5.3) | 5, 9, 10, 13 |
| Pause/resume (5.4) | 1, 8, 9, 13, 20 |
| Credentials/config (6.1-6.2) | 4, 5, 15 |
| Permission governance (6.3) | 12, 15, 17 |
| Code/operational removal (7.1) | 18, 19 |
| Bounded hard deletion (7.2) | 14, 21 |
| Fresh/upgrade/CI bootstrap (7.3) | 14, 16, 19 |
| Test tiers and mocks (8.1-8.3) | 7, 16, 17, 19 |
| Coverage/pipeline preservation (8.4) | 17, 18, 19, 20 |
| Acceptance V1-V17 | 17, 19, 20, 21 |
| Delivery gates | 1, 16, 20, 21 |

**Integrity rules check**

- No task instructs a skip, disable, removal of a shared scenario, or loosened assertion. The only planned deletion is the LangChain-only spec at cutover, guarded by the coverage map and by replacement scenarios that already pass.
- Assertions in every example compare literal expectations, captured IDs, exact bytes or exact counts. No `any()`/`expect.any(...)`/`anything()`/alternative-outcome lists appear.
- Real Qoder acceptance is explicit and pinned to `efficient` in Tasks 1, 20 and the runner contract.

**Type consistency**

`ExecutionMode`, `WorkspaceMode`, `WorkspaceKind`, `AgentExecutionSettings`, `ExecutionSpec`, `CoreTask`, `WorkspaceLease`, `PreparedEnvironment`, `RuntimeHandle`, `StopProof`, `ControlState`, `ControlStrategy`, `ControlAck`, `CoreCapabilities`, `UsageSnapshot`, `CoreResult`, `CoreEvent`, `ArtifactBundle`, `SecretBundle`, `LaunchProfile`, `CoreSession`, `CoreAdapter`, `ExecutionBackend`, `RuntimeActivity`, `WorkspaceService`, `ActorPrincipal`, `PermissionChoice`, `PermissionTarget`, `PermissionOption`, `NativePermission`, `PermissionReply`, `PermissionCoordinator`, `WriteGrantService`, `RetirementManifest`, `RetirementReceipt`, `LegacyRetirementService`, `LegacySetupService`, `CoreExecutionService`, `ExecutionBackendRegistry`, `RunFinalizer`, `RunRuntimeRegistry`, `RuntimeRecoveryCoordinator`, `TaskDeadlineProperties`, `CoreCatalog`, `DefaultAgentExecutionPolicy`, `RunWorkspaceService`, `WorkspacePaths`, `OwnedProcess`, `OwnedProcessController`, `SandboxLifecycle` are each declared once in Section 2.1 and referenced with the same names afterwards. `ExecutionBackend` gained `pauseWriters`/`resumeWriters`, `CoreAdapter` gained `capabilities(ExecutionMode)`; both are consumed in Tasks 9-13. Migration numbers V59-V62 are used once each.

## 6. Execution handoff

After this plan is approved for execution, use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans`. Task 1 blocks all runtime claims; Tasks 20-21 each require their own explicit authorization before touching real credentials, real repositories or real data. Implementation workers never commit; the controller stages exact paths and commits per review checkpoint only after separate authorization.
