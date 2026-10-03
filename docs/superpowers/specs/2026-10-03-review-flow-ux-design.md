# 2026-10-03 Review flow UX: outcome-aware REVIEW cards and batch decisions

Status: design approved by the operator (2026-10-03); implementation not started.

## Problem (audited live + in code)

1. **Failed work lands in REVIEW and is indistinguishable from completed work.**
   - Live stack 2026-10-03: 20 REVIEW cards — **16 linked to FAILED runs, 4 to COMPLETED**; 12 PENDING `REVIEW_REQUEST` asks (9 of them on failed runs).
   - The kanban card payload carries **no run outcome** (`KanbanController.java:41-43` returns the raw entity; the list enriches only `pendingAskCount`, `KanbanService.java:146-156`). `lastError` is written only for pickup failures (`KanbanAutoDispatchListener.java:118-133`), never for failed runs.
   - The outcome text exists only inside the ask content (`KanbanReviewAskCreator.java:77-78`: "Run {id8} completed ({STATUS}) …") and detail views. `ShortApprovalView` says "Run completed" even for FAILED (`ReviewPanels.tsx:190`).
   - The Review Queue renders a `REVIEW_REQUEST` as a generic "Approval" pill with a null `reason`, falling back to the wrong sentence "Awaiting human verification before tool execution proceeds." (`ReviewQueue.tsx:88-114`).
2. **No batch decisions.** Only per-ask `POST /approvals/{id}/decide` and one client-side per-card "Approve all" inside `DecisionPanel` (`ReviewPanels.tsx:125-134`, skips native asks, no Deny-all, unreachable from the board). Current backlog ≈ 32+ decisions.
3. **Ask hygiene.** Pending asks outlive their cards (a pending ask was found on a DONE card); an EXPIRED ask erases the outcome signal entirely (3 failed cards had no pending ask and no visible failure signal). Ask decisions do not move cards (`KanbanReviewCardListener` only links asks to cards on request; the queue decision flips the Approval row only).

## Decisions (operator, 2026-10-03)

- **D1** — Keep failed cards in the REVIEW column, but label them clearly and give them distinct actions: Rework (→ TODO, dispatches a fresh run), Accept failure (→ DONE, closes), Cancel; completed cards keep Approve → DONE.
- **D2** — Batch lives at the **card layer**; each card action also settles that card's pending review asks, so the queue drains naturally. The queue is display + single decisions.
- **D3** — All three batch actions are offered, each behind a confirm dialog that states the affected card count and, for Rework, that **N new runs will be dispatched**.
- **D4** — Approach A: enrich the listing with the run outcome; batch = client-side loop over the existing per-card transition endpoint (no new batch endpoint).

## Design

### Backend

1. **`runOutcome` on the kanban listing.** `GET /api/v1/kanban/items` enriches each item with `runOutcome: COMPLETED | FAILED | ACTIVE | CANCELLED | UNKNOWN`, derived from the linked run's status (ACTIVE covers RUNNING/PENDING/INITIALIZING/PAUSED; UNKNOWN when no linked run). One batched query over the returned items' `linkedRunId`s — same enrichment pattern as `pendingAskCount`; no per-card queries.
2. **Ask sweep in `KanbanTransitionService`.** When a card leaves REVIEW (→ DONE, → TODO, → CANCELLED), settle its still-PENDING **`REVIEW_REQUEST`** asks in the same transaction: APPROVED for accept, DENIED for rework/cancel, with a reason naming the card decision. **Hard guard: never touch native permission asks** (they carry one-use-grant semantics; a card click must not settle them). The deny path already denies card asks (`KanbanTransitionService.java:256-266`); this extends the behavior consistently to approve/request-changes and centralizes it.
3. **Data repair.** A Flyway migration (next free version at implementation time) settles stuck `REVIEW_REQUEST` asks whose card is already DONE/CANCELLED (status → DENIED with a "settled by card state" reason) — the stale-ask class observed live.

### Frontend

4. **Board cards.** REVIEW cards render an outcome chip from `runOutcome`: FAILED (red), COMPLETED (green), ACTIVE (blue), CANCELLED (dim). Copy per outcome in `ShortApprovalView`/`TaskDrawer` ("Run failed — rework or accept" vs the sign-off text); no more "Run completed" on failed cards.
5. **Batch actions.** The REVIEW column header offers **Accept all completed / Rework all failed / Cancel all** (Cancel acts on every REVIEW card, regardless of outcome), each opening a confirm dialog stating "N cards" (and for Rework: "this will dispatch N new runs"); executing runs a sequential client loop over the existing per-card transition endpoint with a per-card result summary (partial failures do not block the rest). Action → existing transition mapping: Accept → approve (card → DONE), Rework → request-changes (card → TODO + fresh dispatch), Cancel → deny (card → CANCELLED).
6. **Review Queue.** `REVIEW_REQUEST` rows get an accurate label and show the ask `content` (which carries "Run … (FAILED)") instead of the generic null-reason fallback; single decisions unchanged. (Whether the queue also needs `runOutcome` enrichment is decided during implementation only if the content text proves insufficient.)

### Error handling

- Batch: sequential, per-item results ("Accepted 3, 1 failed: <reason>"); a failed item never aborts the batch.
- Sweep runs inside the transition transaction; a sweep failure rolls the card transition back (state stays consistent) rather than leaving a moved card with stale asks.

### Testing

- Backend: outcome mapping unit/IT (incl. missing run → UNKNOWN); sweep tests — approve/rework/cancel settle REVIEW_REQUEST asks **and leave native permission asks untouched (explicit negative tests)**; migration test for the stuck-ask repair; existing deny-path tests stay green.
- Frontend: vitest for the outcome chip rendering, batch buttons' counts + confirm dialog copy, queue row text; existing suites stay green.
- Live verification on the local stack using the current 20-card mixed fixture: batch-accept the completed cards (→ DONE, asks settled, queue drains), batch-rework one failed card (→ TODO and a fresh run dispatched), confirm FAILED chips render per run outcome.

## Out of scope

- Unifying asks and cards into one review-decision model (the two-layer decoupling stays; only the sweep couples them).
- A server-side batch endpoint (revisit only if client-loop pain is demonstrated).
- Multi-select arbitrary subsets in the board UI (presets cover the audited backlog; add selection only if asked).
