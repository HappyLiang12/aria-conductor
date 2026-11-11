# Gate A evidence — S1 memory/context (feat/agent-os-memory)

Head: `a6fcfec4a7ce9b29ac8bfb247acb1234b9bf7aa2` ("docs(review): close Wave 1 with A1
and B merged after independent review"), branch `feat/agent-os-memory`, working tree
clean (`git status -sb` prints only the branch line).

Host: Windows 11, OpenJDK 21.0.11 LTS (Microsoft), Maven 3.9.6, Node v24.14.1,
pnpm 10.33.2 (dashboard lockfile pins pnpm 9.15.9), gh authenticated as HappyLiang12.
No `OPENCODE_SANDBOX_*` or provider test variables were exported in the Maven shells.
`packages/qoder-acp-bridge/dist` was rebuilt before any Maven verify
(`pnpm install --frozen-lockfile && pnpm build`), because the core adapter contract
ITs launch the real bridge.

Raw Maven logs are not committed; every number below is a transcript line from the
run named in its section.

## 1. Wave gate (also clears lane C's evidence-only BLOCK)

Command:

```
cd agent-control-tower && mvn -B -pl act-common,act-memory,act-execution,act-agent,act-mcp -am test -Dspring.profiles.active=h2
```

Result: BUILD SUCCESS, 10-module reactor, 3:10 min, 279 unique test classes,
zero failures/errors, coverage met in every module that carries the check.

| Module | Tests run | Failures | Errors | Skipped |
|---|---|---|---|---|
| act-common | 145 | 0 | 0 | 0 |
| act-agent | 314 | 0 | 0 | 0 |
| act-execution | 1424 | 0 | 0 | 0 |
| act-knowledge | 269 | 0 | 0 | 4 |
| act-memory | 152 | 0 | 0 | 0 |
| act-dashboard-api | 90 | 0 | 0 | 0 |
| act-aria | 178 | 0 | 0 | 4 |
| act-mcp | 181 | 0 | 0 | 0 |
| **Total** | **2753** | **0** | **0** | **8** |

act-mcp: 181/0/0/0 over 21 classes, exactly the count lane C reported, so C's
evidence-only BLOCK (its mutant run overwrote the green report) is cleared by this
independent re-run at the merged head.

Second command:

```
cd agent-control-tower && mvn -B -pl act-app verify -Dskip.unit.tests=true -Dit.test=MemoryMigrationIntegrationTest -Dspring.profiles.active=h2
```

Result: BUILD SUCCESS; `MemoryMigrationIntegrationTest` 4/0/0/0 (13.40 s). This covers
V81 on a fresh in-memory H2 in MySQL mode.

## 2. Full local gate — RED at HEAD

Command:

```
cd agent-control-tower && mvn -B -pl act-execution,act-agent,act-aria,act-app -am verify -Dspring.profiles.active=h2
```

Result: BUILD FAILURE in `act-app` failsafe, 7:23 min. All modules before act-app are
green. Totals: 2844 surefire tests (8 skipped) and 263 failsafe tests (6 skipped),
with 2 failures and 1 error, all three in `act-app`'s failsafe tier.

| Module | surefire | failsafe |
|---|---|---|
| act-common | 145/0/0/0 | none |
| act-agent | 314/0/0/0 | 13/0/0/0 |
| act-execution | 1424/0/0/0 | 97/0/0/6 |
| act-knowledge | 269/0/0/4 | 12/0/0/0 |
| act-memory | 152/0/0/0 | none |
| act-dashboard-api | 90/0/0/0 | none |
| act-aria | 178/0/0/4 | none |
| act-mcp | 181/0/0/0 | 18/0/0/0 |
| act-app | 91/0/0/0 | **123 / 2 failures / 1 error / 0 skipped** |

### F-A1 (product, deterministic) — two ITs still assert the retired knowledge prompt block

```
[ERROR] AgentLoopInjectionIntegrationTest.regularAgentRun_isCoordinatorOwned_carriesSystemRuleTask:103
        [knowledge must be injected for non-Aria agents]
[ERROR] OpenCodeTaskExecutionIntegrationTest.coordinatorOwnedOpencodeRun_carriesSystemRuleTaskToTheCore_andNeverConsultsTheProvider:113
        [knowledge must be injected via buildMessages reuse]
```

Both assert `task.systemPrompt()` contains `"## Knowledge Context"`:

- `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/AgentLoopInjectionIntegrationTest.java:101-103`
- `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/OpenCodeTaskExecutionIntegrationTest.java:111-113`

The actual prompt at this head contains the knowledge item as its own section
(`## deploy-proc` / `Always blue-green deploy`), which is the intended A2 behaviour:
`KnowledgeContextContributor` emits one section per item with the item name as the
title (`agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/context/KnowledgeContextContributor.java:17-23`,
`agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/service/KnowledgeService.java:389-411`).
The `## Knowledge Context` heading belonged to the deleted `KnowledgeContextProvider`
(lane A2 handoff, ledger "A2 handoff"; deleted as lead glue in `b06ebf36`).

`git log` for both test files shows their last change is `60d542f1` (PR #109), i.e.
they were never updated by the lane that intentionally changed the section shape.
Deterministic: re-run alone
(`-Dit.test=AgentLoopInjectionIntegrationTest,OpenCodeTaskExecutionIntegrationTest,AriaManagedPromptMigrationIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false`)
gives `AgentLoopInjectionIntegrationTest` 1 run / 1 failure and
`OpenCodeTaskExecutionIntegrationTest` 2 run / 1 failure, same assertions.

### F-A2 (test isolation, order-dependent) — managed-prompt IT deletes an agent with live sessions

```
[ERROR] AriaManagedPromptMigrationIntegrationTest.turnsAnEmptyConfigIntoTheObjectLiteral:93 » DataIntegrityViolation
        SQL [DELETE FROM agents WHERE id = ?]; Referential integrity constraint violation:
        "FK_SESSIONS_AGENT: PUBLIC.AGENT_SESSIONS FOREIGN KEY(AGENT_ID) REFERENCES PUBLIC.AGENTS(ID)
         (UUID '00000000-0000-0000-0000-000000000001')"
```

The test deletes the Aria agent row between loop iterations
(`agent-control-tower/act-app/src/test/java/io/aria/conductor/app/backfill/AriaManagedPromptMigrationIntegrationTest.java:91-93`)
without clearing `agent_sessions`; an earlier IT in the same failsafe JVM leaves a
session row for the fixed Aria id. In isolation the class is 12/0/0/0, so the failure
is order-dependent, not a V82 behaviour change. It is still a red in the gate's run
order.

### Classification

- F-A1: product (stale assertions after the reviewed A1/A2 prompt-shape change),
  deterministic, blocks Gate A under "any compile/test red".
- F-A2: product test isolation (new V82 IT vs shared context), order-dependent,
  also blocks the full tier.
- No environment, path/encoding, flake or clean-install component: the same classes
  reproduce in an isolated re-run with the same messages.

No product code, test or migration was modified by Gate A.

## 3. Full gate, non-Maven surfaces (green)

| Surface | Command | Result |
|---|---|---|
| Qoder ACP bridge | `cd packages/qoder-acp-bridge && pnpm install --frozen-lockfile && pnpm build && pnpm test` | 5 files, 62/62 tests passed |
| Dashboard tests | `cd agent-control-tower/act-dashboard && pnpm install --frozen-lockfile && pnpm test -- --run` | 79 files, 662/662 tests passed |
| Dashboard build | `pnpm build` | `tsc -b && vite build` exit 0, 1.30 s, chunk-size warning only |
| MCP server | `cd packages/mcp-server && pnpm install --frozen-lockfile && pnpm test` | 20 files, 157/157 tests passed |

## 4. Live SDD scenario — BLOCKED by the host runtime (not marked passed)

Stack: fat jar `act-app/target/act-app-0.1.0-SNAPSHOT.jar` (95 MB, built by the full
`verify` at this head), fresh file database
`jdbc:h2:file:./data/gate-a-db;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE`, launched from an
isolated directory (`%TEMP%/gate-a/live`) with the operator's env file sourced
in-process (never printed), `--adk.default-provider=opencode`. Health 200, Flyway
migrated, the active provider auto-created from the environment (DeepSeek /
deepseek-v4-flash), `development-workflow` template APPROVED. The operator GitHub
credential was stored through `POST /api/v1/packs/pack-git-0001/credentials`
(`{"status":"stored","restartRequired":false}`), value read from the authenticated
`gh` CLI and never printed.

Both placement options for the `opencode` core fail on this host:

### Attempt 1 — SANDBOX (the mode V59 gives the opencode agents)

Chain `44683e11-1ebc-417d-98ff-374f4d196d4b`, BA run
`bf3262ef-74b0-42af-b59f-8b35ab6524e6`: RUNNING for ~4 s, then FAILED with
`iterations=0` before the first turn.

```
com.alibaba.opensandbox.sandbox.api.infrastructure.ServerException: Server error : 500 Internal Server Error
{"code":"DOCKER::SANDBOX_START_FAILED","message":"Failed to create or start container: 500 Server Error for
http+docker://localhost/v1.44/containers/.../start: Internal Server Error (\"crun: open `memory.max` for
writing: No such file or directory: OCI runtime attempted to invoke a command that was not found\")"}
```

Reproduced outside the product, so the product is not the cause:

```
$ podman run --rm --memory 256m docker.io/library/node:22-slim true
Error: preparing container ... for attach: crun: open `memory.max` for writing: No such file or directory:
OCI runtime attempted to invoke a command that was not found
```

The podman machine (3 CPU / 2 GiB, WSL) cannot enforce a memory limit, so every
OpenSandbox create fails. Environment defect, not product.

### Attempt 2 — HOST (the placement the ledger names for Gate A)

The opencode CLI on this host is only the npm shim
`C:/Users/User/AppData/Roaming/npm/opencode.cmd`; there is no native
`opencode*.exe` anywhere under the npm prefix or `%LOCALAPPDATA%`. The Product must
start the profile executable, so the shim makes Windows start `cmd.exe`, and the
trusted-launch check refuses it by design:

```
Agents switched to opencode/HOST via PUT /api/v1/agents/runtime {"updated":4,"executionMode":"HOST"}
Chain 682f6537-0ffd-4eda-8bd6-40be5709c49c, BA run f55448d3-6297-4d4e-9649-f26d6bddc6f7
Froze run f55448d3... to core opencode/HOST
java.lang.IllegalStateException: The runtime the OS started (cmd) is not the executable of the trusted
launch profile (opencode)
run status=FAILED, iterations=0
```

This is the fail-closed check doing its job; the gap is environmental (no native
opencode binary). A wrapper `.exe` or borrowing a credential from another store would
be simulation, so it was not done.

### qoder HOST alternative

`C:/Users/User/.qoder/bin/qodercli/qodercli-1.1.61.exe` and a rebuilt
`packages/qoder-acp-bridge/dist` are present, but the qoder core needs an operator PAT
through `PUT /api/v1/cores/qoder/credential` (round-2 finding F1: the CLI login is not
adopted). Neither `QODER_PAT`, `QODER_API_KEY`, `QODER_TOKEN` nor
`ARIA_CORES_QODER_CREDENTIAL` is present in the operator env file, and no operator is
in the loop to supply one. Not run rather than simulated.

### Consequence

No BA/DEV/QA turn ever reached a prompt, so the manifest assertion
(one `context_manifests` row per turn) is NOT VERIFIED, not passed. Both chains are
aborted attempts and are recorded as such. The database was left at
`%TEMP%/gate-a/live/data/gate-a-db` for the stop-and-query step.

## 5. Hosted CI

Workflow `ci.yml` at `039d3d10` (the evidence commit; `git diff a6fcfec4..039d3d10` is
the two documentation files only), `gh workflow run ci.yml --ref feat/agent-os-memory`,
run id `42652224806`.

## 6. Live SDD scenario — retry 2026-11-10

The 2026-11-09 blocker (no native `opencode*.exe`, so the trusted-launch check refused the
npm `cmd` shim) was resolved by the operator: a native executable exists at
`C:/Users/User/AppData/Roaming/npm/node_modules/opencode-ai/node_modules/opencode-windows-x64/bin/opencode.exe`
(1.14.31, 183,150,472 bytes, sha256 `2a35b3b2…`). It was passed through
`ARIA_CORES_OPENCODE_EXECUTABLE`; no code, test, migration or configuration file was modified.

Backend log lines below are local time (UTC+8); API and database timestamps are UTC, quoted
as observed. No credential or `.env` value was printed at any point.

### 6.1 Stack

- Fat jar `agent-control-tower/act-app/target/act-app-0.1.0-SNAPSHOT.jar`, 95,175,062 bytes,
  sha256 `db3ed35c…`, built 2026-11-10 00:05:20 +0800 from `1fb8f542` (docs-only over
  `a6fcfec4`; the jar contains no test classes). The branch advanced to `62020f64` during
  the run (test-only: the F-A1/F-A2 ITs), after the jar was built.
- Isolated directory `%TEMP%/gate-a/live2`, fresh file database
  `jdbc:h2:file:./data/gate-a-db;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE`, operator env file
  sourced in-process by the launcher (never echoed), `--spring.profiles.active=h2
  --adk.default-provider=opencode`. Launch (`launch.mjs` is outside the repository):

```
cd %TEMP%/gate-a/live2 && node launch.mjs
started pid=36012 executable=.../opencode-windows-x64/bin/opencode.exe
```

- Health 200 at 16:25:13Z; Flyway `Successfully applied 79 migrations … now at version v82`.
- `GET /api/v1/llm-providers` → one active provider, `DeepSeek` / `OPENAI` /
  `https://api.deepseek.com` / `deepseek-v4-flash`, auto-created 16:25:13Z.
- `POST /api/v1/packs/pack-git-0001/credentials` with the value of `gh auth token`
  (`{"key":"GITHUB_TOKEN", …}`, value never printed) → 200
  `{"key":"GITHUB_TOKEN","restartRequired":false,"status":"stored"}`.
- `PUT /api/v1/agents/runtime {"adkProvider":"opencode","executionMode":"HOST"}` → 200
  `{"updated":4,"runtime":{"executionMode":"HOST","adkProvider":"opencode"}}`; all four
  agents read back opencode/HOST.

### 6.2 Trusted launch — resolved

Ten run-owned cores were started, one per run attempt: ten `Froze run … to core
opencode/HOST` lines and ten core logs each printing
`opencode server listening on http://127.0.0.1:<port>`. The backend log contains **zero**
occurrences of `not the executable of the trusted launch profile` and no `cmd.exe` child.
The native executable blocker is **resolved** on this host.

### 6.3 Chain creation — two attempts

The previous 404 investigation: `POST /api/v1/workflows/templates/{id}/reuse` instantiates a
*chain* template (`WorkflowService.createFromTemplate`, `WorkflowChain` lookup), not the
seeded knowledge item; it was a wrong route and created nothing. The knowledge-item route is
`POST /api/v1/knowledge/d0000001-0000-0000-0000-000000000001/instantiate-workflow`.

- **Attempt A (malformed, aborted).** Chain `20f0360c-d1cf-418b-b3e1-8b2e23bae286` created
  16:26:15Z with `issueRef` and `repoUrl` but not `issueRepo`, leaving the literal
  `{issueRepo}` in the BA prompt (`gh issue view #112 -R {issueRepo}`). Cancelled at
  16:27:04Z before the model answered; BA run `f3c78db8-2c13-4d8d-aad8-ef9ae73e32c4`
  CANCELLED. Recorded here as an aborted attempt; it produced one manifest row.
- **Attempt B (authoritative).** Chain `588bcde6-6c48-4713-8c7c-0c7d248df72b` created
  16:27:07Z with
  `{"issueRef":"#112","issueRepo":"HappyLiang12/aria-conductor","repoUrl":"https://github.com/HappyLiang12/aria-conductor.git"}`.
  Issue #112 ("SDD spec review reads result-root through a duplicated filesystem contract",
  OPEN) was reused; no issue was created.

### 6.4 Runs of chain `588bcde6-6c48-4713-8c7c-0c7d248df72b`

| # | Step | Run id | Agent | Status | Iter | Tokens | Started (UTC) | Ended (UTC) |
|---|---|---|---|---|---|---|---|---|
| 0 | BA | `fb88fc2f-c0c1-4e80-864a-64ee6b799887` | ba | COMPLETED | 1 | 4634 | 16:27:07.251 | 16:30:31.692 |
| 1 | DEV 1 | `c690bb57-fd58-4cf1-b4e8-a15715cebd95` | dev | COMPLETED | 1 | 449 | 16:31:39.512 | 16:31:55.042 |
| 2 | QA 1 | `4ed10ee8-ca46-4187-b9a5-4ca203522f77` | qa | COMPLETED | 1 | 1172 | 16:31:55.058 | 16:33:15.935 |
| 1 | DEV 2 | `c58a7abf-c5c6-4cee-a237-680a509b0461` | dev | COMPLETED | 1 | 19702 | 16:33:15.953 | 16:33:39.806 |
| 2 | QA 2 | `a69d837f-ba90-4086-911e-19014bf7d00e` | qa | COMPLETED | 1 | 5986 | 16:33:39.814 | 16:34:24.771 |
| 1 | DEV 3 | `3defa75c-ac25-45a8-ab04-321011579957` | dev | COMPLETED | 1 | 4702 | 16:34:24.787 | 16:34:54.704 |
| 2 | QA 3 | `9ae49308-5608-4e2a-bb4f-d2dbe10373f2` | qa | COMPLETED | 1 | 16213 | 16:34:54.720 | 16:35:52.167 |
| 1 | DEV 4 | `1a88f17c-9ead-4a23-bf80-7a8f105b4d39` | dev | COMPLETED | 1 | 6330 | 16:35:52.187 | 16:36:44.783 |
| 2 | QA 4 | `31de2341-085e-4217-995c-a6be8db9b327` | qa | COMPLETED | 1 | 16730 | 16:36:44.791 | 16:37:45.361 |

Total 75,918 tokens across nine runs. DEV was rescheduled once per DEFECT
(`WorkflowService.rescheduleStep`, `DEFAULT_MAX_ATTEMPTS = 3`).

### 6.5 Spec-review gate

- Approval `840f286d-fe32-4ea3-9906-f34df79a356e`, `SPEC_REVIEW`, PENDING from 16:30:31Z,
  expires 17:00:31Z; content is the BA spec for issue #112 (MARKDOWN, knowledge item
  `95f81f00-0f27-44de-9c09-f2f25a7f0e39`).
