# UI Refresh Wave 2a — Overview Replacement (Design)

Status: draft for spec review
Date: 2026-11-25
Base: `main` @ `f987b1f2`

## 1. Outcome

The Overview becomes a fully token-driven surface: every section is rebuilt on shadcn/ui and AI Elements primitives, and no Overview element keeps its legacy chrome. Behaviour and the operator contracts stay unchanged, and the existing E2E/unit suites stay green.

Success criteria:

| id | Criterion | Evidence |
|----|-----------|----------|
| W1 | Every Overview section is rebuilt (KPI row already done, plus decision queue, attention queue, board, executive summary, morning briefing, agent team, activity timeline, review workspace) | Component inventory in section 3; screenshot set |
| W2 | Behaviour parity: decision queue ordering/collapse/stale/partial, board transitions + guards + batch actions, HITL decisions, review workspace Expand/Escape, mobile lane selector | Existing unit + E2E suites unchanged |
| W3 | The 13 E2E specs that depend on the board/queue still pass; any selector change is additive (roles/test ids) rather than a rewrite | `gh pr checks` + local E2E |
| W4 | No horizontal overflow at 390 / 1024 / 1400; the mobile lane selector still switches the visible list | Playwright |
| W5 | Dark/light AA on the new queue, board, status and error surfaces | Painted-element contrast checks |
| W6 | No backend change; no new runtime dependency; `bash scripts/local-ci.sh affected` is the pre-push gate | Empty backend diff + CI |

## 2. Scope

In:

- `OverviewPage` composition and every section it renders.
- Shared `DecisionPanel` / `ShortApprovalView` / `RunOutcomeChip` in `ReviewPanels.tsx`, because the Overview review workspace and the TaskDrawer both render them; the decision zone moves to the new visual language wherever it appears.
- `ReviewWorkspace` (in-place review mode).
- Removal of the legacy CSS selectors that belong to these surfaces, from `styles/*.css` and the unlayered sections of `index.css`.

Out:

- Other routes (Crew, Providers, Runs, Ops, Knowledge, Memory, Reports, Chat, Workflows, Jobs, Settings) — Waves 2b–2d.
- The TaskDrawer/AgentDrawer/ConfigureModal/Toast chrome; only their embedded decision/approval panel changes.
- AriaPanel and the `/aria` transcript.
- IA/route changes, AG-UI, token streaming.
- New board capabilities (dependencies, WIP limits, swimlanes).

## 3. Inventory

### Components and their contracts

| Component | LOC | Consumers | Existing tests | Legacy styling | Contract to preserve |
|-----------|-----|-----------|----------------|----------------|----------------------|
| `OverviewPage.tsx` | 234 | Router `/` | `pages/__tests__/OverviewPage.test.tsx` | `overview-flow.css`, `index.css` | Decision-first order; review workspace/drawer wiring; honest empty states |
| `KanbanBoard.tsx` | 948 | Overview | `KanbanBoard.test.tsx` (1,265) | `kanban-flow.css` + `index.css` Kanban section | `LEGAL_DROPS`, native drag/drop, guards, batch clear/review, create dialog, mobile lane selector, quick ask actions |
| `DecisionQueue.tsx` | 266 | Overview | `DecisionQueue.test.tsx` (204) | `decision-queue.css` | Dangerous-first order; collapse; `data-collapsed`; count/review count; stale/partial; busy; answer flow; operator error remedy |
| `AttentionQueue.tsx` | 142 | Overview | `AttentionQueue.test.tsx` (85) | `attention-queue.css` | Recovery rows; no batch/no fabricated retry; provider/task actions; collapse |
| `ReviewPanels.tsx` | 377 | Overview/ReviewWorkspace + TaskDrawer | `ReviewPanels.test.tsx` (485), TaskDrawer (833) | `index.css` decision zone | Question answer/finalize; approve/deny; request-changes; bulk approve excluding native asks; 403/409 remedy; in-flight guards |
| `ReviewWorkspace.tsx` | 315 | Overview (Expand) | `ReviewWorkspace.test.tsx` (510) | `index.css` review workspace | In-place review; Escape rules; asks/run/outcome; no drawer flash |
| `ExecutiveSummary.tsx` | 231 | Overview | `OverviewPage`/sections tests | `index.css` Executive section | Honest KPI copy; recorded tokens vs unreported runs |
| `MorningBriefing.tsx` | 103 | Overview | existing page tests | `index.css` | Snapshot copy/time; card counts |
| `AgentTeam.tsx` | 124 | Overview | existing page tests | `index.css` | Fleet rows; health/pickup labels |
| `ActivityTimeline.tsx` | 94 | Overview | existing page tests | `index.css` | Recent events; timestamps |

CSS weight: flow CSS for these surfaces is 174 lines (`kanban-flow` 53, `decision-queue` 71, `attention-queue` 21, `overview-flow` 29), but the larger board/agent/activity styling lives in unlayered `index.css` (Executive section ~164–186, Kanban ~187–343, Agent panel ~344–565). The wave must remove both, selector by selector.

### E2E dependencies (13 specs)

