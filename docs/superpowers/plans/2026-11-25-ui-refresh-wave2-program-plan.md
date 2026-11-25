# UI Refresh Wave 2 — Program Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver Wave 2 as one release: foundation primitives, the 2a Overview replacement, the 2b/2c page surfaces, the 2d chat/overlay surfaces, and the legacy-CSS cleanup.

**Architecture:** A shared foundation lands first (notices, dialogs, toast/bell, page primitives). Four file-disjoint workstreams then replace surfaces while keeping behaviour and E2E hooks. The cleanup owner removes legacy CSS only after every consumer stops using it. One integration branch collects the workstreams; they release as one bundle.

**Tech Stack:** React 19, Vite 6, TypeScript 5.6, TanStack Query 5, Tailwind 4, shadcn/ui (Base UI), Vitest 2, Playwright.

**Spec:** `docs/superpowers/specs/2026-11-25-ui-refresh-wave2-program-design.md`
**Decisions:** `docs/superpowers/specs/2026-11-25-ui-refresh-wave2-program-decisions.md`
**2a plan:** `docs/superpowers/plans/2026-11-25-ui-refresh-wave2-overview-plan.md` (approved; workstream A)

## Global Constraints- Frontend only; no backend/API/DTO/event/permission change.
- No new runtime dependency. shadcn registry components (`alert-dialog`, `table`, `alert`, `progress`, etc.) are copied code and allowed. No Sonner/DnD library.
- Keep every existing test id, accessible name and class hook as a compatibility shim (`.col-k`, `.decision-queue`, `.decision-zone`, `mc-*`, page-specific hooks). CSS may be removed; hooks may not be silently dropped.
- `index.css` and shared flow CSS are owned by the cleanup owner; page workstreams never edit them.
- One integration branch `feat/ui-refresh-wave2`; each workstream is a separate reviewable PR/commit series, released together.
- Every task: targeted unit + its E2E subset green; `bash scripts/local-ci.sh affected` before push; one commit.
- Reply to the user in Traditional Chinese; code and commits in English.

## Ownership map

| Owner | Files |
|-------|-------|
| Foundation F | `ConfirmDialog.tsx`, `StateNotice.tsx`, `ErrorNotice.tsx`, `Toast.tsx`, `NotificationBell.tsx`, `RuntimeBadge.tsx`, new `layout/PageHeader.tsx`, `layout/SectionCard.tsx`, their tests, `toast-flow.css`, `state-notice.css`, `feedback-flow.css` |
| 2a (workstream A) | exactly the files in the approved 2a plan |
| 2b | `CrewPage.tsx`, `AgentCard.tsx`, `AgentCatalog.tsx`, `ProvidersPage.tsx`, `RunsPage.tsx`, `StatusBadge.tsx`, `OpsPage.tsx`, `HousekeepingPanel.tsx`, their tests |
| 2c | `KnowledgePage.tsx`, `MemoryPage.tsx`, `ReportsPage.tsx`, `ReportContentViewer.tsx`, `WorkflowsPage.tsx`, `TemplatesPanel.tsx`, `ScheduledJobsPage.tsx`, `SettingsPage.tsx`, their tests |
| 2d | `AriaPanel.tsx`, `AriaProposalCard.tsx`, `ChatPage.tsx`, `ReviewQueue.tsx`, `WorkflowStepper.tsx`, `DelegationTree.tsx`, `MainChatPage.tsx`, `components/main-chat/*`, `TaskDrawer.tsx`, `AgentDrawer.tsx`, `ConfigureModal.tsx`, `RuntimeCredentialsCard.tsx`, their tests |
| Cleanup E | `src/styles/index.css`, the flow CSS files, selector audit, screenshots |

`Layout.tsx` is already refreshed by Phase 1; only the foundation owner may edit its exported badges if a primitive moves.

### Task 0: Base and integration branch

**Files:** none.

- [ ] **Step 1: Verify the design artifacts**

