# Remediation Plan — PR #109 Legacy Execution Retirement

Sequenced by blast radius and reversibility. Owners are assumptions based on module ownership,
not assignments. This plan does not block the merge; it tracks follow-ups surfaced by the review.

## Priority 0 — R-01: Make cancel truthful about compute (High)

**Problem:** `RunService.cancelRun` records `CANCELLED` and publishes an event, but no production
code requests a verified core stop. A cancelled run's core keeps executing until the deadline.

**Options:**

1. Coordinator-owned cancel: `RunService` (or a listener with access to the coordinator) requests
   `CoreExecutionService.cancel`, records the transition, and surfaces whether the stop was
   verified. Unverifiable runtimes still record the transition (current behavior).
2. Operator-facing distinction: keep the recorded transition and add an explicit
   `stopRequested` / `computeBoundedByDeadline` field so the UI does not imply compute stopped.
3. Do nothing beyond documentation: acceptable only if the deadline is treated as the stop SLA.

**Recommendation:** option 1 as the target, option 2 as the immediate wording guard. The reverted
prototype proved a verified stop cannot be obtained for parked/exited runtimes; the design must
therefore treat "recorded" and "stopped" as separate states rather than assuming `StopProof`.

**Acceptance criteria:**

- Cancelling a `RUNNING` run owned by this process either obtains a verified stop or reports that
  the stop was not verified.
- No cancelled run launches, resumes, or overwrites terminal state (already covered by QS-02).
- A live E2E (qoder or opencode) shows the core process exiting after cancel, not only the row
  changing state.

**Validation gate:** `AgentLoopEngineCoreDispatchTest` cancellation cases plus one live E2E.
**Owner assumption:** execution runtime maintainers.

## Priority 1 — R-02: Token cap decision (High)

**Problem:** Cost is bounded only by the wall-clock deadline; there is no per-run token cap.
This is documented and accepted, not introduced by this PR.

**Action:** product decision — introduce a per-run token budget or explicitly keep the deadline as
the only guardrail. If added, the cap must fail the run closed and be visible in the run record.

**Validation gate:** scenario QS-05 with a runaway prompt; assert the run terminates at the budget.
**Owner assumption:** execution runtime + product.

## Priority 2 — R-03: One result-root contract (Medium)

**Problem:** `act-knowledge` recomputes the result path from its own `@Value` default while
`act-execution` captures it. Config drift silently degrades the SPEC_REVIEW content to
`finalOutput`.

**Options:**

1. Single shared configuration property bean consumed by both modules.
2. An artifact lookup service owned by `act-execution` (or `act-common`) that `act-knowledge`
   calls instead of touching the filesystem.
3. Keep the path contract but assert equality at startup and fail fast on drift.

**Recommendation:** option 2 if a runtime service boundary is acceptable; otherwise option 3 as a
low-cost guard (startup check that both resolvers point to the same canonical path).

**Acceptance criteria:**

- Changing the configured root in one place changes both capture and read.
- A test with a non-default root proves the spec is still read from the captured file.
- Fallback to `finalOutput` logs a warning with the resolved path.

**Validation gate:** `SpecReviewCoordinatorTest` + a wiring test that asserts both beans resolve
the same root.
**Owner assumption:** knowledge/SDD maintainers with execution runtime.

## Priority 3 — R-04: Pack credential coverage (Medium)

**Problem:** The deleted tool-pack governance E2E spec covered pack registration and risk tiers,
not credential handoff. The pack API and credential resolution remain live for git handoff, and the
credential path never had E2E coverage. The gap is pre-existing; deleting the adjacent spec removes
the closest coverage and makes it more visible.

**Action:** either add one focused E2E (register pack → store credential → run git handoff) or make
the explicit product decision to drop pack management and move the git token to configuration.
Record the decision in `e2e/agent-core/coverage-map.json` either way.

**Validation gate:** the new E2E runs in the Playwright CI shards, or the coverage map records the
retirement decision.
**Owner assumption:** frontend/E2E maintainers with execution runtime.

## Priority 4 — R-05: Upgrade safety note (Medium)

**Problem:** V69 deletes assignment rows, nulls assignment columns, and deletes config keys. There is
no automated pre-upgrade backup check.

**Action:** add the pre-upgrade backup and restore procedure to the release notes and the upgrade
documentation. Restoring a pre-upgrade backup is sufficient because Flyway re-applies V69 on the
next start; no repair step is normally needed. Optionally add a startup warning when V69 is pending
on a non-empty legacy surface.

**Validation gate:** upgrade rehearsal on a staging copy of a pre-V69 database.
**Owner assumption:** release/operations maintainers.

## Priority 5 — Cleanup batch (Low)

| Item | Action |
|------|--------|
| R-06 residual tool/pack vocabulary | Document the retained surface in `docs/architecture.md`; schedule a removal migration only if pack credentials leave. |
| R-07 approval decide-vs-sweep race | Extend the decision lock idiom to SPEC_REVIEW decisions and to the sweep, or document why single-operator use makes it acceptable. |
| R-08 agentType asymmetry | Keep the MCP default documented; add a contract test asserting the REST field stays required. |

## Fitness Functions

| ID | Attribute | Tool | Rule | CI point | Status |
|----|-----------|------|------|----------|--------|
| FF-01 | No legacy agent type | `git grep -- 'agent-control-tower/*/src/main/java'` | `AgentType.NATIVE` absent from production Java; migrations and migration tests may reference the literal intentionally | CI lint step | Passing |
| FF-02 | Migration postconditions | JUnit (`MigrationIntegrationTest`) | V69 expires only legacy-gate asks; live asks stay pending; survivor config remains | `act-app` tests | Passing |
| FF-03 | Cancel never resurrects | JUnit (`AgentLoopEngineCoreDispatchTest`) | A committed `CANCELLED` run never launches or returns to `RUNNING` | `act-execution` tests | Passing |
| FF-04 | Spec fidelity | JUnit (`SpecReviewCoordinatorTest`) | Captured `spec.md` is preferred, bounded at 50K chars, cleaned conservatively; missing-file fallback to `finalOutput` is currently silent (R-03), read exceptions are logged | `act-knowledge` tests | Passing for fidelity; fallback observability tracked by R-03 |
| FF-05 | Bulk status writes keep `updatedAt` | Reflection test over `RunRepository` `@Query` strings | Every status-updating bulk query also sets `updatedAt` | `act-agent` tests | Proposed |
| FF-06 | Single result root | Spring wiring test | Capture and read resolve the same canonical path for a non-default configuration | `act-app` tests | Proposed |

## Sequencing

R-01 and R-02 are independent. R-03 should follow the merge because it touches the review-fix
surface. R-04 depends on the product decision about pack management. R-05 is documentation and can
ship with the release. The cleanup batch is opportunistic.
