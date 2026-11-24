# Async TOWER Dispatch (Phase 4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A live turn that dispatches an approved run ends after an immediate acknowledgement; the child run's lifecycle reaches the phone as `run_event{origin:"dispatch"}`, its outcome returns as a spoken TOWER message, and a 20 s reminder plus a hard timeout make a long run visible and bounded.

**Architecture:** A per-socket `LiveDispatchService` registry (child run id → socket) is filled when an approved write returns a run id. One Spring `@EventListener` on the existing run events filters by that registry and emits phone frames; two scheduled actions emit the reminder and enforce the deadline. The bridge gains a `mode` on the tower line: `idle` queues until the current response ends, `interrupt` cancels it first.

**Tech Stack:** Java 21 / Spring Boot 3.3 (act-aria, act-common events, act-execution run stop), Node 20 bridge (`packages/qwen-live-bridge`, `ws`), H2 test profile.

**Spec:** `docs/superpowers/specs/2026-11-25-aria-pa-async-tower-design.md`

## Global Constraints

- Java package root `io.aria.conductor`; no new Maven module; no Flyway migration.
- Logs are metadata only — run id, origin, state, elapsed ms. Never a prompt, tool argument, run output, token value or workspace path.
- Frames follow the existing allowlist/`v:1` conventions; new fields are additive (`origin`, `mode`).
- Bridge and backend deploy together; an old bridge ignores `mode` and injects immediately (INTERRUPT behaviour).
- Tests: `bash scripts/mvn-lane.sh inner act-aria -Dtest=<Class>` while iterating, `bash scripts/mvn-lane.sh gate act-aria` before the PR; bridge `cd packages/qwen-live-bridge && npx vitest run`.
- The four operator defaults are fixed: transition line after approval, N=20 s, timeout = run deadline else 10 min, SSE closes after ack (Phase 5, not this plan).

---

### Task 1: Dispatch settings

**Files:**
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/live/LiveProperties.java`
- Modify: `agent-control-tower/act-app/src/main/resources/application.yml`
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/live/LivePropertiesTest.java`

**Interfaces:**
- Produces: `LiveProperties` gains `dispatchReminderMs`, `dispatchTimeoutMs`, `dispatchPreviewChars` with accessors `dispatchReminderMs()`, `dispatchTimeoutMs()`, `dispatchPreviewChars()`; defaults 20000, 600000, 500; a non-positive value falls back to the default.

- [ ] **Step 1: Write the failing test**

```java
@Test
void dispatchDefaultsAndFallbacks() {
    LiveProperties defaults = new LiveProperties(false, null, null, "", 5000, 7200,
            false, null, null, 1500,
            0, 0, 0);
    assertThat(defaults.dispatchReminderMs()).isEqualTo(20_000);
    assertThat(defaults.dispatchTimeoutMs()).isEqualTo(600_000);
    assertThat(defaults.dispatchPreviewChars()).isEqualTo(500);
}
```

The `LiveProperties` constructor is a record-style canonical constructor; append the three fields at the end and update every existing test constructor call with `20_000, 600_000, 500` (grep `new LiveProperties(`).

- [ ] **Step 2: Run it to see it fail**

Run: `bash scripts/mvn-lane.sh inner act-aria -Dtest=LivePropertiesTest`
Expected: compile error (no such fields).

- [ ] **Step 3: Implement**

Add to the record components: `long dispatchReminderMs, long dispatchTimeoutMs, int dispatchPreviewChars`; in the compact constructor:

```java
dispatchReminderMs = dispatchReminderMs <= 0 ? 20_000 : dispatchReminderMs;
dispatchTimeoutMs = dispatchTimeoutMs <= 0 ? 600_000 : dispatchTimeoutMs;
dispatchPreviewChars = dispatchPreviewChars <= 0 ? 500 : dispatchPreviewChars;
```

In `application.yml` under `aria.live`:

```yaml
    dispatch-reminder-ms: ${ARIA_LIVE_DISPATCH_REMINDER_MS:20000}
    dispatch-timeout-ms: ${ARIA_LIVE_DISPATCH_TIMEOUT_MS:600000}
    dispatch-preview-chars: ${ARIA_LIVE_DISPATCH_PREVIEW_CHARS:500}
```

- [ ] **Step 4: Run tests, then commit**

Run: `bash scripts/mvn-lane.sh inner act-aria -Dtest=LivePropertiesTest`
Commit: `feat(live): add the dispatch reminder and timeout settings`

---

### Task 2: Per-socket dispatch registry

**Files:**
- Create: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/live/LiveDispatchService.java`
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/live/LiveDispatchServiceTest.java`

