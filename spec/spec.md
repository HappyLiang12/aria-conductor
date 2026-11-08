# Spec: Fail-fast when no LLM provider is configured (Issue #54)

## Problem Statement

When no LLM provider API key is configured (no active `LlmProvider` row and no `LLM_PROVIDER_API_KEY` / `LLM_API_KEY` environment variable), a workflow step run stays in `RUNNING` state for over 60 seconds instead of failing immediately. The root cause is that no pre-flight validation exists in the run launch path to check whether a usable LLM provider is available before transitioning the run through `INITIALIZING` → `RUNNING`.

The execution flow silently proceeds:

1. `RunService.createRun()` creates the run as `PENDING` — no key check. (`RunService.java:178`)
2. `AgentLoopEngine.startRunInternal()` transitions to `INITIALIZING` — no key check. (`AgentLoopEngine.java:258`)
3. `dispatchRun()` transitions to `RUNNING` — no key check. (`AgentLoopEngine.java:915`)
4. `CoreRunLauncher.execute()` freezes the binding and delegates to the coordinator — no key check. (`CoreRunLauncher.java:97`)
5. `CoreRuntimeConfiguration.openCodeModelProvider()` silently falls back to `deepseekFallback()` when no active provider exists. The fallback has `providerId="deepseek"`, `model="deepseek-chat"`, `baseUrl="https://api.deepseek.com/v1"` but **no API key**. (`CoreRuntimeConfiguration.java:285-293`, `OpenCodeCoreAdapter.java:275-276`)
6. The OpenCode server starts successfully (it does not validate the key at startup).
7. `session.prompt()` sends the prompt; the OpenCode process attempts to call the LLM provider with an empty/missing API key.
8. The HTTP request to the provider either hangs (connection timeout up to 600s — `DefaultLlmClient.java:78`) or returns an auth error after a long delay.
9. The run sits in `RUNNING` for the duration of the timeout/deadline (up to 45 minutes — `application.yml:71`).

The backstop `ZombieRunReaper` eventually sweeps stuck runs after 120 minutes (`ZombieRunReaper.java`), but this is far too slow for operator feedback. Workflow chains are blocked for the entire duration.

The Qoder core path already has a fail-fast pattern: `CoreCredentialService.resolve()` throws `ActionableIllegalArgumentException` with code `RUNTIME_CREDENTIAL_NOT_CONFIGURED` and surface `LLM_PROVIDERS` when the credential row is missing (`CoreCredentialService.java:54-66`). The OpenCode core path has no equivalent because its key comes from environment variables and the `LlmProvider` table, not the credential store.

## Proposed Solution

Add a pre-flight LLM provider validation that runs **before** the run transitions from `PENDING` to `INITIALIZING`. The check must cover both core types (OpenCode and Qoder) and both key sources (database `LlmProvider` row and YAML/env configuration).

### Change 1: New validation method in `CoreRuntimeConfiguration`

Add a static method `requireConfiguredLlmProvider()` that validates a usable LLM provider exists:

- **File:** `act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreRuntimeConfiguration.java`
- **Location:** Adjacent to `openCodeModelProvider()` (line 285)
- **Logic:**
  1. Query `LlmProviderRepository.findByActiveTrue()`.
  2. If an active provider exists, check that its `apiKey` is non-null and non-blank. If the key is missing, throw `ActionableIllegalArgumentException`.
  3. If no active provider exists, check the YAML-configured fallback: `LlmProperties.getApiKey()` (from `llm.provider.api-key` / `LLM_PROVIDER_API_KEY`). If blank, throw.
  4. The exception uses code `LLM_PROVIDER_NOT_CONFIGURED` and surface `ErrorSurface.LLM_PROVIDERS`.

### Change 2: Invoke the validation in `AgentLoopEngine.startRunInternal()`

- **File:** `act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java`
- **Location:** In `startRunInternal()` (line 210), after loading the `Agent` (line 220) and before the admission gate (line 238).
- **Logic:** Call `CoreRuntimeConfiguration.requireConfiguredLlmProvider(llmProviderRepository, llmProperties)`. If it throws, the exception propagates up through `RunExecutionListener.onRunStarted()`, which is caught by the engine's error handling and transitions the run to `FAILED` with the actionable error code/surface recorded in `RunContext`.
- **Dependency injection:** `AgentLoopEngine` already has access to `LlmProviderRepository` (transitively through `CoreRuntimeConfiguration`). The `LlmProperties` bean must be injected if not already available — verify at implementation time.

### Change 3: Handle the pre-flight failure in the run lifecycle

The `ActionableIllegalArgumentException` thrown before the `INITIALIZING` transition must be caught and result in a `FAILED` run with the error code and surface recorded:

- **File:** `act-execution/src/main/java/io/aria/conductor/execution/listener/RunExecutionListener.java`
- **Location:** In `onRunStarted()` (line 23), the catch block around `agentLoopEngine.startRun()`.
- **Logic:** When `startRun()` throws `ActionableIllegalArgumentException`, transition the run directly from `PENDING` to `FAILED` (not through `INITIALIZING`/`RUNNING`), recording `errorCode` and `errorSurface` on the run row. Publish `RunCompletedEvent` so downstream workflow auto-chaining (`WorkflowAutoChainer.java:106-145`) marks the step and chain as `FAILED`.

### Change 4: Unit tests

