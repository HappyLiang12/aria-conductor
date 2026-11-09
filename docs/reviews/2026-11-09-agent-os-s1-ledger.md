# Agent OS S1 Delivery Ledger (2026-11-09)

Status: Wave 0 in progress
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
| W0B | `lane/agent-os-w0b` | `D:/project/aria-agent-os-w0b` | FIXING (review BLOCK) | 100ba176, 73d264a1, e314dff3 (rebased c6d1eb06) | BLOCK (2 High, 2 Medium, 5 Low) | merged, fix in flight |
| A1 | `lane/agent-os-a1` | `D:/project/aria-agent-os-a1` | IN_PROGRESS | - | - | no |
| A2 | `lane/agent-os-a2` | `D:/project/aria-agent-os-a2` | IN_PROGRESS | - | - | no |
| B | `lane/agent-os-b` | pending | PENDING | - | - | no |
| C | `lane/agent-os-c` | `D:/project/aria-agent-os-c` | IN_PROGRESS | - | - | no |
| D | `lane/agent-os-d` | pending | PENDING | - | - | no |
| C2a | `lane/agent-os-c2a` | pending | PENDING | - | - | no |
| C2b | `lane/agent-os-c2b` | pending | PENDING | - | - | no |
| I | feature branch | `D:/project/aria-agent-os` | PENDING | - | - | no |

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

### Wave 0 integration (2026-11-09)

- `feat/agent-os-memory` at c6d1eb06: W0A rebased to 2036e833 and fast-forwarded, then
  W0B rebased and fast-forwarded; no conflicts. W0A and W0B are both in the branch, so
  the `sensitivity_ceiling` column and the non-null entity field exist together.
- Wave gate at the integrated head is re-run after the W0B fix round lands; the earlier
  per-lane evidence (W0A full unit tier, W0B full nine-module unit tier) does not cover
  the combined tree.
- A1 starts from c6d1eb06; A2 and C started from the earlier W0A head d142424f and will
  be rebased onto the integrated head before merge.

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

## Evidence to fill at each boundary

- Lane handoff: branch, commits, exact commands, counts, RED evidence, files, residuals.
- Independent review: verdict and findings per lane.
- Merge: feature-branch commit after each lane.
- Gate A/B: HEAD, commands, counts, hosted run ids, live chain id, residual risks.
- Release note and QA report at the end.
