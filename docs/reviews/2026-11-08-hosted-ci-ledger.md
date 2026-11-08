# Hosted CI ledger — fix/sdd-runtime-hardening

## Run 42389804053 (476d51ff, workflow_dispatch)

- **Result**: 14 jobs, 13 success, 1 failure.
- **Failed job**: `E2E Playwright (shard 2/4)`, step `Run E2E shard`.
- **Class**: generated/contract drift (test locator), not a product defect.
- **Root cause**: `KanbanBoard.tsx` now renders the F24
  `<span class="pill warn">Review</span>` badge on a REVIEW card that has an open
  ask. `e2e/kanban-hitl.spec.ts:222` used the unscoped `reviewCard.locator('.pill.warn')`
  locator, so Playwright strict mode resolved two elements — the `Review` badge and
  the `1 asks` badge — and the assertion failed. Deterministic: the retry failed the
  same way with a different card id.
- **Evidence**: run 42389804053, job 128455202278 (`E2E Playwright (shard 2/4)`).
  The failing log resolved the locator to
  `<span class="pill warn">Review</span>` and `<span class="pill warn">1 asks</span>`
  (strict-mode violation, identical on retry); the run's
  `playwright-results-shard-2` artifact holds the screenshot and trace.
- **Fix**: scope the locator to the ask badge by text
  (`reviewCard.locator('.pill.warn', { hasText: 'asks' })`); the asserted text stays
  `1 asks`, so the test intent (the card advertises its pending ask) is unchanged.
- **Blast radius**: the only other `.pill.warn` query in the E2E tree
  (`journey-agent-run-report.spec.ts:75`) already selects the `n asks` text among
  all warn pills, so it is not affected.
- **Local gate**: `pnpm --dir agent-control-tower/act-dashboard test -- --run`
  -> 564 passed / 68 files; `pnpm --dir agent-control-tower/act-dashboard exec
  playwright test --list e2e/kanban-hitl.spec.ts` -> lists the spec without errors.
  The shard itself is verified by the hosted re-run.

## Run 42390418320 (a73ee161, workflow_dispatch) — GREEN

- **Result**: 15 jobs, 15 success, 0 failures.
- **Jobs**: Detect Changed Trees; Build Backend + Harness Distribution; Java Unit
  Tests (common-agent, execution, knowledge-aria, api-mcp-app); Java Integration
  Tests (Failsafe); Frontend Build Check; TS MCP Server Tests + Coverage; E2E Smoke
  Test (deterministic harness); E2E Playwright shards 1-4/4; Docker Build Verification.
- **Head**: `a73ee161` (contains the shard-2 locator fix; the code head of the
  local full gate is `eed484e4`, and the only delta is this spec plus docs).
- **Local evidence**: local full gate at `eed484e4` 2730/0/0/14; bridge 60/60;
  dashboard 564/564 after the spec change.

## Run 42400077779 (e4f727ec, workflow_dispatch) — GREEN (release head)

- **Result**: 15 jobs, 15 success, 0 failures.
- **Head**: `e4f727ec` = code `23cef4ac` (the five-round-reviewed R7/R10 hardening)
  plus docs only. Local full gate at that head: 2757 tests, 0 failures, 0 errors,
  14 skipped; `HostExecutionBackendIntegrationTest` 42/0.
- **Jobs**: same matrix as run 42390418320, all success including the four
  Playwright shards, the harness smoke lane, and the Docker build verification.
- **Review**: independent reviewer round 5 approved; rounds 1-4 findings were all
  fixed and re-reviewed (see the delivery ledger `release_gate.review_rounds`).

## Run 42420975810 (420dbb7e, workflow_dispatch) — GREEN (final head, R11 fix)

- **Result**: 15 jobs, 15 success, 0 failures.
- **Head**: `420dbb7e` = code `a46fc109` (dead-root stop-proof fix `6f6270e7` +
  `835f095e`, comment corrections `a46fc109`) plus docs.
- **Local gate at `a46fc109`**: 2762 tests, 0 failures, 0 errors, 14 skipped;
  `HostExecutionBackendIntegrationTest` 47/0.
- **Live dead-root re-check**: Aria run `54d94c47` reached WAITING_INPUT, its bridge
  exited, and the cancel returned 200; the run settled CANCELLED with
  `finalized complete=true` and the identity-only stop-proof INFO. No StopProof
  failure.
- **Note**: run 42400077779 was green at `e4f727ec`, but the R11 live cancel
  reproduced the dead-root failure on that code; it is superseded by this run.