```bash
test -f docs/superpowers/specs/2026-11-25-ui-refresh-wave2-program-design.md && echo PROGRAM_SPEC_PRESENT || echo PROGRAM_SPEC_MISSING
test -f docs/superpowers/plans/2026-11-25-ui-refresh-wave2-overview-plan.md && echo 2A_PLAN_PRESENT || echo 2A_PLAN_MISSING
```

Expected: both PRESENT. If the program design is not on the implementation base, merge the design docs first.

- [ ] **Step 2: Create the integration worktree**

```bash
git worktree add /Users/happy/.qoder/worktrees/app/b420ea/aria-conductor-wave2-impl -b feat/ui-refresh-wave2 design/ui-refresh-wave2
cd /Users/happy/.qoder/worktrees/app/b420ea/aria-conductor-wave2-impl
git status -sb
```

Expected: clean `## feat/ui-refresh-wave2`. Each workstream gets its own worktree branched from this integration branch when it starts.

### Phase 0 — Foundation (land before page work)

#### Task F1: Notices and confirm dialog

**Files:** `ConfirmDialog.tsx`, `StateNotice.tsx`, `ErrorNotice.tsx`, their tests; add `pnpm dlx shadcn@latest add alert-dialog alert`.

**Interfaces:** exports and props unchanged; `ConfirmDialog` still returns a confirm/cancel decision; notices keep `kind`, `title`, `message`, `actionLabel`, `onAction`.

- [ ] **Step 1: Write the failing surface test**

Add to `ConfirmDialog.test.tsx` (or its consumer test):
```tsx
it('renders the confirm dialog on a token dialog surface', () => {
  render(<ConfirmDialog open title="Clear finished cards" message="3 cards" onConfirm={vi.fn()} onCancel={vi.fn()} />);
  const dialog = screen.getByRole('alertdialog');
  expect(dialog).toBeInTheDocument();
  expect(dialog.querySelector('[data-slot="alert-dialog-content"]')).not.toBeNull();
});
```

- [ ] **Step 2: Run it red**

`pnpm vitest run src/components/__tests__/ConfirmDialog.test.tsx`

- [ ] **Step 3: Implement**

Rebuild the three primitives with `AlertDialog`, `Alert`, `Card` and tokens. Preserve focus trap, Escape, opener restore, default-dismiss rules and all copy. Do not change the APIs.

- [ ] **Step 4: Gate**

`pnpm vitest run src/components/__tests__/ConfirmDialog.test.tsx src/components/__tests__/StateNotice.test.tsx` (create the notice tests if absent) + `npx tsc --noEmit -p tsconfig.json`.

- [ ] **Step 5: Commit**

`feat(dashboard): rebuild the shared notices and confirm dialog on tokens`

#### Task F2: Toast and notification bell

**Files:** `Toast.tsx`, `NotificationBell.tsx`, their tests; delete `toast-flow.css` after imports are gone.

**Contract:** browser-only notification rule (no OS notifications), manual dismiss only, no auto-close countdown; unread count and navigation unchanged.

- [ ] **Step 1: Failing test** — assert a toast renders inside a token surface and the bell still exposes the unread count and marks read.
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Restyle** with Card/Button/Badge/ScrollArea; keep all handlers and copy.
- [ ] **Step 4: Gate** — `pnpm vitest run src/components/__tests__/Toast.test.tsx src/components/__tests__/NotificationBell.test.tsx` + `e2e/notification-toast.spec.ts e2e/notification-bell.spec.ts`.
- [ ] **Step 5: Commit** — `feat(dashboard): rebuild the toast and notification bell on tokens`

#### Task F3: Page primitives and runtime badge

**Files:** create `src/components/layout/PageHeader.tsx`, `src/components/layout/SectionCard.tsx`; modify `RuntimeBadge.tsx`; tests.

**Interfaces:**
```tsx
export function PageHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) { ... }
export function SectionCard({ title, count, actions, children }: { title: string; count?: number | string; actions?: ReactNode; children: ReactNode }) { ... }
```

