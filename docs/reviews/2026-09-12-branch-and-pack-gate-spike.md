# Branch and Pack Gate Feasibility Spike (gaps 5 and 6)

- **Date:** 2026-09-12 (runs executed 2026-09-12 late evening / 2026-09-13 local time)
- **Branch:** `feat/e2e-gap-closure`
- **Question:** can SDD **branch creation/protection** (gap 5) and a **blocking git-pack PUSH gate**
  (gap 6) be exercised hermetically under CI (H2, no OpenSandbox, no external GitHub, no LLM key)?
- **Method:** read the mechanism in source, then run the cheapest hermetic substitute against the
  already-running local stack. Nothing was started or restarted; no external service was contacted
  or authenticated to.

## Verdict

| # | Gap | Verdict | Evidence |
|---|-----|---------|----------|
| 5 | SDD branch creation / protection | **LOCAL-ONLY — not hermetically testable.** Needs a real GitHub remote plus `GH_TOKEN`; there is no configurable API base URL and no branch-protection feature exists to exercise. | §Gap 5 |
| 6 | Run blocked on the git-pack PUSH gate, then resumed | **HERMETICALLY TESTABLE — proven.** A loopback OpenAI-compatible mock supplies the model, a bare repo on disk is the push target, and the gate blocks and resumes for real. | §Gap 6 |

Specs: `e2e/api/git-pack-gate.api.spec.ts` (runnable, runs in CI) and
`e2e/api/branch-governance.api.spec.ts` (skips with the reason in the skip message; its body was
**not executed** here — see NOT VERIFIED).

---

## Gap 6 — git-pack PUSH gate

### What actually gates the behaviour

1. `git_push` is registered with `riskTier=PUSH` and `status=APPROVED`, `enabled=true`
   (V33 seed). Observed:

   ```
   $ curl -s http://localhost:8080/api/v1/tools -o /tmp/tools.json && node -e "JSON.parse(require('fs').readFileSync('/tmp/tools.json','utf8')).filter(t=>t.name.startsWith('git')).forEach(t=>console.log(t.name,'|',t.riskTier,'|',t.status,'| enabled=',t.enabled,'| pack=',t.packId,'| sandboxMode=',t.sandboxMode))"
   git_status | READ | APPROVED | enabled= true | pack= pack-git-0001 | sandboxMode= NONE
   git_diff | READ | APPROVED | enabled= true | pack= pack-git-0001 | sandboxMode= NONE
   git_log | READ | APPROVED | enabled= true | pack= pack-git-0001 | sandboxMode= NONE
   git_add | WRITE_LOCAL | APPROVED | enabled= true | pack= pack-git-0001 | sandboxMode= NONE
   git_commit | WRITE_LOCAL | APPROVED | enabled= true | pack= pack-git-0001 | sandboxMode= NONE
   git_checkout | WRITE_LOCAL | APPROVED | enabled= true | pack= pack-git-0001 | sandboxMode= NONE
   git_clone | WRITE_LOCAL | APPROVED | enabled= true | pack= pack-git-0001 | sandboxMode= NONE
   git_push | PUSH | APPROVED | enabled= true | pack= pack-git-0001 | sandboxMode= NONE
   git_create_pr | PUSH | APPROVED | enabled= true | pack= pack-git-0001 | sandboxMode= NONE
   git_reset_hard | DESTRUCTIVE | APPROVED | enabled= true | pack= pack-git-0001 | sandboxMode= NONE
   git_force_push | DESTRUCTIVE | APPROVED | enabled= true | pack= pack-git-0001 | sandboxMode= NONE
   ```

   (The same command with `t.name.startsWith('git')` replaced by a single-name match shows
   `request_approval | READ | APPROVED | enabled= true | pack= null | sandboxMode= NONE`.)