| Spec | Hooks it uses | Behaviour it pins |
|------|---------------|-------------------|
| `kanban-board.spec.ts` | `.col-k[data-col]`, lane/card roles | drag/drop, legal transitions, create/clear |
| `kanban-hitl.spec.ts` | `.col-k[data-col]`, decision zone, quick approve/deny/request-changes | per-card HITL decisions |
| `decision-first-overview.spec.ts` | `decision-queue`, `.decision-queue__count`, `attention-queue`, `data-collapsed` | order, collapse, counts, 1024/390 layouts |
| `overview-dashboard.spec.ts` | `decision-queue`, `attention-queue` | decision-first copy and section presence |
| `mobile-flows.spec.ts` | `decision-queue`, `kanban-mobile-list`, lane selector | 390 pane switch, no overflow |
| `approvals-decision-flow.spec.ts` | `.col-k[data-col="REVIEW"]` | Review surface remains the approvals entry |
| `agent-dev-workflow-governance.spec.ts` | `.col-k[data-col]` | governed flow |
| `sdd-workflow.spec.ts` | `.col-k[data-col]` | SDD review flow |
| `core-permissions.spec.ts` | board/decision surfaces | native permission asks |
| `journey-agent-run-report.spec.ts`, `journey-crew-deploy-run.spec.ts` | board/queue | end-to-end journeys |
| `ops-approval-surface.spec.ts` | approval/queue surfaces | ops decisions |
| `api/approval-denial.api.spec.ts` | API only | decision endpoint guard (no UI change) |

Selector counts in E2E: `col-k` 13, `decision-queue` 11, `decision-zone` 7, `kanban-mobile-list` 4, `data-collapsed` 3, `attention-queue` 3, `Kanban Board` 5, `New Item` 3.

Unit coverage for the surfaces: 3,883 lines across `KanbanBoard`, `DecisionQueue`, `AttentionQueue`, `ReviewPanels`, `ReviewWorkspace`, `ReviewQueue`, `TaskDrawer` tests.

## 4. Design

### 4.1 Presentational split

- Keep the behaviour layer where it is: `useDecisionQueue`, `useAttentionQueue`, Kanban mutations/guards, ReviewPanels mutations, drawer wiring.
- Introduce new presentational components under `components/overview/` and `components/kanban/`; each current component keeps its exported API and becomes a thin composition of those primitives.
- Board drag/drop stays native HTML5 (`onDragStart`/`onDragOver`/`onDrop`); no new DnD dependency. The visual card/column changes, the LEGAL_DROPS state machine does not.
- DecisionQueue and DecisionPanel share one visual `DecisionActionBar` primitive (approve/deny/answer/request-changes variants), but keep their separate state machines and testids.

### 4.2 Visual language

- Flat shadcn surfaces: Card/Badge/Button/ScrollArea/Table; 1px borders; one accent.
- Queue rows use the AI Elements Confirmation pattern (risk badge, tool, reason, wait/expiry, actions); dangerous-first order preserved.
- Board columns are shadcn Cards with a header count and a scrollable lane; cards are shadcn Items with status/priority badges and the existing quick actions.
- Attention rows use an Alert/Item composition; Executive/Morning/AgentTeam/Activity use Cards and a Table with the existing honest copy.
- Error, stale, partial and empty states reuse the shared StateNotice/ErrorNotice semantics with new visuals.

### 4.3 DOM hooks

Keep `data-testid` and the class hooks the E2E specs use (`decision-queue`, `decision-queue__count`, `attention-queue`, `data-collapsed`, `.col-k[data-col]`, `.kanban-mobile-list`, `.decision-zone`). New markup emits them alongside token classes. This is a deliberate compatibility shim for Wave 2a; the final cleanup wave may replace them with semantic selectors after the E2E specs are migrated.

## 5. Data and events

No backend, API, DTO, event or permission change. SSE/STOMP, query keys and decision endpoints stay as they are.

## 6. Migration and rollback

| Step | Change | Exit condition |
|------|--------|----------------|
| 1 | Decision queue + attention queue | unit tests + `decision-first-overview`/`overview-dashboard` |
| 2 | Board columns/cards + create/clear/batch dialogs | board/kanban/keyboard/HITL/mobile E2E |
| 3 | DecisionPanel/ShortApprovalView + ReviewWorkspace | ReviewPanels/ReviewWorkspace/TaskDrawer tests |
| 4 | Executive/Morning/AgentTeam/Activity | Overview page tests + screenshots |
| 5 | Remove migrated legacy selectors from flow CSS and `index.css` | no selector remains unreferenced; full suites |

Each step is a separate commit and independently revertible. Legacy selectors are removed only after their step's suite is green.

## 7. Testing and acceptance

- Unit: the existing 7 surface suites + Overview page, unchanged assertions; add only tests for new display branches.
- E2E: the 13 specs above, unchanged except additive roles/test ids; run 390/1024/1400 viewports. The keyboard board spec (`kanban-keyboard.spec.ts`) uses roles rather than the class hooks and must stay green too.
- Contrast: queue risk/error, board status, attention status in both themes.
- Local gate: `bash scripts/local-ci.sh affected` then the touched E2E specs; hosted CI must be green.
- W1–W6 are the acceptance table in section 1; any unrun criterion is NOT VERIFIED in the PR.

## 8. Risks

| Risk | Mitigation |
|------|------------|
| `KanbanBoard` is 948 lines and owns drag/guards/batch/mobile | Extract presentational pieces only; keep state and handlers; step 2 gets its own review |
| `DecisionPanel` is shared with TaskDrawer | Step 3 runs TaskDrawer + ReviewWorkspace tests; drawer chrome stays out of scope |
| Unlayered `index.css` beats Tailwind | Remove the exact legacy selectors in the same step; scope new styles under token classes |
| E2E hooks are legacy class names | Keep them as compatibility hooks in this wave; migrate in the cleanup wave |
| Mobile board regression | `mobile-flows` 390 + lane selector in every step's gate |
| Whole-Overview diff is still large | 5 sequential commits, each independently testable/revertible |

## 9. Out of scope

- Other pages (2b–2d), AriaPanel, drawer chrome, IA changes, AG-UI, token streaming, new board capabilities.