- **File:** `act-execution/src/test/java/io/aria/conductor/execution/runtime/CoreRuntimeConfigurationTest.java`
  - Test: `requireConfiguredLlmProvider` throws when no active provider and blank YAML key.
  - Test: `requireConfiguredLlmProvider` throws when active provider has blank API key.
  - Test: `requireConfiguredLlmProvider` passes when active provider has a non-blank API key.
  - Test: `requireConfiguredLlmProvider` passes when YAML key is set and no active provider.

- **File:** `act-execution/src/test/java/io/aria/conductor/execution/engine/AgentLoopEngineTest.java` (or new test class)
  - Test: `startRunInternal` with no configured provider transitions run to `FAILED` with code `LLM_PROVIDER_NOT_CONFIGURED` and surface `LLM_PROVIDERS`.

### Change 5: Integration test

- **File:** `act-app/src/test/java/.../RunControllerRegressionTest.java` (or new integration test)
  - Test: `POST /api/v1/runs` with no LLM provider configured returns a run that reaches `FAILED` within 5 seconds (not 60+), with `errorCode=LLM_PROVIDER_NOT_CONFIGURED` and `errorSurface=LLM_PROVIDERS`.

## Acceptance Criteria

1. **Fast failure:** When no LLM provider API key is configured (neither in the database nor via environment variable), a run transitions from `PENDING` to `FAILED` within 5 seconds — not 60+ seconds.
2. **Actionable error:** The failed run carries `errorCode = "LLM_PROVIDER_NOT_CONFIGURED"` and `errorSurface = "LLM_PROVIDERS"`, enabling the dashboard to display a repair action (navigate to Configure > LLM Providers).
3. **Workflow propagation:** A workflow step whose run fails with this error causes the step to transition to `FAILED` and the chain to transition to `FAILED` promptly (via the existing `WorkflowAutoChainer` event path).
4. **No regression for configured providers:** When an active `LlmProvider` with a non-blank `apiKey` exists, or when `LLM_PROVIDER_API_KEY` is set, runs proceed normally through `INITIALIZING` → `RUNNING` → `COMPLETED`/`FAILED`.
5. **Both cores covered:** The pre-flight check applies to OpenCode core launches (which use environment-based keys) and Qoder core launches (which use the credential store — already validated by `CoreCredentialService.resolve()`).
6. **Error message quality:** The exception message tells the operator exactly what to do: which configuration to set and where (API endpoint and UI path).
7. **Unit and integration tests pass:** All new and existing tests in `act-execution` and `act-app` pass. Coverage checks pass (`mvn test -pl act-execution`, `mvn test -pl act-app`).

## Error Handling

### Pre-flight validation error (new)

| Condition | Error Code | Error Surface | HTTP Status | Run Status | Message |
|-----------|-----------|---------------|-------------|------------|---------|
| No active `LlmProvider` and blank `LLM_PROVIDER_API_KEY` | `LLM_PROVIDER_NOT_CONFIGURED` | `LLM_PROVIDERS` | 400 (if synchronous) or run reaches `FAILED` (if async) | `FAILED` | "No LLM provider is configured. Save an active provider with an API key in Configure > LLM Providers (API: POST /api/v1/llm-providers) or set the LLM_PROVIDER_API_KEY environment variable, then retry." |
| Active `LlmProvider` exists but `apiKey` is blank | `LLM_PROVIDER_NOT_CONFIGURED` | `LLM_PROVIDERS` | Same as above | `FAILED` | "The active LLM provider '<name>' has no API key. Edit the provider in Configure > LLM Providers and save a valid key (API: PUT /api/v1/llm-providers/{id}), then retry." |

### Existing errors (unchanged)

| Condition | Error Code | Error Surface | Source |
|-----------|-----------|---------------|--------|
| Qoder core with no credential row | `RUNTIME_CREDENTIAL_NOT_CONFIGURED` | `LLM_PROVIDERS` | `CoreCredentialService.resolve()` |
| Agent retired/unhealthy | `AGENT_RETIRED` / `AGENT_UNHEALTHY` | `CREW` | `RunService.createRun()` |

### Failure path flow

```
POST /api/v1/workflows (or /api/v1/runs)
  → RunService.createRun() → Run status = PENDING
  → RunExecutionListener.onRunStarted() → agentLoopEngine.startRun()
    → AgentLoopEngine.startRunInternal()
      → CoreRuntimeConfiguration.requireConfiguredLlmProvider()
        → THROW ActionableIllegalArgumentException("LLM_PROVIDER_NOT_CONFIGURED", LLM_PROVIDERS)
      ← exception propagates
    ← RunExecutionListener catches, transitions run PENDING → FAILED
    ← RunContext records errorCode + errorSurface
  → RunCompletedEvent published
  → WorkflowAutoChainer.onRunCompleted() → step → FAILED, chain → FAILED
```

### Edge cases

- **Race with cancel:** If the run is cancelled between `PENDING` and the pre-flight check, the `updateRunStatusDirect(FAILED)` write refuses because the run is already `CANCELLED` (terminal). The cancel wins; no double-transition.
- **Provider activated mid-queue:** A run queued in `PENDING` while no provider exists will fail immediately on `startRunInternal()`. If the operator activates a provider before the engine picks up the run, the check passes. This is correct — the check is at launch time, not at creation time.
- **Blank vs null API key:** Both are treated as unconfigured. `String.isBlank()` covers null, empty, and whitespace-only.
- **Multiple active providers:** `findByActiveTrue()` returns `Optional<LlmProvider>` (single). If the schema ever allows multiple active rows, the first returned is used — same as today's `openCodeModelProvider()` behavior.
