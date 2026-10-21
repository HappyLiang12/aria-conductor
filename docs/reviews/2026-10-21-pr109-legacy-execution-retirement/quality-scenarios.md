# Quality Scenarios — PR #109 Legacy Execution Retirement

Each scenario states a stimulus, the artifact, a measurable response, and the evidence that
currently supports or refutes it. Status is judged from code, tests, and CI — not estimation.

## QS-01: Operator cancel under a live core (operability)

| Field | Value |
|-------|-------|
| Source | Operator / Kanban / Housekeeping |
| Stimulus | Cancel a `RUNNING` run |
| Artifact | `RunService.cancelRun`, `RunCompletedEvent`, run-owned core |
| Response | Run reads `CANCELLED` immediately; UI and queue release; core eventually stops |
| Measure | Status visible ≤ 1s; core writers stop before the wall-clock deadline; no resurrection |
| Current status | PARTIAL — status is immediate and resurrection is guarded; core stop is not requested |
| Evidence | `RunService.java:208-219`; `AgentLoopEngine.java:515-538`; cancellation tests `AgentLoopEngineCoreDispatchTest.java:258,274`; `CoreExecutionService.cancel` has no production caller |
| Action | Track R-01; document operator semantics; design coordinator-owned verified cancel |

## QS-02: Terminal-state monotonicity (correctness)

| Field | Value |
|-------|-------|
| Source | Concurrent cancel + dispatch/resume |
| Stimulus | Cancel lands after admission but before core launch, or during provisioning |
| Artifact | `AgentLoopEngine`, `RunRepository.updateStatusUnlessTerminal` |
| Response | The cancelled run never returns to `RUNNING`; no core is launched for it |
| Measure | 0 launches after a committed `CANCELLED`; conditional update count = 0 |
| Current status | PASS |
| Evidence | `AgentLoopEngineCoreDispatchTest.java:258,274`; conditional JPQL updates in `RunRepository`; `updatedAt` passed explicitly so reaper timestamps stay fresh |
| Action | None; keep the conditional-write idiom for future run-state changes |

## QS-03: SDD spec fidelity (data correctness)

| Field | Value |
|-------|-------|
| Source | BA step completion within an SDD chain |
| Stimulus | BA writes `spec.md`; runtime finalizes with a verified stop and captures artifacts |
| Artifact | `SpecReviewCoordinator`, `RunWorkspaceService.capture` |
| Response | SPEC_REVIEW shows the full captured spec, up to 50K chars |
| Measure | Content equals the captured file after conservative cleanup (preambles/chatter stripped); fallback only when the file is absent/unreadable |
| Current status | PASS for the golden path; DEGRADED under config drift |
| Evidence | `SpecReviewCoordinator.java:280-315`; `RunWorkspaceService.java:243-250`; `SpecReviewCoordinatorTest` (25/25); `application.yml:113` |
| Action | Track R-03; unify the result-root contract or expose a runtime-owned lookup |

## QS-04: Upgrade with live operator asks (data safety)

| Field | Value |
|-------|-------|
| Source | Deployment of V69 |
| Stimulus | Database contains legacy tool asks plus live review/clarification/ACP asks |
| Artifact | `V69__retire_legacy_execution_surface.sql` |
| Response | Only `TOOL_CALL` + `APPROVAL` + `LEGACY_GATE` pending asks expire; live asks remain `PENDING`; unrelated config survives |
| Measure | Exact postconditions asserted row by row |
| Current status | PASS |
| Evidence | `MigrationIntegrationTest.java:81-135`; V69 lines 28-37; CI `act-app` tests green |
| Action | Keep the pre-upgrade backup/restore note in release instructions (R-05) |

## QS-05: Cost containment under a runaway run (cost)

| Field | Value |
|-------|-------|
| Source | A misbehaving core or prompt |
| Stimulus | The run keeps issuing LLM calls |
| Artifact | Run-owned core runtime |
| Response | The run is terminated at its wall-clock deadline |
| Measure | No run exceeds its deadline; token spend is not independently capped |
| Current status | PARTIAL — deadline enforced, token budget absent |
| Evidence | `docs/architecture.md:77-78`; `AGENTS.md:61`; deadline in `OpenCodeCoreSession.java:102-109` |
| Action | Track R-02; product decision required before adding a token cap |

## QS-06: Retained pack credentials (validation readiness)

| Field | Value |
|-------|-------|
| Source | Git handoff during a run |
| Stimulus | A run needs the git pack token via `GIT_PACK_ID` |
| Artifact | `ToolPackController`, `PackCredentialService`, `GitBranchConfig` |
| Response | Token resolves from stored pack credentials |
| Measure | Handoff succeeds with a registered pack; pack approval enables no tools |
| Current status | PARTIAL — runtime path exists; the credential handoff never had E2E, and the adjacent governance spec (registration/risk tiers) was deleted with the removed features |
| Evidence | `ToolPackController.java:14-19`; `e2e/agent-core/coverage-map.json:1458-1470`; deleted `git-pack-governance.spec.ts` covered registration/risk tiers only |
| Action | Track R-04; add a focused E2E or explicitly drop the surface |

## Summary

| Scenario | Status | Risk |
|----------|--------|------|
| QS-01 cancel | PARTIAL | R-01 |
| QS-02 monotonicity | PASS | — |
| QS-03 spec fidelity | PASS / DEGRADED under drift | R-03 |
| QS-04 upgrade asks | PASS | R-05 (recovery) |
| QS-05 cost | PARTIAL | R-02 |
| QS-06 pack credentials | PARTIAL | R-04 |
