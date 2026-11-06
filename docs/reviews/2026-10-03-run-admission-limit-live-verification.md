# Run admission limit — live verification (2026-10-03)

Branch `feat/run-admission-limit` at `9a0d532e`. Environment: local stack started from this
worktree (`scripts/start.ps1`, podman + OpenSandbox, backend profile `h2`, core
`opencode/SANDBOX`, `LLM_MODEL=deepseek-flash`). Evidence below is quoted from the stack's
`.run/backend.log` and the verification script's captured output; run statuses were read via
`GET /api/v1/runs/{id}`.

## Commands

- Regression: `mvn -B test -pl act-execution -Djacoco.skip=true` → **Tests run: 1221,
  Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS**.
- ITs: `mvn -B verify -pl act-app -am -Dskip.unit.tests=true -Dit.test="RunAdmissionIntegrationTest,KanbanAutoDispatchIntegrationTest,KanbanTransitionIntegrityIntegrationTest,KanbanPickupIntegrationTest,KanbanPickupEligibilityIntegrationTest,KanbanHitlMigrationTest" -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true` → exit 0; per class: 1/1, 2/2, 5/5, 1/1, 3/3, 2/2.
- Live: 8 runs dispatched in ~1s to one sandbox agent (two batches), observed at 4s cadence.

## Criteria evaluated (all PASS)

1. **Cap holds at 6 + queued runs stay PENDING.** Batch 1 at +5s:
   `{"RUNNING":6,"PENDING":2}`; batch 2 at +4s: `{"RUNNING":6,"PENDING":2}`; max concurrently
   executing observed: 6.
2. **A queued run's card waits in TODO and moves IN_PROGRESS only at admission** (run `60a3169d`):
   ```
   19:31:05.598 Auto-created Kanban item for run: runId=60a3169d-...        (born TODO)
   19:31:26.467 Admitted run 60a3169d-... (worker) to the worker pool       (21s queued)
   19:31:26.484 Auto-transitioned Kanban item 7b798030-... to IN_PROGRESS   (+17ms, start signal)
   19:31:40.478 Auto-transitioned Kanban item 7b798030-... to REVIEW        (settlement)
   ```
3. **Completing runs start the next waiter** — `60a3169d` was admitted the moment capacity
   freed (admission line above), then completed.
4. **Cancel-while-queued is honest** (twice: `6cbb1bf0`, `be44c4d6`): `POST
   /api/v1/runs/{id}/cancel` → `CANCELLED` while PENDING; never observed in
   INITIALIZING/RUNNING afterwards (`CANCEL_DRIFT false`); its card settled `CANCELLED`
   (never IN_PROGRESS). The queue refused the waiter truthfully:
   `Run 6cbb1bf0-... left the admission queue before a slot was free`.
5. **No `DOCKER::*` domain 500s during the capped fan-out** — `grep -c "DOCKER::" .run/backend.log`
   → `0` across both batches.
6. **Boot re-enqueue with the reaper strictly first** (backend restarted with 6 runs executing
   and 1 still PENDING):
   ```
   19:35:25.602 Recovering 6 orphaned run(s) left by backend restart            (reaper FIRST)
   19:35:25.687-.760 (the six orphans settle to REVIEW)
   19:35:25.775 Admitted run 4bda6622-45a8-4b5f-ac6d-7e08003504b1 (worker)       (then bootstrap)
   19:35:25.781 Auto-transitioned Kanban item 6c1023a2-... to IN_PROGRESS for run 4bda6622-...
   ```
   Post-boot fates: the re-enqueued run `4bda6622` **COMPLETED** (never reaped); the six
   executing runs FAILED (orphan reaper, expected); the earlier cancelled run stayed CANCELLED.
   No `Could not re-start queued run` line in the log.

## Observations (not pass criteria)

- One batch-1 run (`932ad2e5`) FAILED in the workspace-upload relay phase: the sandbox's
  published-port relay never came up (`Network connectivity error: Failed to connect to
  localhost/127.0.0.1:59165`; attempts 1-10+ within the 90s window, then exhausted). This is the
  pre-existing relay warm-up class (PR #99 widened the window; the code path is untouched by
  this branch) — under 6-way concurrent sandbox creation one relay in 14 run-creations still
  timed out. Recorded as environment flake, not a regression of this feature.
- The Aria reservation pool was unit-tested (queue suite) but not live-exercised here — these
  batches dispatched worker runs only.
- Orphaned sandbox containers from the killed runs were removed after the restart check.

## Verdict

All six criteria above were evaluated and passed. The run admission limit behaves as designed
live: bounded concurrency at 6 workers, honest PENDING queuing with TODO cards, automatic
turnover, truthful cancel-while-queued, zero sandbox-create domain 500s at the capped fan-out,
and a boot re-enqueue that runs strictly after the orphan reaper.
