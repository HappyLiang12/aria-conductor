# Gate B evidence — S1 memory acceptance (feat/agent-os-memory)

Head: `7f6647ab6b0a7dfe1c48d37e02bdcda54b49dbdc` ("test(lane-i): make the memory boundary
canary run the real rule"), branch `feat/agent-os-memory`, working tree clean
(`git status --porcelain` prints nothing). Wave 2 (C2a REST, C2b MCP tools, lane I guards)
is merged at this head.

Host: Windows 11, OpenJDK 21.0.11 LTS (Microsoft), Maven 3.9.6, Node v24.14.1, pnpm
10.33.2 (the dashboard lockfile pins pnpm 9.15.9). The Qoder ACP bridge `dist` was rebuilt
from source before any Maven command (`cd packages/qoder-acp-bridge && pnpm install
--frozen-lockfile && pnpm build`, 01:39 local), because a clean checkout has no `dist` and
`CoreAdapterContractTest` launches the real bridge. The Maven shell exported no
`OPENCODE_SANDBOX_*` or provider test variables.

Raw Maven and backend logs are not committed. Every number and id below is a quoted
transcript value from the run named in its section; the database rows were read with the
H2 Shell (2.3.232, the version bundled in the jar) after the backend was stopped.

## 1. Full local gate — RED at 7f6647ab (Gate B step 6)

Command (exactly as delegated):

```
cd agent-control-tower && mvn -B -pl act-execution,act-agent,act-aria,act-app -am verify -Dspring.profiles.active=h2
```

Result: **BUILD FAILURE** in `act-app` failsafe, 07:21 min, finished
2026-11-10T01:47:32+08:00. Every module before `act-app` is green; the fat jar was packaged
at 01:46:16 +0800 before the failing tier.

| Module | surefire (run/fail/err/skip) | failsafe (run/fail/err/skip) | Reactor |
|---|---|---|---|
| act-common | 145/0/0/0 | — | SUCCESS |
| act-agent | 314/0/0/0 | 13/0/0/0 | SUCCESS |
| act-execution | 1425/0/0/0 | 97/0/0/6 | SUCCESS |
| act-knowledge | 269/0/0/4 | 12/0/0/0 | SUCCESS |
| act-memory | 162/0/0/0 | — | SUCCESS |
| act-dashboard-api | 122/0/0/0 | — | SUCCESS |
| act-aria | 178/0/0/4 | — | SUCCESS |
| act-mcp | 209/0/0/0 | 18/0/0/0 | SUCCESS |
| act-app | 95/0/0/0 | **123 / 0 / 1 / 0** | **FAILURE** |
| **Total** | **2919 / 0 / 0 / 8** | **263 / 0 / 1 / 6** | |

3182 tests ran, 14 skipped, 1 error, 0 failures. Gate A at `62020f64` was 2844 surefire and
263 failsafe; Wave 2 adds 75 surefire tests (act-execution +1, act-memory +10,
act-dashboard-api +32, act-mcp +28, act-app +4).

### F-B1 (product, deterministic) — the test cleanup script truncates a table the pinned migration target does not have

```
[ERROR] ReviewAskRepairMigrationIntegrationTest » ScriptStatementFailed
        Failed to execute SQL script statement #11 of class path resource [db/cleanup-all.sql]:
        TRUNCATE TABLE context_manifests RESTART IDENTITY
Caused by: org.h2.jdbc.JdbcSQLSyntaxErrorException: Table "CONTEXT_MANIFESTS" not found;
        SQL statement: TRUNCATE TABLE context_manifests RESTART IDENTITY [42102-232]
[ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0
```

- The line was added by `ae0c3aeb` ("fix(housekeeping): purge context manifests with their
  run") at
  `agent-control-tower/act-app/src/test/resources/db/cleanup-all.sql:11`. The base class
  runs it before every test class (`.../app/BaseH2IntegrationTest.java:12`).
- `ReviewAskRepairMigrationIntegrationTest` is the only test class that pins the schema to
  V65 (`spring.flyway.target=65`,
  `.../app/ReviewAskRepairMigrationIntegrationTest.java:47-50`); `context_manifests` is
  created by V81. The shared cleanup script therefore references a table that exists only
  on the full schema.
- Deterministic: re-run alone with
  `mvn -B -pl act-app verify -Dskip.unit.tests=true -Dit.test=ReviewAskRepairMigrationIntegrationTest -Dspring.profiles.active=h2`
  gives 1 run / 1 error, same statement, 15.1 s.
- Classification: **product** (test-cleanup drift introduced in Wave 2), not environment,
  path/encoding, flake or clean-install. The bridge `dist` was present and
  `CoreAdapterContractTest` passed 26/0/0/0 in the same run, so this is not the
  clean-install class Gate A saw.
- Blast radius: exactly one class in this suite pins the Flyway target (grep of
  `act-app/src/test` for `spring.flyway.target`), which matches the single error. Two
  correct fixes are possible in a lane that owns the file: split the pinned-target cleanup
  from the full-schema one, or make the table truncation schema-aware. Release QA changed
  no product code, test or migration.
- Gate impact: the plan's Gate B blocks on any compile/test red; **Gate B is BLOCKED** until
  this is fixed and the full gate is re-run green.

## 2. Non-Maven surfaces — GREEN at the same head

| Surface | Command | Result |
|---|---|---|
| Dashboard unit | `cd agent-control-tower/act-dashboard && pnpm install --frozen-lockfile && pnpm test -- --run` | 79 files, 662/662, 16.76 s |
| Dashboard build | `pnpm build` | `tsc -b && vite build` exit 0, 247 modules, 1.28 s, chunk-size warning only |
| MCP server | `cd packages/mcp-server && pnpm install --frozen-lockfile && pnpm test` | 20 files, 157/157, 1.57 s |
| Qoder ACP bridge | `cd packages/qoder-acp-bridge && pnpm install --frozen-lockfile && pnpm build && pnpm test` | 5 files, 62/62, 10.68 s, dist rebuilt from source |

## 3. Live memory acceptance — fresh H2, real backend, real opencode HOST core

### 3.1 Stack

- Fat jar `agent-control-tower/act-app/target/act-app-0.1.0-SNAPSHOT.jar`, 95,222,573
  bytes, sha256 `94a7fc4c92f639ea…`, built 2026-11-10 01:46:16 +0800 by the `verify` above.
- Isolated directory `%TEMP%/gate-b/live` (C:/Users/User/AppData/Local/Temp/gate-b/live),
  fresh file database
  `jdbc:h2:file:./data/gate-b-db;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE`, launched as
  `java -jar … --spring.profiles.active=h2 --adk.default-provider=opencode` with the
  operator env file `D:/project/aria-conductor/.env` sourced in-process by the launcher
  (never printed) and
  `ARIA_CORES_OPENCODE_EXECUTABLE=C:/Users/User/AppData/Roaming/npm/node_modules/opencode-ai/node_modules/opencode-windows-x64/bin/opencode.exe`.
- Health 200 at 17:49:33Z; Flyway "Successfully validated 79 migrations … now at version
  v82". The active provider is `ddb53e55-0d2e-4466-93cf-d2fbe03bb6a0` (DeepSeek,
  OPENAI-compatible, auto-created from the environment, `sensitivity_ceiling='INTERNAL'`
  from V81).
- `PUT /api/v1/agents/runtime {"adkProvider":"opencode","executionMode":"HOST"}` → 200
  `{"updated":4,"runtime":{"executionMode":"HOST","adkProvider":"opencode"}}`; all four
  agents read back opencode/HOST. Every Aria run below logged
  `Froze run <id> to core opencode/HOST` (six runs, six freeze lines); no
  `not the executable of the trusted launch profile` line and no orphaned `opencode.exe`
  after the last run.
- REST writes used curl from loopback with no `Origin` header, so they resolve as the local
  operator (`OperatorAuthorityResolver.resolveOperator`, loopback branch). Every write
  returned 201/200; no bearer was used.

### 3.2 Teach and recall (Gate B steps 2-3)

```
POST /api/v1/memory/entities
{"kind":"PERSON","canonicalKey":"operator","displayName":"Operator","sensitivity":"INTERNAL"}
  → 201 id a3255334-3895-4db7-93b8-c622731cb461
POST /api/v1/memory/entities
{"kind":"REPO","canonicalKey":"repo:aria-conductor","displayName":"aria-conductor","sensitivity":"INTERNAL"}
  → 201 id 12a8bfbe-a98b-4c85-953d-6158d64a5097
POST /api/v1/memory/entities/a3255334…/facts
{"factKey":"timezone","valueText":"Asia/Hong_Kong","valueType":"STRING","sensitivity":"INTERNAL"}
  → 201 id 4a8e66a2-4507-4081-b2e7-2468e9477a25, origin OPERATOR, validFrom 17:49:42.743Z
POST /api/v1/memory/entities/a3255334…/facts
{"factKey":"preferred_editor","valueText":"vim","valueType":"STRING","sensitivity":"INTERNAL"}
  → 201 id 8c66b2e9-4227-430d-a6b9-a2a3e1680ebc, origin OPERATOR, validFrom 17:49:42.803Z
GET /api/v1/memory/recall?q=operator%20timezone → 200
  entities: [a3255334… PERSON/operator INTERNAL]
  facts: [8c66b2e9… preferred_editor=vim INTERNAL OPERATOR,
          4a8e66a2… timezone=Asia/Hong_Kong INTERNAL OPERATOR]
```

Provenance ids, origin and sensitivity are present on both facts. Deviation D-B1 records the
literal `q=timezone` result (empty) and why the entity-naming query is the correct
acceptance form.

### 3.3 Restart persistence (Gate B step 3)

Backend stopped 17:49:51Z (forced stop of the JVM; no shutdown endpoint is exposed). H2
Shell before restart: `memory_entities=6` (2 taught + 4 app-seeded AGENT rows),
`memory_facts=2`, `memory_assertions=4`, `context_manifests=0`. Restarted on the same file
database; health 200 at 17:50:10Z, Flyway validated 79 migrations and did not re-apply
them. `GET /recall?q=operator%20timezone` returns the same entity id and both fact ids with
the same `createdAt`/`validFrom` values. **Persistence VERIFIED.**

### 3.4 Aria turn and manifest (Gate B step 3)

`POST /api/v1/aria/chat {"conversationId":"gate-b-conv-1","message":"Operator here. In one
short sentence, what timezone do I prefer?"}` → 200 in 5.08 s, run
`2b0c53d4-48a7-48a8-9062-2572e207799d`, status COMPLETED, reply "You prefer Asia/Hong_Kong
(Hong Kong time)."

`GET /api/v1/memory/runs/2b0c53d4…/context-manifest` → one row (manifest id
`2e98f0e7-1763-419f-9a4d-da15dfc8387d`, turnNumber 2, adapter SYNC, estimatedTokens 1695),
with the memory section:

```
{"id":"memory",
 "sources":["a3255334-3895-4db7-93b8-c622731cb461",
            "8c66b2e9-4227-430d-a6b9-a2a3e1680ebc",
            "4a8e66a2-4507-4081-b2e7-2468e9477a25"],
 "chars":346,"tokens":99,"truncated":false}
```

The entity id and both fact ids are the sources; the section reports its size only and
carries no content. Turn numbering is 2 for a run's single assembly, the same
`AgentLoopEngine.nextTurnNumber` convention recorded in Gate A §6.7.

### 3.5 PENDING proposal through the real MCP `remember_fact` with a worker actor (Gate B step 4)

The embedded endpoint's live worker path is the one `RunMcpWiring.forRun` mints for an Aria
run (`RunMcpWiring.java:78-87`): the run's opencode core receives a run-scoped bearer and
calls `/mcp` with it; `McpServerConfig.actorTransportContext` resolves it to a worker
principal, and `MemoryTools.rememberFact` maps a non-operator actor to
`MemoryActorType.EXTRACTION`. A static `auth-mode=token` credential carries no actor and
would be refused at the tool boundary (`McpActorContext.require` → "Missing transport
actor"; INFERRED from `McpTokenFilter` and `McpActorContext`, not exercised live). The live
worker call below was therefore made by the Aria run itself, not by an external token.
**No H2 fallback was used.**

- Turn: `POST /api/v1/aria/chat` message "Use the remember_fact tool now with entityId
  a3255334-3895-4db7-93b8-c622731cb461, factKey preferred_widget, valueText teal, sourceRef
  gate-b-turn2. Then reply with the tool result status only." → run
  `ba61eced-544f-48a5-9554-e1a41087f1ea`, 7.04 s, COMPLETED, reply "PENDING".
- `GET /api/v1/memory/proposals?status=PENDING` → one row: id
  `75d9fa1c-9349-454d-af4a-70bbd35a8a6a`, subjectType FACT, entityId a3255334…,
  `preferred_widget=teal`, status PENDING, origin **EXTRACTION**, sensitivity INTERNAL,
  createdAt 17:50:38.154Z.
- Assertion row in `memory_assertions`: `CREATE`, actor_type **EXTRACTION**, actor_ref
  **ba61eced-544f-48a5-9554-e1a41087f1ea** (the run id), source_ref `gate-b-turn2` — the
  worker actor path is recorded, not inferred.
- Next turn (absence): run `19219b2d-f621-40a1-b23a-4b0fbabd97da`, message "Operator here.
  What is my preferred_widget? If you do not know it, reply exactly UNKNOWN." → COMPLETED
  in 5.03 s, reply "UNKNOWN". Manifest memory sources are the entity + the two ACTIVE
  OPERATOR facts; the PENDING id `75d9fa1c…` and the value `teal` occur zero times in the
  manifest (API JSON and DB columns).
- Approve: `POST /api/v1/memory/proposals/75d9fa1c…/decide {"decision":"APPROVE"}` → 200,
  status ACTIVE, origin EXTRACTION, validFrom 17:51:05.714Z. The second assertion row is
  `UPDATE`, actor_type OPERATOR, actor_ref `operator-bearer`.
- Following turn (presence): run `5a9aef9c-5e3a-4417-983c-2479a21a1974`, same prompt →
  COMPLETED in 8.54 s, reply "teal". Manifest memory sources now include
  `75d9fa1c-9349-454d-af4a-70bbd35a8a6a` (four sources, 489 chars / 140 tokens).

### 3.6 RESTRICTED ceiling (Gate B step 5)

- Backend stopped 17:51:26Z. H2 Shell `UPDATE memory_facts SET sensitivity='RESTRICTED'
  WHERE id='75d9fa1c-…'` → 1 row; read-back shows `preferred_widget ACTIVE EXTRACTION
  RESTRICTED`; `llm_providers.sensitivity_ceiling` is still `INTERNAL`.
- Restarted (health 200 at 17:51:52Z). Run `c7af91a8-b589-4f05-afeb-7f944d568626`, same
  widget prompt → COMPLETED in 5.63 s, reply "UNKNOWN"; manifest memory sources are the
  entity + the two INTERNAL facts, and `75d9fa1c…` occurs zero times. The RESTRICTED fact
  does not cross an INTERNAL ceiling.
- Backend stopped; H2 Shell `UPDATE llm_providers SET sensitivity_ceiling='RESTRICTED'
  WHERE id='ddb53e55-0d2e-4466-93cf-d2fbe03bb6a0'` → 1 row, read-back RESTRICTED. Restarted
  (health 200 at 17:52:22Z). Run `34a2f7c5-d9ae-4994-822d-8bee6f36b7c1`, same prompt →
  COMPLETED in 6.12 s, reply "teal"; the memory section now includes `75d9fa1c…` (four
  sources, 489 chars / 140 tokens).
- The manifest's `providerId` is null while `ProviderCeilingResolver.ceilingFor(null)` uses
  the single active provider row, which is the row updated above. The restart avoided any
  cache question; the observed behaviour changes only with the ceiling value, which proves
  the resolution path. Recorded as D-B4.

### 3.7 Manifest hygiene and row counts

- Six Aria runs, six `context_manifests` rows: `group by run_id` returns
  `manifests=1, min_turn=2, max_turn=2` for every run; total 6. `session_trajectory` has
  2 turns per run (min 1, max 2), i.e. one assembly per run's assistant turn; the
  `(run_id, turn_number)` unique key has no duplicate and no gap.
- Prompt/fact text check across all six manifests (API JSON and the DB columns
  `sections_json`, `dropped_json`, `degraded_json`): `Asia/Hong_Kong` 0, `teal` 0,
  `preferred_widget` 0, "what timezone do i prefer" 0, "if you do not know it" 0,
  "remember_fact tool now" 0; DB `LIKE` sweep over the three JSON columns returns 0 hits.
  The only keys present are the whitelist (`id`, `sources`, `chars`, `tokens`, `truncated`,
  plus row metadata); the manifest has no content field by construction
  (`ContextManifestService.record`, `MemoryMapper.manifestView`).
- The turn-1 tools section lists `remember_fact`, `recall_memory`,
  `list_memory_entities`, `get_memory_entity`, `list_memory_proposals`,
  `get_context_manifest`, and does **not** list the OPERATOR_ONLY
  `decide_memory_proposal` or `forget_fact` (0 occurrences), which supports acceptance
  row 9 on the live prompt.
- Fresh schema: the six memory tables (`MEMORY_ENTITIES`, `MEMORY_RELATIONS`,
  `MEMORY_FACTS`, `MEMORY_ASSERTIONS`, `MEMORY_KNOWLEDGE_LINKS`, `CONTEXT_MANIFESTS`)
  exist on the fresh V82 database. MariaDB was not exercised.

## 4. Deviations

| Id | Deviation | Disposition |
|---|---|---|
| F-B1 | Full local gate red: `cleanup-all.sql:11` truncates `context_manifests` (V81) in a V65-pinned IT. | Product/test regression from `ae0c3aeb`; blocks Gate B; one-class deterministic reproducer recorded. No file changed by QA. |
| D-B1 | The plan's `POST /entities/{id}/facts` example uses `{"key":…,"value":…}`; the live DTO is `{"factKey":…,"valueText":…}` (`AddFactRequest`). | Plan snippet drift; the live API was used. Product behaviour matches spec §9. |
| D-B2 | `GET /recall?q=timezone` returns `{"entities":[],"facts":[],"truncated":false}`. | Expected by spec §8.4: recall is entity-mention based. The controller's own test uses `q=operator timezone`. Provenance ids/sensitivity verified with that query; not a defect. |
| D-B3 | The plan names `decide_memory_proposal` for the approval; the delegated step (d) names the REST route. | Approval used `POST /api/v1/memory/proposals/{id}/decide` (200). Creation used the MCP `remember_fact` with the real worker actor, so the governance path under test was exercised. |
| D-B4 | Manifest `providerId` is null; the ledger suggests verifying the run's provider id is the updated row. | `ProviderCeilingResolver` falls back to `findByActiveTrue`; the single active provider `ddb53e55…` was updated and the RESTRICTED fact appeared only after that update. No caching observed across a restart. |
| D-B5 | The single-assembly manifest row records `turnNumber=2`. | Pre-existing convention documented in Gate A §6.7 (`AgentLoopEngine.nextTurnNumber`); one row per assembly, unique key intact. |

## 5. Verdict

- Full local gate at 7f6647ab: **RED** — F-B1, one deterministic error in act-app failsafe
  (123/0/1/0); all upstream modules green (2919 surefire, 263 failsafe). **Gate B is
  BLOCKED** by this red.
- Non-Maven surfaces: **GREEN** — dashboard 662/662 + build, MCP server 157/157, bridge
  62/62 with a source-built dist.
- Live memory acceptance: **VERIFIED** on a fresh H2 with the real opencode HOST core —
  teach/recall provenance, restart persistence, one manifest row per run with fact ids and
  no prompt text, PENDING via the real worker-actor MCP path (EXTRACTION, run id recorded)
  absent then present after approval, RESTRICTED fact blocked at an INTERNAL ceiling and
  present after raising it to RESTRICTED.
- Hosted CI at this head: **NOT VERIFIED** — no dispatch in this task.
- Live SDD rerun at this head: **NOT VERIFIED** — not part of the delegated steps; the
  Gate A SDD blocker (read-only governed toolset for non-Aria agents) is unchanged by this
  evidence.
- Release QA changed no product code, test, migration or configuration. The only changes
  are this evidence file, the ledger Gate B entry and the QA report rows listed there.