- `POST /api/v1/approvals/840f286d-fe32-4ea3-9906-f34df79a356e/decide {"approved":true}` →
  **HTTP 200** `{"status":"processed","approved":true,"approvalId":"840f286d…"}`. It is the
  only approval decision in the run.
- The chain advanced to DEV at 16:31:39Z (`SDD spec approved … advancing to Dev`) and the
  coordinator pushed branch `sdd/588bcde6-6c48-4713-8c7c-0c7d248df72b` at
  `86f9596e08deb8e4d4fa8a601fcfb73ed858c15c` ("sdd: approve spec", one file `spec/spec.md`,
  13,668 bytes), verified read-only through `gh api`.

### 6.6 Outcome — chain FAILED, BLOCKED by the governed toolset

`SDD loop exhausted: chain=588bcde6-… step=1 attempts=3` (16:37:45.384 local); chain status
FAILED at 16:37:45.383889Z. Four QA DoD reviews recorded `passed=false verdict=DEFECT`
(16:33:15.949, 16:34:24.784, 16:35:52.183, 16:37:45.377 local).

Every DEV and QA run finished by reporting that it had no write or shell capability:

- DEV 1 (`c690bb57…`): "this session has only read-only tools (`read`, `glob`, `grep`,
  `webfetch`). There is no shell/Bash tool and no file-write/edit tool, and the checkout
  directory is empty (`repo` does not exist)."
