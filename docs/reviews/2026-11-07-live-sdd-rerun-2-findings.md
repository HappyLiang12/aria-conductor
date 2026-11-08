# Live SDD rerun 2 — the green verdict at the next-wave head

Date: 2026-11-07 (local CST, UTC+8). Base: `fix/sdd-runtime-hardening` @ `c1202a72`
(the next-wave head: V79 long-path clone, V80 Aria prompt, R6/R8/R9 fixes).

## Stack

- H2 copy of the dev DB, Flyway applied V79 and V80 (77 migrations, now v80).
- Backend `:8080` from the worktree, dashboard `:5173`, qoder HOST, model `efficient`,
  qodercli 1.1.61.
- `ARIA_CORES_QODER_EXECUTABLE` pinned to the versioned CLI; no `PACK_CREDENTIAL_KEY`
  (the copied DB's pack credential is Base64 dev-mode storage, 56 chars — see R10/env note).
- Approval helper approved READ-tier asks; the operator approved the template
  instantiation and the spec review, and answered nothing else.

## Exercise

Aria run `b0ce0266-77bb-4925-9330-4c6dd9dacf93` instantiated the approved
development-workflow template `d0000001-0000-0000-0000-000000000001` for issue **#56**
(`POST /api/v1/workflows/{id}/cancel` does not cancel the in-flight run).
Chain `30c6788f-da6b-4c32-a9f1-f352c8acff83`:

| Step | Run | Result |
|---|---|---|
| BA | `d4ca40d4-5e23-49ef-b755-3b1c7c424ae0` | COMPLETED, spec review approved |
| DEV | `48fb43c0-dc53-44e6-a18e-04de5e94faa6` | COMPLETED, pushed `a8bd4c1d` |
| QA | `8b786e2c-e448-4b89-96e2-1a408333dbc9` | COMPLETED, **VERDICT=PASS**, pushed `05342043` |

Branch `sdd/30c6788f-da6b-4c32-a9f1-f352c8acff83`:
`a8bd4c1d sdd dev` (WorkflowService + 97-line WorkflowServiceTest + e2e spec),
`05342043 sdd qa report` (only `qa_report.md`, 213 insertions). Report artifact
`REPORT_ID=7a9c2f8e-4b3d-4e1a-9f5c-8d2e6b4a1c3f`.

Step cards `dd75d2f1` (BA), `06e3297c` (DEV), `02547d13` (QA): all **DONE** after the
chain completed.

## Confirmations

- **R1 (long-path clone)** — the DEV and QA prompt clones ran as
  `git -c core.longpaths=true clone --branch sdd/30c6788f-... https://github.com/... repo`
  and succeeded; no "Filename too long" and no empty-index checkout. The card titles carry
  the prefixed clone too (V79).
- **R2 (Maven temp)** — `mvn test -pl act-agent` ran inside the core's Bash and finished
  `BUILD SUCCESS` (11.4 s) with no `Unable to create temporary directory C:\WINDOWS`
  error and no manual `TEMP`/`java.io.tmpdir` workaround.
- **R4 (assistant approvals)** — zero `decide_approval` asks among 200 recorded approvals
  and zero such tool calls from the Aria run; the assistant instantiated the workflow
  and reported. (V80 corrected the existing Aria row before this run.)
- **F14 (credential)** — the BA/DEV/QA push operations resolved the stored pack credential;
  the DEV pushed `a8bd4c1d` and the QA pushed `05342043` without a restart.
- **F19 (report-only push)** — the QA's report commit changed exactly one file
  (`qa_report.md`, 213 insertions) and the pre-push `git diff --stat` proved it.
- **F2 (sync chat)** — POST `/api/v1/aria/chat` returned `status=RUNNING` with the run id,
  not a false completion.
- **R6 completion path** — the three ask-less step cards settled to DONE; before the fix
  they stayed in REVIEW. (The cancel path itself was not exercised in this rerun; it is
  covered by the four `WorkflowCompletionCardListenerTest` cancel tests.)
- **QA verdict** — the chain reached a green verdict end to end: BA spec approved,
  DEV pushed, QA PASS with a report.

## Findings from this rerun

- **R7 reproduced (medium, open)** — Aria run `b0ce0266` delivered its instantiate tool
  result, then failed finalization with
  `StopProof[runId=b0ce0266..., allWritersStopped=false]` ("nothing was captured,
  destroyed or released"). This is the same fail-closed proof as the prior cancel-path
  observation, now on an ordinary completed assistant run. The core log holds only
  `bridge.ready`; the proof needs job-inventory diagnostics (see the ledger's
  investigate note). The BA/DEV/QA runs of the same chain finalized normally, so the
  trigger is specific to the assistant run's process tree.
- **R10 (new, medium)** — a default HOST launch resolved the bare `qoder` to
  `D:\Qoder\bin\qoder` (an extensionless Unix shim on PATH) before the PATHEXT candidates;
  the bridge failed with `spawn D:\Qoder\bin\qoder ENOENT` and the run surfaced the
  misleading "runtime endpoint did not authenticate the control secret" after 30 s.
  The rerun used `ARIA_CORES_QODER_EXECUTABLE=<versioned qodercli.exe>` as the documented
  workaround. Candidate fix: on Windows prefer spawnable `.exe`/`.com` candidates and
  fall through to the versioned discovery roots, refusing a shell-script candidate by
  property name.
- **Environment note** — the copied DB's pack credential is Base64 (dev-mode storage);
  starting the backend with `PACK_CREDENTIAL_KEY` makes GCM decryption fail
  ("Credential decryption failed"). The live recipe must match the storage mode.
- **R5/R9 and R8 not exercised** — the QA returned PASS on the first round, so no
  DEFECT reschedule and no attempt exhaustion occurred. The fixes are unit-pinned
  (`WorkflowServiceSddTest` 13/0), not live-exercised in this rerun.
- **QA command hygiene (enhancement candidate)** — the QA's exploratory filtered Maven
  runs tripped the repo's `jacoco:check` ratchet and a "no tests matching pattern" error
  before it settled on targeted suites; the report still passed on evidence. The QA
  prompt could name the repository's real test command and the `-Djacoco.skip=true`
  requirement for filtered runs.

## Post-rerun checks at the R10/R7 head

At `eed484e4` (R10 `e3dabe23` + the R7 sampler/diagnostics):

- **R10 live** — the backend started without `ARIA_CORES_QODER_EXECUTABLE`; the default
  resolution found the versioned `qodercli.exe` and both an Aria ping ("ok") and an
  MCP-tool-call ping (list_agents → "4") completed. The shim is no longer selected.
- **R6 cancel path live** — chain `5c543347` (issue #54) was cancelled while the BA run
  was in flight. The completion listener settled card `f12fa95b` to CANCELLED, the
  deferred stop cancelled run `bcde996f`, the run row is CANCELLED, and its finalization
  completed (`complete=true`). One WARN "Refusing to update ... to CANCELLED: the run is
  terminal or missing" followed the deferred stop and the engine then recorded the
  external terminal transition — benign double-cancel noise, the state is correct.
- **R7** — three post-diagnostic runs (two pings and the cancelled BA run) finalized
  cleanly and the new diagnostic WARN did not fire, so the failure is a transient race,
  not a deterministic one. The fix samples each live run's tree every 500 ms while it is
  alive, so a child that outlives its root is one of this run's discovered records at
  stop time and is killed by identity; the inventory WARN stays in place for the next
  occurrence.
- **Full gate at this head** — `mvn -pl act-execution,act-agent,act-aria,act-app
  -am verify -Dspring.profiles.active=h2` at `eed484e4` → BUILD SUCCESS (05:53);
  2730 tests, 0 failures, 0 errors, 14 skipped. The four lane tests ran:
  `WorkflowCompletionCardListenerTest` 14/0, `WorkflowServiceSddTest` 13/0,
  `QoderLaunchProfileTest` 22/0, `OwnedProcessControllerTest` 7/0.

## Live run 3 (aborted)

At `b7da3218` the Aria instantiate request for issue #113 (run
`3630e332-11f2-4a80-ac0e-d1aefa16571b`) stayed RUNNING for ~44 minutes with no
chain; the qoder efficient turns were ~10 minutes apart, so this is assistant
latency rather than a workflow failure. The run was cancelled. The first cancel
returned 500 `NoClassDefFoundError: ActorTokenService$InvalidReason` because
concurrent Maven runs were rewriting `act-execution/target/classes` under the
live JVM; the retry succeeded and the run settled CANCELLED (`completedAt` set,
no stop-proof failure). Lesson: never build against the worktree while the live
backend is up; start the backend from a settled build or the packaged jar.

## Live run 4 (dead-root stop proof, R11)

The final-head check found the remaining R7 shape: an Aria run can deliver a
clarification question and stay WAITING_INPUT while its core bridge exits on its
own. On `23cef4ac`, cancelling run `54d94c47` in that state returned
`StopProof(allWritersStopped=false)` in 163 ms at the `!owns` live-root gate,
never attempted the sampler's observed children, logged nothing, and failed
finalization.

The fix (`6f6270e7`, `835f095e`, comments in `a46fc109`) splits the in-memory
binding from live-root ownership: `stop()` proceeds on a matching binding with a
dead or recycled root, kills and verdict-checks the sampler's observed records by
identity, still nets unaccounted job members, and returns true only when every
known record is gone or a recycled identity; only a live `match` root anchors the
tree sweep (a recycled pid's tree is never enumerated), and every false path logs
its identity-only reason.

Live re-check on the fixed build: run `54d94c47` reached WAITING_INPUT, its bridge
(29192) was terminated to reproduce the natural core exit, and the cancel returned
200; the backend logged "the root 29192@... answered gone -- its binding still
matches ... stopping this run's observed records by identity" and finalized the
run with `complete=true` (CANCELLED, no error). The running build predates the
comment commit `a46fc109`, so its log still carries the old wording; behavior is
identical.

## Evidence

Untracked in the worktree: `live-rerun2-aria-chat.json` (first attempt, failed on the
missing credential), `live-rerun2-aria-chat2.json`, `live-rerun2-aria-request.json`,
`live-rerun2-aria-run.json` (the R7 finalization failure), `live-rerun2-chain.json`,
`live-rerun2-kanban.json`, `live-rerun2-approvals.json`,
`live-rerun2-{dev,qa}-tools.json`.
