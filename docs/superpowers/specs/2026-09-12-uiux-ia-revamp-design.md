# UI/UX and IA Revamp — Design

Date: 2026-09-12
Status: Draft for review (brainstorm validated via visual companion; 7 decisions locked)
Baseline commit: `1ef4e64` (`main`, PR #79 Kanban HITL merged)
Scope: Information architecture restructure, operator-first Overview, two-tier HITL decision model, MCQ ask support, removal of dead/fake surfaces, and honesty fixes for misleading status signals.

Evidence base (produced this session, read-only):
- `docs/reviews/2026-09-12-uiux-live-walk-report.md` — live walk of all 11 routes on a running stack (24 screenshots in `docs/reviews/shots/`)
- `docs/reviews/2026-09-12-uiux-static-audit-report.md` — code-level cross-audit with `file:line` evidence
- Playwright run of the affected specs: 27 passed / 2 skipped

Note: a separate, unrelated proposal exists at `docs/superpowers/specs/2026-09-12-uiux-review-proposal.md` (produced by a different session against port 5174; its figures do not match this repo state). It is not an input to this design and should not be merged with it.

---

## 1. Goals

- Make the landing screen answer the operator's question within one glance: what needs my decision, what is stuck, what is broken.
- Remove surfaces that either do nothing or actively misrepresent system state.
- Reduce the number of entry points and the number of places the same decision can be made.
- Make the app honest: no green "healthy" badge while providers are unhealthy, no fabricated ownership data, no interactive controls that cannot persist.
- Keep everything reachable for the platform-admin and PM jobs without adding a role switcher.

## 2. Non-goals

- No role/tenant model. Operator and PM are the same person, differing only in visit frequency.
- No new visual design language. Existing CSS tokens and component patterns are reused.
- No RBAC implementation. The Knowledge access-control matrix is removed, not backed by a real API.
- No change to the Kanban HITL transition semantics delivered in PR #79.

## 3. Locked decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Static three-layer rail; no role switcher | Operator and PM are one person; a switcher would add state and force three landing pages |
| D2 | Operator-first Overview with three signal zones | Hourly use dominates; weekly use is one scroll away |
| D3 | `Ops`, `Chat`, `/approvals` stop being entry points; their jobs are absorbed | They duplicate Overview, Runs, and Review (see Section 5) |
| D4 | Decision zone allows inline action, and **type** decides who qualifies | Command approvals are routine; spec reviews must not be one-click |
| D5 | MCQ reuses existing `options_json` / `answer` / `/answer` — no migration | Fields and endpoint already exist; only a producer and UI rendering are missing |
| D6 | All-clear state is adaptive: the Kanban board is promoted to the first screen | When there are no signals, "what is moving" is the only useful answer |
| D7 | First-run guidance lives on Overview in place | Keeps the rail at 9 entries; no throwaway route |

## 4. Information architecture

### 4.1 New rail (static, three layers)

Ten entries total: nine routes plus the `Configure` action.

**Now** (per-minute use)
1. Overview
2. Review
3. Runs

**Manage** (daily use)
4. Crew
5. Agent Backends (renamed from `Providers`)
6. Knowledge
7. Configure (opens a modal; not a route)

**Deliver** (weekly use)
8. Reports
9. Workflows
10. Scheduled Jobs (label aligned with the page heading; currently `Jobs`)

### 4.2 Removed entry points and where their jobs go

| Removed | Destination |
|---|---|
| `Ops` | Its "what is abnormal" content becomes the Overview 異常 zone. `Housekeeping` (scan leftovers) becomes an action inside `Configure`. Its `Recent Runs` table is deleted; a link to `Runs` replaces it. |
| `Chat` | The data it renders is the run trajectory, already shown by `RunsPage`'s expanded `RunDetailView`. It merges into `Runs`. The rail slot is given to the real assistant (`Aria`), which currently only exists as a bottom-right FAB. |
| `/approvals` | Route and page deleted. All decisions go through `Review`. |

### 4.3 Naming

- `Providers` → `Agent Backends`, to stop colliding with `Configure › LLM Providers` (two unrelated concepts sharing one word).
- `Chat` page heading `Agent ↔ Agent Conversations` is removed with the page; no agent-to-agent thread model exists (`ChatPage.tsx:176-183` hardcodes `['Operator', agentName]`).
- Rail `Jobs` → `Scheduled Jobs`.

## 5. Overview (operator-first)

### 5.1 Top bar

- Keep: title, `Live` indicator, `Refresh`.
- Remove: the per-second clock (`TopBar.tsx:40-43,105-107`) — the most frequent DOM mutation in the app and pure decoration; it duplicates the OS clock.
- Remove or make navigable: the four `.kpi` tiles, which are `<div>`s with no click handler (verified live: `cursor: auto`, clicking does nothing) and restate the same numbers already shown by the "N Agents Online" badge and Executive Summary.

### 5.2 Signal zones

**Zone 1 — ⚠ Waiting on you** (full-width, visually dominant)
- Content: count, oldest wait, and one row per ask: type, subject, source agent, wait time; expired asks flagged.
- Data: kanban asks via `/api/v1/approvals?kanbanItemId` plus timeout evaluation.
- Behavior: per D4, `TOOL_CALL` and short `QUESTION` asks expose inline `Approve` / `Deny` / `Submit answer`. `SPEC_REVIEW` and `REVIEW_REQUEST` rows expose only `Review →` and carry no decision buttons.

**Zone 2 — ⏸ Stuck**
- Content: workflow chains in `WAITING_APPROVAL` (including expired approvals) and failed runs.
- Data: `/api/v1/workflows` + `/api/v1/runs`.
- Replaces the Ops "Recent Runs" table.

**Zone 3 — ⚡ Needs attention to run**
- Content: provider/sandbox health and degraded agents, each with a remediation line.
- Data: `/api/v1/adk/providers`, `/api/v1/adk/providers/{id}/health`, `/api/v1/agents` `healthStatus`.
- Each unhealthy item shows a one-line copyable fix command, sourced from this repo's own documentation (for example `docker compose up -d opensandbox-server`, `uvicorn src.server:app --port 9300`).
- Replaces the fake health signal described in Section 7.

**Collapsed strip** (below the zones)
- Production summary (Reports / Workflows / weekly completions) — keeps the PM view one scroll away without a route change.
- Kanban board.
- Activity stream.

### 5.3 Adaptive layout (D6)

- Signals present: zones 1–3 dominate, board collapsed.
- All clear: zone 1 collapses to a single line ("✓ nothing waiting on you" plus the last decision time), zone 3 still renders truthfully, and the **Kanban board is promoted to the main content area**.
- The layout change must be self-explanatory (the collapsed zone line states why).

### 5.4 First-run guidance on Overview (D7)

Renders in place of the zones until the completion condition is met.

| Step | Condition to complete | Data source |
|---|---|---|
| 1. Connect a runtime (choose one: `opencode` sandbox or `langchain` ADK) | at least one provider healthy | `/api/v1/adk/providers` health |
| 2. Configure an LLM provider | at least one active provider | `/api/v1/llm-providers` |
| 3. Produce a first successful run | a run reaches a terminal state | `/api/v1/runs` |

- Each step exposes a copyable command or a deep link and a `Re-check` action.
- Dismissible ("skip for now"); recoverable from `Configure`.
- Completion condition is **a first successful run**, not "an agent exists".

Correction discovered during design: a fresh database is **not** empty. `V42__seed_sdd_role_agents.sql` seeds the `ba`/`dev`/`qa` role agents and `AriaDefaultAgentInitializer` guarantees the Aria agent, so a fresh install starts with 4 agents, 62 knowledge items and 1 workflow template. The real gaps are **zero LLM providers** (the migrations create the table but insert nothing) and no running runtime. Guidance must not instruct the user to create an agent that already exists.

## 6. HITL decision model

### 6.1 Two tiers (D4)

| Tier | Ask types | Surface | Affordances |
|---|---|---|---|
| Quick | `TOOL_CALL`; short `QUESTION` | Overview zone 1 | Approve / Deny / submit answer inline |
| Deep | `SPEC_REVIEW`, `REVIEW_REQUEST` | `Review` page | Full content, then Approve / Request changes / Deny, plus fine-tuning feedback |

The `Review` page can adjudicate any ask, including quick-tier ones; the Overview restriction is one-directional. Rationale: a spec approval must not be one click, but a routine command approval should not cost a page navigation.

### 6.2 MCQ support (D5)

Already present, currently unused:
- `Approval.AskType` = `APPROVAL | QUESTION | REVIEW_REQUEST` (`Approval.java`)
- `approvals.options_json` and `approvals.answer` columns
- `POST /api/v1/approvals/{id}/answer`, served by `ApprovalAnswerService` (free-text answer; the `approved` flag is restricted to `QUESTION` asks)
- `optionsJson` projected by `ApprovalController` and declared at `types/index.ts:66`

Missing:
- **No producer.** Nothing in the backend ever sets `options_json`; `KanbanReviewAskCreator` only creates `REVIEW_REQUEST` asks without options. An agent-facing entry point is required, e.g. `ask_question(question, options[], mode)`.
- **No renderer.** `optionsJson` has zero references in `src/` outside its type declaration.

Proposed contract:

```
options_json = {
  "mode": "single" | "multi",
  "options": [ { "id": "utf8", "label": "UTF-8 without BOM", "detail": "..." } ],
  "allowFreeText": true
}

answer = { "selectedIds": ["utf8"], "text": "optional note" }
```

Rendering: single-choice as radio chips, multi-choice as checkboxes, an always-available "other" free-text field when `allowFreeText`, and option `detail` shown on expand. Both `QUESTION` and `REVIEW_REQUEST` asks may carry options.

### 6.3 The Review surface

`Review` renders the **Kanban board** — it does not introduce a new decision component. PR #79 already made the board's Review column the HITL entry (`KanbanBoard.tsx:403-431`, `ReviewWorkspace`), and the board is the only place with column context (what is in progress, what was returned to Todo).

Consequence for the adaptive layout (D6): Overview's all-clear state embeds **the same board component**, not a second implementation and not a second data model. The board is therefore reachable two ways — as the `Review` page, and embedded on Overview when no signals are pending — while decisions still flow through one component and one ask model.

## 7. Honesty fixes (no new features)

These are defects where the UI asserts something false. Each must be fixed rather than restyled.

| Defect | Evidence | Fix |
|---|---|---|
| "System Healthy" whenever any agent exists | `TopBar.tsx:63` `isHealthy = activeAgents > 0`; on screen it says healthy while both providers are UNHEALTHY and Aria is DEGRADED | Derive from real provider + agent health |
| Hardcoded "Healthy & responsive" | `ExecutiveSummary.tsx:95` | Derive from the same source |
| DEGRADED counted as online | `/api/v1/dashboard/summary` returns `activeAgents: 4` including a DEGRADED agent | Either exclude degraded from "online" or label the count explicitly |
| Knowledge per-agent attribution is fabricated | `KnowledgePage.tsx:152-162` — `ownerOf()` falls back to "distribute items across agents by hash of id"; 62 items were rendered as 18/15/14/15 | Remove the panel (see Section 8) |
| Hardcoded promotion path and access matrix | `KnowledgePage.tsx:26-32`, `:39-101` | Remove (see Section 8) |
| Version label renders broken | `KnowledgePage.tsx:665,693` render `v{currentVersion}`; backend returns `null` for 61 of 62 items and the literal `"v1.0.0"` for one, giving bare `v` and `vv1.0.0`. `types/index.ts:104` declares it as `number` | Fix the render and the type; normalise the backend value |
| Approvals presented as inert controls | `ConfigureModal.tsx:260-262` self-declares "not yet enforced by the backend"; `:119-121` `updateSkill` mutates local state only | Remove or hide the Approval Gates and Skills permission editors |
| Inconsistent timestamps leaking OS locale | A canonical `formatTimestamp()` exists (`utils/formatTime.ts`, locale-pinned `HH:mm` / `YYYY-MM-DD HH:mm`) but ~20 call sites bypass it with bare `toLocaleString()` / `toLocaleTimeString([])`, producing OS-locale strings such as `周六 11:34` in an English UI | Route all timestamps through the canonical formatter |
| React key warning on Runs rows | `RunsPage.tsx:204-237` — `filteredRuns.map` returns a keyless fragment wrapping keyed `<tr>`s | Put the key on the fragment |
| `approval.requested` toast has no click-through | `Toast.tsx:91-95` — only the `aria.notification` branch gets an action | Add a route to the Review surface |
| Silent destructive actions | Kanban card cancel (`KanbanBoard.tsx:444-457`), Crew bulk retire (`CrewPage.tsx:346-348`), Knowledge promote (`KnowledgePage.tsx:712-719`), TaskDrawer reject (`TaskDrawer.tsx:447-453`) fire with no confirmation, while sibling actions confirm | Apply one confirmation policy; add `onError` where missing |
| No connection-state indicator | `isConnected` is produced (`useWebSocket.ts:57`) and destructured (`AgentDrawer.tsx:109`) but never rendered | Surface a disconnected state |

## 8. Removal list

### 8.1 Dead files (zero references, verified)

| File | Notes |
|---|---|
| `src/pages/AgentsPage.tsx` | 413 lines duplicating `CrewPage`; only mention is a `test.skip` at `e2e/template-api.spec.ts:146` |
| `src/components/AgentToolPanel.tsx` | Also calls a non-existent `/api/v1/roles/{role}/tools` endpoint |
| `src/components/EvidenceDrawer.tsx` | Last consumer of `api/dod.ts` |
| `src/hooks/useNotificationPrefs.ts` | Referenced only by its own test |
| `src/api/harness.ts` | Zero callers; `HarnessProfileController` stays reachable over REST |
| `src/api/dod.ts` | Only consumer is the dead `EvidenceDrawer`; the DoD backend remains used by the Aria `DoDToolHandler` |

### 8.2 Dead route

- `/approvals` (`App.tsx:43`) and `src/pages/ApprovalsPage.tsx` (a pure `<Navigate to="/" replace />`). `notificationRoutes.ts:25` already routes `approval.requested` to `/`, so nothing in product code links to it.

### 8.3 Fake or unbacked UI

| Surface | Decision |
|---|---|
| Knowledge `Per-Agent Spaces` panel | Remove the panel. Real ownership would require an `agentId` on `KnowledgeItem`, which is a backend change and out of scope. |
| Knowledge `Promotion Path` panel | Remove; it is a static illustration. |
| Knowledge `Access Control` matrix | Remove; hardcoded, uneditable, not backed by any API. |
| `Configure › Approval Gates` tab | Hide until an API persists it. |
| `Configure › Skills & Tools` permission editor | Reduce to a read-only list of real skills from `/api/v1/skills`; drop the non-persisting permission selects. |
| `Ops` page | Remove the page; keep `HousekeepingPanel` reachable from `Configure`. |
| `Chat` page | Remove the page; keep the trajectory view inside `Runs`. |

## 9. Test impact

### 9.1 The existing coverage illusion

Three specs navigate to `/approvals` and then assert only that *some* heading is visible, so they pass without testing anything:

- `e2e/git-pack-governance.spec.ts:35` — the test named "git_push triggers approval gate and resumes after approval" completed in 1.1s
- `e2e/harness-governance.spec.ts:111`
- `e2e/agent-dev-workflow-governance.spec.ts:88`

And one spec still asserts the retired page's content:

- `e2e/sdd-workflow.spec.ts:100-102` asserts `SPEC_REVIEW` text and `.spec-review-markdown` on `/approvals`. It is masked in CI because the preceding BA run fails without an ADK runtime and `test.skip()` aborts the test — but it will fail wherever a real runtime exists.

Two more references are route-only and were already adapted by PR #79: `e2e/approvals-decision-flow.spec.ts:17` and `e2e/journey-agent-run-report.spec.ts:59`.

### 9.2 Required test changes

- Retarget the three vacuous specs to assert real behaviour on the `Review` surface (the duplicate-approval decision and the markdown rendering respectively), so their names match what they verify.
- Retarget `sdd-workflow.spec.ts` step 3 to the `Review` surface and remove its dependence on `/approvals`.
- Delete `e2e/approvals-decision-flow.spec.ts`'s redirect test together with the route.
- Delete `src/pages/__tests__/ApprovalsPage.test.tsx` and `src/hooks/__tests__/useNotificationPrefs.test.ts` with their subjects.
- Update tests coupled to removed panels: `components/__tests__/ActivityTimeline.test.tsx`, `MorningBriefing.test.tsx`, `ExecutiveSummary.test.tsx`, `pages/__tests__/OverviewPage.test.tsx`, `KnowledgePage.test.tsx`.
- Add coverage for the new behaviour: decision-tier gating (which ask types render inline actions), MCQ rendering and answer submission, adaptive all-clear promotion, and the first-run step conditions.

## 10. Phasing

This design is too large for a single implementation plan. It is phased so that Phase 1 is independently valuable and low-risk.

**Phase 1 — Subtraction and honesty (no new features)**
Dead-file and dead-route removal, the Section 7 defect fixes, and the Section 9.2 test adjustments. No new UI concepts.

**Phase 2 — IA restructure**
Three-layer rail, `Agent Backends` rename, absorb `Ops` and `Chat`, remove the fake Knowledge panels and the two inert Configure editors.

**Phase 3 — Overview and decisions**
Operator-first signal zones, the two-tier decision model with type-based gating, adaptive all-clear layout.

**Phase 4 — MCQ and onboarding**
The `ask_question` producer plus MCQ rendering, and the on-Overview first-run guidance.

## 11. Acceptance criteria

1. No screen claims a health status that contradicts provider/agent data.
2. Every interactive control either persists its change or is removed.
3. `approve`/`deny` decisions are reachable from exactly one place (`Review`), with quick-tier asks additionally actionable from Overview zone 1.
4. `SPEC_REVIEW` and `REVIEW_REQUEST` asks cannot be approved from Overview.
5. An MCQ ask round-trips: agent supplies options, the operator selects, the selection reaches the agent.
6. With all queues empty, the first screen still answers "what is running" without scrolling past empty panels.
7. On a fresh database, the first screen states the two real gaps (no LLM provider, no runtime) and does not instruct the user to create an agent.
8. No file from Section 8.1 remains; no reference to `/approvals` remains in `src/`.
9. The three formerly vacuous e2e specs fail if the behaviour they name is broken.
10. All timestamps render in one pinned format regardless of OS locale.

## 12. Risks

- **Removal of `Chat` and `Ops` is a product decision, not a pure cleanup.** Mitigation: Phase 2 is separate from Phase 1 so the removals can be deferred without blocking the honesty fixes.
- **The Kanban board is currently load-bearing for HITL.** Since `Review` becomes the single decision surface, board affordances must be revisited in the same change to avoid leaving a second decision path.
- **`options_json` has no producer today**, so MCQ delivery depends on an agent-facing tool that does not exist yet. If that tool is deferred, the UI must not advertise MCQ.
- **Knowledge ownership** (a real `agentId`) is deliberately deferred; removing the panel avoids implying the data exists.
