# Agent OS S1 Delivery Ledger (2026-11-09)

Status: Wave 1 merged at `a6fcfec4` and closed at `62020f64` (Gate A PASS for the S1
acceptance arm). Wave 2 (B, D, C2a, C2b, I) is merged at `7f6647ab`; **Gate B is BLOCKED**
by one deterministic test-cleanup regression in the full local gate (F-B1), while the live
memory acceptance arm is VERIFIED. Evidence:
`docs/reviews/evidence/2026-11-09-gate-a/gate-a-evidence.md` and
`docs/reviews/evidence/2026-11-09-gate-b/gate-b-evidence.md`.
Spec: `docs/superpowers/specs/2026-11-09-aria-memory-context-design.md` (commits `6e2035c2`, `6664f779`)
Plan: `docs/superpowers/plans/2026-11-09-aria-memory-context-s1.md` (commit `65285701`)
Feature branch: `feat/agent-os-memory`
Base: `825d3b91` (PR #118 merged)

## Environment preflight (2026-11-09, measured on this host)

| Item | Observed |
|---|---|
| Java | OpenJDK 21.0.11 LTS (Microsoft) |
| Maven | 3.9.6 (`C:/Users/User/tools/apache-maven-3.9.6`) |
| Node | v24.14.1 |
| pnpm | 10.33.2 |
| GitHub CLI | authenticated as HappyLiang12, https |
| Podman | machine `podman-machine-default` running, 3 CPUs, 2 GiB RAM, 100 GiB disk |

Note: the sandbox E2E may be memory-tight at 2 GiB. Gate A runs the HOST-mode SDD
scenario and does not require the sandbox; the sandbox workflow is recorded separately
when its paths change.

## Lane status

| Lane | Branch | Worktree | Status | Commits | Review | Merged |
|---|---|---|---|---|---|---|
| W0A | `lane/agent-os-w0a` | `D:/project/aria-agent-os-w0a` | APPROVED | bd2b313f, d6236a31, 2a04fe59, d142424f | APPROVE (6 Low) | no |
| W0B | `lane/agent-os-w0b` | `D:/project/aria-agent-os-w0b` | MERGED (4980b0b1), BLOCK cleared by fix round | 100ba176..4980b0b1 (8) | BLOCK (all findings closed) | yes |
| A1 | `lane/agent-os-a1` | `D:/project/aria-agent-os-a1` | MERGED (2f1ea4e6, rebased from 0245da65) | b62a4fcb..56eca9c6 (6) + 8 | APPROVE (final) | yes |
| A2 | `lane/agent-os-a2` | `D:/project/aria-agent-os-a2` | MERGED (ac04408e) + fixes b06ebf36 | 2129a64a, b3849867, 2dc3a0ee | APPROVE (fixes applied) | yes |
| B | `lane/agent-os-b` | `D:/project/aria-agent-os-b` | IMPLEMENTED (d42b086b), fix rounds done, re-review in flight | 4c56b96d + 6 + 5 | BLOCK -> fix rounds | no |
| C | `lane/agent-os-c` | `D:/project/aria-agent-os-c` | MERGED (f3cc3255), review BLOCK evidence-only | 34db1ac7 | BLOCK pending merged-head gate | yes |
| D | `lane/agent-os-d` | `D:/project/aria-agent-os-d` | MERGED (aa43c9bf, rebased from b7a72679) | e04a7fd8..aa43c9bf (6) | APPROVE (6 P3 open) | yes |
| C2a | `lane/agent-os-c2a` | `D:/project/aria-agent-os-c2a` | MERGED (a6fc68b5) | 4 commits | BLOCK (P1) -> APPROVE after fix | yes |
| C2b | `lane/agent-os-c2b` | `D:/project/aria-agent-os-c2b` | MERGED (15e9ae36) | 6 commits | BLOCK (ceiling/forget) -> APPROVE | yes |
| I | `lane/agent-os-i` | `D:/project/aria-agent-os-i` | IN_REVIEW (96984d75 + guard fix) | 5 commits + fix | APPROVE WITH FIXES | no |

### W0B handoff and review (2026-11-09)

- Commits 100ba176 (module skeleton), 73d264a1 (schema, entities, repositories,
  bounded queries). Full `-pl act-app -am test` at that head: 9 modules, 279 surefire
  reports, zero failures/errors; act-memory slice 4/0/0/0, migration IT 4/0/0/0,
  trajectory bound 2/0/0/0, manifest slice 3/0/0/0.
- Independent review verdict: BLOCK at 73d264a1. Two High: the trajectory bound kept the
  oldest 200 (spec P6 says the last 200) and lanes B/D call repositories that did not
  exist while repository/** is W0B-only. Two Medium: the new module was in no hosted or
  sharded lane, and three of five entities had no round-trip test. Five Low.
- Dispositions: the newest-200 contract was corrected at e314dff3 (test 2/0/0/0; plan
  snippet amended), the hosted matrix group was already added at a9d76c89, and a fix
  round now owns the missing derived queries, the entity round-trips, the deterministic
  fact order, `countByStatus` and the manifest bulk delete. Housekeeping and
  `cleanup-all.sql` registration go to lane I.



### W0A handoff (2026-11-09)

- Commits bd2b313f (context SPI), d6236a31 (tool policy move), 2a04fe59 (provider
  ceiling); worktree clean; every file inside the W0A ownership row.
- RED captured per task (compile errors for the missing types and getter).
- Evidence: `ContextContractTest` 4/0/0/0, `ToolDescriptorTest` 2/0/0/0,
  `LlmProviderCeilingTest` 2/0/0/0; `mvn -pl act-common test` 140/0/0/0 with coverage
  met; `mvn -pl act-mcp -am test` BUILD SUCCESS, act-mcp 190/0/0/0 over 21 classes
  (act-agent 312, act-execution 1351, act-knowledge 263/4 skipped, act-dashboard-api 90,
  act-aria 187/4 skipped, coverage met in 7 modules); `mvn -pl act-app -am test-compile`
  exit 0.
- Note: `*ContractTest` is a failsafe include and a surefire exclude, so
  `ContextContractTest` runs in the `verify` (integration) tier, which Gate A's
  `mvn verify` executes; no rename needed.
- Merge constraint: `LlmProvider.sensitivityCeiling` is non-null with no column until
  W0B's V81, so W0A must not be merged or booted alone; merge W0A and W0B in the same
  integration step before any act-app gate.
- Independent review verdict: APPROVE at 2a04fe59, six Low follow-ups, no Critical/High.
  Fixed in d142424f: `length = 16` on the ceiling column, a null guard in `onCreate`,
  the SPI test renamed to `ContextSpiTest` so the unit tier runs it, and a
  `withMaxSensitivity(null)` case that must stay INTERNAL. Evidence:
  `ContextSpiTest` 5/0/0/0, `LlmProviderCeilingTest` 2/0/0/0.
- Follow-ups handed to other lanes: a full 57-entry policy-map test and the
  autoApproveRead implies WORKER_READ invariant go to C; the fact that no provider DTO
  writes the ceiling stays documented as SQL-only in S1 (spec section 9 has no such
  endpoint); the spec's `estimatedChars`/`estimatedTokens` "field" wording is textual
  drift because both are derived accessors in the approved plan.

### C handoff (2026-11-09)

- Commit 34db1ac7: ToolCatalog with 59 descriptors (57 registered MCP names plus the two
  core-native read-only tools WebSearch/WebFetch), registry delegation, full name→policy
  pin, auto-approve invariants, inventory parity, live endpoint parity. Gate: act-mcp
  181/0/0/0, whole chain BUILD SUCCESS; endpoint IT 2/0/0/0; a policy-swap mutant reddens
  three assertions and is reverted.
- Deviation: `McpProperties` lives in act-execution, so the lane could not make its
  default equal the catalog without touching another module; it kept the 26-name default
  and added the two native tools to the catalog as WORKER_READ instead. Consequence:
  `policyOf("WebSearch"/"WebFetch")` changes from GATED to WORKER_READ, and A1's
  `ToolCatalogContextContributor` must render the full descriptor set minus OPERATOR_ONLY
  (59), so the generated prompt lists the native read tools that operators already
  auto-approve. The reviewer is asked to check for any production call site that resolves
  those names through `policyOf`.

### A2 handoff (2026-11-09)

- Commits 2129a64a (prompt contributor), b3849867 (initializer and duplicate history
  load removed), 2dc3a0ee (knowledge contributor). Gate: act-aria 182/0/0/4,
  act-knowledge 271/0/0/4, act-execution 1351/0/0/0, act-common 145, BUILD SUCCESS with
  coverage met.
- Open cross-lane follow-up: `KnowledgeContextProvider` is NOT deleted because
  `AgentLoopEngine` (lane A1) still injects it. After A1 removes that constructor
  parameter, delete the interface and drop `implements` plus its two methods from
  `KnowledgeService`. The Lead owns this step after the A1 merge.
- Deviations to review: `SkillContextProvider` retained in AriaStreamService (the stream
  entry cannot carry a skill id and the plan's own test asserts the active-skill system
  message); `KnowledgeItemRepository` gained a Pageable overload (the plan's test stubs it
  and P6 forbids the subList path). Both are recorded as accepted pending the reviewer.

### Wave 0 integration (2026-11-09)
- `feat/agent-os-memory` at c6d1eb06: W0A rebased to 2036e833 and fast-forwarded, then
  W0B rebased and fast-forwarded; no conflicts. W0A and W0B are both in the branch, so
  the `sensitivity_ceiling` column and the non-null entity field exist together.
- Wave gate at the integrated head is re-run after the W0B fix round lands; the earlier
  per-lane evidence (W0A full unit tier, W0B full nine-module unit tier) does not cover
  the combined tree.
- A1 starts from c6d1eb06; A2 and C started from the earlier W0A head d142424f and will
  be rebased onto the integrated head before merge.

## Recovery runbook (state as of 2026-11-09T14:13Z)

If this delivery is interrupted, resume from here; nothing below depends on
conversation memory.

- Integration branch: `feat/agent-os-memory` at the commit that carries this file.
  Wave 0A and Wave 0B are merged; Wave 0B's review fix round, A1, A2 and C are running
  in their own worktrees and may have added commits since.
- Worktrees: `aria-agent-os-a1` (`lane/agent-os-a1`, base c6d1eb06),
  `aria-agent-os-a2` and `aria-agent-os-c` (`lane/agent-os-a2`, `lane/agent-os-c`,
  base d142424f), `aria-agent-os-w0b` (`lane/agent-os-w0b`, head c6d1eb06 + fix round).
- To integrate a lane once its review verdict is APPROVE:
  `cd <lane worktree> && git rebase feat/agent-os-memory && cd D:/project/aria-agent-os && git merge --ff-only <lane branch>`.
  A2 and C must be rebased (their base predates the Wave 0 integration); A1, W0B and
  later B/D branch from the integration head and normally fast-forward.
- Wave gate after the W0B fix merges, at the integrated HEAD:
  `cd agent-control-tower && mvn -pl act-common,act-memory,act-execution,act-agent,act-mcp -am test -Dspring.profiles.active=h2`
  plus `mvn -pl act-app verify -Dskip.unit.tests=true -Dit.test=MemoryMigrationIntegrationTest`.
- Then unblock lanes B (`lane/agent-os-b`) and D (`lane/agent-os-d`): create each with
  `git worktree add -b lane/agent-os-b ../aria-agent-os-b feat/agent-os-memory` (same for
  D) and dispatch them against plan lines 1794-1939 and 1997-2061.
- Gate A and Gate B commands and preflight live under "## Gates" above.

## Gates

Gate A (after W0A, W0B, A1, A2, C merge):

```bash
cd /d/project/aria-agent-os/agent-control-tower
mvn -B -pl act-execution,act-agent,act-aria,act-app -am verify -Dspring.profiles.active=h2
cd /d/project/aria-agent-os/agent-control-tower/act-dashboard && pnpm test -- --run && pnpm build
cd /d/project/aria-agent-os/packages/mcp-server && pnpm test
cd /d/project/aria-agent-os/packages/qoder-acp-bridge && pnpm test
```

Preflight before the Gate A command: `git status -sb` clean; build the Qoder ACP bridge
dist (`cd packages/qoder-acp-bridge && pnpm build`) because the core adapter contract ITs
launch the real bridge; run Maven in a shell with no exported `OPENCODE_SANDBOX_*` or
provider test variables (they leak into tests and flip expected bindings).

Plus the live SDD scenario on the HOST backend and hosted CI at the Gate A HEAD.

Live-scenario constraints (from the 2026-11 QA runs; the operator supplies credentials and
no agent reads `.env`):

- build the immutable fat jar at the gate HEAD and run it directly; never
  `spring-boot:run -pl act-app`;
- H2 must stay in `MODE=MySQL`; use a fresh database or a copy that does not carry the
  PACK credential key;
- the opencode HOST provider key must not collide with an opencode built-in provider id,
  and its models block must declare the model; `LLM_API_KEY` comes from the operator
  environment;
- one SDD chain on an existing GitHub issue with the spec gate approved once; assert the
  chain completes, no 500 on approval, and one context manifest per BA/DEV/QA turn.

### Gate A evidence (Release QA, 2026-11-10)

HEAD `a6fcfec4`, tree clean. Full transcript:
`docs/reviews/evidence/2026-11-09-gate-a/gate-a-evidence.md`.

- **Wave gate: GREEN.** `mvn -B -pl act-common,act-memory,act-execution,act-agent,act-mcp -am test`
  at `a6fcfec4`: BUILD SUCCESS, 279 classes, 2753 tests, 0 failures/errors, 8 skipped
  (act-common 145, act-agent 314, act-execution 1424, act-knowledge 269/4, act-memory
  152, act-dashboard-api 90, act-aria 178/4, act-mcp 181), coverage met.
  `MemoryMigrationIntegrationTest` 4/0/0/0 on a fresh H2. act-mcp is 181/0/0/0 over
  21 classes, matching lane C's claim, so **C's evidence-only BLOCK is cleared**.
- **Full local gate: RED.** `mvn -B -pl act-execution,act-agent,act-aria,act-app -am verify`
  builds every upstream module green (surefire 2844 tests, 8 skipped; failsafe 13
  act-agent, 97 act-execution/6 skipped, 12 act-knowledge, 18 act-mcp) and then fails
  `act-app` failsafe: 123 tests, **2 failures, 1 error**.
  - F-A1 product/deterministic: `AgentLoopInjectionIntegrationTest:103` and
    `OpenCodeTaskExecutionIntegrationTest:113` still assert `## Knowledge Context`, the
    heading of the `KnowledgeContextProvider` that A2 deleted. The contributor that
    replaced it renders one section per item (`## deploy-proc`), which the same prompt
    contains. Both tests were last touched at `60d542f1` (PR #109), so the A1/A2 merge
    left them stale. Reproduces in an isolated re-run (1/1 and 1/2 respectively).
  - F-A2 product test isolation: `AriaManagedPromptMigrationIntegrationTest.turnsAnEmptyConfigIntoTheObjectLiteral`
    errors deleting the Aria agent row while `agent_sessions` still references it
    (FK_SESSIONS_AGENT). The class is 12/0/0/0 in isolation; the error is order-dependent
    pollution from an earlier IT in the same failsafe JVM.
  - No product code, test, migration or config was changed by Gate A.
- **Non-Maven surfaces: GREEN.** Bridge 62/62 (5 files, dist rebuilt from source),
  dashboard 662/662 (79 files) and `pnpm build` exit 0, MCP server 157/157 (20 files).
- Lane B and D are already merged at this head (`a6fcfec4`); the plan's merge order is
  therefore ahead of the gate. Gate A must go green before Gate B.
- **Live SDD retry (2026-11-10): opencode launch RESOLVED, scenario BLOCKED.** With
  `ARIA_CORES_OPENCODE_EXECUTABLE` pointing at the native 1.14.31 exe, the fat jar at
  `1fb8f542` launched ten run-owned cores, the trusted-launch check refused none, and the
  only approval decision returned 200. Chain `588bcde6-6c48-4713-8c7c-0c7d248df72b` on issue
  #112 ran BA → DEV → QA four times (nine runs, 75,918 tokens) and ended FAILED
  (`SDD loop exhausted: … attempts=3`): the governed opencode document denies
  `edit`/`write`/`patch`/`bash` (`OpenCodeCoreAdapter.java:126-129`) and the platform MCP is
  wired only for the Aria agent (`RunMcpWiring.java:51`), so every DEV/QA run reported a
  read-only toolset, DEV delivered no code, and each QA verdict was DEFECT. `context_manifests`
  has exactly one row per executed turn (ten rows; `group by run_id` → 1/2/2). Assertions
  `StopProof[allWritersStopped=false]` (0 occurrences) and no-500-on-approval both hold. The
  live scenario is blocked by the SDD toolset, not by opencode; a COMPLETED chain with a
  delivered change is NOT VERIFIED. Full transcript in
  `docs/reviews/evidence/2026-11-09-gate-a/gate-a-evidence.md` §6.

### Gate B evidence (Release QA, 2026-11-10)

HEAD `7f6647ab`, branch `feat/agent-os-memory`, tree clean. Full transcript:
`docs/reviews/evidence/2026-11-09-gate-b/gate-b-evidence.md`.

- **Full local gate: RED.**
  `mvn -B -pl act-execution,act-agent,act-aria,act-app -am verify -Dspring.profiles.active=h2`
  is BUILD FAILURE in act-app failsafe after 07:21 min. Surefire 2919/0/0/8 (common 145,
  agent 314, execution 1425, knowledge 269/4, memory 162, dashboard-api 122, aria 178/4,
  mcp 209, app 95); failsafe 263/0/1/6 (agent 13, execution 97/6, knowledge 12, mcp 18,
  app 123/0/1/0). The fat jar was packaged at 01:46:16 +0800 before the failing tier.
  - **F-B1 product/deterministic:** `ReviewAskRepairMigrationIntegrationTest` errors on
    `cleanup-all.sql` statement #11 "TRUNCATE TABLE context_manifests RESTART IDENTITY —
    Table CONTEXT_MANIFESTS not found". `ae0c3aeb` added the line at
    `act-app/src/test/resources/db/cleanup-all.sql:11`; the IT is the only class pinned to
    `spring.flyway.target=65`, while `context_manifests` is V81. Re-run alone is 1/0/1/0 in
    15.1 s. Owner: a lane that owns the test script (split the pinned-target cleanup or
    make it schema-aware). Release QA changed no product code, test or migration.
- **Non-Maven surfaces: GREEN.** Dashboard 662/662 (79 files) and `pnpm build` exit 0
  (1.28 s); MCP server 157/157 (20 files); Qoder ACP bridge 62/62 (5 files) with the dist
  rebuilt from source before Maven.
- **Live memory acceptance: VERIFIED** on a fresh H2 (`gate-b-db`, V82) with the fat jar at
  this head and the native opencode HOST executable. Six Aria runs, all
  `Froze run … to core opencode/HOST`, no trusted-launch refusal, no orphaned core.
  - Teach/recall: PERSON `a3255334-3895-4db7-93b8-c622731cb461` (timezone
    `4a8e66a2…`, preferred_editor `8c66b2e9…`) and REPO
    `12a8bfbe-a98b-4c85-953d-6158d64a5097`; `GET /recall?q=operator timezone` returns both
    fact ids with origin/sensitivity.
  - Restart (17:49:51Z stop, 17:50:10Z health) keeps the same ids and values.
  - Run `2b0c53d4-48a7-48a8-9062-2572e207799d` (turn 1) manifest id
    `2e98f0e7-1763-419f-9a4d-da15dfc8387d`: memory section sources are the entity + both
    fact ids; one manifest row per run (6 runs → 6 rows, unique `(run_id, turn_number)`).
    `session_trajectory` has 2 turns per run; the single assembly is recorded at turn 2
    (Gate A §6.7 convention).
  - PENDING: run `ba61eced-544f-48a5-9554-e1a41087f1ea` called the real MCP
    `remember_fact` with the run-scoped worker bearer; proposal
    `75d9fa1c-9349-454d-af4a-70bbd35a8a6a` (EXTRACTION) was created at 17:50:38Z with an
    assertion `actor_type=EXTRACTION, actor_ref=ba61eced…` — no H2 fallback. Run
    `19219b2d…` (17:50:54Z) answered "UNKNOWN" and its manifest omits the PENDING id.
    `POST /proposals/75d9fa1c…/decide` (17:51:05Z, 200, ACTIVE) precedes run `5a9aef9c…`,
    whose manifest includes the approved id and answers "teal".
  - RESTRICTED: fact set to RESTRICTED in H2; run `c7af91a8…` (INTERNAL ceiling) omits it
    and answers "UNKNOWN"; after `llm_providers.sensitivity_ceiling='RESTRICTED'` on the
    active provider `ddb53e55-0d2e-4466-93cf-d2fbe03bb6a0` and a restart, run `34a2f7c5…`
    includes it and answers "teal". Manifest `providerId` is null; the resolver falls back
    to the single active provider (D-B4).
  - Manifest hygiene: zero occurrences of `Asia/Hong_Kong`, `teal`, `preferred_widget` or
    either prompt fragment in the API JSON and in `sections_json`/`dropped_json`/
    `degraded_json`; the Aria prompt lists the six worker memory tools and not
    `decide_memory_proposal`/`forget_fact`. Six V82 tables present on the fresh database.
- **Deviations:** D-B1 the plan's `key`/`value` fact payload is `factKey`/`valueText` live;
  D-B2 `q=timezone` alone is empty by spec §8.4's entity-mention recall (the plan example
  and its intent diverge); D-B3 approval used the REST route named by Gate B step d while
  creation used the real MCP worker path; D-B4 null manifest `providerId` with active-row
  ceiling fallback; D-B5 turn number 2 for a run's single assembly.
- **NOT VERIFIED:** hosted CI at this head (no dispatch in this task) and the live SDD
  rerun (not delegated; the Gate A read-only-toolset blocker is unchanged).

Gate B (after B, D, C2a, C2b, I merge):

- fresh H2 database, teach two facts via REST, restart, recall in a new conversation;
- approve a PENDING proposal and observe it on the next turn;
- verify the RESTRICTED ceiling filter. S1 adds no provider-ceiling REST surface, so the
  ceiling is raised by stopping the backend, running the H2 shell against the same file
  database, and restarting:

```bash
java -cp <h2.jar> org.h2.tools.Shell \
  -url "jdbc:h2:file:./data/act_db;MODE=MySQL" -user sa -password "" \
  -sql "UPDATE llm_providers SET sensitivity_ceiling='RESTRICTED'"
```

Verify the run's provider id is the row that was updated; the default provider starts at
`INTERNAL` from V81.
- full local gate plus the live SDD scenario plus hosted CI at the Gate B HEAD.

## Decision log

- 2026-11-09: product direction is a platform-first governed Agent OS. The engineering
  pack is the first pack, not the whole product.
- 2026-11-09: milestone shape is the full platform base plus a separate full product
  redesign and a final real-scenario retest. S1 ships no production UI; the Memory page
  is the first S2 vertical slice.
- 2026-11-09: memory model is normalized entities/relations/facts plus an append-only
  assertion log. Recall is deterministic in M1; no embeddings or vector store.
- 2026-11-09: governance is tiered. Operator writes are ACTIVE; run-derived structural
  facts are ACTIVE with provenance; conversation-inferred preferences/relations are
  PENDING until operator approval.
- 2026-11-09: pre-work refactor bundle P1-P8 plus the P10 seam was approved after the
  refactor audit; P9 is contained, P11 belongs to S2, P12 is untouched.
- 2026-11-09: lanes use the repository's SDD evidence discipline in isolated worktrees.
  The product's live SDD chain is the Gate A/B acceptance scenario, not the build
  mechanism (building the chain with the chain is circular).
- 2026-11-09: merge order is W0A/W0B, then A1/A2/C, Gate A, then B/D/C2a/C2b/I, Gate B.
- 2026-11-09: the hosted unit matrix listed modules explicitly and omitted the new
  act-memory module, so its tests would never run on hosted CI; a `memory` matrix group
  was added at a9d76c89 before Wave 0 merges so Gate A/B evidence covers the module.
- 2026-11-09: the plan's Gate B example called `GET /api/v1/runs/{id}/context-manifest`,
  which contradicts spec section 9 (`/api/v1/memory/runs/{id}/context-manifest`). The
  spec is authoritative; the plan example was corrected and C2a must implement the
  spec path. The W0B repository comment already matches the spec.
- 2026-11-09: integration items that no product lane owns are assigned to lane I:
  confirm the hosted `memory` matrix group added at a9d76c89, and regenerate the
  `scripts/lanes` shard plan for the new act-memory test classes
  (`node scripts/lane-plan.mjs gen` then `check`), because the shard runner refuses to
  start when a compiled test class is covered by no lane.
- 2026-11-09: W0B shipped the trajectory bound as
  `findTop200ByRunIdOrderByTurnNumberAsc`, but spec P6 says the *last* 200 turns; the
  ascending derived query keeps the oldest. The contract was corrected to
  `...OrderByTurnNumberDesc` with the history loader reversing into turn order, and the
  plan's A1 test now stubs newest-first data so a loader that forgets the reversal
  fails. The lane's repository and its bound test must be updated to match.
- 2026-11-09: the dependency graph makes A2 and C depend only on W0A (not W0B), so their
  worktrees branch from the W0A lane head d142424f and both lanes start while W0B is
  still gating. They will be rebased onto the integrated Wave 0 head before merge.
  A1, B and D need W0B artifacts (manifest, entities, V81) and stay blocked.
- 2026-11-09: Wave 1 integration at b06ebf36: C (f3cc3255), A1 (4f726781) and A2
  (ac04408e) rebased and fast-forwarded with no conflicts. A2's review P3s are applied
  as lead glue in b06ebf36: `KnowledgeContextProvider` and its two obsolete tests
  deleted after A1 removed the engine dependency, the knowledge clip is now at most 500
  chars including the ellipsis, and the source-id assertion pins the item id. C's BLOCK
  is evidence-only (the mutant run overwrote its green report) and is cleared by the
  wave gate at this head. A1's review and B-2 are in flight; D is still implementing.
- 2026-11-09: A1 review BLOCK rulings. (1) The tool catalog cannot fit the spec's
  3,000-character tools cap with per-tool descriptions: the real 51 visible entries
  render to roughly 15,000 characters, which is what produced the observed mid-line
  cut. The prompt section is therefore compact (`- name (POLICY)`, sorted, no
  descriptions); the core binds tools natively through MCP and the section exists to
  make the reviewed policy classification visible. Truncation stays line-safe for a
  pathological catalog. This is a deliberate deviation from spec §8.1's "one-line
  descriptions" and is recorded in the release notes. (2) `dropped_json` must never
  carry prompt text: history drops become `history:dropped:<index>`. (3) The current
  request renders once, in the Goal section; the engine no longer appends the seed as
  a message, and `CoreTask.userPrompt` is read directly from the run's prompt seed per
  spec §8.3. (4) The unused history cap is applied, and optional sections scale
  proportionally before the drop order per spec §8.2 step 5. Deferred to lane I:
  seeding `memory.operator.*`; provider id/CORE emission stays with A1 if it is local.
- 2026-11-09: reviews for B (040d5179) and D (dfd61bf3) are dispatched read-only while
  the A1 fix round runs; reviewers may not run Maven so the two do not contend.
- 2026-11-09: D review APPROVE at b7a72679 after the V82 corruption fix. D was rebased
  onto the feature head and merged as aa43c9bf. Six P3s remain and are accepted as
  residual risks, none blocking: a lost-race entity is not added to linkTargets (its
  name-matched links may never be proposed if the winning pass died between insert and
  link pass); the completion marker itself can lose a duplicate-key race and log a
  false failure although the marker exists; V82's WHERE is narrower than the CASE could
  safely handle, so `{"a":1 }` / `{  }` rows are never marked (no corruption; primary
  writers emit compact JSON); the "completion last" test fails at the first read rather
  than at links.save; concurrency is proven with mocks, not a real constraint
  translation; MariaDB execution of V82 is unverified (H2 only). V82 is lane-local, so
  editing it breaks no released checksum.
- 2026-11-10: Gate A live retry outcome. opencode HOST is verified live on this host
  (native 1.14.31 executable, trusted-launch check passed, real turns). The live SDD
  scenario is still not a gateable pass: the governed read-only toolset for non-Aria agents
  prevents DEV/QA from doing their steps, independent of S1. Gate A's live arm therefore
  stays BLOCKED; the fix belongs to the core tool surface (platform MCP for worker agents or
  a reviewed write policy), not to the S1 lanes, and no qoder fallback or simulation was
  used.
- 2026-11-10: Gate A completion. (1) The first hosted run (42652224806 at 039d3d10) was
  16/17 with the failsafe lane red: two app-level ITs still asserted the deleted
  `## Knowledge Context` block (F-A1) and the V82 IT deleted an Aria row with live
  `agent_sessions` children (F-A2). Both were test debt, fixed in lane fix-a
  (3b352d96, merged 62020f64) with real assertions on the new `## <item>` shape and an
  FK-safe cleanup; the act-app failsafe tier is 123/0/0/0. (2) Clean-room at 62020f64
  (fresh worktree, no dist): the first attempt failed with 17 CoreAdapterContractTest
  errors because a clean checkout has no `packages/qoder-acp-bridge/dist`; this is
  clean-install, not product. Rebuilding the bridge first, the full gate is green:
  Java verify (act-app failsafe 123/0/0/0), dashboard 662/662 plus build, mcp-server
  157/157, qoder-acp-bridge 62/62. (3) Hosted CI run 42656087082 at 62020f64 is 17/17
  success. (4) The live SDD retry verified the S1 arm end to end: chain 588bcde6, real
  opencode HOST cores, approval 200, 10 context_manifests rows for 10 runs (one per
  executed turn, unique key intact, no gaps), no StopProof false, 75,918 tokens; the
  chain itself failed because the pre-existing governed HOST toolset denies write/bash
  and the platform MCP is Aria-only, and the SANDBOX arm cannot start because the
  podman machine cannot enforce `memory.max` (a machine restart did not change it).
  Gate A verdict: PASS for the S1 acceptance arm (context, manifests, approvals,
  regression), with the live SDD deliverable BLOCKED by those two pre-existing
  platform/environment gaps, recorded rather than simulated.
- 2026-11-10: Wave 2 reviews. C2a was BLOCKed on one P1 (a duplicate entity create
  reached the global handler as a 500 with the wrong envelope and h2 constraint text);
  the fix maps only unique collisions to 409 and other integrity failures to 422, with
  a regression test. Accepted with follow-ups: MemoryLifecycleService reimplements
  rename/alias/retire entity and retire fact outside act-memory (governance parity
  checked; move into the command service later), q filtering is an in-controller scan
  over a bounded page without alias matching, and memory reads keep the existing
  unauthenticated dashboard posture (decision: same as the rest of the local console;
  C2b's MCP reads are separately clamped). C2b was BLOCKed on the read path leaking
  above-INTERNAL facts and on the inline, transaction-unsafe forget mutation; the fix
  clamps every memory read at INTERNAL (actor can only lower it, unlabelled fails
  closed), adds MemoryCommandService.retireFact with flush/conflict translation and
  real tests, and makes the forget acknowledgement metadata-only. Decisions: memory
  read tools stay autoApproveRead=false, proposal reason is not persisted, and
  get_context_manifest is global metadata. Lane I was APPROVED WITH FIXES: the ArchUnit
  canary did not exercise the real boundary selectors (fixed with a fixture-based
  violation proof), and the purge test lacked a pre-purge existence assertion.

- 2026-11-10: Gate B full gate red. `ae0c3aeb` added `TRUNCATE TABLE context_manifests`
  to the shared test cleanup script, but `ReviewAskRepairMigrationIntegrationTest` pins
  Flyway to V65 and `context_manifests` is V81, so the class errors before any test. This
  is a Wave 2 test-cleanup regression (deterministic, 1/0/1/0 in isolation), not an
  environment or flake failure; it blocks Gate B under "any compile/test red". The fix
  belongs to a lane that owns the test script: either split the pinned-target cleanup or
  make the truncation schema-aware. Release QA changed no product code, test or migration.
- 2026-11-10: Gate B live memory arm verified on a fresh H2 at `7f6647ab`: teach/recall
  provenance (`a3255334…` PERSON; facts `4a8e66a2…`, `8c66b2e9…`; REPO `12a8bfbe…`),
  restart persistence, six Aria runs each with one context manifest and no prompt text,
  PENDING proposal `75d9fa1c…` created by the real MCP `remember_fact` worker actor
  (EXTRACTION with the run id as actor_ref, no H2 fallback) and absent until approved, and
  the RESTRICTED fact blocked at the INTERNAL ceiling then admitted after raising the
  active provider's ceiling. Deviations D-B1..D-B5 are recorded in the evidence file; the
  noteworthy ones are the plan's fact payload fields and the plan's `q=timezone` recall
  example, both plan-text drift rather than product defects.
- 2026-11-10: Gate B remains BLOCKED by F-B1 even though the memory arm is VERIFIED.
  Hosted CI at `7f6647ab` and the live SDD rerun are NOT VERIFIED (not run in this task);
  no release verdict is issued.

## Evidence to fill at each boundary

- Lane handoff: branch, commits, exact commands, counts, RED evidence, files, residuals.
- Independent review: verdict and findings per lane.
- Merge: feature-branch commit after each lane.
- Gate A/B: HEAD, commands, counts, hosted run ids, live chain id, residual risks.
- Release note and QA report at the end.
