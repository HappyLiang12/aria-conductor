# Concurrency & Multi-Agent E2E — Findings Ledger

Stack under test: `main`@`a719f61` (worktree `3hwPRj`), profile `h2`, backend `:18080`, ADK `:9500`, frontend `:5173`, **no LLM key** (real-LLM tiers skipped). HikariCP pool = 8; circuit-breaker caps `max-tokens-per-run=100000`, `max-iterations=50`.

Test assets (new): `act-dashboard/e2e/api/*.api.spec.ts` (5 specs) + `fixtures.ts` concurrency/metrics helpers + `playwright.config.ts` `api` project. Evidence: `e2e-evidence/20260726-233448/` (`api-run.log`, `api-results.json`, `metrics-*.json`).

Result of the API tier: first run **15 passed / 3 skipped / 5 failed** surfaced F1+F2 (plus a test-design fix); after fixes the re-run is **20 passed / 3 skipped / 0 failed** (evidence `e2e-evidence/20260727-000359/`). Java: `ApprovalGateConcurrencyTest` 5/5, `StatusTransitionPropertyTest` 7/7; module suites `act-agent` + `act-knowledge` green with coverage gates met. UI tier: `concurrent-collaboration-ui.spec.ts` green.

| ID | Area | Sev | Repro (spec) | Evidence | Suspected source | Class | Status |
|----|------|-----|--------------|----------|------------------|-------|--------|
| F1 | Knowledge approval | P1 | `knowledge-approval-concurrency.api.spec.ts:3` | before `doubleWins=5/8` `[200,200]`; after `doubleWins=0/8` `[200,409]` | `KnowledgeService.reviewKnowledge` L184 guard is read-check-then-write; `KnowledgeItem` has no `@Version` | bug (data race / governance bypass) | **fixed + verified** |
| F2 | Workflow lifecycle | P1 | `workflow-state-concurrency.api.spec.ts:7` + load `B` | before **500 UnexpectedRollbackException** (12/12 under load); after **404**, load `errorRate=0` | `WorkflowService.startStep` catch persists into an already rollback-only tx (createRun on missing agent poisons it) | bug (500 on invalid input) | **fixed + verified** |
| O1 | Run execution | P2 | observed in `workflow` retry-race attempt | chain stayed `RUNNING` >60s with `apiKey=MISSING` and no active LLM provider | run does not fail-fast when no LLM provider is configured/reachable | observation / UX | documented |
| O2 | Skill system | P2 | `skill-lifecycle.api.spec.ts` A/E | `/api/v1/skills` = `[]`; no REST path authors a `SkillDefinition` | skills only via `SelfImprovementService.promoteToSkill` (needs real prompt-calls); `/knowledge/promote` makes a *KnowledgeItem*, not an assignable skill | coverage/feature gap | documented |
| O3 | Workflow cancel | P3 | `workflow-state-concurrency.api.spec.ts:3` | cancel guard held (`doubleWins=0/8`, `[200,400]`) but underlying run is not cancelled | `cancelWorkflow` sets chain CANCELLED, doesn't cancel the in-flight run → orphaned run | observation | documented |
| O4 | ADK lifecycle | P3 | process audit during restart | ~30 orphaned ADK `python.exe` accumulated across runs; not promptly reaped | `AdkProcessReaper` leaves subprocesses when the parent is killed / on churn | observation | documented |

## F1 — Concurrent opposing knowledge reviews both succeed (TOCTOU)
**Symptom.** Firing `APPROVED` and `REJECTED` at the same PENDING item: 5 of 8 items accepted **both** (`[200,200]`), the other 3 correctly returned `[200,409]`. The `if (status != PENDING) throw` guard in [`KnowledgeService.reviewKnowledge`](agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/service/KnowledgeService.java) is a read-then-write with no row lock/`@Version`, so two transactions both read `PENDING` and both commit (last-writer-wins), firing conflicting side-effects (e.g. a spurious `KnowledgeApprovedEvent`).

**Fix (migration-free).** Replace the read-check with an **atomic conditional update** `UPDATE KnowledgeItem SET status=:target WHERE id=:id AND status='PENDING'`. Exactly one concurrent writer flips the row (returns 1); the loser matches 0 rows → `InvalidStateTransitionException` (409). No schema change.

**Regression.** `KnowledgeServiceTest` — sequential double-review still 409; E2E `knowledge-approval-concurrency` race now passes (`doubleWins=0`).

## F2 — Workflow creation 500s on an unresolvable agent
**Symptom.** `POST /workflows` with a non-existent `agentId` returns **500** `UnexpectedRollbackException: Transaction ... marked as rollback-only`. Root cause: `startStep` calls `RunService.createRun` (same tx); the missing-agent `ResourceNotFoundException` marks the shared tx rollback-only; `startStep`'s catch then `save()`s a FAILED chain and returns → commit throws. Also fired 12/12 times in the mixed-load probe, inflating the error rate.

**Fix (migration-free).** Validate every step's `agentId` up front in [`WorkflowService.createAndStart`](agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/service/WorkflowService.java) and throw a clean `ResourceNotFoundException` (→ 404) before any run is started — mirroring the YAML path (`execute-yaml` already rejects unknown agents). No poisoned transaction, no half-created chain.

**Regression.** `WorkflowServiceTest` — bad agent → `ResourceNotFoundException` (not `UnexpectedRollbackException`); E2E `workflow-state-concurrency:7` now `< 500`.

## Passing coverage (evidence the systems are healthy)
- **Knowledge**: lifecycle submit→query→approve→Git-backed version ✓; 24 concurrent submits all persist (stresses `FilesystemMirror.sync`) ✓.
- **Workflow**: define/query, terminal guards, merge, execute-yaml, concurrent-cancel safety (no 5xx, valid terminal) ✓.
- **Multi-agent**: 3-role BA→Dev→QA chain defined in order, shared approved knowledge queryable, two concurrent chains isolated, approval-gate API guarded ✓.
- **Load/stability (LLM-free)**: 100-read burst all 2xx (p95 ≈ 0.9 s); mixed load @16 `errorRate=0`; saturation @48 (6× pool) `serverErrors=0`, stack healthy afterwards → graceful degradation ✓.

## Skipped (require a real `LLM_PROVIDER_API_KEY`)
`multi-agent-collab:5` (BA→Dev→QA handoff), `skill-lifecycle:D/E` (skill toggle race / execution — also blocked by O2), and the UI real-LLM tier. Re-run with a key exported to exercise these.
