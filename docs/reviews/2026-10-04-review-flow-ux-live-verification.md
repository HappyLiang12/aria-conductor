# Review flow UX — live verification (2026-10-04)

Branch `feat/review-flow-ux` at `30b97f91` (base `85f77b4c` = main after the plan commit). Environment: the
branch build served from the review-flow worktree (`scripts/start.ps1`, podman + OpenSandbox, qoder/HOST
agents, `LLM_MODEL=efficient` for qoder, local admission cap=3); the H2 database was copied from the
session worktree (v65) so the live V66 repair and the pre-existing card cohort could be exercised on real
data. Evidence below quotes the regression log, `.run/backend.log`, API payloads and drill-script output.

## Step 1 — regression on 30b97f91

- `mvn -B test -pl act-execution,act-aria,act-agent`: full green. act-aria `Tests run: 383, Failures: 0,
  Errors: 0, Skipped: 4` (pre-existing skips); act-execution 1258 green (its last change was T6; the two
  T11 reactor builds report `BUILD SUCCESS`). Log: `.superpowers/sdd/2026-10-04-review-flow-ux/t11-regression.log`.
- ITs `KanbanTransitionIntegrityIntegrationTest, RunDispatchGroupMigrationIntegrationTest,
  ReviewAskRepairMigrationIntegrationTest, RunAdmissionIntegrationTest, KanbanAutoDispatchIntegrationTest`:
  `Tests run: 13, Failures: 0, Errors: 0` (6+3+1+1+2), `BUILD SUCCESS`.
- Frontend: `npx vitest run` → `Tests 474 passed (474)` (56 files); `pnpm build` → built (only the
  pre-existing chunk-size warning).

## Step 2 — V66 repairs real stuck asks at boot

- Pre-boot snapshot on the copied DB (H2 shell, offline): **7** PENDING `LEGACY_GATE` asks on
  DONE/CANCELLED cards (the stale-ask class observed live on 2026-10-03/04).
- Boot log: `Migrating schema "PUBLIC" to version "66 - settle stale review asks"` →
  `Successfully applied 1 migration ... now at version v66`.
- Post-boot spot-checks (4 of the 7): `719e7087`, `d8f7a599`, `e0ae2df0`, `b734a7aa` → all
  `DENIED | settled by card state`. The Review Queue rail rendered `0 PENDING — queue is clear` afterwards.

## Step 3 — live drills

**A — native web tools auto-approve (D5).** A qoder/HOST research run (`b596b62f`, prompt: find Naha's
population with a source) COMPLETED with a real answer (316,995 + a worldpopulationreview URL).
- Pending web asks after the run: **0**.
- `backend.log`: `Run ...: platform MCP tool WebSearch auto-approved by the configured read-only policy
  (auto-approved: read-only platform tool (aria.mcp.auto-approve-read-tools))` (+ the same for `WebFetch`;
  the reworked research runs show the identical lines, including 4 WebFetch auto-approvals in one run).

