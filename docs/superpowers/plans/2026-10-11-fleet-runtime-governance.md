# Fleet Runtime Governance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One fleet-wide runtime (core+mode) configured in the Configure modal, hard-blocking mixed usage, with drain/answer-time migration so live runs transition without errors.

**Architecture:** Enforcement lives in AgentService (act-agent) — a uniformity guard on every agent create/update plus a transactional batch `PUT /api/v1/agents/runtime`. Cross-core continuation lives in the CoreExecutionService turn loop (act-execution): after an answer wakes a parked run, a loop-side comparison of the frozen binding vs the agent's current config triggers teardown + re-freeze + re-seed on the new core. UI adds a Configure Runtime tab, an Overview RUNTIME stat card, and core/mode badges on Crew and Runs.

**Tech Stack:** Java 21 / Spring Boot 3.3 (act-agent, act-execution), React 19 + TanStack Query (act-dashboard), H2/MariaDB via Flyway (no schema changes).

**Spec:** docs/superpowers/specs/2026-10-11-fleet-runtime-governance-design.md

## Global Constraints

- Shell hygiene: every Bash command pure ASCII (this environment fails wholesale on non-ASCII); file content via Write/Edit tools.
- No model parameter exists on the Agent tool — subagents inherit the session model (recorded per execution).
- No Flyway migrations: no schema change in this plan (uniformity is service logic; RunResponse reads the existing RunExecutionBinding table).
- Enforcement matrix (verbatim from spec §3): uniform A + write A -> 200; uniform A + write B -> 409 "Runtime mixing is not allowed: the fleet runs A; use PUT /api/v1/agents/runtime to change it fleet-wide"; mixed fleet + any per-agent write -> 409 "The fleet runtime is mixed; converge it first via PUT /api/v1/agents/runtime". Retirement/deletion exempt.
- Same-session continuation is guaranteed ONLY while the core is unchanged; cross-core answers continue via trajectory re-seed (spec §4.5).
- Authority posture for the new endpoint matches existing agent config routes (unauthenticated config CRUD; loopback auto-authority resolves the operator).
- Content-locate everything: line numbers in this plan are snapshots; grep the shown symbols first.
- Filtered `-Dtest` runs on act-app fail on the JaCoCo ratchet (pre-existing) — full module suites are the gate.

---

### Task 1: Uniformity guard in AgentService (create/update)

**Files:**
- Modify: `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/service/AgentService.java` (createAgent + updateAgent paths; locate by grep `public AgentResponse createAgent\|updateAgent` or the actual method names in the file)
- Test: the existing AgentService test class (`grep -rl "AgentService" agent-control-tower/act-agent/src/test --include=*Test.java` — extend, mirroring its rigging)

**Interfaces:**
- Consumes: `AgentRepository` (existing), `Agent` entity fields `adkProvider`, `executionMode`, `healthStatus`/`retiredAt` (retired filter — read how the repo already filters retired agents; mirror it).
- Produces: a package-private helper `assertNoRuntimeMix(String adkProvider, ExecutionMode executionMode)` on AgentService, throwing `IllegalStateException` with the exact messages in Global Constraints; called at the head of createAgent and updateAgent. Later tasks rely on nothing else from this task.

- [ ] **Step 1: Read the current shapes.** Open AgentService create/update methods, the Agent entity's retired marker, and the exception->HTTP mapping for act-agent controllers (`grep -rn "IllegalStateException" agent-control-tower/act-common/src/main/java/io/aria/conductor/common/exception/GlobalExceptionHandler.java` and the controller Advice in act-agent if separate). Confirm IllegalStateException maps to 409; if it does not, add the mapping in the same handler this task (mirror how other statuses map there).