2. The tier is what forces the human gate:
   `ToolRiskResolver.requiresApproval` (`act-execution/.../pipeline/ToolRiskResolver.java:37-40`)
   returns true for `PUSH`/`DESTRUCTIVE`; `ActionClassifier.classify`
   (`.../pipeline/ActionClassifier.java:22-27`) turns that into `ActionClassification.highRisk`;
   `ActionExecutionPipeline.execute` stage 4 (`.../pipeline/ActionExecutionPipeline.java:90-104`)
   sets `Run.status=PAUSED` (`:93`) and blocks on `ApprovalGate.requestApproval`.
3. `ApprovalGate.requestApproval` (`.../approval/ApprovalGate.java:176-235`) persists the PENDING
   `Approval` (linked to the pre-created `ToolCall`), publishes `ApprovalRequestedEvent`, and parks
   the virtual thread on a `CompletableFuture`. `decideApproval` (`:240-278`) flips the approval to
   `APPROVED`/`DENIED`, sets the tool call to `EXECUTING`/`DENIED` (`:259-264`), and completes the
   future. This is the block/resume mechanism the gap is about.
4. The tool call itself is created before the pipeline runs
   (`.../engine/AgentLoopEngine.java:1051-1060`) and executed through the pipeline at `:1072-1073`;
   the status mapping back onto it is at `:1076-1106`.
5. Execution is a real `git push` (`.../tool/handlers/GitPackHandler.java:120-127`, argv form) run
   by `ProcessBuilder` with `directory = workspaceDir` (`:149-191`). The workspace is the run's
   isolated dir: `ToolExecutionEngine.executeViaHandler` injects `_workspaceDir` from
   `WorkspaceManager.getOrProvision` (`.../tool/ToolExecutionEngine.java:96-113`,
   `.../tool/WorkspaceManager.java:53-61`), i.e. `<workspace-root>/<runId>`.

### What external dependency it needs — and why the answer is "none"

| Need | Why it looked blocking | Hermetic substitute used |
|------|------------------------|--------------------------|
| A model that emits a `git_push` tool call | An action can only come from a provider response; CI has no LLM key | Loopback OpenAI-compatible mock. `LangChainAdkProvider.buildRequestBody` (`.../adk/LangChainAdkProvider.java:395-408`) takes `llm_base_url` and the model from the **active DB `LlmProvider` row**, and `resolveAdkApiKey` (`:470-478`) takes the key from the same row; the Python runtime forwards them to `ChatOpenAI` (`langchain-adk/src/agent.py:357,378-381`). Create + activate a temporary provider pointing at `127.0.0.1:<port>/v1` (`act-agent/.../service/LlmProviderService.java:27,81`) and the mock is the model. |
| A git remote to push to | The pack runs `git push <remote> <branch>`; a real remote means GitHub | A bare repository created on disk by the spec (`git init --bare <tmp>/remote.git`); the spec turns the run workspace into a git repo whose `origin` is that path while the gate is open, so the approved push lands locally and is read back with `git rev-parse`. |
| An OpenSandbox | The default provider is opencode/task-capable | Used a `langchain` ADK agent (`supportsTaskExecution=false`, observed below), so the run goes through the turn loop where the `ActionExecutionPipeline` gate lives. |
| LLM key | not needed | The mock answers any key. |

Observed provider inventory (the langchain provider is what makes the turn loop reachable):

```
$ curl -s http://localhost:8080/api/v1/adk/providers
[{"id":"langchain","displayName":"LangChain ADK","supportsTaskExecution":false,"isDefault":false},
 {"id":"opencode","displayName":"OpenCode","supportsTaskExecution":true,"isDefault":true}]
```

### What was run, and what happened

Probe (throwaway, `%TEMP%\aria-spike-probe.js`, not committed — superseded by the committed spec):
create the mock + a temporary provider, activate it, seed an ADK(langchain) dev agent, start a run.
Captured output (verbatim excerpt):

