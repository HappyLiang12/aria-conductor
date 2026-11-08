# Live SDD round 2 on main - exercise, confirmations and findings

Date: 2026-11-08
Head: `482a8815` (PR #117 rebase-merged onto `main`; 7 commits, tree identical to the green
`df238ee7`)
Workspace: canonical checkout `D:\project\aria-conductor` (branch `main`)
Stack: backend `:8080` (H2 file, `--adk.default-provider=opencode`, qoder HOST runtime),
dashboard `:5173` (Vite), OpenSandbox `:8090`; the stack was rebuilt and restarted after the merge
and `packages/qoder-acp-bridge/dist` was rebuilt (`pnpm install --frozen-lockfile && pnpm build`).

This round exercised the real product end to end: Aria instantiated the approved
`development-workflow` template for a real GitHub issue, the BA/DEV/QA roles ran against a real
branch with the qoder HOST core, and the operator babysat the approvals. It then exercised the
direct run-cancel path, which is where the new findings are.

## Exercise

- Aria run `41debd5a` instantiated template `d0000001-0000-0000-0000-000000000001` for GitHub
  issue **#112** (`aria-conductor`, "Unify Result-Root Configuration for Spec Review").
- Chain `3f115ce0-9510-4d46-95e2-5b754bf3e837`: created `2026-11-08T08:19:48Z`, `COMPLETED`
  `08:55:44Z`, 3/3 steps.
- BA run `43557292-2607-408c-951b-e265891d53de` (2m42s): spec written, spec approval accepted,
  branch `sdd/3f115ce0-...` created at `217bf384` ("sdd: approve spec").
- DEV run `ee3a53af-3576-472d-88be-07d74ed618e1` (25m21s): commit `8ddc2ad1` ("sdd dev"),
  5 files, +75/-9. The run's own final output reports act-common 131, act-knowledge 264,
  act-execution 1302 and act-app 77 tests, all passing.
- QA run `f6bdfaf4-db43-4e92-a1c2-f58f877607e8` (6m39s): `qa_report.md` (83 lines), all six
  acceptance criteria `VERIFIED`, 1774 tests, 0 failures, 0 errors, 4 skipped,
  `VERDICT=PASS`. Commit `7b5a3546` ("sdd qa report") adds only `qa_report.md`.
- Remote branch head verified: `git ls-remote origin 'refs/heads/sdd/3f115ce0*'` ->
  `7b5a35461a9344837d5b3ff7eeca67cfd98d791c`.
- The DoD recorded the QA stage at `16:55:44` local: `stage=qa passed=true reviewer=engine
  verdict=PASS`; the chain then reached `COMPLETED`.
- Kanban: the three step cards `b56e1ee5` (BA), `0dc39119` (DEV), `23376cde` (QA) all settled to
  `DONE`.

## Confirmations

- **R1 long-path clone** - both workflow prompts use
  `git -c core.longpaths=true clone --branch ...`; the DEV and QA result workspaces contain full
  checkouts and both pushed commits. The Windows long-path failure (F12) did not recur.
- **F19 report-tree guard** - the QA head commit `7b5a3546` is `+83` on `qa_report.md` and touches
  nothing else (`git show --stat 7b5a3546`); the chain advanced. The guard did not have to reject
  anything this round; the rejection paths are unit-tested at this head.
- **F22 verdict gate** - the `PASS` was accepted only with every criterion `VERIFIED` and the three
  module test tables green; the QA did not have to fall back to "environment limitation".
- **F24 completed step cards** - the three step cards are `DONE`; the prior round's `REVIEW` state
  for finished steps did not recur.
- **Approval gate** - the SDD runs produced 49 approvals (35 `Bash`, 10 `Edit`, 3 `Write`, one
  legacy gate), all parked and decided; the real runs resumed after each approval. See F28 for the
  risk-tier label of those asks.
- **Real delivery** - the chain produced a real, reviewed change on a real branch (new
  `WorkspaceProperties` bean, `CoreRuntimeConfiguration`/`SpecReviewCoordinator` injection, new
  `@TempDir` regression test) rather than a stub.

## Findings

### F25 - A live qoder run can fail every cancel with a stranded job member (cancel UX, high)

The stop proof is fail-closed by design, and it did the safe thing. The defect is attribution: a
child process that qoder creates around a turn cannot be attributed to the run, so the proof
refuses to certify the stop, the run is marked `FAILED`, the workspace is not released and the
child keeps writing until it exits on its own.

Three independent direct-run drills at this head, each cancelled through
`POST /api/v1/runs/{id}/cancel`:

- `4bd06a41-2980-4bb5-9522-b4ebd5b0075c` (prompt: run `sleep 120`). Cancel at `16:57:15`;
  proof at `16:57:20`:
  `root=gone known=[...16 records...] job=[35224@134386018254682214]
  unaccounted=[35224@134386018254682214] ... the proof fails closed`
  (`.run/backend.log` lines 11966-11975 in the preserved copy). `35224` was `sleep.exe`, created
  `16:57:05` (12.3 s after the root) with parent `28540`, which was already gone; the sampler's
  last pass had captured children created up to 12.1 s, not this one. The run failed with
  `allWritersStopped=false` and `nothing was captured, destroyed or released`. `sleep.exe` was
  still alive at `16:57:20` and exited at its own deadline.
