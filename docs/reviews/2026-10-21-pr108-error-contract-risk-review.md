# Risk and Quality Review: PR #108 actionable error contract and configurable deadline

- Review date: 2026-10-21
- Skill: `architecture-visualization:risk-quality-reviewer`, supported by `architecture-visualization:graphviz` for the risk map
- Scope: branch `fix/actionable-error-ux`, base `5d16e349` (origin/main), head `504193a3`
- Change size: 7 commits, 107 files, +2629 / -330; no CI, dependency lockfile, or infrastructure changes
- Audience: PR author, backend/frontend maintainers, operator-facing release owner

> **Post-merge status (same day).** The branch was merged with main's legacy
> NATIVE/ADK retirement (#109), which renumbered this branch's migrations:
> the run error contract is now **V72** and the deadline seed is **V73** (V70/V71
> in this snapshot were the pre-merge branch files). The error-contract delta was
> ported onto main's refactored `AgentLoopEngine`; the retired task path is gone.
> Findings R-02 (stale fault-injection spec), R-03 (surface contract guard) and
> R-04 (deadline single source of truth) are closed by contract tests and the
> rewritten spec; R-01 gained a real-scheduler test and a session-timeout race
> fix (both enforcers now settle CANCELLED with `RUN_DEADLINE_REACHED` /
> `SYSTEM_CONFIG`). This body remains the evidence snapshot at `504193a3`.

## 1. Review question and tolerance

Is the new failure-identity and deadline architecture healthy enough to merge and operate, and where are the remaining risks?

Risk tolerance for this review: no Critical or High findings outstanding at merge; Medium findings must be tracked with an owner and a validation gate; Low findings may enter the backlog. The quality attributes that matter for this change are operability/recoverability (can an operator act on a failure), reliability (does the identity stay truthful through the run lifecycle), maintainability (can a new failure surface be added safely), observability (can failures be measured), and upgrade safety (migrations and config).

## 2. What changed, architecturally

The PR establishes a failure-identity flow that crosses five layers:

1. Producers: actionable exceptions (`ActionableFailure`, `ErrorSurface`) thrown at admission, credential, workspace, fleet, breaker, and deadline boundaries.
2. HTTP contract: `GlobalExceptionHandler` adds `code` and `surface` only for actionable failures; all other responses keep their previous shape.
3. Run lifecycle: `RunContext` captures the first actionable failure; `AgentLoopEngine` settles runs with the bounded message plus code/surface; deadline termination is recorded only after a verified stop.
4. Persistence and events: V70 adds `runs.error_code` / `runs.error_surface`; `RunCompletedEvent` and `RunResponse` expose them; V71 seeds `aria.tasks.deadline-minutes`.
5. Presentation: `errorPresentation.ts` maps the served surface to a Configure tab or Crew route; `ErrorNotice` renders the repair action; legacy text matching remains for records that predate the contract. The Configure UI also validates the deadline range before writing.

This is an additive, backward-compatible contract: nullable columns, optional JSON fields, event constructors kept, and the legacy fallback preserved.

## 3. Evidence base

| Evidence | Command / source | Result |
| --- | --- | --- |
| Diff scope | `git diff --stat 5d16e349...504193a3` | 107 files, +2629 / -330 |
| Control-plane change | `git diff --name-status ... -- .github **/pom.xml package.json pnpm-lock.yaml` | none |
| Backend suite | `mvn clean test -Dspring.profiles.active=h2` | BUILD SUCCESS, 2,757 tests, 0 failures/0 errors, 8 skipped, coverage gates met |
| Frontend | `pnpm build`; `npx vitest run` | build success; 60 files / 514 tests passed |
| CI | run `39603811844` on `504193a3` | all jobs success (unit lanes, Failsafe, 4/4 Playwright shards, smoke, Docker, frontend) |
| Sandbox workflow | run `39603811787` | success |
| Live config write path | `GET`/`PUT /api/v1/system-config/aria.tasks.deadline-minutes` | GET 45; PUT 0 -> 400 `INVALID_SYSTEM_CONFIG_VALUE` / `SYSTEM_CONFIG`; PUT 45 -> 200 |
| Live failure identity | dispatch run `49c8ea3e-67a6-4b6f-9060-e2541a77116b` | FAILED, `errorCode=RUNTIME_CREDENTIAL_NOT_CONFIGURED`, `errorSurface=LLM_PROVIDERS` |

## 4. Strengths

- The contract is enforced at one HTTP boundary and one run-capture point, and it degrades safely: unknown surfaces render no action instead of crashing, and stored legacy runs still get text-based actions ([GlobalExceptionHandler.java:148-167](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-common/src/main/java/io/aria/conductor/common/exception/GlobalExceptionHandler.java#L148-L167), [errorPresentation.ts:34-47](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-dashboard/src/api/errorPresentation.ts#L34-L47)).
- `RunContext` applies a testable first-actionable-wins rule, and the completion path consumes stale termination intents without attaching them to a COMPLETED run ([RunContext.java:140-159](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/RunContext.java#L140-L159)).
- The deadline identity is recorded only after a verified stop, closing the round-3 defect where a refused stop could label a successful run `RUN_DEADLINE_REACHED` ([CoreExecutionService.java:839-877](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreExecutionService.java#L839-L877)).
- Migrations are additive and re-appliable; the pinned-migration test executes the real V70 script rather than a copy ([V70__run_error_contract.sql:3-4](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-app/src/main/resources/db/migration/V70__run_error_contract.sql#L3-L4), [ReviewAskRepairMigrationIntegrationTest.java](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-app/src/test/java/io/aria/conductor/app/ReviewAskRepairMigrationIntegrationTest.java)).
- Error messages name repair locations and configuration keys without echoing credential values; the unauthenticated API posture is a documented deployment assumption in SECURITY.md, not an incremental exposure of this PR.
- Validation is broad: unit, migration, component, and E2E coverage; CI is fully green on the reviewed head.

## 5. Findings

Severity uses Critical / High / Medium / Low. Likelihood, impact, and confidence are separate. Owner is an assumption because the repository does not declare team ownership.

| ID | Severity | Finding | Likelihood | Impact | Confidence | Owner | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- |
| R-01 | Medium | The full deadline path (timer -> verified cancel -> persisted identity -> UI) has no automated end-to-end test; only unit tests with a manual scheduler exist | Medium | Medium | High | backend maintainers | `CoreExecutionServiceTest` uses `ManualDeadlineScheduler`; `grep -rn "aria.tasks.deadline\|system-config\|SystemConfig\|Run Deadline" act-dashboard/e2e` returns no match |
| R-02 | Medium | The fault-injection E2E asserts a provider-level deadline that no longer governs coordinated task runs, so its premise is stale and can produce misleading evidence | Medium | Medium | High | backend/QA maintainers | [fault-injection.api.spec.ts:15-17](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-dashboard/e2e/api/fault-injection.api.spec.ts#L15-L17), [AgentLoopEngine.java:1147-1149](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java#L1147-L1149), [application.yml:51-56](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-app/src/main/resources/application.yml#L51-L56) |
| R-03 | Medium | The surface/action contract is duplicated across a Java enum, a TypeScript union and switch, persisted raw strings, and a legacy text matcher, with no contract test or generated type | Medium | Medium | High | backend + frontend maintainers | [ErrorSurface.java:8](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-common/src/main/java/io/aria/conductor/common/exception/ErrorSurface.java#L8), [errorPresentation.ts:6](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-dashboard/src/api/errorPresentation.ts#L6), [V70__run_error_contract.sql](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-app/src/main/resources/db/migration/V70__run_error_contract.sql) |
| R-04 | Medium | The deadline range 1-1440 exists in four independent places (runtime read, controller validation, UI metadata, seed description) | Medium | Low | High | backend + frontend maintainers | [TaskDeadlineProperties.java:53-54](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/TaskDeadlineProperties.java#L53-L54), [SystemConfigController.java:18-20](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/controller/SystemConfigController.java#L18-L20), [SystemConfigPanel.tsx:48](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-dashboard/src/components/SystemConfigPanel.tsx#L48), [V71 seed:4](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-app/src/main/resources/db/migration/V71__seed_task_deadline_config.sql#L4) |
| R-05 | Low | Actionable identity is carried by exception type, so a wrapper boundary (ExecutionException, CompletionException, TaskExecutionException) can drop code/surface; no current provider throws an actionable exception inside `executeTask`, so this is latent | Low | Medium | Medium | backend maintainers | [AgentLoopEngine.java:1268-1275](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java#L1268-L1275), [TaskExecutionException.java:10](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/TaskExecutionException.java#L10), [RunContext.java:152](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/RunContext.java#L152) |
| R-06 | Low | Failure identities are not instrumented: no counter/timer by `code` or `surface`, so failure distribution and deadline/credential trends are not measurable or alertable | High | Low | High | platform/backend maintainers | `grep -rn "MeterRegistry" act-execution act-agent` returns no instrumentation |
| R-07 | Low | Migration immutability has no CI guard, and editing an already-applied migration breaks startup; `repair-on-migrate` only repairs failed migrations, not checksum changes | Medium | Medium | High | backend maintainers | Observed locally: `FlywayValidateException: Migration checksum mismatch for migration version 70`; schema history repaired manually |
| R-08 | Low | The new cross-layer error contract is not documented in AGENTS.md or docs/architecture.md, so future endpoint work can bypass it | Medium | Low | High | maintainers | `grep -n "ActionableFailure\|ErrorSurface\|error_code" AGENTS.md docs/architecture.md` returns nothing |
| R-09 | Low | `terminationFailures` is a process-lifetime map consumed only on completion; a run that never completes after a recorded termination leaks one small entry | Low | Low | Medium | backend maintainers | [RunInputCoordinator.java:137-158](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/RunInputCoordinator.java#L137-L158) |

### R-01: Deadline full path lacks an automated end-to-end test

The deadline timer is exercised with a manual scheduler in `CoreExecutionServiceTest`, and the completion behavior is exercised with a mocked coordinator in `AgentLoopEngineWaitingInputTest`. The wiring between the real scheduler, a blocked prompt, the verified stop, the persisted run, and the rendered notice is not covered by any integration or Playwright test. The live checks confirmed the config write path and the credential identity path, not a real deadline expiry.

Why it matters: the deadline is now the operator-visible recovery lever for runaway runs. A regression in the timer registration, the scheduler lifecycle, or the `TaskContext` deadline plumbing would pass the current suite.

Validation gate: an integration test that freezes a spec with a short deadline (test-profile override or injectable clock/scheduler), blocks the prompt, and asserts the run settles CANCELLED with `RUN_DEADLINE_REACHED` and `SYSTEM_CONFIG`, plus a second case where the stop is refused and the run completes without the identity.

### R-02: Stale provider-deadline premise in the fault-injection E2E

`fault-injection.api.spec.ts` states that the effective sendMessage deadline comes from `opencode.max-task-minutes` and asks the test profile to set it to 1 minute. Coordinated task runs now build `TaskContext` with `aria.tasks.deadline-minutes` ([AgentLoopEngine.java:1147-1149](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java#L1147-L1149)), so the provider property is only a fallback when the context has no duration ([application.yml:51-56](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/agent-control-tower/act-app/src/main/resources/application.yml#L51-L56)). The spec skips without an LLM key and a healthy sandbox, so CI can be green while the premise is wrong.

Why it matters: the spec is a validation asset for the exact reliability behavior this PR makes configurable. When run live with a 1-minute provider budget, the expected 60-second timeout will not fire under the 45-minute run deadline; the poll may time out or produce misleading evidence.

Validation gate: update the spec and its comments to configure the System Config deadline (1 minute is the minimum) or pass an explicit short task duration, then re-run the live fault-injection scenario.

### R-03: Duplicated surface/action contract without a guard

The backend enum, the frontend union and switch, the persisted `error_surface` strings, and the legacy text matcher must agree. There is no contract test that enumerates every `ErrorSurface` value and asserts the matching frontend action. Adding a surface without a UI case silently yields no action; renaming a surface breaks historical rows whose stored strings keep the old name.

Why it matters: the actionability of every future failure depends on four places staying in sync, and the failure mode is silent degradation, not a test failure.

Validation gate: a contract test enumerating `ErrorSurface.values()` against the frontend action map (or generated TypeScript types), plus a documented rule that persisted surface names are immutable.

### R-04: Deadline range has four sources of truth

The runtime read clamps with `1, 1440`; the controller repeats the constants; the UI metadata repeats `min: 1, max: 1440`; the V71 description repeats the range text. The UI and API now both validate, but only the API validates this key, and nothing asserts the four values agree.

Why it matters: drift can produce a UI that accepts a value the API rejects, or a description that advertises a range the runtime clamps differently.

Validation gate: one shared source (a config metadata registry) or a guard test asserting the runtime, controller, UI metadata, and seed description agree.

### R-05: Identity can be dropped at wrapper boundaries (latent)

`RunContext.addError(Throwable)` checks only the top-level exception. `awaitTaskResult` wraps any non-TaskExecutionException cause in a `TaskExecutionException`, which does not implement `ActionableFailure`. No current provider throws an actionable exception inside `executeTask` (grep), so the gap is latent; a future provider-level actionable failure would persist a message with no code/surface and rely on text matching.

Validation gate: a test where a provider throws `ActionableIllegalStateException`; expect the persisted run to carry its code/surface. Fix by unwrapping the cause chain in `RunContext` or making the wrapper surface-aware.

### R-06: No observability for failure identity

The identity is queryable in the database and rendered in the UI, but there is no counter or alert by code/surface. Operators cannot see whether credential failures, deadline expiries, or breaker trips are increasing, and there is no alert on a deadline expiry.

Validation gate: counters such as `aria.run.failures{code,surface}` and an alert on `RUN_DEADLINE_REACHED` / `RUNTIME_CREDENTIAL_NOT_CONFIGURED`; verify with a failing run and the metrics endpoint.

### R-07: Migration immutability has no guard

V70 was edited pre-merge to use `ADD COLUMN IF NOT EXISTS`. That is correct while the migration is unreleased, but any database that already applied the old V70 fails startup with a checksum mismatch; `spring.flyway.repair-on-migrate` only repairs failed migrations, so it does not fix this. The local development database required a manual schema-history checksum repair. Production is unaffected while the PR is open, but a post-merge edit would brick upgrades.

Validation gate: a CI check that migration files present on the base branch are byte-identical in a PR; document the pre-merge window and the repair procedure.

### R-08: Contract not documented for contributors and agents

AGENTS.md documents module responsibilities and validation commands but not the error contract. New endpoints can return a plain message and silently bypass the code/surface flow. Add a short section to AGENTS.md and docs/architecture.md: when to throw an actionable exception, how to choose a surface, and the persisted-string immutability rule.

### R-09: Termination failure map has no sweeper

The map is consumed on completion (including the new always-consume behavior), and normal paths clear it. A run that records a termination reason but never reaches completion (for example, an abnormal engine exit) leaves one entry for the process lifetime. No such path was observed; confidence is medium.

Validation gate: clear the entry in the finalizer/terminal path regardless of completion, or bound the map and expose its size.

## 6. Accepted risks and assumptions

- Unauthenticated `/api/v1/**` is a documented deployment limitation in SECURITY.md; the review assumes deployment behind an authenticating gateway. The config write endpoint is therefore not treated as a new exposure.
- Actionable messages name internal endpoints and config keys but never credential values. This is acceptable for the operator-only dashboard under the documented posture.
- A refused deadline stop leaves the run running past its deadline; the circuit breaker's run-duration cap remains the backstop. This is pre-existing and not reclassified by this PR.
- Impact estimates for R-05, R-06, and R-09 are qualitative because the repository has no failure telemetry or incident history.
- Graphviz rendering was not performed (`dot` is not installed on this machine); the `.dot` source is the maintained artifact.

## 7. Business application fitness

| Dimension | Assessment | Evidence |
| --- | --- | --- |
| Business model clarity | Strong: operator/Crew/kanban/run/approval concepts are explicit; the change adds a recovery action without changing the domain model | `act-common` models, RunService, ApprovalSource, Crew UI |
| Data and state understanding | Strong: Run lifecycle states, error identity columns, approval and workspace records; migrations are additive | V70, V71, `Run`, `RunCompletedEvent` |
| End-to-end path grounding | Strong for credential and config failures (live verified); partial for the deadline timer path (R-01) | live run `49c8ea3e...`, live config PUT |
| Agent instruction use | Gap: AGENTS.md does not describe the error contract or the new config key | R-08 |
| Business validation readiness | Strong overall: 2,757 backend tests, 514 frontend tests, 4 Playwright shards, migration guards; two validation gaps (R-01, R-02) | CI `39603811844` |

## 8. Verdict

The architecture is healthy for merge: the contract is additive, the identity stays truthful through the completion paths, the deadline write path is validated, and the verification base is broad. No Critical or High findings were found. The Medium findings are validation and contract-maintenance gaps rather than runtime regressions; R-01 and R-02 should be scheduled immediately after merge, and R-03/R-04 in the next iteration.

Supporting artifacts:
- [risk-map.dot](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/docs/reviews/2026-10-21-pr108-error-contract-risk-review/risk-map.dot)
- [risk-map-evidence.md](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/docs/reviews/2026-10-21-pr108-error-contract-risk-review/risk-map-evidence.md)
- [quality-scenarios.md](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/docs/reviews/2026-10-21-pr108-error-contract-risk-review/quality-scenarios.md)
- [remediation-plan.md](file:///C:/Users/User/.qoder/worktrees/app/95d9ac/aria-conductor/docs/reviews/2026-10-21-pr108-error-contract-risk-review/remediation-plan.md)
