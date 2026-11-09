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
| W0A | `lane/agent-os-w0a` | `D:/project/aria-agent-os-w0a` | IN_PROGRESS | pending | pending | no |
| W0B | `lane/agent-os-w0b` | `D:/project/aria-agent-os-w0b` | IN_PROGRESS | pending | pending | no |
| A1 | `lane/agent-os-a1` | pending | PENDING | - | - | no |
| A2 | `lane/agent-os-a2` | pending | PENDING | - | - | no |
| B | `lane/agent-os-b` | pending | PENDING | - | - | no |
| C | `lane/agent-os-c` | pending | PENDING | - | - | no |
| D | `lane/agent-os-d` | pending | PENDING | - | - | no |
| C2a | `lane/agent-os-c2a` | pending | PENDING | - | - | no |
| C2b | `lane/agent-os-c2b` | pending | PENDING | - | - | no |
| I | feature branch | `D:/project/aria-agent-os` | PENDING | - | - | no |

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

## Evidence to fill at each boundary

- Lane handoff: branch, commits, exact commands, counts, RED evidence, files, residuals.
- Independent review: verdict and findings per lane.
- Merge: feature-branch commit after each lane.
- Gate A/B: HEAD, commands, counts, hosted run ids, live chain id, residual risks.
- Release note and QA report at the end.
