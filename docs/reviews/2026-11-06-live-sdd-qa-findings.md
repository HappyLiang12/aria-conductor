# Live SDD exercise: observed bugs, UX issues and enhancement candidates

Date: 2026-11-06
Stack: local aria-conductor, backend `:8080` (h2, qoder HOST core, model `efficient`, qodercli 1.1.61),
dashboard `:5173`, OpenSandbox container `aria-opensandbox`.
Exercise: let Aria pick an open GitHub issue in `HappyLiang12/aria-conductor`, then instantiate the
APPROVED `development-workflow` template (`d0000001-0000-0000-0000-000000000001`) and babysit the
BA -> SPEC_REVIEW -> DEV -> QA chain.

Status: exercise completed 2026-11-07 00:40 local. Every finding below is either an observed response
body, a session tool result, or a commit/contents lookup; anything inferred is labelled.

Environment note: to keep the exercise moving, the operator temporarily added `mvn`, `gh` and `java`
wrapper scripts to `C:/Users/User/bin` (a POSIX entry in the core's reduced PATH) because the qoder
core cannot see the host toolchain. They were removed after the run. The product gap behind that
workaround is F13.

## Findings

### F1 - Qoder core credential is not adopted from an existing qoder CLI login (onboarding, medium)
- Observed: the first Aria run failed with `RUNTIME_CREDENTIAL_NOT_CONFIGURED` even though the host
  is logged in to the qoder CLI. The operator must `PUT /api/v1/cores/qoder/credential` with the PAT
  before any qoder HOST run can start.
- Evidence: run `46650059` -> `status FAILED`, `errorCode RUNTIME_CREDENTIAL_NOT_CONFIGURED`.
- Candidate: read the CLI session/credential on startup, or surface a "Use local qoder login" action in
  Configure instead of requiring a raw PAT paste.

### F2 - Aria sync chat reports success while the underlying run is still running or failed (UX, high)
- Observed: sync `POST /api/v1/aria/chat` returned "I've completed your request..." while the run was
  still RUNNING; later it returned "Still working - reasoning models can take a little longer..." with
  no run id and no link to the run's error state.
- Evidence: run `e823008f` (RUNNING at the time of the "completed" reply), and the same run's chat
  reply when the instantiation had actually failed.
- Candidate: include the run id and status, and link the chat bubble to the Runs drawer; never say
  "completed" before the run reaches a terminal status.

### F3 - `aria.cores.qoder.bridge-entry` default is cwd-relative and its own error hint is circular (config, medium)
- Observed: launching from `act-app/` fails with
  `...configured value does not resolve to one: packages/qoder-acp-bridge/dist/main.js
  (resolved: ...\act-app\packages\qoder-acp-bridge\dist\main.js)`, but the hint recommends
  "leave the property unset to use the default path" - the default is the same relative string.
- Evidence: run `65f81daf` -> `status FAILED`, error message above.
- Mitigation already present: `scripts/start-backend.ps1` exports an absolute
  `ARIA_CORES_QODER_BRIDGE_ENTRY` when the built bridge exists (`scripts/start-backend.ps1:39-42`).
- Candidate: resolve the default against the repository root, and remove the "unset it" advice when the
  default is the failing value.

### F4 - `aria.cores.qoder.executable` default `qoder` does not resolve to the versioned CLI on Windows (config, medium)
- Observed: a HOST run needs the explicit versioned executable
  `C:/Users/User/.qoder/bin/qodercli/qodercli-1.1.61.exe`; the bare `qoder` default was not enough in
  this session's environment.
- Evidence: backend restart in this session exports `ARIA_CORES_QODER_EXECUTABLE` to the versioned path;
  without it the core could not launch.
- Candidate: discover the qodercli executable next to the bridge/repo, or fail with a path-preflight
  message that names the expected location.

### F5 - Wrong HTTP status for client errors: 500 instead of 400/405 (API, low)
- `GET /api/v1/runs/46650059` -> HTTP 500 with
  `MethodArgumentTypeMismatchException: Failed to convert value of type 'java.lang.String'
  to required type 'java.util.UUID'`.
- `GET /api/v1/packs/pack-git-0001/credentials` (unsupported method) -> HTTP 500 with
  `HttpRequestMethodNotSupportedException: Request method 'GET' is not supported`.
- Candidate: map `MethodArgumentTypeMismatchException` to 400 and
  `HttpRequestMethodNotSupportedException` to 405 in the shared exception handler.

### F6 - Long synchronous Aria chat holds a DB connection long enough for Hikari to log a leak (backend, medium; tracks issue #58)
- Observed: `HikariPool-1 - Previously reported leaked connection ... was returned to the pool
  (unleaked)` after a long sync chat.
- Evidence: backend log during run `e823008f`.
- Candidate: keep the sync chat path off the request-scoped connection while waiting on the core
  (or default the UI to the SSE endpoint).

### F7 - ACP permission approvals are per-tool, serial and argument-blind (governance UX, high)
- Observed: each native tool call (Bash, then MCP `instantiate_workflow_template`) paused the run with
  a separate PENDING approval. The approval payload has `toolName: null`, `arguments: null`; the only
  human-readable detail is the `reason` string, and the automatically created kanban card truncates
  that JSON at ~180 characters.
- Evidence: approvals `417bcfe5`, `a1a6b136`, `5e47aeb8`; kanban `7139f3cf` description truncated
  mid-JSON (`"toolName":"mcp__ar...`).
- Candidate: render the tool name and its arguments (at least the MCP parameters) in the approval row;
  offer "allow for this session/tool" or a batch approve for read-only tools. At minimum do not
  truncate the stored description.

### F8 - Git credential store takes effect only after a backend restart, and the dashboard has no editor (config UX, high)
- Observed: `POST /api/v1/packs/pack-git-0001/credentials` returned `{"status":"stored"}`, but the
  workflow instantiation kept failing until the backend restarted. `GitBranchConfig` resolves the token
  once in an eager `@Bean` (`GitBranchConfig.java:47-82`).
- The startup preflight already admits the gap: "Set GITHUB_TOKEN and restart the backend: the credential
  is resolved once at startup. The dashboard has no credential editor yet..." (`scripts/start-backend.ps1:103-104`).
- Candidate: add a Git credential editor to Configure; either resolve the token per operation or make the
  store endpoint return an explicit "restart required" state in the UI.

### F9 - Real qoder runs report `totalTokensUsed = 0` (observability, medium)
- Observed: run `e823008f` completed with `iterationCount 3`, `totalTokensUsed 0`.
- Impact: per-run cost is invisible; the Runs dashboard shows zero usage for real LLM work.
- Candidate: surface provider usage from the bridge, or label the field as "not reported by this core".

### F10 - Aria's Bash tool cannot run `gh` in the backend environment (tooling, medium)
- Observed: Aria reported "The `gh` CLI isn't available in this environment" and fell back to a
  hard-coded candidate shortlist. The host shell has an authenticated `gh`.
- Impact: the "Aria picks a GitHub issue" flow cannot actually inspect issues unless the operator
  supplies a shortlist.
- Candidate: preflight the backend PATH for `gh` (or expose issue listing through an MCP tool instead of
  a shell call); if unavailable, say which path was searched.

### F11 - The SDD dev/QA prompts hardcode sandbox paths that break Windows HOST mode (workflow template, high)
- Observed: the instantiated step prompts say `git clone --branch sdd/<chain> ... /workspace/repo`
  and "read the spec at /workspace/repo/spec/spec.md". In HOST mode on Windows the clone resolves to
  `C:/Program Files/Git/workspace/repo` and fails:
  `fatal: could not create leading directories ... Permission denied` (exit 128). `mkdir -p /workspace`
  fails the same way.
- Evidence: dev run `5bc8169d`, qoder session tool results; the chain prompt preview for steps 1 and 2
  (`GET /api/v1/workflows/9f3d06d3-...`).
- The agent recovered only because it improvised: clone into `repo/` relative to its scratch dir and
  use absolute Windows paths.
- Candidate: parameterise the checkout root per execution mode (sandbox `/workspace` vs host scratch
  dir) and never embed a fixed absolute path in the template.

### F12 - Windows long paths break the agent's first clone (host tooling, medium)
- Observed: `git clone` of the repo fails to check out two
  `Knowledge*RepositorySliceIntegrationTest.java` files with `Filename too long`; the agent had to set
  `git config core.longpaths true` and reset. A second clone had already failed.
- Evidence: dev run `5bc8169d` session tool results (`error: unable to create file ... Filename too long`).
- Candidate: have the bridge set `core.longpaths=true` for run-owned repositories on Windows, or use
  `git -c core.longpaths=true clone`.

### F13 - The core's Bash PATH is a reduced PATH: `gh`, `jq` and `mvn` are all missing (host tooling, high)
- Observed PATH inside the qoder core shell:
  `/c/Users/User/.qoder/plugins/cache/...:/c/Users/User/.local/bin:/c/Users/User/bin:/mingw64/bin:/usr/local/bin:/usr/bin:/bin:/d/bin:...`
  - `gh` (installed at `C:/Program Files/GitHub CLI/gh.exe`) -> not found
  - `jq` -> not found
  - `mvn` (installed at `C:/Users/User/tools/apache-maven-3.9.6/bin`) -> not found
- Impact: the BA prompt's prescribed `gh issue view #81 ...` cannot run; the Dev prompt's
  `mvn test ...` from AGENTS.md cannot run. Both agents burned turns discovering this and worked
  around it only with absolute paths / unauthenticated curl / a background `find`.
- Evidence: BA run `5b249b0f` (`which: no gh in (...)`), dev run `5bc8169d` ("Maven is not in the PATH"),
  and the eventual absolute-path test command.
- Candidate: the bridge should pass the host PATH through (or append a configured toolchain PATH) for
  HOST mode; run a preflight that lists missing required tools per workflow step.

### F14 - Workflow agents get GH_TOKEN/GITHUB_TOKEN values that GitHub rejects 401, while the git pack credential is valid (credential model, high)
- Observed: inside the BA core shell both `GH_TOKEN` and `GITHUB_TOKEN` are non-empty, but
  `curl -H "Authorization: token $GITHUB_TOKEN" https://api.github.com/repos/.../issues/81` returns
  `{"message":"Bad credentials","status":"401"}`. The prompt still says "GH_TOKEN is already
  configured". The same request without the Authorization header succeeds (public repo).
- The credential stored through the documented pack endpoint is valid (it created branch
  `sdd/9f3d06d3-...` at `39005c86` through `GitBranchService`), so the core is not seeing that value.
- Evidence: BA run `5b249b0f` session tool results; `git ls-remote`/branch lookup on the remote.
- Candidate: one credential resolution path - inject the resolved pack credential into the core
  environment (as `GH_TOKEN`/`GITHUB_TOKEN`) and do not pass through unrelated ambient values; or stop
  claiming the token is configured in the prompt.

### F15 - BA starts in an empty scratch directory, not a checkout (workflow workspace, medium)
- Observed: `pwd && ls -la` in the BA run returns
  `.../data/workspaces/runs/scratch/5b249b0f-...` with `total 4` (empty). The agent then Grepped and
  Read the operator's live worktree at `/d/project/aria-conductor` to analyse the issue.
- Impact: the BA spec depends on whatever branch the operator happens to have checked out; the
  "workspace" is not isolated to the target revision.
- Candidate: give the BA step a checkout of the target branch (the spec branch already exists) or
  explicitly state that only the issue body is provided.

### F16 - `POST /api/v1/runs/{id}/inject` does not reach a running core (operator tooling, medium)
- Read from `RunToolCallController.java:43-58`: the endpoint only appends a `SessionTrajectory` row; there
  is no delivery to the live qoder session. An operator cannot supply a missing hint (tool path, issue
  body) without restarting the run.
- Candidate: route inject through the run coordinator / core session, or document it as a
  trajectory-only annotation and remove the impression of live steering.

### F17 - Qoder HOST runs have no tool-call or trajectory audit through the product API (observability, high)
- Observed: `GET /api/v1/runs/5b249b0f-.../tool-calls` returns `[]` and `/trajectory` returns only the
  initial user prompt, while the qoder CLI session log for the same run contains every tool call,
  result and thinking block. Operators must read `~/.qoder/projects/...` to know what an agent did.
- Evidence: empty API responses vs
  `C:/Users/User/.qoder/projects/D--project-aria-conductor-...-5b249b0f.../<session>.jsonl`.
- Candidate: persist the bridge's ACP tool-call events into `tool_call`/`session_trajectory` so the Runs
  drawer, approvals and audit log show the same record.

## Exercise result (2026-11-07 00:40 local)

- Aria picked issue **#81** (`[opencode] Expected execd warmup retry logs a full ERROR stack trace`).
- Chain `9f3d06d3-f3a9-4666-af7f-3e8a1baf72ad` was instantiated from `development-workflow` and reached
  `COMPLETED`.
  - BA (run `5b249b0f`): spec written and approved; branch `sdd/9f3d06d3-...` created at `39005c86`.
  - Dev (run `5bc8169d`): added the `CommandsAdapter` logger to
    `act-dashboard-api/src/main/resources/logback-spring.xml`; ran `*OpenCode*` (57 passed) and
    `act-dashboard-api` (90 passed); commit `451c4867` ("sdd dev") pushed.
  - QA (run `8c51fa8f`): report written to `qa_report.md`; final answer `VERDICT=PASS`,
    `REPORT_ID=qa-9f3d06d3-2026-11-07-1793983091`.
- Remote branch head `59055b9c` ("restore files deleted by bad merge") with all root files plus
  `spec/` and `qa_report.md`. No PR was opened and nothing was merged.

## Findings found during the run

### F18 - Legacy run review requests are created per step and never gate or expire (governance UX, medium)
- Every workflow step produced a `REVIEW_REQUEST` / `LEGACY_GATE` approval (BA `55737967`, Dev
  `75910d4b`, QA `9060a1af`). The chain auto-advanced and completed while all three were still
  `PENDING`; they only clutter the approvals queue and require manual clearing.
- Candidate: either suppress them when the auto-chainer owns the gate, or surface them as a
  post-hoc "run review" task with a clear owner.

### F19 - The QA report commit deleted the entire repository tree on the chain branch (workflow safety, critical)
- Commit `984df552` ("sdd qa report", parent `451c4867`) removed **300 files** and left the branch
  tree containing only `qa_report.md`. The QA agent's clone had suffered the Windows long-path
  checkout failure, and it committed from that partial state after its reset/restore attempts.
- The branch was repaired by the agent itself with `59055b9c` ("restore files deleted by bad merge"),
  which restored all files and kept `qa_report.md`; verified via the GitHub contents API (22 root
  entries).
- The chain did not validate the branch tree before accepting the step. Candidate: after a step
  pushes, compare the branch tree with its parent (file-count/removal guard) and fail the step if a
  "report/docs" task removes source files; and make the QA step commit with an explicit pathspec
  (`git commit -- qa_report.md`) instead of the index.

### F20 - The run scratch directory resolves to the operator's git repository (isolation, high)
- QA scratch: `.../data/workspaces/runs/scratch/8c51fa8f-.../` lives inside the operator checkout.
  `git rev-parse --show-toplevel` executed there returned `D:/project/aria-conductor`.
- During the QA loop the agent ran bare `git reset --hard HEAD` / `git restore --source=HEAD :/`
  from the scratch root (no `cd repo`). In this session the live checkout was unaffected (branch
  `feat/uiux-decision-first` remained at `b69d8937`; reflog checked), but a dirty operator tree would
  have been reset.
- Candidate: give each run an isolated work root outside the operator repository, or set
  `GIT_CEILING_DIRECTORIES` / a per-run `GIT_DIR` so bare git calls cannot escape.

### F21 - A literal `%SystemDrive%` directory is created in the QA scratch (workspace prep, low)
- `ls -la` in the QA scratch shows an empty directory named `%SystemDrive%` alongside `repo/` and
  `qa_report.md`. An unexpanded Windows environment variable was used in a path. Mechanism not
  traced in this session (INFERRED: run workspace preparation or the agent's first commands).
- Candidate: expand or reject `%VAR%` tokens when building run paths; add a workspace hygiene test.

### F22 - The QA verdict PASS was accepted while AC4 was NOT VERIFIED and tests errored (gate integrity, high)
- QA report: `act-dashboard-api` "90 tests, 85 errors, 0 failures"; `*Sandbox*` "75 tests, 57 errors,
  0 failures"; AC4 "NOT VERIFIED (environment limitation)"; final `VERDICT=PASS`; the chain completed.
- The same tests passed for the Dev in the same environment once `JAVA_HOME`, `TEMP` and
  `core.longpaths` were set (`57` OpenCode + `90` dashboard-api tests passed), so the QA's
  "environment limitation" was self-inflicted, not a platform limit.
- This contradicts the repository's evidence discipline (a PASS requires every criterion evaluated).
  Candidate: the auto-chainer should refuse a PASS marker when the report contains "NOT VERIFIED";
  the QA prompt should require a successful test command or an explicit DEFECT verdict.

### F24 - Completed workflow steps leave their kanban cards in REVIEW with zero pending asks (board UX, low)
- After the chain reached `COMPLETED`, the three step cards remained
  `REVIEW` with `pendingAskCount = 0`:
  `9d57be41` (BA), `d3e7f97c` (Dev), `c61d0bb7` (QA). The board therefore shows finished work as
  awaiting review, with no ask to act on.
- Candidate: on chain completion, move step cards to `DONE` (or a dedicated completed state), or
  render the card's `runOutcome`/chain status so REVIEW means "needs a human" only.

## Positive observations

- The ACP permission gate works end to end: the run parks, the approval appears in the dashboard queue,
  and `POST /api/v1/approvals/{id}/decide` resumes it.
- A clarification question (`askType QUESTION`, `source CLARIFICATION`) correctly pauses the run and
  accepts a free-text `answer`.
- The credential error text names the exact remediation (`GitCredentialGuidance`) instead of failing
  silently.
- A backend restart did not leave run `e823008f` stuck in RUNNING; it remained terminal (COMPLETED).

## Reproduce

```bash
curl -s http://localhost:8080/api/v1/runs/46650059            # F5 (500, not 400)
curl -s http://localhost:8080/api/v1/packs/pack-git-0001/credentials  # F5 (500, not 405)
curl -s http://localhost:8080/api/v1/approvals?status=PENDING # F7 payload shape
```
