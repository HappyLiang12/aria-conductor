# Kanban HITL Redesign — Design

Date: 2026-09-08
Status: Approved (brainstorm validated via visual companion, Q1-Q8)
Scope: Kanban board simplification, drag-and-drop orchestration, Review column as the single human-in-the-loop entry point, dashboard HITL signal consolidation.

## 1. Goals

- Make the Kanban board the primary control surface: the operator moves cards, Aria executes.
- Replace the label-based column hack (`qa-gate` / `review` / `backlog` labels) with real status-driven columns.
- Reduce columns from 7 to 5; cancel becomes a card action, the QA gate column is removed (QA remains an internal step of In Progress runs).
- Make the Review column the single human-in-the-loop (HITL) hub: every item waiting on the user (approvals, questions, spec reviews) is visible there, with a decision panel per card.
- De-emphasize the separate approval-gate surface on the dashboard; consolidate to one "Waiting on you" signal.

## 2. Decisions (from brainstorm)

| # | Decision |
|---|----------|
| D1 | 5 columns: Backlog / Todo / In Progress / Review / Done. Cancel = card action, not a column. QA Gate column removed. |
| D2 | Pause semantics: step-boundary pause (existing `RunContext.pause()` hook). Progress and trajectory preserved; resume continues from the next step. |
| D3 | Pickup trigger: card entering Todo is a dispatch intent — Aria assigns an agent (two-phase, see 4.2) and the run starts automatically. Backlog = queued, never executes. |
| D4 | Review column is the single HITL entry point. Approvals page retires (redirect). Orphan approvals auto-create Review cards. |
| D5 | Review card detail: TaskDrawer collapsed view (decision zone on top) + Expand button switching to a full-page review mode (spec/report left, decision rail right); dashboard side widgets reflow to the bottom row while expanded. Board itself shows no overlay; the Review column is never covered. |
| D6 | New Task creation: enhanced modal — title, large description textarea, priority segmented control, assign-to dropdown sourced from agent templates (default "Aria auto-assign"), two submit targets (Todo / Backlog). |
| D7 | Dashboard signal: Executive Summary "PENDING APPROVALS" card becomes "Waiting on you (N asks)"; sidebar Approvals entry removed; notification bell approval events route to the Review card. |
| D8 | Architecture: backend orchestrator — one transition endpoint encapsulates all side effects (approach A). |

## 3. Data Model and Migration (V52)

### 3.1 KanbanStatus

Add `BACKLOG`. Transition table (`KanbanService.VALID_TRANSITIONS`):

```
BACKLOG      -> TODO, CANCELLED
TODO         -> IN_PROGRESS, BACKLOG, CANCELLED
IN_PROGRESS  -> TODO, BACKLOG, REVIEW, DONE, CANCELLED
REVIEW       -> IN_PROGRESS, TODO, DONE, CANCELLED
DONE         -> (terminal)
CANCELLED    -> (terminal)
```

Note: `IN_PROGRESS -> TODO/BACKLOG` is the pause path (drag the card back); `TODO -> BACKLOG` re-queues without executing.

- `BLOCKED` stays in the enum but is no longer used by the board. Migration rewrites existing `BLOCKED` items to `REVIEW`.
- The frontend column filters that rely on labels (`backlog`, `review`, `qa-gate`) are removed; migration recomputes item statuses from labels before the columns stop reading them.

### 3.2 Approval extensions (HITL ask)

New nullable columns:

- `kanban_item_id` — links the ask to a card; backfilled by the auto-card listener for orphan approvals.
- `ask_type` — `APPROVAL | QUESTION | REVIEW_REQUEST` (default `APPROVAL`, preserving current behavior).
- `context_md` (TEXT) — agent-provided background: why it is asking, impact, options considered.
- `options_json` — suggested options array (`[{label, suggested}]`).
- `answer` (TEXT) — free-text answer for QUESTION asks.

### 3.3 KanbanItem extensions

- `description` (TEXT, nullable) — from the new-task modal.
- `agent_template_id` (nullable) — explicit template chosen in the modal; used by the assignment phase.
- `last_error` (nullable) — shown on the card when pickup fails.

## 4. Backend: Transition Orchestrator

### 4.1 Endpoint