**Interfaces:**
- Produces:
  - `record Dispatch(UUID runId, LiveSession session, String agentName, Instant startedAt)`
  - `void register(UUID runId, LiveSession session, String agentName)`
  - `Optional<Dispatch> find(UUID runId)`
  - `Optional<Dispatch> remove(UUID runId)`
  - `void removeSocket(LiveSession session)` (all runs of that socket)
  - `Set<UUID> runIds()`
- Later tasks consume `find` (listener, timers) and `remove` (terminal, close).

- [ ] **Step 1: Write the failing test**

```java
@Test
void aRunBelongsToOneSocketAndIsRemovedOnce() {
    LiveDispatchService service = new LiveDispatchService();
    LiveSession one = new LiveSession("s1", null, null);
    LiveSession two = new LiveSession("s2", null, null);
    UUID run = UUID.randomUUID();

    service.register(run, one, "Analyst");
    assertThat(service.find(run)).get().extracting(Dispatch::agentName).isEqualTo("Analyst");
    assertThat(service.remove(run)).isPresent();
    assertThat(service.remove(run)).isEmpty();

    service.register(run, two, "Analyst");
    service.removeSocket(one);
    assertThat(service.find(run)).as("another socket's cleanup leaves it").isPresent();
    service.removeSocket(two);
    assertThat(service.find(run)).isEmpty();
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `bash scripts/mvn-lane.sh inner act-aria -Dtest=LiveDispatchServiceTest`
Expected: cannot find symbol `LiveDispatchService`.

- [ ] **Step 3: Implement**

```java
@Slf4j
@Service
public class LiveDispatchService {
    @Getter
    @RequiredArgsConstructor
    public static class Dispatch {
        private final UUID runId;
        private final LiveSession session;
        private final String agentName;
        private final Instant startedAt;
    }

    private final Map<UUID, Dispatch> byRun = new ConcurrentHashMap<>();

    public void register(UUID runId, LiveSession session, String agentName) {
        byRun.put(runId, new Dispatch(runId, session, agentName == null ? "" : agentName, Instant.now()));
        log.debug("live dispatch registered: run={}", runId);
    }

    public Optional<Dispatch> find(UUID runId) {
        return Optional.ofNullable(byRun.get(runId));
    }

    public Optional<Dispatch> remove(UUID runId) {
        return Optional.ofNullable(byRun.remove(runId));
    }

    public void removeSocket(LiveSession session) {
        byRun.values().removeIf(d -> d.getSession() == session);
    }

    public Set<UUID> runIds() {
        return Set.copyOf(byRun.keySet());
    }
}
```

- [ ] **Step 4: Run tests, then commit**

Run: `bash scripts/mvn-lane.sh inner act-aria -Dtest=LiveDispatchServiceTest`
Commit: `feat(live): add the per-socket dispatch registry`

---

### Task 3: Carry the child run id out of the approved write

**Files:**
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/live/LiveToolExecutor.java`
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/live/LiveEventRouter.java`
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/live/LiveEventRouterTest.java`

**Interfaces:**
- `ToolOutcome.Value` gains an optional child id: `record Value(String json, UUID childRunId)` with a `Value(String json)` convenience constructor (`childRunId == null`).
- `startAgentRun(JsonNode)` and `runSkill(JsonNode)` return `new ToolOutcome.Value(compact(view), run.getId())`.
- `LiveEventRouter.approve` registers the child before answering:

```java
if (outcome instanceof LiveToolExecutor.ToolOutcome.Value value && value.childRunId() != null) {
    String agentName = agents.findById(...).map(Agent::getName).orElse("");
    dispatches.register(value.childRunId(), session, agentName);
}
```

- [ ] **Step 1: Write the failing test**

```java
@Test
void anApprovedStartRegistersTheChildRunForThatSocket() {
    // Arrange the router with a stub executor returning Value("{}", childId) and a stub registry.
    router.approve(session, call("start_agent_run"));
    verify(dispatchService).register(eq(childId), eq(session), anyString());
    // The turn's own run is not registered.
    verify(dispatchService, never()).register(eq(turnRun), any(), any());
}
```

- [ ] **Step 2: Run it to see it fail** — `bash scripts/mvn-lane.sh inner act-aria -Dtest=LiveEventRouterTest`

- [ ] **Step 3: Implement** the record change and the two return sites; the approved result text stays exactly `"[tower] " + value.json()` so the phone contract in the spec §4.1 is unchanged.

- [ ] **Step 4: Run tests, then commit** — `feat(live): register the dispatched run on approval`

---

### Task 4: Child-run lifecycle frames

