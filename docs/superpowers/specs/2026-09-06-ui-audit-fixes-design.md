# UI Audit Fixes Design

- **Date**: 2026-09-06
- **Status**: Approved in brainstorm (scope + approach); pending spec review
- **Source**: UI audit reports `ui-audit-browser.md` (5 BUG / 1 STUB / 9 UX-GAP / 39 OK) and `ui-audit-code-stubs.md` (15 findings), cross-checked
- **Scope**: ALL 18 findings + the audit-incident knowledge-state revert
- **Delivery**: single branch `fix/ui-audit-findings` (from main), single PR, commit groups (a)-(e)

## 1. Background

Browser-verified sweep of all 11 routes plus a code-side stub scan confirmed the user-reported problems: dead entries left from demo iterations, Live Activity Stream with no history, and unreachable approval markdown. Root causes and exact mismatch points are in the two audit reports (repo root). This design fixes every finding and remediates the audit incident.

Success criteria:
1. A chain stuck in WAITING_APPROVAL (approval expired) is visible and recoverable from the Approvals page.
2. Opening the Live Activity Stream shows the run's past thinking/tool progress, then appends live frames without dropping any.
3. Every approval's markdown (any status) is reachable and rendered with the full markdown viewer.
4. No dead buttons or unrouted demo pages remain.
5. The 2 real knowledge items mutated by the audit crawler are restored to PENDING.

## 2. Section A — Backend: progress event persistence + replay

- **Flyway V51**: table `run_progress_events` (`id UUID PK`, `run_id UUID NOT NULL`, `agent_id UUID`, `iteration INT`, `kind VARCHAR NOT NULL`, `seq INT NOT NULL`, `content TEXT`, `tool_name VARCHAR`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`; index `(run_id, seq)`).
- **Persistence point**: `EventBroadcastListener.onRunProgress` inserts the row in the same place it broadcasts (persist first, broadcast best-effort — a broadcast failure must not lose the event, a persist failure must not break the run).
- **Replay endpoint**: `GET /api/v1/runs/{id}/progress?afterSeq=` — ascending by seq, `afterSeq` enables incremental fetch; response reuses the WS event field names so the frontend renders one shape.
- **Retention**: housekeeping module gains a progress-cleanup step — delete events older than `aria.progress.retention-days` (default 7); housekeeping report shows the removed count.
- Entity/repository/DTO follow existing Run-aggregate patterns (act-execution or act-common alongside Run).

## 3. Section B — Frontend: WS subscription model + drawer rebuild

- **`useWebSocket` refactor**: replace single `lastMessage` state with a callback-subscription model (handler set inside the hook; messages dispatch one-by-one — no overwrites). Components migrate: AgentDrawer, Toast, NotificationBell, Overview widgets. Dedupe on `(runId, seq)` at subscription level. WS frame format unchanged.
- **Drawer rebuild**: on open, fetch `GET /runs/{id}/progress` backlog → render → append live frames (same seq dedupe). Remove the fake "Connected to" seed line, the pump-indicator stub (references a nonexistent endpoint), and the `agent.heartbeat` dead branch. "Active Run" card only matches non-terminal statuses (RUNNING/WAITING_APPROVAL).

## 4. Section C — Approvals page rework

- **Stuck chains panel**: client-side join (no new backend endpoint) — fetch `GET /api/v1/workflows` + `GET /api/v1/approvals?status=PENDING`, derive stuck = chains with status WAITING_APPROVAL whose run ids have no PENDING approval match → panel explains the trap ("approval expired; chain waits forever") with one-click Resubmit (existing `POST /api/v1/workflows/{id}/resubmit-approval`); the fresh window appears in Pending above.
- **History detail**: History rows expand/click to render the approval's markdown with the same panel used for Pending (fixes unreachable expired markdown).
- **Deny confirm dialog** (mirrors Approve).
- **Markdown unification**: ApprovalsPage adopts the full markdown viewer used by ReportsPage; rendered container carries the `.spec-review-markdown` class (E2E asserts it).
- **Knowledge review confirm dialog** before ✓/✗ (prevents the audit-incident class; true REJECTED→PENDING undo API is out of scope, noted as follow-up).

## 5. Section D — Quick wins batch

- Toast "View" button → navigate by notification resourceType (reuse NotificationBell's route map).
- Ops "Escalate" mock button → remove (no backend API; YAGNI).
- ScheduledJobs `alert()` → toast pattern.
- /runs deleted-agent rows → resolve agent names via the existing agents API map.
- /reports Copy Link → "Copied" toast feedback.
- /chat inject duplicate POSTs → add a submit lock; verify against network capture.
- Workflows stale step status → derive displayed step status from the run's terminal status.
- AgentDrawer hardcoded token bars → remove; show real iteration count.
- Dead pages deleted with tests: DashboardPage.tsx (links to nonexistent /agents), DoDPage.tsx, KanbanPage.tsx, ToolManager.tsx.
- Slash menu empty state: 0 SKILL-stage skills → "No skills yet — author one in Knowledge".

## 6. Section E — Incident revert (operational)

Audit crawler mutated 59 PENDING knowledge items (57 e2e junk — left as-is). Restore the 2 real items to PENDING: stop backend → H2 Shell targeted UPDATE on `knowledge_items` + `knowledge_versions` rows for `804e7379` (SPEC) and `825af0dd` (WORKFLOW template) → restart → verify in UI. Template `825af0dd` being APPROVED is arguably useful; revert both anyway for state honesty (decided with user).

## 7. Testing

- Backend: unit tests for progress insert + replay endpoint (incl. `afterSeq`); housekeeping retention test.
- Frontend: vitest for the new subscription model (no dropped events under burst) + drawer backlog-then-live behavior + Approvals stuck-panel logic.
- E2E: new assertions — History detail opens markdown; stuck-chain panel appears when a gate expires (may need time control).
- Close-out: browser spot re-audit of the fixed entries.

## 8. Rollout & PR

Branch `fix/ui-audit-findings` from main; commit groups (a) backend progress, (b) WS model + drawer, (c) Approvals, (d) quick wins, (e) incident revert executed at rollout (before UI verification). Single PR. CI runs test profile — MCP and progress features unaffected there except compile coverage.
