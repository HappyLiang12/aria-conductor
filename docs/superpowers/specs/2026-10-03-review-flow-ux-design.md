# 2026-10-03 Review flow UX: outcome-aware REVIEW cards and batch decisions

Status: design approved by the operator (2026-10-03); amended 2026-10-04 (amendment: Qoder permission-ask UX); implementation not started.

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

---

# Amendment (2026-10-04): Qoder permission-ask UX

Added after the 2026-10-04 qoder/HOST Okinawa drill (3 researchers, 2 fact-checkers, one synthesis; every ask
individually operator-approved). Complements the decisions above; nothing in D1-D4 changes.

## Problem (observed live in the 2026-10-04 drill)

1. **Ask storm.** One researcher's run raised about 95 asks for the Qoder CLI's OWN native tool `WebSearch`
   (`mcp__aria-conductor__run_agent` dispatches and native web reads together: 271 approved asks in one drill).
   The auto-approve policy cannot cover these today: a native ask without the platform-MCP prefix
   (`mcp__aria-conductor__`) is never covered by `aria.mcp.auto-approve-read-tools`, by design ("the core's own
   tool keeps the per-call operator approval"). Dashboard-only operation therefore needs a human clicking within
   minutes of every web call.
2. **Invisible ask expiry, three layered windows.** The CLI's own wait for a permission answer is the effective
   bound (minutes): it stops waiting, treats the call as denied, and continues (observed: Aria reported "you
   rejected the first tool call" for a call no one had decided). The bridge's `permissionTimeoutMs` (default
   600000 = 10 min) bounds the bridge-to-CLI reply. The platform ask row's `expiresAt` is the run's task
   deadline (a late backstop; `PermissionCoordinator.register` persists `runtime.spec().deadline()`), and a
   leftover ask is settled at run end with reason "Run cancelled" - even for a COMPLETED run. The operator sees
   none of this: the ask simply stops existing.
3. **Zombie artifacts after settle.** The auto-created `Review: tool call (run X)` card stays after its ask
   settles (deciding again returns 409; an expired ask leaves the card in REVIEW). The `approval.requested`
   notification stays unread even after the ask was decided (about 330 unread after one drill).
4. **No context on tool-call asks.** The card title and queue row say `Review: tool call (run X)` - no tool
   name, no arguments, though both exist (`approval.reason` names the tool; `AcpPermissionRequest.argumentsJson`
   carries the arguments).

## Decisions (operator, 2026-10-04)

- **D5** - Web reads must not ask: extend the auto-approval policy to the Qoder CLI's native read-only web
  tools via the operator-configured list (default list gains `WebSearch`, `WebFetch`). `run_agent` and every
  other tool keep the per-call operator approval.
- **D6** - Settle must propagate: an ask settling (approved / denied / expired) settles its card and flips its
  notification; expiry additionally raises a visible `approval.expired` notification. The bridge window stays
  10 min (documented, not lengthened).
- **D7** - Tool-call cards and Review Queue rows carry the tool name and an arguments excerpt.

## Design

1. **Policy: the native clause (act-execution).** `PlatformMcpAutoApproval` gains a native-tool clause: a
   `NATIVE_TOOL` ask whose normalized name is on `aria.mcp.auto-approve-read-tools` auto-settles through the
   same single-allow-once reply path the manual decision uses (the existing single-ALLOW_ONCE-option guard
   stays: no single option, no auto-settle). The platform-prefix clause is unchanged; a native ask not on the
   list keeps the per-call approval (this amendment is the operator decision the 2026-09-29 policy required).
   Defaults gain `WebSearch` and `WebFetch`; `run_agent` stays off the list.
2. **Settle events (act-common; recon adjustment).** Planning recon found the lifecycle events already exist:
   `ApprovalDecidedEvent` (published by `ApprovalGate.decideApproval`, which the native decide path already
   routes through) and `ApprovalExpiredEvent` (published by the legacy timeout path only). No third event is
   introduced. The coordinator's `expire()` gains the `ApprovalExpiredEvent` publish (carrying the tool name),
   and the run-end settle for native asks is added to the coordinator (item 3), so the two existing events
   cover every settle path.
3. **Native lifecycle fix (defect found in planning recon).** `Approval.source` defaults to `LEGACY_GATE` and
   `PermissionCoordinator.register()` never stamps it, so native ACP asks persist as LEGACY_GATE: the
   repository guards meant to protect them (`denyPendingByKanbanItemId` and `markStaleByKanbanItemId` filter
   LEGACY_GATE; `cancelAllPendingForRun` skips ACP_PERMISSION) all misfire - observed in the drill as the
   ask settled "Run cancelled" by the legacy run-cancel sweep. `register()` stamps `ACP_PERMISSION`; the
   coordinator gains `cancelPendingForRun(runId)` settling a run's still-pending native asks at run end
   (EXPIRED, reason "run ended", waiter released, `ApprovalExpiredEvent` published); the engine's run-end
   hook calls it beside the gate sweep.
4. **Card settle (act-execution/kanban).** A listener on `ApprovalDecidedEvent` + `ApprovalExpiredEvent`
   resolves the approval's `kanbanItemId` (linked by `KanbanReviewCardListener.linkReviewCard`) and
   transitions the card: APPROVED -> DONE, DENIED/EXPIRED -> CANCELLED. `KanbanReviewCardListener` gains a
   guard: never create or link a card for an already-settled ask. The D2 sweep guard is untouched - with the
   source stamp of item 3 the repository guards become effective.
5. **Notification flip and expiry notice (act-aria).** On `ApprovalDecidedEvent`/`ApprovalExpiredEvent`: mark
   the matching `approval.requested` notification(s) (type + resourceId = approvalId) read. The
   `approval.expired` notification itself already exists (UX-6); its text gains the tool name (from the
   extended event) and the frontend `NotificationType` union gains `approval.expired` with bell rendering.
6. **Ask context (frontend + card creation).** Tool-call cards carry the tool name and an arguments excerpt
   in title/description at creation (the queue rows on the Ops surface already render `toolName` and the
   native facts; the Overview rail and the board cards are the gaps).

## Error handling

- Settle listeners are best-effort like `KanbanReviewCardListener`: a failure is logged and never breaks the
  settle transaction (the settle itself is the authority; the event is the propagation).
- A card already settled by the operator (or missing): the transition refusal is logged, never retried, and
  the notification flip still runs.

## Testing

- Unit: native-clause coverage (a listed native ask auto-settles when a single ALLOW_ONCE option is offered;
  an unlisted native ask stays PENDING; a listed ask without a single option stays PENDING); `register()`
  stamps ACP_PERMISSION; the run-end `cancelPendingForRun` settles pending native asks with the "run ended"
  reason and publishes `ApprovalExpiredEvent`; the legacy sweeps (deny/stale/cancelAllPendingForRun) leave
  ACP_PERMISSION rows untouched (explicit negative tests).
- Listener: card DONE on decide-approved, CANCELLED on decide-denied and on expired; no card creation for an
  already-settled ask; `approval.requested` flipped read on both events; the expiry notification carries the
  tool name (creation itself already exists since UX-6).
- Existing suites: the sweep negative tests (native asks untouched) stay green; `PlatformMcpAutoApprovalTest`
  extended; notification tests extended.
- Live: rerun the qoder drill - zero `WebSearch` asks; tool-call cards reach terminal states with their asks;
  the unread count does not grow with settled asks; leave one `run_agent` ask unanswered - the expiry notice,
  the card settle and the flip all fire (compare against the drill's silent lapse).

## Out of scope (amendment)

- No remembered or session-wide grant for `run_agent` (per-call approval stands).
- No window lengthening; the CLI's internal wait bounds the effective window and is documented here, not
  changed.
- No read-side computed card state (settle is event-written).
- Usage/token accounting for qoder runs (observed `totalTokensUsed=0`) is a separate follow-up, not this
  amendment.
