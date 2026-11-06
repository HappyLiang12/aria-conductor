# Remediation plan: PR #108 error contract and deadline

Priorities: P1 = schedule immediately after merge (within one week); P2 = next iteration; P3 = backlog. Effort is S (hours), M (1-2 days), L (multi-day). Validation gates must be automated unless explicitly marked live.

## P1

### R-02: Update the fault-injection E2E premise (S)

- Update `act-dashboard/e2e/api/fault-injection.api.spec.ts` comments and setup: the coordinated task deadline is `aria.tasks.deadline-minutes`, not `opencode.max-task-minutes`.
- Either configure the System Config deadline to 1 minute for the timeout case, or pass an explicit short task duration through the test path.
- Validation gate: run the fault-injection spec against a live sandbox and LLM key; the timeout case must abort within the poll window and the log must name the configured deadline source.
- Why P1: this is the only validation asset for the behavior the PR makes configurable, and it currently documents the wrong knob.

### R-01: Add a deadline integration test (M)

- Add an integration test that freezes a spec with a short test-profile deadline (or an injectable clock/scheduler), blocks the prompt, and asserts CANCELLED + `RUN_DEADLINE_REACHED` / `SYSTEM_CONFIG` in the persisted run and event.
- Add a companion case where the stop is refused and the prompt completes; assert no identity is persisted.
- Validation gate: the test fails if the scheduler is not registered or the reason is recorded before verification.
- Why P1: the timer-to-persistence path is the operator recovery lever and is currently covered only by manual-scheduler unit tests.

### R-03: Guard the surface/action contract (S)

- Add a contract test that enumerates `ErrorSurface.values()` and asserts a frontend action exists for each value (or generate the TypeScript union from the backend enum).
- Document that persisted `error_surface` strings are immutable and require a data migration if renamed.
- Validation gate: adding a surface without a frontend case fails the build.

### R-04: Single source for the deadline range (S)

- Introduce one metadata source (for example, a small config-metadata registry consumed by the controller and exposed to the UI) or a guard test that asserts the runtime read, controller constants, UI metadata, and V71 description agree.
- Validation gate: changing any one of the four values makes the guard fail.

## P2

### R-05: Preserve identity through wrapper boundaries (S)

- Unwrap `ExecutionException` / `CompletionException` / `TaskExecutionException` causes in `RunContext.addError`, or make wrappers carry the actionable identity.
- Validation gate: a provider that throws `ActionableIllegalStateException` yields a persisted code/surface.

### R-06: Instrument failure identity (M)

- Add counters tagged by `code` and `surface`, plus an alert for `RUN_DEADLINE_REACHED` and `RUNTIME_CREDENTIAL_NOT_CONFIGURED`.
- Validation gate: a synthetic failing run increments the expected counter; the alert fires in a staging environment.

### R-08: Document the error contract (S)

- Add a short section to AGENTS.md and docs/architecture.md: when to throw an actionable exception, how to choose a surface, how the HTTP/run/event/UI layers carry it, and the persisted-string immutability rule.
- Add the contract to the "add a backend API endpoint" path in AGENTS.md.
- Validation gate: documentation review only.

### R-07: Protect migration immutability (S)

- Add a CI check that migration files already present on the base branch are byte-identical in a PR (for example, compare `act-app/src/main/resources/db/migration` against the merge base).
- Document the pre-merge window: a migration in an open PR may be edited, but a database that applied the old checksum needs a schema-history repair.
- Validation gate: a test PR that edits an applied migration fails the new check.

### R-09: Sweep termination failures on terminal paths (S)

- Clear `terminationFailures` in the finalizer/terminal path even when completion is bypassed; alternatively bound the map and expose its size.
- Validation gate: a test that records an intent without a completion leaves no entry (or the map stays bounded).

## P3

- Monitor R-05 and R-09 after telemetry exists; promote if observed.
- Revisit R-06 with a dashboard panel and a runbook link from the CTA message.

## Sequencing

1. R-02 and R-03 are small and protect the evidence base; do them first.
2. R-01 follows, because it depends on the decision made for R-02 (which deadline source the E2E uses).
3. R-04 is independent and can ship with R-03.
4. P2 items are independent; R-05 and R-09 are code-path hardening, R-06/R-08 are operational/documentation.

## Acceptance for closing this review

- R-01, R-02, R-03, R-04 have automated validation gates.
- The next architecture review can mark QS-04, QS-05, and QS-06 as VERIFIED rather than PARTIAL/NOT VERIFIED.
- No new Critical or High findings are introduced by the remediation.
