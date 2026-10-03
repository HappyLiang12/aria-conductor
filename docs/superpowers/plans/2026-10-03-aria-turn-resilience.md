# Aria Turn Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Failed Aria turns become visible (timeline entry + retry + honest panel copy), Aria-dispatched batches wake their conversation through a notification and a one-click synthesis, and a sandbox whose upload relay never establishes is recreated once instead of failing the run.

**Architecture:** Three phases on one branch, executed C → A → B. Phase C hardens `SandboxExecutionBackend`/`SandboxLifecycle` (act-execution). Phase A extends the existing conversation timeline/stream/panel machinery (act-aria + act-dashboard). Phase B adds a `dispatched_by_run_id` column (V65), stamps it in the Aria run tool, and adds a batch-completion listener + notification + synthesize endpoint + one-click UI.

**Tech Stack:** Java 21 / Spring Boot 3.3 (act-execution, act-aria, act-common, act-app), Flyway (H2 MODE=MySQL), React 19 / Vite / vitest (act-dashboard).

**Spec:** `docs/superpowers/specs/2026-10-03-aria-turn-resilience-design.md` (commits `14b7b561`, `7fa5e825`) — the binding authority.

## Global Constraints

- Branch is cut from `main` AFTER PR #102 merges; all tasks run in that branch's worktree. Do not build on `feat/run-admission-limit`.
- Children dispatched by Aria MUST NOT receive `conversationId` (timeline/context pollution trap) — only `dispatched_by_run_id`.
- `TimelineEntry` gains fields additively (`error`, `retryPrompt`); existing consumers must keep compiling.
- Listener phase pattern for completion work: `@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)`.
- No new user-facing configuration knobs; the recreate budget is a constant (`1`).
- Migration id: `V65` (latest is `V64`). H2 MODE=MySQL-compatible DDL, matching `V63__approval_source.sql` style.
- Docs, comments and commit messages in English; TDD (RED → GREEN) per task; each task ends with its own commit.
- Every exact value (message strings, payload keys, test expectations) appears once, in this plan; implementers use them verbatim.

---

