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