- [ ] **Step 1: Failing test** — render `PageHeader` and `SectionCard`, assert `data-slot="card"` and the accessible heading.
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement** with tokens/Card/Badge; `RuntimeBadge` keeps its text/aria.
- [ ] **Step 4: Gate** — `pnpm vitest run src/components/layout/__tests__ src/components/__tests__/RuntimeBadge.test.tsx`.
- [ ] **Step 5: Commit** — `feat(dashboard): add shared page and section primitives`

### Workstream A — 2a Overview

Execute the approved plan `docs/superpowers/plans/2026-11-25-ui-refresh-wave2-overview-plan.md` tasks 1–5 with its own gates. Do not duplicate its file work here.

### Workstream B — 2b operator pages

Each task: add one token-surface assertion to the page test, keep every existing assertion, restyle the page-specific components, run the page test + its E2E subset, commit.

#### Task B1: Crew

**Files:** `CrewPage.tsx`, `AgentCard.tsx`, `AgentCatalog.tsx`, `pages/__tests__/CrewPage.test.tsx`; `AgentCard`/`AgentCatalog` tests.

- [ ] **Step 1: Failing test** — `render(<CrewPage />)` (existing helper) and assert `[data-slot="card"]` on the fleet section; keep existing assertions.
- [ ] **Step 2: Run red** — `pnpm vitest run src/pages/__tests__/CrewPage.test.tsx`.
- [ ] **Step 3: Implement** — `PageHeader` + `SectionCard`; AgentCard/AgentCatalog token Cards/Badges; keep every accessible name, health/pickup label, action.
- [ ] **Step 4: Gate** — `pnpm vitest run src/pages/__tests__/CrewPage.test.tsx src/components/__tests__/AgentCard.test.tsx src/components/__tests__/AgentCatalog.test.tsx` and `e2e/crew-crud.spec.ts e2e/crew-telemetry.spec.ts e2e/multi-agent-lifecycle.spec.ts e2e/journey-crew-deploy-run.spec.ts`.
- [ ] **Step 5: Commit** — `feat(dashboard): rebuild the crew page on tokens`

#### Task B2: Providers

**Files:** `ProvidersPage.tsx` + page test/E2E.

- [ ] **Step 1: Failing test** — token surface assertion in the page test.
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement** — PageHeader/SectionCard + token forms; keep provider health, key masking and credential guidance copy unchanged.
- [ ] **Step 4: Gate** — page test + `e2e/adk-providers.spec.ts e2e/config-knowledge-honesty.spec.ts`.
- [ ] **Step 5: Commit** — `feat(dashboard): rebuild the providers page on tokens`

#### Task B3: Runs

**Files:** `RunsPage.tsx`, `StatusBadge.tsx`, `pages/__tests__/RunsPage.live.test.tsx`.

- [ ] **Step 1: Failing test** — token surface + status badge assertion.
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement** — token table/cards, status badge; keep run filters, stop/pause controls, timestamps and run ids.
- [ ] **Step 4: Gate** — page test + `e2e/runs-page.spec.ts e2e/observability-live.spec.ts e2e/journey-agent-run-report.spec.ts`.
- [ ] **Step 5: Commit** — `feat(dashboard): rebuild the runs page on tokens`

#### Task B4: Ops

**Files:** `OpsPage.tsx`, `HousekeepingPanel.tsx`, `pages/__tests__/OpsPage.test.tsx`.

- [ ] **Step 1: Failing test** — token surface + housekeeping panel assertion.
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement** — PageHeader/SectionCard, token tables; keep approval/ops actions, housekeeping confirmations, counts and copy.
- [ ] **Step 4: Gate** — page test + `e2e/ops-monitoring.spec.ts e2e/ops-approval-surface.spec.ts e2e/housekeeping-cleanup.spec.ts`.
- [ ] **Step 5: Commit** — `feat(dashboard): rebuild the ops page on tokens`

