# Exchangeable Agent Provider Architecture: OpenCode Integration

## Overview

Aria Conductor currently ships a single agent runtime backend: the self-built LangChain ADK (Python subprocess per agent). This design makes the agent runtime **exchangeable at runtime through the UI**: any agent instance can be switched between backends (LangChain ADK, OpenCode, future providers) without code changes or restarts.

The existing `AdkProvider` strategy interface and `AdkProviderRegistry` (Spring bean auto-registration, routing on `agents.adk_provider`, default fallback) already provide the pluggable foundation. This design:

1. Extends `AdkProvider` with **task-level execution semantics** via default methods + capability probing (LangChain untouched)
2. Adds `OpenCodeAdkProvider` — one `opencode serve` instance per agent running **inside an OpenSandbox sandbox** (hardened isolation), HTTP communication
3. Adds a **delegation branch** in `AgentLoopEngine` for task-level providers (task-level governance: approval gate / budget abort / audit)
4. Adds a provider listing API + dynamic Agents form dropdown (fixing a hardcoded bug) + a dedicated Providers management page
5. Requires **no database migration** (`agents.adk_provider` column exists; OpenCode configuration is purely external files)

## Problem

### Current State

| Layer | Status |
|---|---|
| Strategy interface | `AdkProvider` (providerId/call/prepareAgent/isHealthy/shutdown) + `AbstractAdkProvider` base class — exists |
| Registration & routing | `AdkProviderRegistry`: Spring bean auto-injection, routes on `agent.getAdkProvider()`, falls back to configured default — exists |
| Persistence | `agents.adk_provider` column (V15 migration), `AgentService` create/update support — exists |
| Execution engine | `AgentLoopEngine` resolves via `registry.resolve(agent)` — exists |
| Frontend | ADK Provider dropdown in Agents form — **hardcoded to `langchain`**, contains a duplicate-option bug (both `<option>` values are `"langchain"`) |
| OpenCode implementation | — missing |
| Provider listing API | — missing (UI cannot enumerate available providers) |
| Aria initializer | `AriaDefaultAgentInitializer` hardcodes `LangChainAdkProvider` injection — needs registry routing |

### Core Architectural Conflict

The existing `AdkProvider.call()` has **turn-level LLM semantics** (returns content + tool calls; the Java-side `AgentLoopEngine` executes the tool loop itself). OpenCode is an **end-to-end agent** (given a task, it loops through tools internally until done). The design bridges this with a capability-probed task-level path rather than forcing OpenCode into turn-level semantics.

## Design Goals & Non-Goals

**Goals:**
- G1: Multiple agent backends (LangChain ADK + OpenCode) coexist; switching per agent at runtime via UI
- G2: Per-agent configuration persisted and independently effective (no cross-agent leakage)
- G3: Future providers integrable by adding one Spring bean class — no registry/engine edits
- G4: UI management surface: per-agent backend choice + provider inventory page
- G5: No DB migration; OpenCode behavior config purely external (workspace `opencode.json` / `AGENTS.md`)

**Non-Goals:**
- NG1: Runtime registration/removal of providers (registration = Spring bean; default = config file)
- NG2: Runtime modification of the global default provider via UI (read-only display)
- NG3: Per-tool approval interleaving inside OpenCode tasks (OpenCode internal permissions are tightened via workspace config; Java governs at task level)

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│ AgentLoopEngine                                                  │
│   │  startRun → registry.resolve(agent)                          │
│   ├─ supportsTaskExecution()==false → existing turn loop (untouched)
│   └─ supportsTaskExecution()==true  → taskExecutionPath()        │
│              │                                                  │
│              ▼                                                  │
│   AdkProviderRegistry (Spring bean auto-registration,            │
│                         routes on agent.adkProvider)             │
│     ├─ LangChainAdkProvider   (turn-level, zero changes)         │
│     └─ OpenCodeAdkProvider    (task-level, new)                  │
│           │ OpenSandbox Java SDK (com.alibaba.opensandbox:sandbox)│
│           ▼                                                      │
│     Sandbox instance (template image pre-installs opencode,      │
│                        one per agent)                            │
│       └─ opencode serve --hostname 0.0.0.0 --port 4096           │
└──────────────────────────────────────────────────────────────────┘
```

## Interface Evolution

### AdkProvider (default methods — existing implementations zero changes)

```java
/** Whether this provider executes end-to-end tasks (agent semantics). Default false. */
default boolean supportsTaskExecution() { return false; }

/** Task-level execution: hand the whole run to the provider's internal loop. */
default TaskResult executeTask(Agent agent, UUID runId, String taskPrompt, TaskContext context) {
    throw new UnsupportedOperationException(
        "Provider " + providerId() + " does not support task execution");
}

/** Abort an in-flight task (budget exceeded / user cancel). */
default void abortTask(UUID runId) { }
```

### New Types (same package)

```java
public record TaskResult(UUID runId, String sessionId, String finalOutput,
                         int inputTokens, int outputTokens, boolean aborted) {}