**B — an unanswered ask settles visibly (D6, the drill's silent-lapse class).** A chat turn asked Aria to
dispatch one `run_agent`; the ask (`784c763e`) was deliberately never decided.
- Parent run terminal (COMPLETED) after 550 s; then:
- `approval.expired` notification: `Approval 784c763e-... expired without a decision (run ended). Tool call
  skipped: mcp__aria-conductor__run_agent.` (tool name present — T5's extension, live).
- The ask's `approval.requested` notification: read=true (flip).
- Its card `Review: tool call - mcp__aria-conductor__run_agent (run 19a4de84)` → **CANCELLED**.
- Ask final state: `EXPIRED | run ended` (T3's run-end settle; nothing silent).

**C — a live decision settles the card and flips the request (D6/R-RFUX3).** A dispatch ask (`fca7cb48`)
approved via the operator API while the parent run was RUNNING:
- Card `Review: tool call - mcp__aria-conductor__run_agent (run 78b43dc6)` → **DONE** (the settled-ask
  exemption from the active-run DONE guard works live), `approval.requested` read=true.

**D — dashboard batch + chips (D1-D4, D7; browser-driven).** On the 12-card REVIEW cohort copied from the
session DB (2 FAILED / 10 COMPLETED, chips rendered on the compact cards):
- `Accept all completed (10)` → dialog "This accepts 10 cards. Each card moves to Done and its pending
  review asks are settled." → Confirm → DONE 21→31, REVIEW 12→2.
- `Cancel all` preset opened with "This cancels 2 cards..." and was dismissed (verified, not executed);
  the per-card `Cancel task` was executed instead on one card (below).
- `Rework all failed (2)` → dialog "This reworks 2 cards — this will dispatch 2 new runs..." → Confirm →
  both cards moved to Todo→In-Progress and two fresh runs were dispatched live (`66f975ca` Ritz,
  `c9e391a7` Okuma, prompts `Kanban task: TASK (retry): ...`).
- Per-card `Cancel task` on the in-progress Okuma card (`2b6f3ee7`) → dialog "This cancels card 2b6f3ee7
  and stops the work in progress on it..." → Confirm → card CANCELLED and its live run `c9e391a7` reached
  CANCELLED.
- The new tool-call cards carry the D7 title (`Review: tool call - mcp__aria-conductor__run_agent (run ...)`),
  and the Review Queue rail renders the accurate `Review` label with the ask content (no generic fallback).

End state after the drills: `{"DONE":31,"CANCELLED":25,"REVIEW":1}` (the 1 = a reworked run that completed
during the drill and whose fresh review ask awaits the operator — normal lifecycle).

## Not verified live (recorded honestly)

- The `Cancel all` preset dialog was verified but its execution was deliberately not run (it would have
  cancelled the two freshly reworked cards); the per-card Cancel executed the same path.
- The batch per-item failure summary line was not observed live (no partial failure occurred); it is pinned
  by the KanbanBoard unit tests.
- The ReviewWorkspace rail's ask-ful chip parity (T7 minor) was not exercised in the browser.
- The benign double-settle loser warn (T4) did not appear in this drill's logs.

## Accepted residuals (final review triage, 2026-10-04)

The final whole-branch review accepted the following limitations; each is recorded so the ledger matches
the code:

- **Settled-ask exemption is time-unbounded** (`KanbanService.isSettledNativeAskCard`,
  `KanbanService.java:353`): a card with at least one settled ACP ask and no PENDING ACP ask stays exempt
  from the run-active DONE guard (`KanbanService.transition`, `:223-226`) on every future REVIEW→DONE
  transition, however old the settle. Consequence is bounded: a run can keep working on a card that is
  already DONE, and the mirror later creates a replacement card for a later ask (the link path reuses only
  REVIEW/TODO/IN_PROGRESS cards, `KanbanReviewCardListener.linkReviewCard`).
- **Link-side re-check settles cards for any settled ask; the settle listener is native-only**
  (`KanbanReviewCardListener.settleCardIfAskSettledAfterLink`, `:191` — no source filter — vs
  `ApprovalSettleCardListener.settle`, `:74` — `ACP_PERMISSION` only): an ask that settles while its card
  is being linked settles the card regardless of source, while the event-driven settle path fires only for
  native asks. For legacy asks in the link window the outcome is therefore timing-dependent; accepted as
  ledger-bounded (the V66 repair and the run-end sweep cover the stale-ask class).
- **V66 also flips pre-stamp native rows** (`V66__settle_stale_review_asks.sql`): native asks persisted
  before the `ACP_PERMISSION` source stamp carry `LEGACY_GATE` and are indistinguishable from legacy asks,
  so the migration's card-state repair converts them to `DENIED | settled by card state`. Benign for
  long-dead asks on terminal cards — their decision surface is gone either way.