### Task 1: Relay-never-established classification (SandboxLifecycle)

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/sandbox/SandboxLifecycle.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/sandbox/OpenSandboxSdkTest.java`

**Interfaces:**
- Consumes: the existing upload loop (`OpenSandboxSdk.upload`, SL:815-841) and its exhaustion exception `TaskExecutionException(Cause.SANDBOX_UNAVAILABLE, "Workspace upload failed for sandbox <id>: <last message>")`.
- Produces: `static boolean isRelayNeverEstablished(Throwable exhaustionCause)` on `SandboxLifecycle` (package-visible) — true when this is the relay warm-up failure class.

- [ ] **Step 1: Write the failing tests** (append to `OpenSandboxSdkTest`)

```java
    @Test
    void relayNeverEstablished_matchesTheConnectRefusedRelayPattern() {
        Exception attempt = new RuntimeException(
                "Network connectivity error: Failed to connect to localhost/127.0.0.1:59217");
        TaskExecutionException exhaustion = new TaskExecutionException(
                TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                "Workspace upload failed for sandbox abc: " + attempt.getMessage(), attempt);
        assertThat(SandboxLifecycle.isRelayNeverEstablished(exhaustion)).isTrue();
    }

    @Test
    void relayNeverEstablished_matchesTheIpv6RelayPatternInTheCause() {
        TaskExecutionException exhaustion = new TaskExecutionException(
                TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                "Workspace upload failed for sandbox abc: transport error",
                new RuntimeException("Network connectivity error: "
                        + "Failed to connect to localhost/[0:0:0:0:0:0:0:1]:59217"));
        assertThat(SandboxLifecycle.isRelayNeverEstablished(exhaustion)).isTrue();
    }

    @Test
    void relayNeverEstablished_rejectsTheDistributionFaultAndPlainTimeouts() {
        TaskExecutionException distribution = new TaskExecutionException(
                TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                "Workspace upload failed for sandbox abc: Server error : 500 Internal Server Error "
                        + "{\"code\":\"DOCKER::SANDBOX_EXECD_DISTRIBUTION_FAILED\"}",
                new RuntimeException("broken pipe"));
        assertThat(SandboxLifecycle.isRelayNeverEstablished(distribution)).isFalse();

        TaskExecutionException eof = new TaskExecutionException(
                TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                "Workspace upload failed for sandbox abc: unexpected end of stream",
                new RuntimeException("unexpected end of stream"));
        assertThat(SandboxLifecycle.isRelayNeverEstablished(eof)).isFalse();
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest=OpenSandboxSdkTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: FAIL — `isRelayNeverEstablished` does not exist (compile error).

- [ ] **Step 3: Implement the classifier** (in `SandboxLifecycle`, next to `isTransientUploadError`)

```java
    /**
     * The relay-warm-up failure class: every upload attempt was refused by the
     * sandbox's published-port relay, so the port NEVER accepted a connection
     * within the window. Unlike a transient execd fault, waiting longer cannot
     * heal this sandbox -- only a fresh one can (see the recreate path).
     */
    static boolean isRelayNeverEstablished(Throwable exhaustionCause) {
        for (Throwable t = exhaustionCause; t != null; t = t.getCause()) {
            if (t.getCause() == t) break;
            String message = t.getMessage();
            if (message == null) continue;
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("failed to connect to localhost/127.0.0.1:")
                    || lower.contains("failed to connect to 127.0.0.1:")
                    || lower.contains("failed to connect to localhost/[0:0:0:0:0:0:0:1]:")) {
                return true;
            }
        }
        return false;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest=OpenSandboxSdkTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/sandbox/SandboxLifecycle.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/sandbox/OpenSandboxSdkTest.java
git commit -m "feat(runtime): classify the relay-never-established upload failure"
```

---

### Task 2: Bounded sandbox recreate on the relay-dead upload (SandboxExecutionBackend)

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/sandbox/SandboxExecutionBackend.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/sandbox/SandboxExecutionBackendTest.java`

**Interfaces:**
- Consumes: Task 1's `SandboxLifecycle.isRelayNeverEstablished(Throwable)`; the existing `SEB.prepare` create call (SEB:129-136) and `SEB.launch` upload sequence + catch (SEB:162-174); `lifecycle.destroy(UUID)` (SL:417-425, idempotent); `lifecycle.create(UUID, String image, Map)` (SL:190-204); the endpoint resolution pattern used in `prepare` (`resolveEndpoint`, SEB:129-136).
- Produces: relay-dead uploads are retried once against a FRESH sandbox inside `launch`; constant `RELAY_RECREATE_BUDGET = 1`.

Verified anchors (recon): `SEB.launch` currently is:
```java
lifecycle.uploadSnapshot(environment.runId(), prepared.snapshotRoot(), manifest);
lifecycle.uploadRunConfiguration(environment.runId(), prepared.stagingDirectory(), "config");
lifecycle.launch(environment.runId());
} catch (RuntimeException e) {
    environments.remove(environment.runId());
    lifecycle.destroy(environment.runId());
    throw e;
```

- [ ] **Step 1: Write the failing test** (append to `SandboxExecutionBackendTest`, using the existing `RecordingSdk` double at SEBT:636)

Structured test (follows the recording-double + operation-order pattern already in this class, e.g. `containsExactly("create","upload","launch",...)` SEBT:177):

- Configure `RecordingSdk` so the FIRST `upload` call throws `TaskExecutionException(SANDBOX_UNAVAILABLE, "Workspace upload failed for sandbox <first-id>: Network connectivity error: Failed to connect to localhost/127.0.0.1:59217", new RuntimeException("Failed to connect to localhost/127.0.0.1:59217"))` and the SECOND upload succeeds.
- Drive the production path that reaches `launch` (the same harness/flow the existing tests use).
- Assert the operation sequence contains, after the failed upload: `destroy(<first-id>)`, `create` (a NEW sandbox id), a second `upload`, `launch` — and the run's environment references the NEW sandbox id.
- Second scenario in the same class: with a NON-relay upload failure (message `unexpected end of stream`), the sequence contains exactly ONE `create` and the run fails as today (no recreate).

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest=SandboxExecutionBackendTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: FAIL — no recreate happens; the first upload failure propagates.

- [ ] **Step 3: Implement the recreate in `launch`**

Add to `SandboxExecutionBackend`:
```java
    /** One recreate attempt per launch: a second relay-dead sandbox fails the run. */
    static final int RELAY_RECREATE_BUDGET = 1;
```

Restructure `launch` so the upload sequence runs as a local helper that can run twice:

```java
    @Override
    public RuntimeHandle launch(PreparedEnvironment prepared, LaunchProfile profile) {
        PreparedEnvironment environment = prepared;
        int recreateBudget = RELAY_RECREATE_BUDGET;
        try {
            while (true) {
                try {
                    uploadAll(environment);
                    lifecycle.launch(environment.runId());
                    break;
                } catch (TaskExecutionException e) {
                    if (recreateBudget <= 0 || !SandboxLifecycle.isRelayNeverEstablished(e)) {
                        throw e;
                    }
                    recreateBudget--;
                    log.warn("Relay never established for sandbox {} (run {}); recreating ({} left)",
                            environment.sandboxId(), environment.runId(), recreateBudget + 1);
                    lifecycle.destroy(environment.runId());
                    environment = recreateSandbox(prepared, profile);
                    log.info("Recreated sandbox {} for run {}", environment.sandboxId(), environment.runId());
                }
            }
            // ... existing post-launch wiring continues unchanged ...
    }
```

Implement `uploadAll(PreparedEnvironment)` as the existing three upload calls moved verbatim, and `recreateSandbox(PreparedEnvironment, LaunchProfile)` by reading the CURRENT `prepare` method: it must create a fresh sandbox via `lifecycle.create(runId, image, Map.of())` (image comes from the same `LaunchProfile`/profile source `prepare` uses) and re-resolve the endpoint exactly as `prepare` does, returning an updated `PreparedEnvironment` that keeps the same `runId`/snapshot paths but the new sandbox id. Keep the existing outer catch (destroy + remove + rethrow) unchanged for all other failures.

Implementer note: adapt names/signatures to the real code in `SandboxExecutionBackend` (this task's snippet is behavior-binding; only names/positions may adapt). If `LAUNCH` must also re-upload the run CONFIGURATION on the fresh sandbox, `uploadAll` is the single place that does both uploads, so both rerun by construction.

- [ ] **Step 4: Run the class + the SDK tests to verify GREEN**

Run: `cd agent-control-tower && mvn -B test -pl act-execution -Dtest="SandboxExecutionBackendTest,OpenSandboxSdkTest" -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: PASS (new scenarios + all existing).

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/sandbox/SandboxExecutionBackend.java \
        agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/sandbox/SandboxExecutionBackendTest.java
git commit -m "feat(runtime): recreate a relay-dead sandbox once and re-upload"
```

---

### Task 3: Timeline failure entry (backend)

**Files:**
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/dto/TimelineEntry.java`
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/controller/AriaConversationController.java` (`getTimeline`, L76-103)
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/controller/AriaConversationControllerTest.java` (create if absent; follow the module's existing test style — check an existing act-aria test for the pattern first)

**Interfaces:**
- Produces: `TimelineEntry.error` (`boolean`), `TimelineEntry.retryPrompt` (`String`, nullable). For every conversation run with status `FAILED`, `getTimeline` appends one synthetic entry AFTER that run's trajectory entries:
  - `role = "assistant"`, `error = true`, `runId = run.getId().toString()`,
    `timestamp = run.getCompletedAt() != null ? run.getCompletedAt() : run.getUpdatedAt()`,
    `content = "回合執行失敗：" + clip(run.getErrorMessage(), 300)` (null/blank message → `"回合執行失敗：原因不明"`),
    `retryPrompt = run.getPromptSeed()`.
- Consumes: `runRepository.findByConversationIdOrderByCreatedAtAsc` (exists), `trajectoryRepository.findByRunIdInOrderByTurnNumberAsc` (exists).

- [ ] **Step 1: Write the failing test**

Unit test mocking `RunRepository` + `SessionTrajectoryRepository`, constructing the controller directly (fields are constructor-injected — match the real constructor), with:
- run A COMPLETED with 2 trajectories; run B FAILED (`errorMessage = "Workspace upload failed for sandbox x: Failed to connect"`, `promptSeed = "what next"`, `completedAt` set) with 1 trajectory.
- Assert the returned list order: A's entries, B's trajectory entry, then B's synthetic entry with `error=true`, `retryPrompt="what next"`, and `content` = `"回合執行失敗：Workspace upload failed for sandbox x: Failed to connect"`.
- Second case: FAILED run with null errorMessage → content `"回合執行失敗：原因不明"`.
- Third case: COMPLETED run → no synthetic entry.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn -B test -pl act-aria -Dtest=AriaConversationControllerTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: FAIL (no synthetic entry; fields missing).

- [ ] **Step 3: Implement**

`TimelineEntry`: add `private boolean error;` and `private String retryPrompt;` (Lombok `@Data @Builder` already present, additive).

`getTimeline`: after mapping the trajectories, append synthetic entries. Sketch (adapt names to the real method):

```java
        List<TimelineEntry> timeline = new ArrayList<>(mappedTrajectories);
        for (Run run : runs) {
            if (run.getStatus() != RunStatus.FAILED) continue;
            timeline.add(TimelineEntry.builder()
                    .role("assistant")
                    .error(true)
                    .runId(run.getId().toString())
                    .timestamp(run.getCompletedAt() != null ? run.getCompletedAt() : run.getUpdatedAt())
                    .content("回合執行失敗：" + clipError(run.getErrorMessage()))
                    .retryPrompt(run.getPromptSeed())
                    .build());
        }
        return ResponseEntity.ok(timeline);
```

with a private static `clipError(String message)` returning `"原因不明"` for null/blank and `message.length() > 300 ? message.substring(0, 300) : message` otherwise (append the synthetic entries in run order AFTER all trajectory entries of that run — keep the existing global sort for trajectories; appending per failed run in `runs` order satisfies "after that run's entries").

- [ ] **Step 4: Run test to verify it passes**

Run: `cd agent-control-tower && mvn -B test -pl act-aria -Dtest=AriaConversationControllerTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/dto/TimelineEntry.java \
        agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/controller/AriaConversationController.java \
        agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/controller/AriaConversationControllerTest.java
git commit -m "feat(aria): surface failed turns as timeline entries with a retry prompt"
```

---

### Task 4: Failed-run note in the LLM context (AriaService)

**Files:**
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaService.java` (`loadConversationHistory`, L318-343)
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/service/AriaServiceHistoryTest.java` (or extend the existing AriaService test class if one exists — check first)

**Interfaces:**
- Produces: FAILED runs are no longer filtered out of history; each FAILED run appends one synthetic assistant message after its trajectories:
  `"（系統註記：上一回合因「" + clip(run.getErrorMessage(), 200) + "」失敗，未產生回覆。）"` (null/blank → `"（系統註記：上一回合失敗，未產生回覆。）"`).

- [ ] **Step 1: Write the failing test**

Mock `RunRepository`/`SessionTrajectoryRepository` so the conversation has one COMPLETED run (1 user + 1 assistant trajectory) and one FAILED run (1 user trajectory + `errorMessage = "relay dead"`). Assert `loadConversationHistory` (call the public path that reaches it, or make the method package-private — match the class's existing test exposure pattern): contains the user seed of the failed run, contains an assistant message exactly `"（系統註記：上一回合因「relay dead」失敗，未產生回覆。）"`, and count is 4.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn -B test -pl act-aria -Dtest=AriaServiceHistoryTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: FAIL — the failed run is excluded today.

- [ ] **Step 3: Implement**

Replace the filter block (`L323-325`) so `priorRunIds` includes ALL runs; after building `history`, append per failed run (in `priorRuns` order) the synthetic `LlmMessage.assistant(...)` line using the exact template above with a 200-char clip. Keep the existing role/content filters and `keepMostRecent(history, 40)` at the end (the note count feeds the cap like any message).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd agent-control-tower && mvn -B test -pl act-aria -Dtest=AriaServiceHistoryTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaService.java \
        agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/service/AriaServiceHistoryTest.java
git commit -m "feat(aria): tell the next turn when the previous one failed"
```

---

### Task 5: Stream failure payload enrichment (AriaStreamService)

**Files:**
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaStreamService.java` (failure catch L97-109; `sendErrorSilent` L166-174)
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/service/AriaStreamServiceFailureTest.java` (or the module's existing AriaStreamService test — check first)

**Interfaces:**
- Produces: on a turn failure the SSE `error` payload becomes
  `{"message": <existing message>, "turnFailed": true, "runId": "<run uuid>", "reason": clip(run.errorMessage|cause message, 300)}`
  (additive keys; the existing `message` key stays). Non-turn errors keep the old payload.

- [ ] **Step 1: Write the failing test**

Use a mock `SseEmitter` capturing `send(SseEventBuilder)` payloads (or extract the payload-building into a package-private static method and test that — pick the approach matching how the class is currently tested). Drive the failure path (engine throws / marks the run FAILED) and assert the last emitted event name is `error` and its data JSON contains `"turnFailed":true` and the run id. Include a case asserting the old payload still has `message`.

- [ ] **Step 2: Run to verify it fails**

Run: `cd agent-control-tower && mvn -B test -pl act-aria -Dtest=AriaStreamServiceFailureTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: FAIL.

- [ ] **Step 3: Implement**

In the catch (L97-109) where the run is marked FAILED, build the enriched payload exactly as in **Produces** (clip 300, fallback to the exception message when `errorMessage` is blank) and pass it to `sendErrorSilent`. Keep `emitter.complete()` behavior.

- [ ] **Step 4: Run to verify it passes**

Run: `cd agent-control-tower && mvn -B test -pl act-aria -Dtest=AriaStreamServiceFailureTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaStreamService.java \
        agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/service/AriaStreamServiceFailureTest.java
git commit -m "feat(aria): mark turn failures in the stream error payload"
```

---

### Task 6: Panel copy + retry for failed turns (frontend, Phase A complete)

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/api/ariaConversations.ts` (`TimelineEntry` type L9-14)
- Modify: `agent-control-tower/act-dashboard/src/components/AriaPanel.tsx` (timeline mapping L172-183; `reportRunUncertain` L246-297; `handleRetry` L389-397)
- Test: `agent-control-tower/act-dashboard/src/components/__tests__/AriaPanel.test.tsx`

**Interfaces:**
- Consumes: A1's timeline fields (`error`, `retryPrompt`), A4's stream payload (`turnFailed`, `reason`, `runId`).
- Produces: loading a conversation maps timeline `error` entries to `PanelMessage` with `error: true` and `retryText: retryPrompt`; the panel prefers the actual failure reason over the pending-ask copy; Retry is allowed for a failed turn and resends `retryText`.

- [ ] **Step 1: Write the failing tests** (extend `AriaPanel.test.tsx`, vitest + RTL, existing mock style)

1. `'a failed turn from the timeline renders its reason and offers retry using its own prompt'` — mock `getConversationTimeline` returning `[{role:'user',content:'what next',...},{role:'assistant',content:'回合執行失敗：relay dead',error:true,retryPrompt:'what next',runId:'r1'}]`; assert the error bubble text contains `relay dead` and a Retry button; click it and assert `streamMessage` was called with `'what next'`.
2. `'a stream turn failure shows the reason instead of the pending-ask copy'` — trigger `onError` with `{turnFailed:true, reason:'relay dead'}` while `listApprovals('PENDING')` returns 14; assert the bubble contains `relay dead` and NOT `pending ask`; assert Retry present.
3. Keep the existing pending-ask test green (non-turn error path unchanged).

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/AriaPanel.test.tsx`
Expected: FAIL (new assertions).

- [ ] **Step 3: Implement**

- `ariaConversations.ts`: extend `TimelineEntry` with `error?: boolean; retryPrompt?: string;`.
- `AriaPanel.tsx` timeline mapping: when `entry.error` → message `{role:'assistant', content: entry.content, error: true, retryText: entry.retryPrompt}` (`PanelMessage` gains optional `retryText?: string`).
- `reportRunUncertain(conversationId, origin, errorDetail)`: parse `errorDetail` first — when it carries `turnFailed` → bubble `⚠ 上一回合失敗：${reason}` with `noRetry: false`; otherwise keep the existing approvals-based copy (unchanged).
- `handleRetry`: resend `messages[last].retryText ?? lastSentMessage`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/AriaPanel.test.tsx`
Expected: PASS (new + existing).

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard/src/api/ariaConversations.ts \
        agent-control-tower/act-dashboard/src/components/AriaPanel.tsx \
        agent-control-tower/act-dashboard/src/components/__tests__/AriaPanel.test.tsx
git commit -m "feat(dashboard): failed turns show their reason and retry with their own prompt"
```

---

### Task 7: Dispatch-group schema (V65 + Run)

**Files:**
- Create: `agent-control-tower/act-app/src/main/resources/db/migration/V65__run_dispatch_group.sql`
- Modify: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/Run.java`
- Modify: `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/repository/RunRepository.java`
- Test: `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/RunDispatchGroupMigrationIntegrationTest.java` (model: `ExecutionBindingMigrationIntegrationTest`)

**Interfaces:**
- Produces: `runs.dispatched_by_run_id UUID NULL` + index `idx_runs_dispatched_by`; `Run.dispatchedByRunId` (`UUID`); `RunRepository.findByDispatchedByRunId(UUID)`.

- [ ] **Step 1: Write the failing test** (SpringBootTest IT on H2, following the migration-IT pattern)

Insert a run with `dispatched_by_run_id` set via the entity, read it back through `findByDispatchedByRunId`, assert round-trip; assert an existing run without the column value still reads (null-safe).

- [ ] **Step 2: Run to verify it fails**

Run: `cd agent-control-tower && mvn -B verify -pl act-app -am -Dskip.unit.tests=true -Dit.test=RunDispatchGroupMigrationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: FAIL (column/field missing).

- [ ] **Step 3: Implement**

Migration (V63 style):
```sql
-- Dispatch-group linkage: the Aria turn's run that dispatched a child run.
-- Children NEVER carry conversation_id (the timeline/context select by it).
ALTER TABLE runs ADD COLUMN dispatched_by_run_id UUID NULL;
CREATE INDEX idx_runs_dispatched_by ON runs (dispatched_by_run_id);
```
`Run`: add `@Column(name = "dispatched_by_run_id", columnDefinition = "UUID") private UUID dispatchedByRunId;` (additive, Lombok builder covers it).
`RunRepository`: `List<Run> findByDispatchedByRunId(UUID dispatchedByRunId);` (derived query, matching house style).

- [ ] **Step 4: Run to verify it passes**

Same command as Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-app/src/main/resources/db/migration/V65__run_dispatch_group.sql \
        agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/Run.java \
        agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/repository/RunRepository.java \
        agent-control-tower/act-app/src/test/java/io/aria/conductor/app/RunDispatchGroupMigrationIntegrationTest.java
git commit -m "feat(runs): dispatched_by_run_id column and finder (V65)"
```

---

### Task 8: Stamp the dispatch group in the Aria run tool

**Files:**
- Modify: `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/dto/CreateRunRequest.java` (add `UUID dispatchedByRunId`)
- Modify: `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/service/RunService.java` (`createRun` builder, L89-94 — set it when present)
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/tools/handlers/RunToolHandler.java` (`startRun`, L57-71)
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/tools/handlers/RunToolHandlerDispatchStampTest.java`

**Interfaces:**
- Consumes: `_runContext` injected by `ToolExecutionEngine` into tool arguments (precedent: `GitPackHandler` reads `_runId`); `RunContext.getRunId()`.
- Produces: child runs created by `RunToolHandler` carry `dispatched_by_run_id = <dispatching turn's runId>`; when `_runContext` is absent (non-engine callers/tests), the stamp is simply null (no conversationId stamping ever).

- [ ] **Step 1: Write the failing test**

Unit test constructing `RunToolHandler` with mocked `RunService`/`AgentToolHandler` deps (match its real constructor): call `execute(Map.of("toolName","run_agent","agentId","<uuid>","prompt","x","_runContext", runContext))` where `runContext = new RunContext(uuid, "...", ...)` — build it the way tests do elsewhere (grep for existing RunContext test constructions first); capture the `CreateRunRequest` passed to `runService.createRun` and assert `getDispatchedByRunId()` equals `runContext.getRunId()`. Second case: no `_runContext` key → `getDispatchedByRunId()` null.

- [ ] **Step 2: Run to verify it fails**

Run: `cd agent-control-tower && mvn -B test -pl act-aria -Dtest=RunToolHandlerDispatchStampTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: FAIL.

- [ ] **Step 3: Implement**

`CreateRunRequest`: add `private UUID dispatchedByRunId;` (Lombok builder). `RunService.createRun`: `.dispatchedByRunId(request.getDispatchedByRunId())` in the builder. `RunToolHandler.startRun`: read `arguments.get("_runContext")`; when it is a `RunContext`, set `.dispatchedByRunId(ctx.getRunId())`. NEVER set a conversationId anywhere in this path.

- [ ] **Step 4: Run to verify it passes**

Run: `cd agent-control-tower && mvn -B test -pl act-aria -Dtest=RunToolHandlerDispatchStampTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/dto/CreateRunRequest.java \
        agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/service/RunService.java \
        agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/tools/handlers/RunToolHandler.java \
        agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/tools/handlers/RunToolHandlerDispatchStampTest.java
git commit -m "feat(aria): stamp dispatched_by_run_id on tool-dispatched runs"
```

---

### Task 9: Batch-completion notification

**Files:**
- Create: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/listener/RunBatchCompletionListener.java`
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/persistence/` notification repository (add derived finder `boolean existsByTypeAndResourceIdAndBodyContaining(String type, String resourceId, String fragment)` — match the real repo interface name)
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/listener/RunBatchCompletionListenerTest.java`

**Interfaces:**
- Consumes: `RunCompletedEvent` (getters `runId, agentId, status`); `RunRepository.findByDispatchedByRunId` + `findById`; `NotificationService.create(type,title,body,resourceType,resourceId)` (NotificationService.java:46).
- Produces: when the completed run has a `dispatchedByRunId` and ALL its siblings (same `dispatchedByRunId`) are terminal, exactly one notification:
  - type `run.batch.completed`
  - title `子任務批次完成（N 個：成功 X／失敗 Y）` (N=group size; X=COMPLETED count; Y=terminal-but-not-COMPLETED count)
  - body `Batch <dispatchedByRunId>: Runs: <id:status>, <id:status>, ...` (the dispatch-group id marker verbatim first, then the list in createdAt order, 5 max, `…` if more)
  - `resourceType "CONVERSATION"`, `resourceId = parent.conversationId` (parent = `findById(dispatchedByRunId)`; if the parent or its conversationId is missing, skip with a warn).
  - Dedupe is PER dispatch group: skip when a notification of this type already exists on this conversation whose body contains the current group's id — `existsByTypeAndResourceIdAndBodyContaining("run.batch.completed", conversationId, dispatchedByRunId.toString())`. A later batch in the same conversation notifies again; a replayed completion of an already-notified group stays suppressed. A residual simultaneous-completion race is accepted.
- Listener annotation: `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)` (house pattern).

- [ ] **Step 1: Write the failing tests** (mock repos + notification service; call the listener method directly)

1. Child completes but a sibling is RUNNING → no notification.
2. Last child completes → one notification with the exact title for 3 children (2 COMPLETED, 1 FAILED), body headed by `Batch <dispatchedByRunId>:`, and resourceId = parent conversationId.
3. Same completion replayed for an already-notified group → the per-group dedupe check suppresses a second create (stub `existsByTypeAndResourceIdAndBodyContaining` true for this group's id).
4. Run without `dispatchedByRunId` → no notification.
5. REGRESSION (R-ATR3): a SECOND batch (different `dispatchedByRunId`) completing in the SAME conversation notifies again — pin its own `Batch <dispatchedByRunId>: Runs: ...` body and title (a conversation-scoped dedupe would have suppressed every later batch forever).

- [ ] **Step 2: Run to verify it fails**

Run: `cd agent-control-tower && mvn -B test -pl act-aria -Dtest=RunBatchCompletionListenerTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: FAIL (class missing).

- [ ] **Step 3: Implement** the listener exactly per **Produces** (group load → all-terminal check → parent lookup → dedupe check → create; wrap the whole body in a try/catch that only logs — a listener must never break the publisher).

- [ ] **Step 4: Run to verify it passes**

Same command as Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/listener/RunBatchCompletionListener.java \
        agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/persistence/ \
        agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/listener/RunBatchCompletionListenerTest.java
git commit -m "feat(aria): notify the conversation when a dispatched batch completes"
```

---

### Task 10: Synthesize endpoint

**Files:**
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/controller/AriaConversationController.java` (add `POST /{conversationId}/synthesize`)
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaService.java` (add `composeSynthesisPrompt`)
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/service/AriaSynthesisPromptTest.java`
- Modify (required at execution, review-adjudicated): `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/controller/AriaConversationControllerTest.java` — the controller constructor gains `AriaService`; without the updated test construction the module's tests do not compile.

**Interfaces:**
- Consumes: `findByConversationIdOrderByCreatedAtAsc` + `findByDispatchedByRunId`.
- Produces: `POST /api/v1/aria/conversations/{conversationId}/synthesize` with optional body `{"dispatchedByRunId": "<uuid>"}` → `{"prompt": "<composed>"}`. When `dispatchedByRunId` is absent, resolve the LATEST group: the newest run in the conversation that has at least one child (via `findByDispatchedByRunId`), else 404. Composed prompt (exact template):

```
以下子任務已完成，請彙整結果並給我建議報告與下一步：
<for each child by createdAt: "- run <id>：<status>">
請先讀取需要的子任務結果（用你的 run 工具），以繁體中文輸出：完成/失敗統計、各子任務重點、整體建議、仍無法核實之事項。
```

- [ ] **Step 1: Write the failing test**

Mock repos: conversation with parent P (conversationId `conv-1`) and 3 children (2 COMPLETED, 1 FAILED). Assert `composeSynthesisPrompt("conv-1", null)` returns a string containing `run <completed-id>：COMPLETED`, `run <failed-id>：FAILED`, the instruction lines verbatim, and that children are listed in createdAt order; unknown conversation → empty/exception per the chosen 404 mapping (match controller conventions for error mapping).

- [ ] **Step 2: Run to verify it fails**

Run: `cd agent-control-tower && mvn -B test -pl act-aria -Dtest=AriaSynthesisPromptTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Expected: FAIL.

- [ ] **Step 3: Implement** the service method + controller endpoint (controller catches the not-found into the module's usual 404).

- [ ] **Step 4: Run to verify it passes**

Same command as Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/controller/AriaConversationController.java \
        agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaService.java \
        agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/service/AriaSynthesisPromptTest.java \
        agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/controller/AriaConversationControllerTest.java
git commit -m "feat(aria): compose a one-click synthesis prompt for a dispatch batch"
```

---

### Task 11: One-click 彙整 in the dashboard (Phase B complete)

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/api/ariaConversations.ts` (add `composeSynthesis(conversationId): Promise<{prompt: string}>`)
- Modify: `agent-control-tower/act-dashboard/src/components/NotificationBell.tsx` (row rendering L142-158)
- Modify: `agent-control-tower/act-dashboard/src/components/AriaPanel.tsx` (listen for the `aria:compose` window event)
- Test: `agent-control-tower/act-dashboard/src/components/__tests__/NotificationBell.test.tsx` (create) + extend `AriaPanel.test.tsx`

**Interfaces:**
- Consumes: the Task 10 endpoint; notification `type === 'run.batch.completed'` with `resourceType 'CONVERSATION'` and `resourceId` = conversationId.
- Produces: the notification row shows a 「彙整」button (rendered inside `notif-item-content` after the title, gated on the type); clicking calls `composeSynthesis(resourceId)`, marks the notification read, and dispatches `window.dispatchEvent(new CustomEvent('aria:compose', { detail: { prompt } }))`. `AriaPanel` adds a `useEffect` listener for `aria:compose` that sends the prompt via `sendStreamed(prompt)` (and clears the listener on unmount).

- [ ] **Step 1: Write the failing tests**

1. `'a run.batch.completed notification renders the 彙整 action and composes a synthesis prompt'` — mock the API list with that notification; click 彙整; assert `composeSynthesis` called with the conversationId and that an `aria:compose` CustomEvent fires with the prompt (spy on `window.dispatchEvent` or add a listener in the test).
2. Extend `AriaPanel.test.tsx`: `'an aria:compose event sends the prompt through the stream path'` — dispatch the event on `window`; assert `streamMessage` called with the prompt.

- [ ] **Step 2: Run to verify they fail**

Run: `cd agent-control-tower/act-dashboard && npx vitest run src/components/__tests__/NotificationBell.test.tsx src/components/__tests__/AriaPanel.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Implement** per **Produces** (small styles for the button reuse existing classes; `handleItemClick` navigation stays for plain clicks).

- [ ] **Step 4: Run to verify they pass**

Same command as Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard/src/api/ariaConversations.ts \
        agent-control-tower/act-dashboard/src/components/NotificationBell.tsx \
        agent-control-tower/act-dashboard/src/components/AriaPanel.tsx \
        agent-control-tower/act-dashboard/src/components/__tests__/NotificationBell.test.tsx \
        agent-control-tower/act-dashboard/src/components/__tests__/AriaPanel.test.tsx
git commit -m "feat(dashboard): one-click synthesis from the batch-completion notification"
```

---

### Task 12: Regression and live verification

**Files:** none (verification only; evidence lands in `docs/reviews/2026-10-03-aria-turn-resilience-live-verification.md`).

- [ ] **Step 1: Module + IT regression**

Run: `cd agent-control-tower && mvn -B test -pl act-execution,act-aria,act-agent -Djacoco.skip=true`
Run: `cd agent-control-tower && mvn -B verify -pl act-app -am -Dskip.unit.tests=true -Dit.test="RunAdmissionIntegrationTest,KanbanAutoDispatchIntegrationTest,KanbanTransitionIntegrityIntegrationTest,RunDispatchGroupMigrationIntegrationTest" -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true`
Run: `cd agent-control-tower/act-dashboard && npx vitest run && pnpm build`
Expected: BUILD SUCCESS / all green.

- [ ] **Step 2: Live verification on the local stack (coordinator-run, stack from the branch worktree, local cap=3)**

Replay a small version of the Okinawa drill: dispatch 4 short research runs through an Aria turn on a real sandbox stack; kill one turn's relay artificially if it occurs naturally, otherwise assert the shipped behaviors that are observable: (a) after a forced failed turn (e.g. cancel/stop mid-upload or rely on the next natural relay flake), the conversation shows the failure entry with Retry; (b) the batch-completion notification appears and one click composes + sends the synthesis; (c) grep `Relay never established` / `Recreated sandbox` in `.run/backend.log` for any natural occurrence. Evidence (quoted log lines + API payloads) committed to the review file.

- [ ] **Step 3: Push and open the PR** (requires the operator's explicit go-ahead first — standing push-confirmation rule).

---

## Self-Review (run before executing)

- **Spec coverage:** C detection ✓ Task 1, C heal ✓ Task 2, C observability ✓ Task 2 (log lines); A1 ✓ Task 3, A2 ✓ Task 4, A3 ✓ Task 6, A4 ✓ Task 5; B1 ✓ Tasks 7-8, B2 ✓ Task 9, B3 ✓ Task 9, B4 ✓ Tasks 10-11; regression/live ✓ Task 12. Non-goals honored (no auto-turn, no config knobs, no CANCELLED entry).
- **Placeholder scan:** Task 2's `launch` restructuring is behavior-binding with verified anchors (SEB:129-136, 162-174) and an explicit adapt-names note — the same structured-pattern ruling used in prior plans; Tasks 3-11 carry exact code/templates/strings. No TBD markers.
- **Type consistency:** `isRelayNeverEstablished(Throwable)` (T1) consumed by T2; `TimelineEntry.error/retryPrompt` (T3) consumed by T6 (`retryText` rename at the PanelMessage boundary is explicit); `dispatchedByRunId` consistent across T7/T8/T9/T10; notification type string `run.batch.completed` consistent T9/T11; synthesize contract `{prompt}` consistent T10/T11.
- **Known judgment calls to watch:** (a) exact `PreparedEnvironment`/`LaunchProfile` field names in T2 — adapt to real code, behavior binding; (b) the notification repository finder name in T9 — match the real interface; (c) whether `_runContext` reaches `RunToolHandler` in every dispatch path (kanban-originated dispatches are explicitly out of scope — they carry no conversation).