- [ ] **Step 2: Write the failing tests** (mirror the file's existing Mockito/service rigging):

```java
    @Test
    void creatingAnAgentWithTheFleetRuntimeIsAllowed() {
        seedAgent("a1", "opencode", ExecutionMode.SANDBOX);
        // fleet uniform: opencode/SANDBOX
        service.create(aRequest("opencode", ExecutionMode.SANDBOX)); // no exception
    }

    @Test
    void creatingAnAgentOffTheFleetRuntimeIsRejected() {
        seedAgent("a1", "opencode", ExecutionMode.SANDBOX);
        assertThatThrownBy(() -> service.create(aRequest("qoder", ExecutionMode.HOST)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Runtime mixing is not allowed")
                .hasMessageContaining("PUT /api/v1/agents/runtime");
    }

    @Test
    void updatingAnAgentIntoAMixedFleetIsRejected() {
        seedAgent("a1", "opencode", ExecutionMode.SANDBOX);
        seedAgent("a2", "qoder", ExecutionMode.HOST); // fleet already mixed
        assertThatThrownBy(() -> service.update("a1", anUpdateRequest("opencode", ExecutionMode.HOST)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("The fleet runtime is mixed");
    }

    @Test
    void retiringAnAgentNeverTriggersTheGuard() {
        seedAgent("a1", "opencode", ExecutionMode.SANDBOX);
        seedAgent("a2", "qoder", ExecutionMode.HOST);
        service.retire("a1"); // deletion/retirement is exempt per spec
    }
```

(Adapt method names to the file's real API — `service.create/update/retire` are placeholders for the actual public signatures found in Step 1; `aRequest/anUpdateRequest` mirror the existing request-builder helpers of that test class.)

- [ ] **Step 3: Run to red.** `cd agent-control-tower && mvn -q test -pl act-agent -Dtest='*AgentService*' -Dsurefire.failIfNoSpecifiedTests=false` — Expected: new tests FAIL (no guard; no exception thrown).

- [ ] **Step 4: Implement the guard.** In AgentService add:

```java
    /**
     * Fleet runtime uniformity (2026-10-11 fleet runtime governance): the fleet
     * runs ONE (core, mode) pair. A write that would introduce a second pair is
     * refused; on an already-mixed fleet every per-agent write is refused — the
     * batch runtime endpoint is the only converging action. Retirement is exempt.
     */
    private void assertNoRuntimeMix(String adkProvider, ExecutionMode executionMode) {
        Set<String> runtimes = agentRepository.findAll().stream()
                .filter(a -> !a.isRetired())
                .map(a -> a.getAdkProvider() + "/" + a.getExecutionMode())
                .collect(java.util.stream.Collectors.toSet());
        if (runtimes.size() > 1) {
            throw new IllegalStateException(
                    "The fleet runtime is mixed; converge it first via PUT /api/v1/agents/runtime");
        }
        String incoming = adkProvider + "/" + executionMode;
        if (!runtimes.isEmpty() && !runtimes.contains(incoming)) {
            throw new IllegalStateException("Runtime mixing is not allowed: the fleet runs "
                    + runtimes.iterator().next() + "; use PUT /api/v1/agents/runtime to change it fleet-wide");
        }
    }
```

Call it at the head of createAgent and updateAgent (before any persistence). `isRetired()` — use the entity's real retired predicate (retiredAt != null or a health status) as found in Step 1. Null adkProvider/executionMode on update (partial update keeping current values) must be resolved to the AGENT'S CURRENT values before the guard runs, so a name-only update on a uniform fleet passes.

- [ ] **Step 5: Run to green** (same command as Step 3), then the full act-agent suite: `mvn -q test -pl act-agent`. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A agent-control-tower && git commit -m "feat(agent): fleet runtime uniformity guard on agent create/update"
```

---

### Task 2: Batch runtime endpoint + spec amendment

**Files:**
- Modify: `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/service/AgentService.java` (add applyRuntimeToAll)
- Modify: the act-agent controller exposing agent routes (locate: `grep -rn "api/v1/agents" agent-control-tower/act-agent/src/main/java --include=*Controller.java | head -3`)
- Create: `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/dto/ApplyRuntimeRequest.java` (record `ApplyRuntimeRequest(String adkProvider, ExecutionMode executionMode)`)
- Modify: `docs/superpowers/specs/2026-09-22-agent-core-execution-modes-design.md` (append §12 amendment)
- Test: the act-agent controller/service test classes from Task 1

**Interfaces:**
- Consumes: Task 1's guard is NOT used here (the batch endpoint is exempt by design — it IS the converging action).
- Produces: `PUT /api/v1/agents/runtime` with body `{"adkProvider": "...", "executionMode": "HOST|SANDBOX"}` -> 200 `{"runtime": {"adkProvider": "...", "executionMode": "..."}, "updated": N}`; 400 when the pair is not declared in the provider inventory. Later tasks (5) consume this route; Task 3 consumes nothing from it.

- [ ] **Step 1: Read the provider inventory.** Locate the ADK provider registry the backend serves at `/api/v1/adk/providers` (`grep -rn "adk/providers\|AdkProvider" agent-control-tower/act-agent/src/main/java agent-control-tower/act-execution/src/main/java --include=*.java | grep -i "controller\|service" | head -5`). Identify how a core's declared execution modes are exposed (the /providers page renders an EXECUTION MODES column from it). Note the service/method to validate `adkProvider` + mode support.

- [ ] **Step 2: Write the failing tests** (controller/service rig from Task 1):

```java
    @Test
    void applyRuntimeUpdatesEveryNonRetiredAgentInOneCall() {
        seedAgent("a1", "opencode", ExecutionMode.SANDBOX);
        seedAgent("a2", "qoder", ExecutionMode.HOST);
        seedRetiredAgent("a3", "opencode", ExecutionMode.SANDBOX);

        ApplyRuntimeResult result = service.applyRuntimeToAll("qoder", ExecutionMode.HOST);

        assertThat(result.updated()).isEqualTo(2); // retired agent untouched
        // both live agents now qoder/HOST
    }

    @Test
    void applyRuntimeRejectsAnUndeclaredCore() {
        assertThatThrownBy(() -> service.applyRuntimeToAll("nonexistent", ExecutionMode.HOST))
                .isInstanceOf(IllegalArgumentException.class); // surfaces as 400
    }
```

Plus one controller-level MockMvc test: `PUT /api/v1/agents/runtime` -> 200 with `{"runtime":..., "updated":...}` and agents mutated (mirror the controller test rig used for the existing agent routes).

- [ ] **Step 3: Run to red** (same pattern as Task 1 Step 3).

- [ ] **Step 4: Implement.** Service method (validate inventory first — 400 via IllegalArgumentException; then one transaction: load all non-retired agents, set adkProvider + executionMode, saveAll; return a result record `ApplyRuntimeResult(String adkProvider, ExecutionMode executionMode, int updated)`). Controller: `@PutMapping("/runtime")` taking `@RequestBody ApplyRuntimeRequest`, returning the result map. NOTE: this endpoint bypasses Task 1's per-agent guard deliberately (spec §3: the batch endpoint is the only converging action) — add a comment saying exactly that so a future reader does not "unify" them.

- [ ] **Step 5: Append the spec amendment** to the 2026-09-22 design doc (new section `## 12. Amendment (2026-10-11): fleet runtime governance`), one paragraph: fleet uniformity enforced at create/update (matrix), batch converging endpoint exempt, per-agent editing removed from the UI, cross-core continuation via trajectory re-seed (pointer to the fleet-runtime spec).

- [ ] **Step 6: Run to green** + full act-agent suite. Commit:

```bash
git add -A agent-control-tower docs && git commit -m "feat(agent): fleet runtime batch endpoint with inventory validation; spec amendment"
```

---

### Task 3: RunResponse exposes the frozen core/mode

**Files:**
- Modify: `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/dto/RunResponse.java` (add `String coreId`, `String executionMode`)
- Modify: the RunService mapping that builds RunResponse (`grep -n "RunResponse.builder\|new RunResponse" agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/service/RunService.java`)
- Test: the act-agent RunService test class

**Interfaces:**
- Consumes: `RunExecutionBinding` + its repository (locate: `grep -rn "interface RunExecutionBindingRepository" agent-control-tower --include=*.java | grep -v target`), fields `getCoreId()`, `getExecutionMode()`.
- Produces: `RunResponse.coreId` / `RunResponse.executionMode` (String / String, null when no binding exists) — Task 6's RunsPage badge consumes these via the TS types task.

- [ ] **Step 1: Write the failing test** (mirror the existing RunService test rig):

```java
    @Test
    void runResponseCarriesTheFrozenRuntime() {
        // seed a run whose RunExecutionBinding says qoder/HOST
        RunResponse response = <map the run the way the service does>;
        assertThat(response.getCoreId()).isEqualTo("qoder");
        assertThat(response.getExecutionMode()).isEqualTo("HOST");
    }

    @Test
    void runResponseWithoutABindingCarriesNullRuntime() { ... both null ... }
```

- [ ] **Step 2: Red** (`mvn -q test -pl act-agent -Dtest='*RunService*' -Dsurefire.failIfNoSpecifiedTests=false`).

- [ ] **Step 3: Implement.** In the run->RunResponse mapping, look up the binding by runId (repository `findByRunId` — add the derived query if absent, mirroring the repo's style). Set coreId/executionMode from the binding; null-safe when absent. If the LIST endpoint maps many runs, batch the binding lookup (one `findAllById`-style query or `findByRunIdIn`) to avoid N+1 — check how many queries the list test asserts; keep it bounded.

- [ ] **Step 4: Green** + full act-agent suite. Commit:

```bash
git add -A agent-control-tower && git commit -m "feat(runs): expose the frozen core and mode on run responses"
```

---

### Task 4: Cross-core answer migration in the turn loop

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreExecutionService.java` (the turn loop after `RunInputReceivedEvent` — locate by content: the answer branch publishing RunInputReceivedEvent and building `new CoreTask("", List.of(), input.answer())`)
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreRunLauncher.java` (expose the binding re-freeze — the binding save lives here today; locate `bindings.save(binding)` and add a package-private `refreezeBinding(runId, coreId, mode, credentialRef, reason)` mirroring the freeze path minus the idempotency guard)
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/runtime/CoreExecutionServiceTest.java` (the RecordingSession rig from Plan B task 5)

**Interfaces:**
- Consumes: Task 4's migration branch consumes nothing from Tasks 1-3. It uses: `RunInputCoordinator.OperatorInput` (unchanged), the agent repository (`AgentRepository.findByAgentId`-equivalent — locate how CoreExecutionService or its collaborators already reach agent config; if none exists, inject the act-common agent repository the same way CoreRunLauncher does), `RunExecutionBinding` repository, `ClarificationQuestions` (unchanged), the trajectory repository for history re-seed (locate the trajectory repo the engine uses for `recordTaskTrajectory`).
- Produces: the migration branch inside `execute`'s answer path — same public contract of `execute` (LAST turn's result). Later tasks consume nothing from it directly; Task 8 verifies it live.

- [ ] **Step 1: Write the failing tests** (extend CoreExecutionServiceTest; the rig already has `scriptedResults`, `secondPrompt`, recording publisher, `ManualDeadlineScheduler`):

```java
    @Test
    void answeringAfterTheAgentCoreChangedMigratesTheRunToTheNewCore() {
        // Rig: agent's CURRENT config is opencode/HOST (mutate whatever the rig
        // uses for agent lookup), the spec() under test is qoder/HOST.
        session.scriptedResults = new ArrayDeque<>(List.of(
                new CoreResult("session-1", "Working\n[NEED-INPUT] Which database?", usage(), false),
                new CoreResult("session-1", "All done on the new core", usage(), false)));
        Thread answerer = new Thread(() -> {
            Awaitility.await().atMost(5, Duration.SECONDS)
                    .until(() -> published(RunWaitingForInputEvent.class));
            coordinator.submitAnswer(RUN, "postgres");
        });
        answerer.start();
        CoreResult result = service.execute(spec(), adapter, task());
        answerer.join();

        // The loop tore down the old runtime and relaunched on the NEW core:
        assertThat(recordedSteps).containsSubsequence(
                "open-session", "prompt", "stop-writers", "capture", "destroy", "release",
                "launch", "open-session", "prompt", "stop-writers");
        // The re-seed task carries the trajectory history and the answer as the
        // user prompt — NOT the bare-answer task of the same-core path.
        assertThat(session.secondPrompt.userPrompt()).contains("postgres");
        assertThat(session.secondPrompt.systemPrompt()).isBlank();
        assertThat(session.secondPrompt.history()).isNotEmpty();
        assertThat(result.finalOutput()).isEqualTo("All done on the new core");
        // A migration marker is recorded on the binding re-freeze (verify via the
        // bindings collaborator the rig exposes).
    }

    @Test
    void answeringWithAnUnchangedCoreKeepsTheSameSessionBehavior() {
        // agent config still qoder/HOST (unchanged): existing same-core pin from
        // Plan B task 5 must hold — bare answer, no history, no teardown between
        // turns. This is the regression pin; the existing test already asserts
        // it, so this task must NOT weaken it (it stays green unchanged).
    }
```

- [ ] **Step 2: Red** (`mvn -q test -pl act-execution -Dtest=CoreExecutionServiceTest` — new test FAILS: today the loop re-prompts on the same session with the bare answer).

- [ ] **Step 3: Implement the migration branch.** In the answer path after `eventPublisher.publishEvent(new RunInputReceivedEvent(...))`:

```java
                ExecutionSpec current = spec;
                Agent agent = agents.findById(spec.agentId()).orElse(null);
                boolean coreChanged = agent != null
                        && (!spec.coreId().equals(agent.getAdkProvider())
                            || spec.mode() != agent.getExecutionMode());
                if (coreChanged) {
                    // Operator-driven runtime migration (fleet runtime spec 4.4):
                    // finalize the old runtime, re-freeze the binding, relaunch on
                    // the new core and re-seed the session from the trajectory.
                    finalizeRuntime(runtime); // verified stop + capture + destroy + release (existing helper)
                    ExecutionSpec migrated = new ExecutionSpec(spec.runId(), spec.agentId(),
                            agent.getAdkProvider(), agent.getExecutionMode(), settings carried over,
                            credentialReferenceOf(agent.getAdkProvider()), configurationRevision carried over,
                            fresh per-turn deadline from clock);
                    refreezeBinding(migrated, "operator runtime migration");
                    runtime = openRuntime(migrated, adapters.require(migrated.coreId()));
                    List<CoreTask.Turn> history = trajectory turns of this run mapped to history entries;
                    nextTask = new CoreTask("", history, input.answer());
                    // NOTE: `spec` stays the original for logging; the loop now works
                    // against `current = migrated` for credentialRef resolution and
                    // the returned CoreResult path. Keep the loop's variable naming
                    // honest — rename the loop-local spec holder, do not shadow.
                } else {
                    nextTask = new CoreTask("", List.of(), input.answer());
                }
                firstTurn = false;
```

Implementation notes (binding for the implementer, not steps):
- `finalizeRuntime(runtime)` is the same helper the failure path uses — reuse; do NOT duplicate teardown logic.
- The credential resolution inside openRuntime uses `spec.credentialRef()` — the migrated spec carries the new core's credential reference (qoder -> "qoder:operator", opencode -> null; reuse CoreRunLauncher's `credentialReference(coreId)` static — make it accessible or move it to a shared home if visibility blocks).
- The re-seed history: read the run's SessionTrajectory rows (ordered by turnNumber) and map each row to the CoreTask history representation (READ CoreTask's history component type first and map accordingly; user/assistant roles map to the history entry type it expects).
- `adapters.require(newCoreId)` — the adapters registry field already in the service (Plan B task 5 used it in tests as `adapter`; production has the registry).
- The rig's step recorder: extend whatever records "launch"/"open-session" so a second launch cycle is observable (the rig already records these steps — verify the recorder keys on the adapter calls, in which case the migrated second cycle records automatically).
- The binding re-freeze: add to CoreRunLauncher a package-private/static-accessible method the service can call, updating the binding row's coreId/executionMode/credentialRef/configurationRevision + deadline, and log `Migrated run {} to core {}/{} (operator runtime migration)`. Keep the original freeze method untouched.

- [ ] **Step 4: Green** — the focused class, then the full act-execution suite (`mvn -q test -pl act-execution`). ALL Plan B same-core pins stay green unchanged.

- [ ] **Step 5: Commit**

```bash
git add -A agent-control-tower && git commit -m "feat(runtime): answer-time cross-core migration re-seeds the session from the trajectory"
```

---

### Task 5: Configure Runtime tab (frontend)

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/components/ConfigureModal.tsx` (new tab `runtime` + `RuntimePane`; follow the GatesPane/SkillsPane inline pattern; footer info arm)
- Modify: `agent-control-tower/act-dashboard/src/api/agents.ts` (add `applyFleetRuntime(adkProvider: string, executionMode: ExecutionMode): Promise<{ runtime: { adkProvider: string; executionMode: string }; updated: number }>` calling `client.put('/api/v1/agents/runtime', { adkProvider, executionMode })`; verify the shared client's POST/PUT JSON behavior for object bodies — object payloads are JSON-stringified by axios as normal, no text/plain concern)
- Create: `agent-control-tower/act-dashboard/src/utils/runtimeComposition.ts` (`composeRuntimes(agents: Agent[]): { runtime: string; count: number }[]` + `isUniform(agents: Agent[]): boolean` — Task 7's Overview card consumes the same helpers; one implementation, two consumers)
- Test: `agent-control-tower/act-dashboard/src/components/__tests__/ConfigureModal.test.tsx` (create; render with QueryClientProvider wrapper — mirror the ReviewPanels test's providers)

**Interfaces:**
- Consumes: `listAgents` (existing), `listAdkProviders` (existing, `api/adk.ts`), Task 2's `PUT /api/v1/agents/runtime`.
- Produces: the `runtime` tab key inside ConfigureModal's `activeTab` state; Task 7 (Overview card) opens the modal directly on this tab — so extract the modal's `activeTab` initial value into an optional prop `initialTab?: string` (default 'gates') in this task.

- [ ] **Step 1: Write the failing tests** (sketch; adapt selectors to the built JSX):

```tsx
  it('shows the fleet composition and applies the runtime to all agents', async () => {
    // agents mocked: 3x qoder/HOST + 3x opencode/SANDBOX
    render(<ConfigureModal initialTab="runtime" />);
    expect(screen.getByText(/qoder\/HOST x3/i)).toBeTruthy();
    expect(screen.getByText(/opencode\/SANDBOX x3/i)).toBeTruthy();
    const apply = screen.getByRole('button', { name: /apply to all agents/i });
    await userEvent.click(apply);
    // ConfirmDialog shows the composition change; confirm:
    await userEvent.click(screen.getByRole('button', { name: /^Confirm$/ }));
    expect(mockedApplyFleetRuntime).toHaveBeenCalledWith('qoder', 'HOST'); // from the dropdowns' values
  });

  it('renders the composition in green wording when the fleet is uniform', () => {
    // agents mocked: all qoder/HOST
    render(<ConfigureModal initialTab="runtime" />);
    expect(screen.getByText(/qoder\/HOST - 6 agents/i)).toBeTruthy();
  });
```

- [ ] **Step 2: Red** (`cd agent-control-tower/act-dashboard && pnpm test -- --run` — new tests fail: no runtime tab).

- [ ] **Step 3: Implement.** RuntimePane: composition computed from the agents query (group by `${adkProvider}/${executionMode}`); two dropdowns whose options come from `listAdkProviders` (core list; mode list from the selected core's declared modes — mirror the Add Agent dialog's governed logic in CrewPage; a removed core renders the explicit unsupported state); Apply button disabled until a pair is selected; ConfirmDialog (existing component) with the before/after composition; onSuccess invalidates `agents`, `runs`, `kanban-items`, `dashboard` queries and shows the updated composition. Footer info arm: `activeTab === 'runtime'` -> 'One runtime for the whole fleet — applying updates every agent'. Unicode strings via Write/Edit only.

- [ ] **Step 4: Green + build** (`pnpm test -- --run && pnpm build`). Commit:

```bash
git add -A agent-control-tower && git commit -m "feat(dashboard): configure runtime tab applies one core/mode to the whole fleet"
```

---

### Task 6: Runtime badges (Crew, drawer, Runs)

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/pages/CrewPage.tsx` (agent card badge)
- Modify: `agent-control-tower/act-dashboard/src/components/AgentDrawer.tsx` (header badge next to `NATIVE - ...`)
- Modify: `agent-control-tower/act-dashboard/src/pages/RunsPage.tsx` (row badge; TS types first)
- Modify: `agent-control-tower/act-dashboard/src/types/index.ts` (Run type gains `coreId?: string | null; executionMode?: string | null` — match the backend nullability from Task 3)
- Test: extend the existing CrewPage/RunsPage/AgentDrawer test files if present (grep first); otherwise one small new test per surface following the nearest existing pattern; if a surface has NO test file, note it and rely on the wave's browser check (do not invent heavy rigs).

**Interfaces:**
- Consumes: `Agent.adkProvider`/`Agent.executionMode` (already in the TS Agent type — verify), Task 3's `RunResponse.coreId/executionMode` (TS type updated in this task).
- Produces: nothing consumed downstream; visual only.

- [ ] **Step 1: Badge component.** Add a tiny shared badge (e.g. in `components/RuntimeBadge.tsx` — one file, one purpose: `RuntimeBadge({ core, mode })` rendering `core · mode` with the card's existing muted/badge styling classes; null-safe, renders `-` when both absent).
- [ ] **Step 2: Wire into the three surfaces** (Crew card meta row, AgentDrawer header line, RunsPage row meta). RunsPage uses the run's `coreId/executionMode`; Crew/Drawer use the agent's.
- [ ] **Step 3: Tests** — for each surface with an existing test file, assert the badge renders the agent's/run's `core · mode` (e.g. `qoder · HOST`); null renders `-`.
- [ ] **Step 4: Green + build.** Commit:

```bash
git add -A agent-control-tower && git commit -m "feat(dashboard): runtime badges on crew, drawer and runs"
```

---

### Task 7: Executive Summary RUNTIME card

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/pages/OverviewPage.tsx` (stat card in the Executive Summary · LIVE block; click opens ConfigureModal on the runtime tab — OverviewPage must render the modal or lift a state hook it already has; check how OverviewPage currently hosts ConfigureModal/KanbanBoard — the modal is opened from Layout's "Open configuration" button, so the Overview card can use the same mechanism: locate how that button triggers the modal — if it lives in Layout state, expose a context/event or render a second modal instance locally on OverviewPage; choose the smallest wiring that avoids prop-drilling through Layout)
- Test: `agent-control-tower/act-dashboard/src/pages/__tests__/OverviewPage.test.tsx` (extend)

**Interfaces:**
- Consumes: the agents query (composition logic — reuse the small composition helper from Task 5; extract it to `utils/runtimeComposition.ts` in Task 5 so both consume one implementation), ConfigureModal's `initialTab` prop from Task 5.
- Produces: nothing downstream.

- [ ] **Step 1: Failing test** — renders the RUNTIME stat (`qoder/HOST - 6 agents` when uniform; the two-line breakdown + warn styling when mixed; `draining xN` when any non-terminal run's frozen runtime != its agent's current config — the draining computation needs the runs query OverviewPage may not have: if absent, compute draining from the runs list query added to the card, or render the card without draining when runs are not loaded; state the chosen minimal path in the task report). Click opens the modal on `runtime`.
- [ ] **Step 2: Red** (`pnpm test -- --run`).
- [ ] **Step 3: Implement** the card (mirror the existing stat-card markup/classes of the Executive Summary block; the four existing cards are the pattern).
- [ ] **Step 4: Green + build.** Commit:

```bash
git add -A agent-control-tower && git commit -m "feat(dashboard): executive summary runtime card with mix and drain states"
```

---

### Task 8: Wave verification + live convergence

**Files:** none created; verification only. (Controller-run or a single subagent — see execution choice.)

- [ ] **Step 1: Backend suites.** `cd agent-control-tower && mvn -q test -pl act-agent,act-execution,act-common && mvn -q verify -pl act-app` (failsafe tier included — CI lesson from PR #106).
- [ ] **Step 2: Frontend.** `cd agent-control-tower/act-dashboard && pnpm test -- --run && pnpm build`.
- [ ] **Step 3: Leftover sweep.** `grep -rn "agents/runtime" agent-control-tower --include=*.java --include=*.ts --include=*.tsx | grep -v target | grep -v test` — every hit is the batch endpoint, its client, or its test (no accidental per-agent call sites).
- [ ] **Step 4: Live convergence on the local stack** (browser): Configure -> Runtime tab shows the mixed composition amber (3x qoder/HOST + 3x opencode/SANDBOX from the drill era); Apply qoder/HOST to all; badges on Crew/Runs flip; Overview RUNTIME card goes green; then dispatch one small task end-to-end on the converged fleet (SDD BA agent is now on qoder core for the first time — verify a completed run). If the running backend predates these commits, restart it first (with `ARIA_CORES_QODER_EXECUTABLE` set, per the operator environment note).
- [ ] **Step 5: Record results** in the session ledger; commit only if fixes were produced.

---

## Execution notes (binding on the executor)

- Tasks 1-3 are independent of each other EXCEPT Task 2's service sits in the same file as Task 1's guard — execute 1 then 2 sequentially; 3 may run in parallel with 2 if dispatching readers/writers carefully (implementation writes touch the same module — serial is simpler and matches the established cadence).
- Task 4 is independent of 1-3 (different module).
- Tasks 5-7 depend on 2 (endpoint) and 3 (RunResponse fields) for their consumed data; Task 6's RunsPage badge needs Task 3.
- The JaCoCo ratchet quirk (act-app filtered runs) and the content-locate rule apply throughout (Global Constraints).