### Workstream C — 2c knowledge/admin pages

#### Task C1: Knowledge

**Files:** `KnowledgePage.tsx`, `pages/__tests__/KnowledgePage.test.tsx`.

- [ ] **Step 1: Failing test** — token surface assertion; keep version/history/promotion assertions.
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement** — token cards/tables/forms; keep search, versioning, promotion and diff behaviour.
- [ ] **Step 4: Gate** — page test + `e2e/knowledge-review.spec.ts e2e/journey-knowledge-promotion.spec.ts e2e/config-knowledge-honesty.spec.ts`.
- [ ] **Step 5: Commit** — `feat(dashboard): rebuild the knowledge page on tokens`

#### Task C2: Memory

**Files:** `MemoryPage.tsx`, `pages/__tests__/MemoryPage.test.tsx`. `AriaProposalCard` is owned by 2d; consume its unchanged API.

- [ ] **Step 1: Failing test** — token surface assertion.
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement** — token entities/facts/proposals layout; keep recall preview, proposal decisions and sensitivity copy.
- [ ] **Step 4: Gate** — page test + `e2e/memory-onboarding.spec.ts`. Memory's visual acceptance excludes `AriaProposalCard` until D1 lands; the integration/union gate covers the combined surface.
- [ ] **Step 5: Commit** — `feat(dashboard): rebuild the memory page on tokens`

#### Task C3: Reports

**Files:** `ReportsPage.tsx`, `ReportContentViewer.tsx`, `pages/__tests__/ReportsPage.test.tsx`.

- [ ] **Step 1: Failing test** — token surface + report viewer assertion.
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement** — token list/detail; keep generate/amend/regenerate/archive and sandbox iframe behaviour.
- [ ] **Step 4: Gate** — page test + `e2e/reports-generation.spec.ts`.
- [ ] **Step 5: Commit** — `feat(dashboard): rebuild the reports page on tokens`

#### Task C4: Workflows

**Files:** `WorkflowsPage.tsx`, `TemplatesPanel.tsx`, `pages/__tests__/WorkflowsPage.test.tsx`.

- [ ] **Step 1: Failing test** — token surface + template panel assertion.
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement** — token tabs/tables/forms; keep template apply, YAML validation and governance copy.
- [ ] **Step 4: Gate** — page test + `e2e/workflow-ui-interaction-e2e.spec.ts e2e/workflow-data-flow-e2e.spec.ts e2e/workflow-lifecycle-e2e.spec.ts e2e/workflow-state-machine-e2e.spec.ts e2e/workflow-template-e2e.spec.ts e2e/workflow-validation-e2e.spec.ts e2e/workflow-yaml-e2e.spec.ts e2e/journey-workflow-yaml.spec.ts e2e/sdd-workflow.spec.ts e2e/template-api.spec.ts`.
- [ ] **Step 5: Commit** — `feat(dashboard): rebuild the workflows page on tokens`

#### Task C5: Scheduled jobs

**Files:** `ScheduledJobsPage.tsx`, `pages/__tests__/ScheduledJobsPage.test.tsx`.

- [ ] **Step 1: Failing test** — token surface assertion.
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement** — token cards/table; keep create/pause/resume/delete and schedule copy.
- [ ] **Step 4: Gate** — page test + `e2e/scheduled-jobs-page.spec.ts`.
- [ ] **Step 5: Commit** — `feat(dashboard): rebuild the scheduled jobs page on tokens`

#### Task C6: Settings

**Files:** `SettingsPage.tsx`, `pages/__tests__/SettingsPage.test.tsx`.

- [ ] **Step 1: Failing test** — token surface assertion.
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement** — token forms; keep runtime/credential/settings copy and operator authority behaviour.
- [ ] **Step 4: Gate** — page test + `e2e/config-knowledge-honesty.spec.ts` (and any settings spec present).
- [ ] **Step 5: Commit** — `feat(dashboard): rebuild the settings page on tokens`

