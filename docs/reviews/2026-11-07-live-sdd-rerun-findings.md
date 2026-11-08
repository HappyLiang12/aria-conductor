# Live SDD rerun on the hardening branch: confirmations and new findings

Date: 2026-11-07
Stack: local aria-conductor from `D:/project/aria-sdd-fixes` at code commit `74e568eb`
(H2 file DB copied from the dev stack, Flyway applied V76-V78, backend `:8080`, dashboard `:5173`),
qoder HOST core, model `efficient`, qodercli 1.1.61, fleet of 4 agents all `qoder/HOST`.
Exercise: let Aria pick an open issue in `HappyLiang12/aria-conductor` and run the APPROVED
`development-workflow` template (`d0000001-0000-0000-0000-000000000001`) end to end while the
operator babysits. This reruns the 2026-11-06 exercise after the hardening wave.

## Outcome

- Aria picked **issue #60** (git push exit 128, GH_TOKEN not used by HTTPS git).
- Chain `6dfb895c-e155-42b9-b753-a4752599faa9`, branch `sdd/6dfb895c-e155-42b9-b753-a4752599faa9`.
- BA `704137d7` COMPLETED 13:11 local; spec approved by the operator 13:11 (`repo/spec/spec.md` on the branch).
- DEV `d6e5db2e` COMPLETED 13:35; pushed `7fc325e9 sdd dev`.
- QA `bb326051` COMPLETED 13:41; report `7536c33d`; **VERDICT=DEFECT** (AC-8 not verified: pre-existing
  `SddWorkflowIntegrationTest` schema error plus Windows temp-directory errors in three test classes).
- DEV retry `9ded065f` COMPLETED 13:51; pushed `95aec569 sdd dev`.
- QA retry `22fc1f0c` COMPLETED 13:58; report `546018ca`; **VERDICT=DEFECT** (same pre-existing/
  environment failures; git-related tests `GitBranchConfigTest` 5/5 and `RunWorkspaceServiceTest` 40/40 pass).
- The chain rescheduled DEV again (`e217f2bd`); the operator cancelled the chain at 14:01 to stop an
  unbounded loop. The active DEV run was stopped by the cancel path and finalized FAILED at 14:01:58
  with `StopProof[allWritersStopped=false]` (`nothing was captured, destroyed or released`).
- Every run reports `totalTokensUsed = 0`; the Aria run took 3 iterations, each chain step 1.

Remote branch commits (GitHub API):

```
12f0f3f6 sdd: approve spec
7fc325e9 sdd dev
7536c33d sdd qa report
95aec569 sdd dev
546018ca sdd qa report
```

## Confirmations (each with evidence)

| Finding | Observed |
|---|---|
| F2 sync chat honesty | `POST /api/v1/aria/chat` returned `{"runId":"356e3f40...","status":"RUNNING","message":"Run ... is still RUNNING. It has not completed; follow it in the Runs dashboard or use streaming chat for live updates."}` - no false "completed". |
| F7 approval payload | The first native ask persisted `toolName=Bash`, `riskTier=READ`, the redacted/bounded argument document, `optionsJson` (`proceed_once`/`cancel`), `expiresAt` and the linked card. |
| F11 parameterised paths | DEV prompt: `git clone --branch sdd/6dfb... <repoUrl> repo (all paths below are under repo; when it is relative it is relative to your current working directory ...)`; QA prompt the same. No `/workspace` literal. |
| F13 host PATH | Aria's Bash resolved and ran `gh issue list --repo HappyLiang12/aria-conductor` and read issues #60/#56/#54; DEV ran `mvn test` through the same shell. The temporary `C:\Users\User\bin` shims were removed from the backend PATH for this run and never used. |
| F14 git credential | The BA bound checkout cloned 142 MB; the DEV and QA cloned the sdd branch and `git push` succeeded three times (`7fc325e9`, `7536c33d`, `95aec569`, `546018ca`). |
| F15 BA checkout | The BA read `.../workspaces/runs/scratch/704137d7/repo/agent-control-tower/...`; the spec it wrote is on the branch at `spec/spec.md` (10,153 B). |
| F17 audit | `GET /api/v1/runs/{id}/tool-calls` and `/trajectory` exposed live tool calls and results for the Aria, BA, DEV and QA runs. |
| F19 report commit | The QA report commits changed one file each (`qa_report.md`, +323 and +101/-54); the branch tree was not deleted. The guard's decision was not visible at INFO (INFERRED: additive diff passes). |
| F20 scratch isolation | Run scratch under `agent-control-tower/act-app/data/workspaces/runs/scratch/<runId>`; no operator repository write. |
| F21 `%SystemDrive%` | No literal `%SystemDrive%` directory in the BA scratch. |
| F22 gate spirit | The QA refused a PASS while AC-8 was NOT VERIFIED in both rounds and reported DEFECT with the unverified criterion named. The PASS+NOT VERIFIED branch was not triggered naturally. |
| F24 on cancel | NOT settled: after the cancel the chain's step cards (BA `8210dd40`, DEV `c68047eb`/`0e2cbabf`/`70b26ebe`, QA `5b2af83b`/`a8c296d5`) remained REVIEW with 0 pending asks, and the in-flight card `70b26ebe` only reached REVIEW after its run failed; see R6. |

## New findings