**Files:**
- Create: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/live/LiveDispatchListener.java`
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/live/LiveFrames.java` (`runEvent` gains `origin`)
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/live/LiveDispatchListenerTest.java`

**Interfaces:**
- `LiveFrames.runEvent(UUID runId, String state, String summary)` keeps its signature (origin absent); add `runEvent(UUID runId, String state, String summary, String origin)` that puts `origin` when non-null.
- Listener consumes `RunStartedEvent`, `RunIterationEvent`, `RunProgressEvent`, `RunCompletedEvent` and calls `frames.toPhone(dispatch.getSession(), LiveFrames.runEvent(runId, state, summary, "dispatch"))` only for registered ids.
- Progress coalescing: at most one `progress` frame per run per second (`Instant lastProgress` per run in a local map, cleaned with the registry).

- [ ] **Step 1: Write failing tests**

```java
@Test
void childRunEventsReachOnlyTheirSocketWithOrigin() {
    dispatches.register(child, one, "Analyst");
    listener.onStarted(new RunStartedEvent(child, agentId));
    listener.onCompleted(new RunCompletedEvent(child, RunStatus.COMPLETED, null));
    JsonNode started = phone(one).get(0);
    assertThat(started.path("runId").asText()).isEqualTo(child.toString());
    assertThat(started.path("state").asText()).isEqualTo("started");
    assertThat(started.path("origin").asText()).isEqualTo("dispatch");
    assertThat(phone(two)).isEmpty();
}

@Test
void unknownRunsAndTheTurnRunAreIgnored() { /* publish for child==null and an unregistered id; assert nothing */ }

@Test
void progressIsCoalescedToOneSecond() { /* two RunIterationEvent at t and t+10ms -> one frame */ }
```

- [ ] **Step 2: Run to fail** — `bash scripts/mvn-lane.sh inner act-aria -Dtest=LiveDispatchListenerTest`

- [ ] **Step 3: Implement**

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class LiveDispatchListener {
    private final LiveDispatchService dispatches;
    private final LiveFrames frames; // existing bean or meta-annotated helper

    @EventListener
    public void onStarted(RunStartedEvent event) { progress(event.getRunId(), "started", "running"); }

    @EventListener
    public void onIteration(RunIterationEvent event) {
        progress(event.getRunId(), "progress", "iteration " + event.getIteration());
    }

    @EventListener
    public void onCompleted(RunCompletedEvent event) {
        Optional<LiveDispatchService.Dispatch> found = dispatches.find(event.getRunId());
        if (found.isEmpty()) return;
        LiveDispatchService.Dispatch d = found.get();
        String state = event.getStatus() == RunStatus.COMPLETED ? "finished" : "failed";
        frames.toPhone(d.getSession(), LiveFrames.runEvent(event.getRunId(), state,
                state.equals("finished") ? "finished" : "failed: " + stableReason(event), "dispatch"));
        dispatches.remove(event.getRunId());   // terminal: the timers cancel in Task 6
    }
}
```

Read the real event accessors before writing (`RunIterationEvent`, `RunProgressEvent`, `RunCompletedEvent`); use the payload-free ones only.

- [ ] **Step 4: Run tests, then commit** — `feat(live): stream the dispatched run's lifecycle to the phone`

---

### Task 5: Outcome TOWER with idle/interrupt modes

**Files:**
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/live/LiveEventRouter.java` (or the listener's terminal branch)
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/live/LiveFrames.java` (`tower(text, mode)`)
- Modify: `packages/qwen-live-bridge/src/protocol.ts` (`TowerLine.mode`)
- Modify: `packages/qwen-live-bridge/src/session.ts` (tower queue)
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/live/LiveFramesTest.java`, `packages/qwen-live-bridge/test/session.test.ts`

**Interfaces:**
- Java: `{"type":"tower","v":1,"text":<text>,"mode":"idle"|"interrupt"}` to the bridge. Completion text `[tower] <agent> 已完成：<preview>` bounded by `previewChars`; failure `[tower] <agent> 失敗：<reason>`.
- Bridge: `tower(text, mode)`; `idle` queues in `pendingTowers: string[]` and flushes when `!responseActive && deferredCommit === null` (the same quiet check as `flushAnsweredCalls`); `interrupt` sends `response.cancel` if `responseActive`, then injects and `response.create`.

- [ ] **Step 1: Write failing tests**

Java: `towerCarriesTheModeAndABoundedPreview` — 2 000-char output becomes ≤500 chars, contains no prompt, mode `idle`; failure is `interrupt`.

Bridge:
```ts
it('holds an idle tower until the current response ends, then asks once', async () => {
  // response.active -> tower(idle) -> no conversation.item.create yet
  // response.done -> item.create + response.create, exactly once
});
it('interrupts an active response for a safety tower', async () => {
  // response.active -> tower(interrupt) -> response.cancel, item.create, response.create
});
```

- [ ] **Step 2: Run to fail** — `bash scripts/mvn-lane.sh inner act-aria -Dtest=LiveFramesTest` and `npx vitest run session`

- [ ] **Step 3: Implement both sides**; keep one `response.create` per injected message (reuse `flushAnsweredCalls`'s outstanding-call rule).

- [ ] **Step 4: Run tests, then commit** — `feat(live): return a dispatched run's outcome as a spoken tower`