### Workstream D — 2d chat and overlays

#### Task D1: AriaPanel split and restyle

**Files:** `AriaPanel.tsx`, `AriaProposalCard.tsx`, `AriaPanel.test.tsx`, `AriaProposalCard.test.tsx`; new `src/components/aria/*` presentational files.

- [ ] **Step 1: Failing test** — assert the panel renders a token Card surface and its existing accessible names/decision cards remain.
- [ ] **Step 2: Run red** — `pnpm vitest run src/components/__tests__/AriaPanel.test.tsx src/components/__tests__/AriaProposalCard.test.tsx`.
- [ ] **Step 3: Split** — extract thread/composer/decision/activity presentational components; keep the reducer/stream/attachments/stop logic and every callback signature.
- [ ] **Step 4: Restyle** with tokens; `AriaProposalCard` keeps its decisions; Memory consumes the same API.
- [ ] **Step 5: Gate** — AriaPanel/proposal tests + `e2e/aria-attachments.spec.ts e2e/aria-engine-unification.spec.ts e2e/aria-inflow-decisions.spec.ts e2e/aria-session-id.spec.ts e2e/aria-timeout-cancel.spec.ts e2e/aria-conversation-id-regression.spec.ts e2e/fab-send-overlap.spec.ts`.
- [ ] **Step 6: Commit** — `feat(dashboard): split and restyle the Aria panel`

#### Task D2: Chat page and review queue

**Files:** `ChatPage.tsx`, `ReviewQueue.tsx`, `WorkflowStepper.tsx`, `DelegationTree.tsx`, `ChatPage.live.test.tsx`, `ChatPage.mobile.test.tsx`; delete `chat-flow.css` after imports are gone.

- [ ] **Step 1: Failing test** — token surface assertion; keep pane/thread state and review queue assertions.
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement** — token list/thread/review surfaces; keep `data-mobile-pane`, `.chat-thread-panel`, Back/open behaviour and delegation/workflow trees.
- [ ] **Step 4: Gate** — both ChatPage suites + `e2e/chat-smoke.spec.ts e2e/mobile-flows.spec.ts`.
- [ ] **Step 5: Commit** — `feat(dashboard): rebuild the chat page and review queue on tokens`

#### Task D3: Task drawer chrome

**Files:** `TaskDrawer.tsx`, `TaskDrawer.test.tsx`; `ReviewPanels`/decision zone come from 2a.

- [ ] **Step 1: Failing test** — token drawer surface assertion; keep focus contract, Escape, Expand and decision panel.
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement** — shadcn Dialog/Sheet shell with tokens; keep opener restore, focus heading, editable-draft Escape rules.
- [ ] **Step 4: Gate** — TaskDrawer tests + `e2e/review-decision-zone.spec.ts e2e/kanban-hitl.spec.ts`.
- [ ] **Step 5: Commit** — `feat(dashboard): restyle the task drawer shell`

#### Task D4: Agent drawer

**Files:** `AgentDrawer.tsx`, its tests.

- [ ] **Step 1: Failing test** — token drawer surface assertion; keep telemetry/run links/actions.
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement** — token drawer shell and content; `RuntimeBadge` from foundation.
- [ ] **Step 4: Gate** — AgentDrawer tests + `e2e/multi-agent-lifecycle.spec.ts`.
- [ ] **Step 5: Commit** — `feat(dashboard): restyle the agent drawer`

#### Task D5: Configure modal

**Files:** `ConfigureModal.tsx`, `RuntimeCredentialsCard.tsx`, their tests; delete `configure-flow.css` after imports are gone.

