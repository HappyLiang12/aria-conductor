# Live SDD scenario on the qoder HOST core (2026-11-10)

This file records the option-3 live exercise: the same SDD scenario (issue #112) driven by
the qoder HOST core instead of opencode. It complements
`docs/reviews/evidence/2026-11-10-e2e-sandbox/e2e-sandbox-evidence.md`, which records the
opencode SANDBOX arm after the F-E2E-1 fix.

## 1. Provenance and environment

- Feature worktree `D:/project/aria-agent-os`, branch `feat/agent-os-memory`. The runtime
  is the fat jar rebuilt at `89c565f1`:
  `agent-control-tower/act-app/target/act-app-0.1.0-SNAPSHOT.jar`, 95,222,833 bytes,
  sha256 `3f14484b4d9f61326b8525590c3b7eb17d92ae7ba9bcbaed96d9423d61b58e0a`. Commits after
  it touch only tests and docs.
- Fresh H2 in `%TEMP%/s1-qoder/live`
  (`jdbc:h2:file:./data/act_db;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE`); Flyway applied the 79
  migrations on an empty schema, now at v82.
- Backend launched from the jar (never `spring-boot:run`) with the operator environment
  sourced in-process and never printed:
  `ARIA_CORES_QODER_EXECUTABLE=C:/Users/User/.qoder/bin/qodercli/qodercli.exe`,
  `ARIA_CORES_QODER_BRIDGE_ENTRY=D:/project/aria-agent-os/packages/qoder-acp-bridge/dist/main.js`
  (the built, git-ignored entry), `ARIA_CORES_QODER_MODEL=efficient`,
  `ARIA_CORES_TOOLCHAIN_PATH` = JDK 21 bin + Maven 3.9.6 bin + `C:/Program Files/GitHub CLI`,
  `GITHUB_TOKEN` from the operator's `gh` session, a per-attempt `PACK_CREDENTIAL_KEY`, and
  the run/result workspace roots under the same live directory. No `.env` file is read.
- Operator PAT: stored through the production route,
  `PUT /api/v1/cores/qoder/credential`, with the raw body read from a private temp file.
  The response was
  `{"credentialRef":"qoder:operator","coreId":"qoder","environmentVariable":"QODER_PERSONAL_ACCESS_TOKEN","configured":true,"maskedSecret":"****f1e1"}`.
  The value was never printed, logged or committed; the temporary file was deleted right
  after storing, and the key should be rotated after this exercise.

## 2. Smoke runs and toolchain

- Smoke `42a2e7ab-17b4-4d23-8907-1449adc85c96` (QA agent, qoder/HOST) reached `COMPLETED`
  with final output `READY`. (An earlier smoke `2a59a5ad` failed honestly because the
  bridge path in the launch environment had lost its backslashes; the error names the
  property and the resolved value. It was an operator environment defect, not a product
  defect, and was fixed by using forward slashes.)
- Toolchain `473e1835-fbe3-4529-b71c-6b63c40e9dc3`: the native ask
  (`ACP_PERMISSION`, tool `Bash`, risk `DESTRUCTIVE`) was created, approved through
  `POST /api/v1/approvals/{id}/decide {"approved":true}` (200 `processed`), and the run
  `COMPLETED` with `gh 2.90.0`, `Apache Maven 3.9.6` and `java 21.0.11` in the output.
- Fleet convergence: `PUT /api/v1/agents/runtime {"adkProvider":"qoder","executionMode":"HOST"}`
  answered `{"updated":4,...}`; BA, DEV, QA and Aria all read back `qoder | HOST`.

## 3. Chain and approvals

- Template `development-workflow` (`d0000001-0000-0000-0000-000000000001`, APPROVED) was
  instantiated through `POST /api/v1/knowledge/{id}/instantiate-workflow` with
  `{issueRef:"#112", issueRepo:"HappyLiang12/aria-conductor",
  repoUrl:"https://github.com/HappyLiang12/aria-conductor.git"}`. `workspaceRoot` and
  `branchName` are system placeholders; the branch is derived as `sdd/<chainId>`.
- Chain `0525d3df-d50b-4de7-9238-cd5d3bdb4865` (`development-workflow-instance`), three
  steps (BA, DEV, QA).
- BA `ebf0172d-831e-4f7b-a61b-4d43ec8110df` `COMPLETED` with a full `spec.md` ending in
  `SPEC_ID=d4eb6cbe-f0de-4775-8b8f-00c1ddf8abcf`. SPEC_REVIEW `97f17be0-2fe4-492a-bb36-62da31477712`
  was approved through the operator route (200 `processed`); the spec was committed on
  the branch as `9829eef8 sdd: approve spec`.
- DEV attempt 1 `c06fd03d-5476-4499-be56-cb6e10249cf2` was cancelled by the
  admission-frozen 45-minute deadline at `2026-11-10T14:09:34Z`. Before the cut its
  implementation was complete and its gates were green:
  `gate:act-common` rc=0 (11.8 s), `gate:act-execution` rc=0 (101 s),
  `gate:act-knowledge` rc=0 (17.7 s), `inner:act-app` rc=0 (19.1 s), and a second
  execution pass rc=0 (95.7 s); it had not committed. This is an operator-budget defect,
  not an agent or product defect.
- Operator remedy: `aria.tasks.deadline-minutes` raised 45 → 120 through
  `PUT /api/v1/system-config/aria.tasks.deadline-minutes`, and the step retried with
  `POST /api/v1/workflows/{id}/retry {"stepIndex":1}` as run
  `76981115-83c4-4669-a8a9-507e8dad1f38`.
- Native qoder asks surface as `ACP_PERMISSION` approvals. A loopback driver
  (`approval-driver.mjs`, 3-second poll) approves only `source == "ACP_PERMISSION"`; it
  never approves the SPEC_REVIEW gate or the harness `LEGACY_GATE` rows, so the human
  decision stays human. Every decision is logged with approval id, run id, tool, risk and
  response status in `approval-driver.log`.

## 4. Result

The retry `76981115-83c4-4669-a8a9-507e8dad1f38` completed at `2026-11-10T14:45:51Z`
and committed the implementation as `da4282e3 sdd dev` on the branch (10 files,
+1,204/−48); the QA step `6c557916-bd62-47f7-92de-ff51acdee42c` completed at
`15:03:31Z`, and the chain reached `COMPLETED` with steps `[COMPLETED, COMPLETED,
COMPLETED]` at `15:03:34Z`. No step failed, and no DEFECT or SPEC_GAP loop occurred.

**DEV output (run `76981115`).** Issue #112: `aria.workspaces.result-root` now has a single
owner in act-execution. New `RunArtifactReaderPort` (act-common) and
`WorkspaceRunArtifactReader` (act-execution), built from `RunWorkspaceService.resultRoot()`
— the same canonical path `capture` writes under — and `SpecReviewCoordinator` lost its
`@Value`, its `runResultRoot` field and its own path arithmetic. Measured gates, each at
the final committed state:

| command | result |
|---|---|
| `gate act-common` | rc=0 — 136 tests, 0 failures, 0 errors, 0 skipped |
| `gate act-execution` | rc=0 — 1,364 tests, 0 failures, 0 errors, 0 skipped |
| `gate act-knowledge` | rc=0 — 270 tests, 0 failures, 0 errors, 4 skipped (all in the untouched `SandboxExecutorTest`) |
| `inner act-app -Dtest=SddSpecReviewResultRootTest` | rc=0 — 2 tests, 0 failures, 0 errors, 0 skipped |

The run also fixed a real FK-ordering test defect (seed the `agents`/`runs` rows before
publishing the BA event) and regenerated the lane plan (`g4.txt`). The full report is
`docs/reviews/2026-11-10-issue-112-result-root-contract.md` on the branch; it records the
one NOT VERIFIED item honestly (Q5's live SDD run with a relocated result root).

**QA output (run `6c557916`).** The QA worker re-cloned the branch at `da4282e3`,
reproduced all four gates independently (same counts, plus coverage ratchets: line 0.8308
≥ 0.70 and branch 0.6490 ≥ 0.54 in act-knowledge), and mutation-checked AC2: pointing the
reader at its own root makes the context test fail, so the criterion is not vacuous. It
reported `VERDICT=PASS` and `REPORT_ID=db82f5ba-847e-4d4e-8a72-3da9e7c2cf03`, and
committed `f3dbeb03 sdd qa report` (`qa_report.md`, 260 lines, the only file in the diff).
Four non-blocking findings are in that report: `reader.resolve()` sits outside the
coordinator's try (latent, unreachable today), a self-contradictory `RunArtifactText.path`
comment in the spec, AC6's token half proven at the resolver rather than by a booted
context, and Q2/Q4/Q6 answered unilaterally by the implementer.

**Approvals.** Native qoder asks arrived as `ACP_PERMISSION` approvals: 140 for DEV and 57
for QA were approved by the loopback driver (each logged with approval id, run id, tool,
risk and response status in `approval-driver.log`); the SPEC_REVIEW gate was approved by
the operator, never by the driver.

**Branch state.** `sdd/0525d3df-d50b-4de7-9238-cd5d3bdb4865` carries `9829eef8` (spec),
`da4282e3` (dev) and `f3dbeb03` (qa report); the remote head is `f3dbeb03`. It was not
merged.

**Security.** The QA worker's credential-presence check echoed the `gho_…` GitHub token
into its own run transcript. That token is treated as exposed and must be rotated; it is
not reproduced here or in any committed file.

## 5. Residuals

- F-E2E-2 and the unauthenticated loopback reach (`aria.mcp.auth-mode=none`, INFERRED) are
  unchanged and recorded in the QA report.
- The qoder run usage counters are not meaningful for this exercise: the smoke runs
  reported `totalTokensUsed=0` even though the model answered. This is the same unknown
  usage surface recorded for the qoder core in S1; it is not used as evidence here.
- The qoder CLI writes its checkout under the engine's legacy per-run scratch directory
  (`data/workspaces/runs/scratch/<runId>/`), not the newer admitted workspace path. The
  runs are unaffected (the prompts are relative to the working directory), but the
  divergence is noted for the workspace follow-up.
