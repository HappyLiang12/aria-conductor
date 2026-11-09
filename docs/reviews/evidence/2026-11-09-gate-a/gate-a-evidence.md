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

## 4. Not yet run at the time of this file

- Live SDD scenario (Gate A step 3) — recorded in the ledger when complete.
- Hosted CI run id (Gate A step 4) — recorded in the ledger when complete.
