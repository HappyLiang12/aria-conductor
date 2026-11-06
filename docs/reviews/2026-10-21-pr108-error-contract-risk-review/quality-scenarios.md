# Quality attribute scenarios: error identity and deadline

Status values: VERIFIED means the scenario was evaluated with the cited evidence; PARTIAL means part of the path was evaluated; NOT VERIFIED means the scenario has no automated or live evaluation yet. A PARTIAL or NOT VERIFIED status is not a pass.

## QS-01 Operability: an operator can repair a credential failure

- Stimulus: a coordinated qoder run is dispatched while `qoder:operator` has no credential.
- Response: the run settles FAILED with `RUNTIME_CREDENTIAL_NOT_CONFIGURED` and `LLM_PROVIDERS`; the Runs detail shows the message and a button that opens Configure on LLM Providers.
- Measure: persisted `error_code` and `error_surface` are non-null and match the served HTTP `code`/`surface`; the repair button is rendered without text matching.
- Evidence: live run `49c8ea3e-67a6-4b6f-9060-e2541a77116b` (`FAILED`, code/surface as above); `AgentLoopEngineWaitingInputTest`; drawer/workspace/agent-drawer surface tests.
- Status: VERIFIED for the credential path.

## QS-02 Operability: an invalid deadline cannot be stored

- Stimulus: an operator enters `0` or a non-numeric value for `aria.tasks.deadline-minutes`.
- Response: the API returns 400 with `INVALID_SYSTEM_CONFIG_VALUE` / `SYSTEM_CONFIG`; the Configure UI disables Save and marks the field invalid.
- Measure: HTTP status and body code/surface; `aria-invalid` state and Save disabled.
- Evidence: live `PUT` with `0` -> 400 with the exact body; `SystemConfigControllerTest`; `SystemConfigPanel.test.tsx`.
- Status: VERIFIED.

## QS-03 Reliability: a refused deadline stop does not mislabel a completed run

- Stimulus: the deadline elapses, the backend refuses the stop, and the prompt then completes.
- Response: the run settles COMPLETED with no error identity; no `RUN_DEADLINE_REACHED` is persisted or published.
- Measure: persisted status COMPLETED, `error_code`/`error_surface` null, event identity null; the termination intent is consumed.
- Evidence: `AgentLoopEngineWaitingInputTest.completeRunCompletedConsumesButDoesNotPersistAStaleTerminationFailure`; `CoreExecutionServiceTest.aTerminationReasonIsRecordedOnlyAfterTheStopIsVerified`.
- Status: VERIFIED at unit level; the live refused-stop path was not exercised.

## QS-04 Reliability: a real deadline expiry settles the run with its identity

- Stimulus: a run is frozen with a short deadline and its prompt stays in flight past it.
- Response: the coordinator verifies the stop, settles the run CANCELLED, and persists `RUN_DEADLINE_REACHED` / `SYSTEM_CONFIG` with the operator reason.
- Measure: scheduled timer fires, verified stop, persisted identity, `RunCompletedEvent` identity, UI notice with the System Config action.
- Evidence: timer/stop behavior is unit-tested with `ManualDeadlineScheduler`; the persistence/event path is unit-tested with a mocked coordinator; no integration or E2E test drives the real scheduler end to end.
- Status: PARTIAL (unit pieces verified, full path NOT VERIFIED; see R-01).

## QS-05 Maintainability: a new repair surface keeps a UI action

- Stimulus: a developer adds a new `ErrorSurface` value and emits it from a new endpoint.
- Response: the dashboard maps it to a repair action, or a contract test fails the build.
- Measure: every enum value has a frontend action; the build fails when a value is unmapped.
- Evidence: `ErrorSurface` has four values; `errorActionForSurface` has four cases; no guard test exists.
- Status: NOT VERIFIED (see R-03).

## QS-06 Observability: failure distribution is measurable

- Stimulus: operators need to know whether credential, deadline, or breaker failures are increasing.
- Response: metrics are exposed by code and surface, with an alert on deadline expiry and credential misconfiguration.
- Measure: a counter per `code`/`surface` and an alert rule.
- Evidence: no metrics instrumentation exists; identity is only persisted and rendered.
- Status: NOT VERIFIED (see R-06).

## QS-07 Upgrade safety: an existing database upgrades without manual repair

- Stimulus: a database at schema V69 starts the new build.
- Response: V70 and V71 apply cleanly; a rerun after a failed/partial apply succeeds; no manual checksum work is needed.
- Measure: fresh Flyway migration succeeds; re-applying V70 is a no-op; V69/V71 reseeding does not duplicate rows.
- Evidence: `V70RunErrorContractMigrationTest`; `V69SeedRunDurationConfigTest`; `V71SeedTaskDeadlineConfigTest`; full CI migration tests.
- Status: VERIFIED for fresh and re-applied migrations. A database that applied the old pre-merge V70 checksum needs a manual repair (R-07).