```
[probe] mock listening on 127.0.0.1:18091
[probe] previously active provider: DeepSeek a4e9fa4d-fe8f-4125-8858-74291fec7358
[probe] create provider: 201 378b47a0-c0ba-497f-9fa9-d11272eb2126
[probe] activate provider: 200 true
[probe] create agent: 201 9faa34ff-13db-437c-84c6-f45a62076e16 provider= langchain
[probe] start run: 201 f68f8824-9693-4815-ba86-6ebbfe987372
[mock] #1 /v1/chat/completions safety=false toolResult=false tools=27
[mock] #2 /v1/chat/completions safety=true toolResult=false tools=0
[probe] PENDING approval: {"id":"f316331b-...","toolCallId":"593d630e-...","reason":"Agent requests approval to execute git_push {\"branch\": \"e2e-gate-probe\"}","approvalType":"TOOL_CALL"}
[probe] tool calls while blocked: [{"toolName":"git_push","result":null,"status":"PENDING",...}]
[probe] run status while blocked: PAUSED
[probe] decide approve: 200
[probe] tool calls after approve: [{"toolName":"git_push","result":"ERROR: Exit code: 1\nerror: src refspec e2e-gate-probe does not match any\nerror: failed to push some refs to 'https://github.com/HappyLiang12/aria-conductor.git'","status":"FAILED",...}]
[probe] run status after approve: COMPLETED
[probe] restore previously active provider: 200
[probe] delete mock provider: 204
```

The probe proved the gate is reachable and that approval resumes the run. It also produced the first
failed attempt worth recording: the push was executed **not** in a prepared workspace but through
the enclosing checkout — `agent-control-tower/act-app/data/workspaces/<runId>` sits inside the
aria-conductor working tree, so an unprepared workspace inherits that repo's `origin`
(`https://github.com/HappyLiang12/aria-conductor.git`) and the push failed on refspec resolution
before any network call. A wrong workspace root therefore fails loudly, never silently.

**Committed spec, RED first.** With the mock emitting a READ-tier `git_status` instead of `git_push`
(one-line edit, then reverted), no PENDING ask is ever created for the run:

```
$ npx playwright test e2e/api/git-pack-gate.api.spec.ts --project=api
Error: pollUntil timed out for /approvals after 120000ms; last=[{"id":"83515152-...","runId":"ec8b360d-...","toolCallId":null,"status":"EXPIRED","reason":"Run cancelled",...}]
  at pollUntil (agent-control-tower/act-dashboard/e2e/fixtures.ts:196:9)
  at .../e2e/api/git-pack-gate.api.spec.ts:210
  1 failed
```

**GREEN.** With `git_push` restored:

```
$ npx playwright test e2e/api/git-pack-gate.api.spec.ts --project=api
Running 1 test using 1 worker
  ok 1 [api] › e2e\api\git-pack-gate.api.spec.ts:192:1 › a run blocks on the git_push PUSH gate and resumes after approval, pushing to the local remote (8.6s)
  1 passed (9.9s)
```

Reproduced 5×: 3 standalone runs plus one inside the full suite
(`ok 204 [api] › e2e\api\git-pack-gate.api.spec.ts:192:1 › … (8.7s)`) and once more after the final
revision (`1 passed (7.5s)`).

**Full-suite context (local, one worker set, 11.3m).** `npx playwright test`:

```
  4 failed
    [chromium] › e2e\crew-telemetry.spec.ts:107:3 › Crew Telemetry Display › should show zero values for agents with no activity
    [chromium] › e2e\housekeeping-cleanup.spec.ts:42:3 › Housekeeping cleanup › kanban quick-clear removes finished cards via confirm modal
    [chromium] › e2e\review-decision-zone.spec.ts:37:1 › clicking Deny in the decision zone resolves the ask
    [chromium] › e2e\workflow-ui-interaction-e2e.spec.ts:165:1 › 5. FAILED workflow → Retry button visible with step number
  26 skipped
  10 did not run
  204 passed (11.3m)
```