public record TaskContext(int maxRounds, Duration maxDuration,
                          BiConsumer<String, Integer> auditSink) {}

public class TaskExecutionException extends RuntimeException {
    // Cause enum: SANDBOX_UNAVAILABLE / TIMEOUT / ABORTED / PROVIDER_ERROR
}
```

## OpenCodeAdkProvider

### Components (new package `io.aria.conductor.execution.adk.opencode`)

| File | Responsibility |
|---|---|
| `OpenCodeAdkProvider` | `providerId()="opencode"`, `supportsTaskExecution()=true`; instance map `ConcurrentHashMap<UUID, OpenCodeInstance>` (mirrors LangChain pattern); failure-count → sandbox rebuild (RESTART_AFTER_FAILURES=3 pattern) |
| `OpenCodeProperties` | `opencode.*` prefix (see Configuration) |
| `OpenCodeHttpClient` | Single-file isolation of version drift: `POST /session`, `POST /session/:id/message`, `POST /session/:id/abort`, `GET /global/health`; parameterized timeouts/retries |
| `OpenCodeSandboxManager` | OpenSandbox SDK wrapper: `createSandbox(agentId, image)`, `uploadWorkspace(agentId, files)`, `runCommand(sandboxId, "opencode serve ...")`, `getSandboxUrl(sandboxId, port)`, `killSandbox(sandboxId)` |

### Lifecycle Mapping

| Method | Implementation |
|---|---|
| `prepareAgent(agentId, agent)` | create sandbox → upload `data/workspaces/{agentId}` (opencode.json/AGENTS.md) → start `opencode serve --hostname 0.0.0.0 --port {port}` → poll `GET /global/health` ready (60s timeout, 500ms interval, LangChain waitForReady pattern); on failure destroy sandbox and throw `TaskExecutionException(SANDBOX_UNAVAILABLE)` — **no silent fallback** |
| `executeTask(...)` | `POST /session` (title=runId) → `POST /session/:id/message` (system prompt injects agent role rules, aligned with existing `buildMessages`) → poll message status within `TaskContext.maxDuration` (SSE optional) → assemble `TaskResult`; maintain `runId → sessionId` map |
| `abortTask(runId)` | look up mapping → `POST /session/:id/abort` |
| `isHealthy(agentId)` | `GET /global/health` + failure count (3 failures → sandbox rebuild) |
| `shutdownAgent(agentId)` | abort in-flight task + `sandbox.kill()` + clear mapping |
| `shutdownAll()` | kill all sandboxes (`@PreDestroy`) |

### Workspace

Each agent gets an isolated directory `data/workspaces/{agentId}` (directory already exists under act-app/data/workspaces). OpenCode behavior (model, permissions, agent mode) is controlled entirely by `opencode.json` / `AGENTS.md` in that directory — Java passes only messages and the agent ID. The serve process is **not** started with `--auto`; permission requests default to denied.

## Engine Delegation Branch

### Detection (AgentLoopEngine.startRunInternal)

```java
AdkProvider provider = adkProviderRegistry.resolve(agent);
if (provider.supportsTaskExecution()) {
    taskExecutionPath(ctx, provider, emitter, intent);   // delegation branch
    return;
}
// existing turn loop — zero changes
```

### taskExecutionPath Flow

1. Load agent + run; build `TaskContext` (`agent.config.maxToolCallRounds` default 50 → maxRounds; `opencode.max-task-minutes` → maxDuration)
2. **Approval gate**: reuse `ApprovalGate` — high-risk tasks enter PENDING until approved, same entry logic as the turn path
3. Build task prompt: system rules from `buildMessages` (role/knowledge/skill injection) + user messages
4. `provider.executeTask(...)` on a virtual thread; SSE bridge: `run.started` → (optional intermediate) → `run.completed` / `run.error`
5. **Budget abort**: timeout or round limit → `provider.abortTask(runId)` → run ABORTED (existing state transitions)
6. **Audit**: `PromptCall` (input/output tokens) + `Run.finalOutput` persisted (AuditRecorder)

### Governance Layering

- Task level (Java): approval gate before start, budget abort during run, audit after completion
- Inside OpenCode (sandbox): permissions tightened via workspace `opencode.json`; no per-tool interleaving

### AriaDefaultAgentInitializer Fix

Hardcoded `LangChainAdkProvider` injection replaced by `AdkProviderRegistry` resolving the Aria agent's own provider.

## REST API

`AdkProviderController`:

| Endpoint | Description |
|---|---|
| `GET /api/v1/adk/providers` | `[{ id, displayName, supportsTaskExecution, isDefault }]` from `AdkProviderRegistry` (displayName convention: `opencode`→`OpenCode`, `langchain`→`LangChain ADK`) |
| `GET /api/v1/adk/providers/{id}/health` | `{ providerId, healthy }` |

Global default provider shown read-only (`adk.default-provider`).

## Frontend UI

- `src/types/index.ts`: `AdkProviderInfo { id; displayName; supportsTaskExecution; isDefault }`
- `src/api/adk.ts`: `listAdkProviders()`, `getAdkProviderHealth(id)`
- `src/pages/AgentsPage.tsx`: merge duplicate `<option value="langchain">` into one; ADK Provider dropdown rendered dynamically from `useQuery(['adk-providers'])` (fallback `langchain` on empty/error, keeping the existing E2E selector `.form-card select` hasText 'LangChain' compatible); detail dialog shows provider capability badge (task-level / turn-level)
- `src/pages/ProvidersPage.tsx` (new): provider table (ID/display name/capability/isDefault/health) + per-agent backend overview (agents list + adkProvider); wired into `src/App.tsx` sidebar (`data-view="providers"`) and routing

## Configuration & Deployment

```yaml
adk:
  default-provider: langchain        # existing, unchanged