`POST /api/v1/kanban/{id}/transition` — single transactional endpoint (new `KanbanTransitionService` in `act-execution`). Body: `{ toStatus, feedback?, agentTemplateId? }`.

| Transition | Side effects |
|------------|--------------|
| -> BACKLOG | status change only |
| -> TODO | two-phase pickup (4.2) |
| -> IN_PROGRESS | manual force-start with existing assignee (override Aria's choice) |
| REVIEW -> TODO | carries `feedback`; open asks marked `CHANGES_REQUESTED`; run re-dispatched with context |
| -> DONE | existing linked-run protection unchanged |
| -> CANCELLED | cancels/pauses linked run; open asks auto-DENY with reason "task cancelled" |

Each transition publishes `KanbanTransitionedEvent` (existing WS broadcast path) plus `KanbanAssigningEvent` during phase 1.

### 4.2 Two-phase pickup on -> TODO

1. **Assign phase**: if the card has no assignee, pick an agent by rule — exact `agentTemplateId`/template-label match against healthy agents, falling back to the first healthy agent (rule-based only, no LLM call in v1). Writes `assignee` + `linkedAgentId`. Frontend shows "Aria assigning..." driven by `kanban.assigning` WS event.
2. **Pickup phase**: creates and starts a run via `RunService.createRun/startRun`, then transitions the item to `IN_PROGRESS`.

Failure semantics: pickup failure (agent/ADK unavailable) leaves the card in `TODO` with `last_error` set; no automatic retry — the user re-drags to retry.

### 4.3 Orphan approval auto-carding

A listener on `ApprovalRequestedEvent`: if no kanban item is linked, create a `REVIEW` card (title from run/workflow name) and backfill `kanban_item_id`. Card cancellation auto-DENIEs its pending asks (coexists with the existing 30-minute approval TTL).

### 4.4 Concurrency

Optimistic concurrency: transitions verify the item version; a losing writer gets 409. Pause race (card dragged back while the run just completed): orchestrator trusts DB run status; a finished run only changes the card status, no error.

### 4.5 MCP exposure (rewire, not new tools)

`transition_kanban_item` already exists on two surfaces; both are rewired to the new orchestrator semantics (two-phase pickup, feedback, agentTemplateId) while keeping the tool name and backward-compatible argument shape:

- `packages/mcp-server/src/tools/kanban.ts` — `transition_kanban_item` input schema gains optional `feedback` / `agentTemplateId`; the server routes the call to the new `POST /api/v1/kanban/{id}/transition` endpoint instead of the legacy transition API.
- `act-aria` `KanbanToolHandler.transition_kanban_item` — delegates to `KanbanTransitionService` (same path as REST), so Aria-driven moves and external MCP clients share one semantic.

No new tool names; `list/get/create/update_kanban_item` responses gain `pendingAskCount` and `lastError` fields.

## 5. Frontend

### 5.1 Board and DnD

- Native HTML5 drag & drop (no new dependency). Cards `draggable`; columns are drop zones; invalid targets render a disabled style from the transition table (DONE cards cannot be dragged out).
- Optimistic move on drop (card shows a spinner), then the transition call; on failure the card snaps back with a toast carrying the backend reason.
- Column rendering by real `status`; label hack removed. Review column header: amber highlight + count; each card carries a `pendingAskCount` badge (kanban list response includes it).
- Flash animation on `kanban.transitioned` kept as-is.

### 5.2 TaskDrawer: collapsed / expanded

- Collapsed (default): amber "NEEDS YOUR DECISION" zone at the top listing asks (title + suggested option); below it the existing Status / Description / AC / Artifacts sections. An Expand button in the header.
- Expanded (full-page review mode): left pane renders the spec/report markdown (existing `MarkdownViewer` component), right rail is the decision column (per-ask radios / free-text answer, comment box, action bar). Dashboard right-column widgets (Agent Team, Review Queue, Activity Timeline) and the briefings reflow into one row at the bottom while expanded; Collapse restores the dashboard layout.
- Actions per ask: choose an option or answer free text; card-level: `Approve all` / `Request changes` (feedback textarea; submit = transition to TODO with feedback) / `Deny`. Prev/next navigation across Review cards in both drawer and expanded modes.

### 5.3 New Task modal

Stacked form: Title input; Description = large textarea (6-8 rows); Priority = segmented control (LOW/MEDIUM/HIGH/CRITICAL); Assign to = dropdown from the agent templates API (default "Aria auto-assign"); footer buttons `Create in Todo` and `Backlog` (map to `targetStatus`).

### 5.4 Dashboard HITL signal

- Executive Summary card: `PENDING APPROVALS` renamed to `Waiting on you (N asks)`; amber styling when N > 0; click navigates to the Kanban board and opens the first Review card's drawer.
- Sidebar Approvals entry removed; the Approvals page becomes a redirect.
- Notification bell approval-type events route to the corresponding Review card (reuse the fine-grained `type` routing key convention from `notificationRoutes`).

## 6. Error Handling and Edge Cases

- Pickup failure: card stays in TODO with `last_error` on the card face; re-drag retries.
- Invalid transition (e.g., dragging a DONE card): 400, card snaps back, toast shows the backend reason.
- Request-changes with unanswered QUESTION asks: they are marked `STALE`; the agent re-asks after the re-run.
- Cancel with pending asks: auto-DENY with reason; approval TTL checker remains as a backstop.
- Concurrent drags: 409 on version conflict, toast informs the operator.

## 7. Testing Strategy

- Backend unit (TDD): `KanbanTransitionService` paths (two-phase pickup, pause race, cancel-deny chain); update `StatusTransitionPropertyTest`-equivalent for the new KanbanStatus table (new enum values must be added to the transition map to avoid NPE).
- Backend unit: `act-aria` `KanbanToolHandler.transition_kanban_item` delegating to the orchestrator (argument mapping, error strings).
- MCP server (`packages/mcp-server`, vitest): updated `transition_kanban_item` schema and routing to the new endpoint; existing `kanban.test.ts` extended, `npx vitest run` must stay green.
- Backend integration (failsafe / `mvn verify`, h2 profile): transition -> run create/start -> pause -> resume full chain; orphan approval auto-carding listener; transition via MCP tool path equals REST path outcome.
- Frontend unit (Vitest): DnD legality matrix, drawer collapse/expand state machine, modal validation, ExecSummary card routing.
- E2E (Playwright): drag Todo -> In Progress with mock ADK; request-changes loop; review decision approve/deny; dashboard signal navigation.

## 8. Out of Scope

- Automatic draining of the Todo queue (no scheduler; pickup is always user-action driven).
- Multi-board support; per-column WIP limits; board persistence of column order.
- Rework of approval types beyond the new columns (tool-call approvals keep their current flow, they just surface through Review cards).

## 9. Accepted deviations (implementation record)

- Agent assignment is rule-based only (template/label match → first healthy agent); no LLM fallback in v1 (§4.2).
- CHANGES_REQUESTED ask state is represented as EXPIRED + reason "superseded by request changes" (ApprovalStatus has no dedicated value).
- Answer endpoint (`POST /approvals/{id}/answer`) intentionally does not release ApprovalGate futures or publish ApprovalDecidedEvent; the frontend routes gate asks through /decide and QUESTION asks through /answer (§5.2).
- Pickup failure semantics: predictable failures (agent not found / retired / unhealthy / no eligible agent) leave the card in place with `lastError`; unexpected createRun failures propagate and roll the whole transition back (§4.2/§6 refinement).
- BACKLOG→IN_PROGRESS is rejected on all surfaces; Backlog items dispatch only via Todo (D3 enforced at the orchestrator, the board legality mirror, and the MCP/Aria tool documentation).
- REVIEW→IN_PROGRESS with a terminal linked run transitions the card without re-dispatch ("force-continue" is a manual operator decision; re-dispatch goes through TODO).
- Notification bell routes approval.requested to the overview; opening the exact Review card is done from the Waiting-on-you card (§5.4 reduction).
- `kanban.assigning` is broadcast during the synchronous transition request; the initiating client sees the optimistic move instead (the indicator serves concurrent viewers).
- Pre-existing (not introduced here): `approveApproval`/`rejectApproval` in `api/approvals.ts` try `/approve`|`/reject` endpoints that no controller exposes and fall back to `/decide` — follow-up cleanup candidate.