None of the four can come from this spike's spec: all four failed **before** `git-pack-gate` ran
(their indices are #57, #60, #113 and #189; the new spec is #204 and passed), and this task adds no
production code. Classification of the four, from isolated re-runs and the artefact in each failure:

- `crew-telemetry.spec.ts:107` and `review-decision-zone.spec.ts:37` **passed in isolation**
  (`npx playwright test e2e/crew-telemetry.spec.ts e2e/housekeeping-cleanup.spec.ts e2e/review-decision-zone.spec.ts --project=chromium` → `1 failed / 9 passed`) — parallel-run interference on a
  shared dirty DB, not a regression.
- `housekeeping-cleanup.spec.ts:42` fails in isolation too, and for an environment reason: the
  failure is `pollUntil timed out for /runs/9c0b380a-…` with `"status":"PAUSED"` and a PENDING ask
  `Agent requests approval to execute shell_exec {"command": "pwd && ls -la"}`. `shell_exec` is
  `DESTRUCTIVE` (observed via `GET /api/v1/tools`), so any `shell_exec` needs human approval; on this
  local stack a real LLM key is configured, so the seeded run asks for a shell command and blocks for
  the full 60s. CI (no key) fails the run fast, which is what the spec assumes.
- `workflow-ui-interaction-e2e.spec.ts:165` expects a FAILED chain; it observed `COMPLETED` — again
  a live-LLM environment, not a code change.

Neither the spike's spec nor its provider switch is implicated; the switch happened after those
failures, and no failure was reported after test #204 (`ok 204 … git-pack-gate …`).

The spec asserts, in order: the PENDING ask has a `toolCallId` and its reason names `git_push`
(the tool gate, not the gap-1 task gate); the linked tool call is `git_push` and `PENDING`; the run
is `PAUSED`; the tool call has no result yet; after `POST /approvals/{id}/decide {approved:true}`
the call reaches `COMPLETED` (a real `git push`), the run reaches `COMPLETED`, and
`git rev-parse refs/heads/<branch>` in the local bare remote equals the commit the spec created in
the workspace.

**Failed attempt inside the spec's own build-out (evidence that the assertion is real):** the first
GREEN attempt used a hard-coded workspace root (`agent-control-tower/data/workspaces`) and failed:

```
git_push result: ERROR: Exit code: 1
error: src refspec e2e-pack-gate-... does not match any
error: failed to push some refs to 'https://github.com/HappyLiang12/aria-conductor.git'

    > 239 |   expect(settledCall.status, `git_push result: ${settledCall.result}`).toBe('COMPLETED');
  1 failed
```

(Line 239 was the assertion's position in that revision; it is now line 278 after the candidate-root
refactor.)

The live root was then read from the app's own startup log (`Workspace root resolved to:
D:\project\aria-conductor\agent-control-tower\act-app\data\workspaces` — `.run/backend.log`, which
is gitignored at `.gitignore:33`, so it is cited here as an observation, not as an artifact). The
spec now prepares **every** candidate root
(`$TOOLS_FILE_WORKSPACE_DIR`, `<module>/data/workspaces`, `<module>/act-app/data/workspaces`) and the
"push landed in the bare repo" assertion identifies which one the run used. (Also hit during
build-out: `git init --initial-branch` is unsupported by the local git 2.24.0.windows.2 and was
replaced with `git checkout -b`.)

### Cleanup (verified after the green run)

```
$ ls data/workspaces/ agent-control-tower/data/workspaces/ agent-control-tower/act-app/data/workspaces/
(empty)  (empty)  (empty)
$ curl -s .../api/v1/llm-providers   → DeepSeek active=true      # mock provider deleted, original re-activated
$ ls -d /tmp/aria-pack-gate-*        → (no matches)              # bare repo + temp root removed
```

The spec removes its fixture workspaces (`afterAll`) and the temporary provider, re-activates the
previously active provider, closes the mock server and deletes the temp root. The earlier RED run's
leftover workspace directory was deleted manually during this session. Note that the backend's own
workspace root (`agent-control-tower/act-app/data/workspaces`) also accumulates directories from
*other* specs' runs that never reached a terminal state (e.g. a run blocked on the `shell_exec`
approval gate left by `housekeeping-cleanup.spec.ts`); those are not fixtures of this spec.

### Residual risks (gap 6)

- **Global provider switch.** The only way to give the ADK a deterministic model is to activate a
  temporary `LlmProvider` row, which is shared state for the duration of the test (~10s). The spec
  restores it in `afterAll`, answers foreign (non-marker) requests with a benign completion so they
  cannot stall on a fabricated gate, and on entry deletes any `e2e-mock-llm*` provider left active by
  an interrupted earlier run (re-activating a real provider first) so a killed run cannot strand the
  stack on a dead mock. `.github/workflows/ci.yml:248-252` shows the e2e job exports only
  `DEEPSEEK_API_KEY`; a real-LLM spec running concurrently in the same shard could still lose a turn
  to the mock. An in-flight foreign run at the moment of the switch is the residual exposure.
  INFERRED (not measured): no CI shard was executed here.
- **Workspace-root dependence.** The candidate list covers the observed launch modes; a backend
  started from a fourth CWD would fail the spec loudly (see the failed attempt above), not silently.

---

## Gap 5 — SDD branch creation / protection

### What actually gates the behaviour

- The branch handoff is `SpecReviewCoordinator.createBranchAndCommitSpec`
  (`act-knowledge/.../knowledge/sdd/SpecReviewCoordinator.java:345-363`), reached from the
  SPEC_REVIEW approval handler (`:168-175`). It calls `gitBranchService.createBranch`, `putFile` and
  `branchHeadSha`, and records the resulting sha under `specCommitSha`
  (`.../execution/git/GitHandoffMetadata.java:22,26`; branch name `sdd/<chainId>` at `:26`).
- `GitBranchService` is a **pure GitHub REST client** (`.../execution/git/GitBranchService.java`):
  `GET /repos/{owner}/{repo}` → `GET /repos/{owner}/{repo}/git/ref/heads/{default}` →
  `POST /repos/{owner}/{repo}/git/refs` (`:70-100`), with `putFile`/`getFile` on the contents API
  (`:106-138`). Its base URL is the constant `https://api.github.com` (`:30`).
- The bean is wired by `GitBranchConfig` (`.../execution/git/GitBranchConfig.java:22-55`). With
  `GH_TOKEN` blank it returns a disabled subclass whose every operation throws
  `GitBranchException("GH_TOKEN is not configured; ...")` (`:23-53`).

### External dependency and why no hermetic substitute exists

- **The API base URL cannot be redirected.** The only constructor that accepts an alternative base
  URL is the package-private, test-only overload (`GitBranchService.java:46-53`), used by the
  WireMock unit test (`act-execution/src/test/.../git/GitBranchServiceTest.java:43`). The Spring bean
  uses the public one-arg constructor (`GitBranchConfig.java:54`), and no property or environment
  variable feeds it:

  ```
  $ grep -rn "apiBaseUrl\|GH_API\|api.github" --include=*.java --include=*.yml --include=*.properties \
      agent-control-tower/*/src/main .github scripts
  GitBranchService.java:30:  static final String DEFAULT_API_BASE_URL = "https://api.github.com";
  GitBranchService.java:46:  GitBranchService(String ghToken, String apiBaseUrl) {
  GitBranchService.java:215: ...URI.create(apiBaseUrl + path)
  ```

  A bare repository on disk speaks the git protocol, not the GitHub REST API, so it is not a
  substitute; the only local stand-in would be an HTTP emulator of `/repos/...`, which needs a
  production change to `GitBranchConfig` plus a backend restart. Out of scope here (and the local
  stack must not be restarted).
- **A token is required and CI has none.** `.github/actions/start-stack/action.yml:28-37` starts the
  backend with only `ADK_DEFAULT_PROVIDER=langchain` in the environment; nothing supplies `GH_TOKEN`.
  In that state the disabled bean above throws on every branch operation, so a CI run of the SDD flow
  cannot create a branch — which is exactly what `e2e/sdd-workflow.spec.ts:69` already records.
  INFERRED for the local stack: `GitBranchConfig.java:22` takes the value only from the environment
  (`@Value("${GH_TOKEN:}")`), and the repo's untracked `.env` (`.gitignore:2`) contains no `GH_TOKEN`
  key (`grep -c GH_TOKEN .env` → `0`); no other config source defines it.
- **"Protect" has no implementation.** No branch-protection code path exists under
  `agent-control-tower`: a case-insensitive search for `protect` in `*.java/*.ts/*.tsx` returns only
  unrelated hits (`protected` members, `McpTokenFilter.PROTECTED_PATHS`, a UI comment). There is
  nothing to assert, so the "protect" half of gap 5 is a **product finding: not implemented**.
- The JGit-based local branch mechanism (`LocalGitClient.createBranch`,
  `act-knowledge/.../knowledge/git/LocalGitClient.java:97-119`, used by
  `KnowledgeSubmissionSaga.java:99`) is filesystem-local and would be hermetically testable, but it is
  **not reachable**: `grep -rn "SubmissionSaga" agent-control-tower` matches only the class itself and
  its unit test — no production caller, so no API or workflow can trigger it. It is a different
  mechanism from the SDD handoff regardless.

### Local-only run instructions

```
export GH_TOKEN=<token with repo scope>
export SDD_REPO_URL=https://github.com/<owner>/<repo>.git
# start backend + frontend with the same environment, plus an LLM key so the BA step can run
cd agent-control-tower/act-dashboard
npx playwright test e2e/api/branch-governance.api.spec.ts --project=api
```

The spec (`e2e/api/branch-governance.api.spec.ts`) instantiates the seeded `development-workflow`
template against `SDD_REPO_URL`, waits for the chain-scoped SPEC_REVIEW ask, asserts the branch does
not exist before approval, approves the gate (which runs `createBranchAndCommitSpec`), and then reads
`refs/heads/sdd/<chainId>` back through the GitHub REST API. It skips unless both environment
variables are set, and its skip message carries the blocker and this recipe.

Observed skip (the body did not run):

```
$ npx playwright test e2e/api/branch-governance.api.spec.ts --project=api
  -  1 [api] › e2e\api\branch-governance.api.spec.ts:85:1 › SDD branch handoff creates the chain branch on a real GitHub remote (local-only)
  1 skipped
```

---

## NOT VERIFIED

- **`e2e/api/branch-governance.api.spec.ts`: not executed.** Neither the creation assertion nor the
  local-only recipe was run in this session (no `GH_TOKEN`); the body is unverified code that exists
  to document the local-only gate. Its skip path was executed (`1 skipped`, above).
- **CI execution of `e2e/api/git-pack-gate.api.spec.ts`: not executed.** All runs were against the
  local stack. CI-safety arguments (langchain ADK venv present via
  `.github/actions/start-stack/action.yml:25-37`; no `GH_TOKEN`; `git` on the runner) are INFERRED
  from those files, not observed.
- **Cross-spec interference of the provider switch: not measured.** Only the full-suite run on this
  machine (see the report for `task-10-report.md`) gives indirect evidence.
- **Branch protection: nothing to verify** — the feature does not exist.
- The gate's behaviour under an approval *denial* at the PUSH tier (as opposed to resume) was not in
  scope for gap 6; gap 1's spec covers denial at the task-level gate.