### R1 - A core-side clone still dies on Windows long paths, and leaves an empty index (critical)
- Observed: the DEV's `git clone` printed `error: unable to create file ...KnowledgeItemRepositorySliceIntegrationTest.java: Filename too long` / `fatal: unable to checkout working tree`; the agent worked around it with `git config core.longpaths true` + `git restore --source=HEAD :/`.
- The failed checkout left 265 files missing and the index empty; `git status` reported 1727 staged deletions. The operator restored the 265 paths and re-added them before the agent committed, so the branch kept its tree.
- The GIT_CONFIG_COUNT/KEY/VALUE pairs F12 relies on were passed as `--child-env` but did not prevent the failure in the core's Bash; the effective environment of tool commands needs verification (INFERRED: the CLI's Bash tool does not inherit those pairs).
- Candidate: set `core.longpaths=true` in the run workspace's git config during provisioning and verify the child env actually reaches git.

### R2 - Maven cannot create a temp directory in the core's Bash (high)
- Observed: `ForkStarter IOException: Unable to create temporary directory C:\WINDOWS\surefire-User` on every `mvn test`; the DEV had to run `TEMP=/tmp/mockito-test TMP=/tmp/mockito-test mvn test -Djava.io.tmpdir=/tmp/mockito-test`.
- Root cause: the launcher clears the environment and the bridge's allowlist carries only PATH/SystemRoot; Java falls back to `C:\WINDOWS` as `java.io.tmpdir`.
- Fixed in this wave: `CoreLaunchEnvironment.childEnvironment` now carries TEMP/TMP from the host on Windows
  (`CoreLaunchEnvironmentTest` 15/0). Not re-exercised live yet.

### R3 - Token usage stays 0 for every qoder run (medium, accepted residual)
- All seven runs (Aria, BA, DEV x2, QA x2, failed DEV) report `totalTokensUsed = 0`, `inputTokens`/`outputTokens` absent.
- The bridge relays what the CLI reports; the pinned 1.1.61 frames carry a placeholder zero (or no usage) here. The F9 fix cannot invent counts. Recorded as an observability residual, not a regression.

### R4 - The assistant can decide its subagents' permission asks (governance, high)
- Observed: Aria called `mcp__aria-conductor__decide_approval` with `{"approvalId":"01370c3e...","approved":true}` to approve the BA's `Write spec.md` ask. That call itself surfaced as a native ask, which the operator denied.
- The barrier is only the permission ask for the decide tool; an operator who approves it lets the assistant grant its own/subagents' asks. Decide whether the assistant may hold this tool at all, or restrict it to non-native/gate approvals with an operator-only native path.

### R5 - A DEFECT reschedule lost the QA report (high)
- Observed: the DEV retry prompt's feedback was `Feedback from the previous round: verdict from output marker`; the QA's actual findings were not passed. The retry could not see what failed.
- Root cause: the marker path passed the routing placeholder as the reschedule reason.
- Fixed in this wave: the marker feedback now carries up to 6,000 characters of the QA output
  (`WorkflowAutoChainerQaVerdictGateTest` 14/0).

### R6 - A manual chain cancel never settles its cards (medium)
- Observed: after `POST /api/v1/workflows/{id}/cancel`, the chain's step cards stayed in REVIEW with 0 pending asks (and the in-flight card was IN_PROGRESS at cancel time).
- Root cause: `cancelWorkflow` publishes `WorkflowCancelledEvent`, while `WorkflowCompletionCardListener` settles only on `WorkflowAdvancedEvent`; and the in-flight run's card only moves to REVIEW after the run is finalized, i.e. after the cancel event.
- Candidate: settle REVIEW cards on `WorkflowCancelledEvent` and settle a finalized run's card when its chain is already terminal.

### R7 - The cancel path finalizes the active run FAILED on an unverifiable stop proof (medium)
- Observed: the cancelled chain's active run ended FAILED with
  `Run ... was not finalized: the backend did not verify that all writers stopped (StopProof[...allWritersStopped=false]); nothing was captured, destroyed or released`.
- The safe degradation worked (no capture/destroy/release), but the operator sees FAILED for a cancel, and the stop proof cannot be satisfied for this core. Candidate: confirm the writers' identity for the qoder bridge and record the stop as cancelled-with-warning when the proof is only "nothing to lose".

### R8 - No convergence path for a pre-existing failure (medium)
- Both QA rounds honestly returned DEFECT because AC-8 (the whole suite green) is blocked by failures that
  predate the change (the `INPUT_TOKENS` schema error we fixed on this branch is still on `main`) and by the
  Windows temp gap. The chain has no round cap and no operator way to accept a pre-existing failure, so it
  loops until cancelled. Candidate: a bounded retry count plus an operator "accept known failure" decision
  recorded on the DoD review.

## Evidence files

- `live-rerun-backend.log` (backend), `live-rerun-approver.log` (every approved ask)
- `live-rerun-aria-chat.json`, `live-rerun-{aria,dev,dev2,qa,qa2}-traj.json` (captured API responses)
- `live-rerun-missing.txt` (the 265 paths restored after R1)
- Branch `sdd/6dfb895c-e155-42b9-b753-a4752599faa9` on `HappyLiang12/aria-conductor`

All timestamps above are local CST (UTC+8); API payloads use UTC.