- `c9536988-f487-462f-999b-00c18171be5a` (same prompt, on the same backend). Cancel at `17:01:52`;
  proof at `17:01:57` reports the same shape with
  `unaccounted=[35928@134386020360351740]` - `sleep.exe` created `17:00:36`, parent gone
  (lines 13082-13100). Run `FAILED`.
- `7c91791d-5f40-4b39-afe9-38ec46386fa7` (prompt explicitly says *do not use any tools*). Cancel
  at `17:03:18.9`; proof at `17:03:18.998` reports
  `unaccounted=[34660@134386021988395865]` - a `bash.exe` running qoder's per-turn shell-snapshot
  script, created `17:03:18.83` (about 100 ms before the root was killed). Run `FAILED`
  (lines 13882-13887).

Root cause: the sampler walks the tree by parent PID every 500 ms. A child whose intermediate
parent (qoder's Bash wrapper, or the shell-snapshot bash qoder starts at each turn) exits between
samples is no longer reachable from the root, and the shared job object can only report it as an
unaccounted live member. That is the residual documented in `WindowsProcessController`'s class
javadoc (review round 6), but live traffic hits it often: qoder spawns a shell-snapshot bash at
every turn, so a cancel that lands near a turn boundary tends to strand one. The proof cannot kill
the member because it is not this run's discovered record, and the shared-job isolation ruling
forbids guessing.

Impact: cancel of a run with an active core is unreliable; the run becomes `FAILED` rather than
`CANCELLED`, the workspace is retained (`WorkspaceManager: Skipped cleanup ... no released,
unretained scratch lease`), a `REVIEW_REQUEST` ask is created, and the child keeps running.
Suggested directions, in preference order:

1. A per-run job object (nested jobs on Windows 8+) so membership attributes the child directly;
   the shared job is the reason attribution has to go through heuristics.
2. At stop time, attribute an unaccounted member to this run when its creation identity falls
   inside the run's live window and no other supervised run was live in that window; kill it by
   identity. This fixes the single-run case (all three drills) without touching a foreign run.
3. Document the residual explicitly in the operator UI ("cancel could not prove a writer stop")
   and keep the failure. This is safe but the operator has no way to stop the child.

The fix must not weaken the fail-closed rule or the per-run isolation ruling; the unit tests for
the unaccounted-member path are the guardrail.

### F26 - A cancel during the core launch returns 409 and leaves the run RUNNING (cancel UX, medium)

`e02f5226-1e64-402e-9bec-2af5966da63f` was cancelled 3 s after creation. The backend refused with
`409: ... its run-owned core had not launched yet, so no writer stop can be proven; the in-flight
launch attempt was told to stop and will not prompt`, then logged `was terminated during its core
launch: the launched core was stopped and released and the run keeps its committed RUNNING state`.
The run stayed `RUNNING`, the workspace was cleaned up, and a second cancel 22 s later settled it
`CANCELLED` with no error. The operator has to cancel twice and the intermediate `RUNNING` is
misleading; when the launch abort is acknowledged, the run can be settled `CANCELLED` in the same
operation.

### F27 - A live backend can serve stale module classes; the run drawer shows a raw NoClassDefFoundError (DX/UX, high)

At `17:05:30` the run drawer's Session Trajectory and Tool Calls panels rendered
`ServletException: Handler dispatch failed: java.lang.NoClassDefFoundError:
io/aria/conductor/common/model/ToolCallResponse` (and `TrajectoryResponse`); the endpoints
returned 500. The classes exist on `main` and in both `act-common/target` and
`~/.m2/.../act-common-0.1.0-SNAPSHOT.jar`. After restarting the backend, both endpoints return
200 and the drawer renders the QA trajectory and the tool-call section.

The exact mechanism is INFERRED: the JVM had a stale view of the mutable `.m2` jar. The
`act-common` jar in `.m2` was rewritten at `16:44` while the backend (started `16:16`) was live,
most plausibly by a workflow agent's Maven build sharing the operator's local repository; the
first request for the class came later and the class loader could not find it. The finding is
real regardless of the mechanism: the live loop must not share `.m2` with agent builds, and a
restart is required after a module install. The UI should show an actionable error (for example,
"backend module set does not match the checkout; restart the backend"), not a raw
`ServletException`.

### F28 - Native ACP tools are stamped `riskTier=READ` (security UX, high)

All 49 approvals of the three SDD runs are stamped `READ`: 35 `Bash`, 10 `Edit`, 3 `Write` (and one
legacy gate without a tool). `ToolRiskResolver.resolve` returns `toolRepo.findByName(name)
.map(ToolDefinition::getRiskTier).orElse(RiskTier.READ)`, and native qoder tools
(`Bash`, `Write`, `Edit`) have no `tool_definitions` row, so they all fall through to `READ`.
The dashboard risk chip therefore presents a shell/commit/push capability as read-only, and any
operator policy or automation keyed on the tier inherits the error. Add an explicit native-tool
map before the fallback (`Read`/`Glob`/`Grep`/`LS` -> `READ`; `Write`/`Edit` -> `PUSH`;
`Bash` -> `DESTRUCTIVE` or `PUSH` by policy) and keep the fallback for unknown names.

### F29 - Real qoder runs still report no token usage (observability, medium)

`GET /api/v1/runs/{id}` for the three SDD runs returns `totalTokensUsed: 0` and
`iterationCount: 1` (one iteration for the whole run); older runs return `null`. The Runs table
shows "not reported" or `0` and the header shows `Recorded tokens 0`. F9 persists: the ACP/bridge
usage is not propagated into the run row, so cost and progress are invisible for the real runs.

### F30 - Run workspaces and scratch remain inside the operator checkout (isolation, high; F20/F21 persist)

The live roots resolve to
`agent-control-tower/act-app/data/workspaces/runs/...` (scratch and run-owned core logs) and
`agent-control-tower/act-app/data/workspaces/results/<runId>/repo` (the DEV/QA clones), all inside
the operator checkout. A bare `git` command in a scratch directory still resolves to the operator
repository (F20). Both DEV and QA result workspaces also contain a literal `%SystemDrive%`
directory (F21), for example
`.../results/ee3a53af-3576-472d-88be-07d74ed618e1/%SystemDrive%/ProgramData`. Recommend a run root
outside the checkout by default and expanding or rejecting `%VAR%` tokens when building run paths.

### F31 - A stale bridge dist fails the run with a generic control-secret error (DX, medium)

Run `6d68651c-ae01-42f1-bd93-5d4140e77878` failed with
`The runtime endpoint ... did not authenticate the run's control secret (no authenticated answer
within PT30S ...)`, while its run-owned core log carried the actual cause:
`{"type":"bridge.error","code":"E_USAGE","message":"Unknown flag: --child-env-file"}`. The bridge
dist was stale after the merge; rebuilding it fixed the next run (`41debd5a`). The failure surface
should include the child's error code/message, and the start script should rebuild or verify the
bridge dist against its sources.

### Environment note - POSIX shims on PATH shadow the real tools

The start script found `C:\Users\User\bin\java` (a POSIX shim left by an earlier live exercise)
and failed with `Cannot run a document in the middle of a pipeline`. The shims were moved to
`C:\Users\User\bin\_live-shims-backup` so the stack could start. The DEV run's Maven command
inside the HOST core proves `java`/`mvn` reach the core after the move; `gh` was not exercised and
`jq` is still absent (the start preflight warns). This is an operator environment artifact, not a
product defect, but the start script could prefer `.exe` targets.

## Reproduce

```bash
# chain and run records
curl -s http://localhost:8080/api/v1/workflows/3f115ce0-9510-4d46-95e2-5b754bf3e837
curl -s http://localhost:8080/api/v1/runs/f6bdfaf4-db43-4e92-a1c2-f58f877607e8
curl -s http://localhost:8080/api/v1/kanban/items       # step cards are DONE
curl -s http://localhost:8080/api/v1/approvals          # 49 SDD asks; all riskTier READ

# the report-only QA commit and the remote head
cd agent-control-tower/act-app/data/workspaces/results/f6bdfaf4-db43-4e92-a1c2-f58f877607e8/repo
git show --stat 7b5a3546
git ls-remote origin 'refs/heads/sdd/3f115ce0*'

# cancel drills (each drives a new run; F25)
curl -s -X POST http://localhost:8080/api/v1/runs -H 'Content-Type: application/json' \
  --data '{"agentId":"00000000-0000-0000-0000-000000000001","promptSeed":"Do not use any tools. Write a 2000-word essay.","maxIterations":5}'
curl -s -X POST http://localhost:8080/api/v1/runs/<id>/cancel
grep 'stop proof' .run/backend.log | tail
```

## Evidence retained

- `/tmp/round2-backend-before-restart.log` (copy of `.run/backend.log` before the restart; lines
  11663-14456 cover the chain, the cancel drills and the `NoClassDefFoundError`).
- `/tmp/live-round2-chain.json`, `/tmp/run-<id>.json`, `/tmp/approvals-all.json`,
  `/tmp/kanban-all.json`.
- The dashboard check at `:5173/runs` was done in a real browser: before the restart the drawer
  showed the `NoClassDefFoundError`; after the restart the QA trajectory and the tool-call section
  render, with no `ServletException` text on the page.
- No secret, token or credential value is quoted in this report; approval arguments are not
  reproduced.

## Limits

- The cancel drills were direct run cancels, not a workflow-chain cancel; the SDD cancel path was
  not re-exercised at this head.
- F25's fix is not in this round; the finding is recorded with the fail-closed behaviour intact.
- The F27 mechanism (stale `.m2` view) is INFERRED; the observation and the restart recovery are
  measured.