opencode:
  sandbox-server-url: http://localhost:8080   # OpenSandbox server
  sandbox-api-key: ${OPENSANDBOX_API_KEY:}
  image: aria-conductor/opencode-sandbox:1.0  # template image with opencode pre-installed
  port: 4096                                    # per-agent instance port
  max-task-minutes: 30                          # TaskContext default timeout
```

- `act-execution/pom.xml`: add `com.alibaba.opensandbox:sandbox` SDK dependency
- `docker-compose.yml`: add `opensandbox-server` service (Docker runtime, docker socket mount)
- `agent-control-tower/opencode-sandbox/Dockerfile` (new): Node + `npm i -g @opencode-ai/cli` template image
- Backend container gets `OPENSANDBOX_API_KEY` env var

## Error Handling Matrix

| Failure | Behavior |
|---|---|
| Sandbox creation / serve startup failure | `TaskExecutionException(SANDBOX_UNAVAILABLE)`; sandbox destroyed; run FAILED with actionable message (no silent fallback, mirrors LangChain) |
| Task timeout / round limit | `abortTask(runId)` → `POST /session/:id/abort` → run ABORTED |
| HTTP error from opencode serve | Mapped to `TaskExecutionException(PROVIDER_ERROR)` in `OpenCodeHttpClient` |
| Health probe failures (3 consecutive) | Sandbox rebuilt on next `prepareAgent`/`call` |
| Unknown `adkProvider` value on agent | Registry falls back to default provider (existing behavior) |
| Providers API unavailable in UI | Dropdown falls back to `langchain` (existing E2E selector stays valid) |

## Testing Strategy

| Layer | Coverage |
|---|---|
| Unit (act-execution) | `OpenCodeHttpClientTest` (WireMock: session/message/abort/health + error mapping); `OpenCodeAdkProviderTest` (prepare success/sandbox failure, executeTask success/timeout→abort, health rebuild, shutdown kill); `AgentLoopEngineTaskPathTest` (mock task provider: delegation taken, call not invoked, approval gate, budget abort, audit, run states) |
| Integration (act-app) | `OpenCodeTaskExecutionIntegrationTest` (@SpringBootTest + BaseH2IntegrationTest + @MockBean AdkProviderRegistry, mirroring AgentLoopInjectionIntegrationTest): executeTask invoked with system-rule prompt (ArgumentCaptor), run COMPLETED + finalOutput, TIMEOUT → FAILED/ABORTED + PromptCall audit, REST `GET /api/v1/adk/providers` lists opencode+langchain |
| E2E (act-dashboard) | `opencode-adk-e2e.spec.ts` mirroring `langchain-adk-e2e.spec.ts` full flow: configure LLM → create OpenCode agent → verify list/detail + capability badge → runtime switch opencode→langchain→opencode (persistence) → run → poll completion; `adk-providers.spec.ts`: Providers page rendering + dynamic dropdown; existing `langchain-adk-e2e.spec.ts` stays green (regression) |
| CI smoke (optional) | Docker present: build opencode-sandbox image → start opensandbox-server → create sandbox → `opencode --version`; skipped without Docker |

## Assumptions

- OpenSandbox server deploys via Docker runtime (local dev needs Docker Desktop); the opencode template image is provided by this design's Dockerfile, image building is a follow-up task
- OpenCode version pinned; client logic isolated in `OpenCodeHttpClient` for easy upgrades
- No runtime provider add/remove; no runtime change of global default (registration = Spring bean, default = config file)
- Sandbox unavailability fails explicitly (no silent fallback)
- E2E run scenario tolerates timeout when the sandbox is unavailable (same leniency as the existing langchain spec); switch/management scenarios do not depend on a real sandbox

## Future Extensions

- New providers: implement `AdkProvider` (or extend `AbstractAdkProvider`), register as `@Component` — registry, engine, API and UI pick them up automatically
- Task-level semantics for other end-to-end agents (e.g., Claude Code, Codex CLI) via `supportsTaskExecution()`
- Runtime default-provider selection once a management need arises (currently read-only by design)