- DEV 4 (`1a88f17c…`): "this sandbox has **no shell, no write/edit, and no git tools**".
- QA 1 (`4ed10ee8…`): "this sandbox exposes only read-only tools … I therefore verified the
  branch through the GitHub API"; QA 4 ended `VERDICT=DEFECT`.
- The BA could not write `spec.md` either; the spec came from its final output
  (`SPEC_ID=7f3c2a9e-…`), the documented `finalOutput` fallback in `SpecReviewCoordinator`.

**Anomaly (product, pre-existing on this branch's base).** The governed opencode document
denies `edit`, `write`, `patch` and `bash`
(`agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/core/OpenCodeCoreAdapter.java:126-129`)
and allows only `read`/`list`/`glob`/`grep`/`webfetch`/`websearch` plus `aria-conductor*`
(line 133). The platform MCP block that would satisfy `aria-conductor*` is wired only for the
Aria assistant: `RunMcpWiring.forRun` returns empty unless the run's agent is
`AriaConstants.ARIA_AGENT_ID` (`.../execution/mcp/RunMcpWiring.java:51`,
`ARIA_AGENT_ID = 00000000-0000-0000-0000-000000000001`). SDD BA/DEV/QA agents therefore get
read-only tools and no MCP, while the template prompts instruct them to clone, build, commit
and push. The permission block arrived with #92 (`fdff463d`) and the Aria-only wiring with
#93 (`624ee893`), so this is not an S1 regression, but it makes a working opencode SDD loop
impossible at this head: DEV delivered nothing, the branch holds only the spec commit, and
the automatic DEFECT rework cannot converge.

### 6.7 Manifest assertion

Read after stopping the backend (forced stop of pid 36012; no shutdown endpoint is exposed),
with the H2 Shell from `~/.m2` (2.3.232, the version bundled in the jar):

```
java -cp ~/.m2/repository/com/h2database/h2/2.3.232/h2-2.3.232.jar org.h2.tools.Shell \
  -url "jdbc:h2:file:./data/gate-a-db;MODE=MySQL" -user sa -password "" \
  -sql "select run_id, turn_number, adapter, estimated_tokens, created_at from context_manifests order by created_at"
```

| Run | Step | Manifests | Turn | Adapter | Est. tokens |
|---|---|---|---|---|---|
| `f3c78db8…` (aborted attempt A) | BA | 1 | 2 | SYNC | 1029 |
| `fb88fc2f…` | BA | 1 | 2 | SYNC | 1034 |
| `c690bb57…` | DEV 1 | 1 | 2 | SYNC | 950 |
| `4ed10ee8…` | QA 1 | 1 | 2 | SYNC | 1176 |
| `c58a7abf…` | DEV 2 | 1 | 2 | SYNC | 1709 |
| `a69d837f…` | QA 2 | 1 | 2 | SYNC | 1176 |
| `3defa75c…` | DEV 3 | 1 | 2 | SYNC | 1714 |
| `9ae49308…` | QA 3 | 1 | 2 | SYNC | 1176 |
| `1a88f17c…` | DEV 4 | 1 | 2 | SYNC | 1682 |
| `31de2341…` | QA 4 | 1 | 2 | SYNC | 1176 |

Ten rows for ten runs, exactly one row per executed turn; `group by run_id` returns
`manifests=1, min_turn=2, max_turn=2` for every run, so the `(run_id, turn_number)` unique
key has no duplicate and no gap. The turn number is `2` because
`AgentLoopEngine.nextTurnNumber` returns one past the run's last trajectory turn
(`AgentLoopEngine.java:1128-1130`) and the trajectory's turn 2 is written after the
assembly; internally consistent, noted because a first turn reading "2" looks odd.

### 6.8 Gate assertions

- `StopProof[allWritersStopped=false]`: **absent** (`grep -c` = 0; the string `StopProof`
  does not appear in the log at all). Ten launches, nine completed runs and one cancelled
  run; no orphaned `opencode.exe` remained after the chain ended.
- 500 on approval: **none**; the single `decide` returned 200 (section 6.5).
- One `context_manifests` row per BA/DEV/QA turn: **met** for all ten executed turns
  (section 6.7).

### 6.9 Verdict

- opencode executable blocker: **RESOLVED — VERIFIED live** (native 1.14.31 launched,
  trusted-launch check passed, every turn served by the real core).
- Live SDD scenario end to end: **BLOCKED** — not by the launch, but by the governed
  read-only toolset wired for non-Aria agents (§6.6). DEV cannot implement and QA cannot
  verify, so the chain ends FAILED after three attempts; a COMPLETED chain with a delivered
  and verified change is **NOT VERIFIED**. The three explicit gate assertions are met for
  every turn that actually executed.
- Not re-tested here: qoder (`QODER_PAT` still absent), SANDBOX (podman memory limit from
  §4 unchanged), the manifest section payloads beyond row/turn/token presence, and any
  dashboard rendering of this chain.