---

### Task 6: Reminder and hard timeout

**Files:**
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/live/LiveDispatchService.java` (schedule/cancel)
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/live/DeepSeekLocator`-style clock injection is not needed: use the existing `ScheduledExecutorService` pattern from `LiveWebSocketHandler` (`deadlines`/`backups`); inject one into the dispatch service.
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/live/LiveDispatchServiceTest.java`

**Interfaces:**
- On `register`, schedule:
  - reminder at `now + reminderMs`; when it fires: re-read `runService.getRun(id)`; if still active and `reminderSent == false` → `tower("[tower] <agent> 仲做緊", "idle")`, set the flag.
  - deadline at `min(run deadline, now + timeoutMs)`; when it fires and the run is still active → `runStopService.stop(id)` (pick the existing service used by the REST stop; verify its name), then `run_event{state:"failed",summary:"failed: timeout",origin:"dispatch"}` + failure tower `interrupt`.
- On terminal status (`LiveDispatchListener.onCompleted`) or socket close: cancel both futures.

- [ ] **Step 1: Write failing tests** with a fake `ScheduledExecutorService` (run the captured `Runnable`s directly) and a stub run service: reminder once; no reminder for a finished run; timeout stops the run and emits failed + tower; a second terminal event emits nothing.

- [ ] **Step 2: Run to fail**
- [ ] **Step 3: Implement**
- [ ] **Step 4: Run tests, then commit** — `feat(live): announce a long dispatch and bound it with a timeout`

---

### Task 7: Cleanup on close and finalize

**Files:**
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/live/LiveWebSocketHandler.java` (socket close path)
- Modify: `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/live/LiveEventRouter.java` (finalize path)
- Test: `agent-control-tower/act-aria/src/test/java/io/aria/conductor/aria/live/LiveWebSocketHandlerTest.java`

**Interfaces:** on either path call `dispatches.removeSocket(live)` and cancel that socket's timers; no frames are emitted to a closed socket.

- [ ] **Step 1: Failing test** — open a socket, register a child, close it, publish the child's completion; assert no phone frames and no timers left (registry empty).
- [ ] **Step 2: Run to fail** — `-Dtest=LiveWebSocketHandlerTest`
- [ ] **Step 3: Implement** (removeSocket in `afterConnectionClosed` before the bridge is reaped; cancel futures inside `removeSocket`).
- [ ] **Step 4: Run tests, then commit** — `fix(live): drop a socket's dispatch bookkeeping when it closes`

---

### Task 8: Integration, E2E, conformance notes, deploy

**Files:**
- Test: `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/LiveDispatchIntegrationTest.java` (fake bridge + real socket, `@SpringBootTest` H2)
- Test: `packages/qwen-live-bridge/scripts/phone-sim.mjs` (optional flags to print `origin` and tower turns)
- Docs: `docs/live-operations.md` (event sequence §4.1 + `origin`), this spec's §4.1 table is the source of truth.

- [ ] **Step 1: Integration test** — approve a `start_agent_run` through the REST decision, publish `RunStartedEvent`/`RunCompletedEvent` for the child, assert the phone frame order `started → finished` with `origin:"dispatch"` and the bridge `tower{mode:"idle"}`; publish a failure and assert `interrupt`.
- [ ] **Step 2: Deterministic E2E** — scripted long run: 20 s reminder then timeout failure with the run stopped (fake engine clock shortened through the properties).
- [ ] **Step 3: Gate** — `bash scripts/mvn-lane.sh gate act-aria`, `bash scripts/mvn-lane.sh gate act-app`, `cd packages/qwen-live-bridge && npx vitest run && pnpm build`.
- [ ] **Step 4: Deploy + report** — rebuild the boot jar, restart 8080, and send the phone the §4.1 sequence with field names plus one real dispatched run's frames (captured from the live socket).
- [ ] **Step 5: Commit** — `feat(live): prove the async dispatch seam end to end`

---

## Self-review

- Spec §4.1 is shipped today and asserted as-is in Task 6 of this plan's integration test; §4.2–§4.5 map to Tasks 3–7; §10 (SSE) is deliberately absent.
- No task renames an existing frame or event; `origin`/`mode` are additive.
- Timers re-read the run state; terminal paths cancel them; socket close cancels them (no leaked task, no frame to a dead socket).
- The tower preview reuses the tool-output bound rule; no new egress.