- [ ] **Step 1: Failing test** — token dialog surface assertion; keep runtime/provider/credential/skills settings.
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement** — shadcn Dialog + token forms; keep key masking, credential guidance, save/close and focus restore.
- [ ] **Step 4: Gate** — ConfigureModal/RuntimeCredentialsCard tests + `e2e/configure-modal-close.spec.ts e2e/config-knowledge-honesty.spec.ts e2e/core-permissions.spec.ts`.
- [ ] **Step 5: Commit** — `feat(dashboard): restyle the configure modal`

#### Task D6: Main-chat page residual rail and progress

**Files:** `MainChatPage.tsx`, `components/main-chat/ConversationSidebar.tsx`, `components/main-chat/ProgressTimeline.tsx`, `pages/__tests__/MainChatPage.test.tsx`; `main-chat.css` is removed by E1 after this task.

- [ ] **Step 1: Failing test** — assert the conversation rail/progress surfaces are token Cards and the page keeps `.mc-*` hooks.
- [ ] **Step 2: Run red** — `pnpm vitest run src/pages/__tests__/MainChatPage.test.tsx`.
- [ ] **Step 3: Implement** — token rail/progress surfaces; keep conversation switch/delete, progress steps, stop and `aria-live`.
- [ ] **Step 4: Gate** — MainChatPage test + `e2e/aria-main-chat.spec.ts`.
- [ ] **Step 5: Commit** — `feat(dashboard): restyle the main-chat rail and progress`

### Phase E — Cleanup and integration

#### Task E1: Legacy CSS cleanup and screenshots

**Files:** `src/styles/index.css`, remaining flow CSS, `e2e/ui-refresh.spec.ts`, `docs/reviews/shots/`.

- [ ] **Step 1: Selector audit**

```bash
cd agent-control-tower/act-dashboard
for sel in col-k kanban-grid kanban-mobile-list kanban-lane-select batch-actions priority-seg cap-btn decision-zone ask-card decision-queue__count attention-queue__count rw- rf- stat agents tline ev crew- chat- mc- configure- toast-; do
  echo "== $sel"; grep -R "className=.*${sel}" src --include='*.tsx' || true
done
```
Remove a block only when no JSX usage remains. Keep globals, scrollbars, layout shell and the theme bridge.

- [ ] **Step 2: Delete the replaced flow CSS**

Remove `decision-queue.css`, `attention-queue.css`, `kanban-flow.css`, `overview-flow.css`, `chat-flow.css`, `main-chat.css`, `configure-flow.css`, `toast-flow.css`, `feedback-flow.css`, `state-notice.css` once their consumers are token-only. Confirm with grep and a build.

- [ ] **Step 3: Screenshot/contrast matrix**

Capture every route at 390/1024/1400 in both themes into `docs/reviews/shots/ui-refresh-wave2-*`; extend `ui-refresh.spec.ts` for body/status/risk/error/link contrast.

- [ ] **Step 4: Full gate**

```bash
bash scripts/local-ci.sh all --with-e2e
```
Expected: green; the full E2E suite (57 specs) and all unit suites pass.

- [ ] **Step 5: Commit** — `chore(dashboard): remove the replaced legacy UI styling`

#### Task I1: Integration

- [ ] Merge order into `feat/ui-refresh-wave2`: foundation → 2a → 2b → 2c → 2d → cleanup.
- [ ] After each merge, rebase the next workstream and run its subset; after cleanup run the full union and hosted CI.
- [ ] Release as one bundle; per-workstream PR bodies carry their own acceptance subset.

## Self-review

| Program criterion | Tasks |
|---|---|
| P1 all routes token-driven | 2a, B1–B4, C1–C6, D1–D5 |
| P2 behaviour parity / 57 E2E | every task + E1 |
| P3 overlays | F1–F3, D1–D5 |
| P4 viewports | B/C/D E2E + E1 |
| P5 contrast | F1–F3, E1 |
| P6 legacy CSS removed | E1 |
| P7 no backend/dependency/local CI | Global constraints, E1 |

Placeholder scan: no TBD/TODO. The only external scaffolds are the `shadcn add` commands and the shared component examples, each with an exact path/API.
