# UI Refresh Wave 2a — Overview Replacement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace every Overview surface with token-driven shadcn/AI Elements markup while preserving the operator contracts and all existing tests.

**Architecture:** Keep the behaviour layer (`useDecisionQueue`, `useAttentionQueue`, Kanban state/mutations, ReviewPanels mutations) and replace presentation. Extract the board column/card into presentational components. Keep the existing DOM hooks as compatibility shims for this wave; remove the replaced legacy CSS selector by selector.

**Tech Stack:** React 19, Vite 6, TypeScript 5.6, TanStack Query 5, Tailwind 4, shadcn/ui (Base UI), Vitest 2, Playwright.

**Spec:** `docs/superpowers/specs/2026-11-25-ui-refresh-wave2-overview-design.md`
**Decisions:** `docs/superpowers/specs/2026-11-25-ui-refresh-wave2-overview-decisions.md`

## Global Constraints

- Frontend only; no backend, API, DTO, event or permission change.
- No new runtime dependency. shadcn registry components (`pnpm dlx shadcn@latest add ...`) are allowed; DnD stays native HTML5.
- Preserve every existing E2E hook: `.col-k[data-col]`, `.kanban-mobile-list`, `.kanban-lane-select`, `decision-queue`, `.decision-queue__count`, `data-collapsed`, `attention-queue`, `.decision-zone`, `ask-card`, `mc-*` elsewhere. New markup emits them alongside token classes.
- Do not touch other routes, AriaPanel, `/aria`, TaskDrawer/AgentDrawer/ConfigureModal/Toast chrome.
- Remove legacy CSS only for selectors whose JSX usage is gone; verify with grep first. Never rewrite all of `index.css`.
- Every task: targeted unit + E2E gate green, `bash scripts/local-ci.sh affected` before push, one commit.
- Reply to the user in Traditional Chinese; code and commits in English.

## File ownership

| Task | Owns |
|------|------|
| 1 | `src/components/DecisionQueue.tsx`, `src/components/AttentionQueue.tsx`, their tests, `styles/decision-queue.css`, `styles/attention-queue.css`, optional `src/components/overview/QueueCard.tsx` |
| 2 | `src/components/KanbanBoard.tsx`, new `src/components/kanban/BoardColumn.tsx`, `src/components/kanban/KanbanCard.tsx`, `KanbanBoard.test.tsx`, `styles/kanban-flow.css`, board selectors in `index.css` |
| 3 | `src/components/ReviewPanels.tsx`, `src/components/ReviewWorkspace.tsx`, `ReviewPanels.test.tsx`, `ReviewWorkspace.test.tsx`, `TaskDrawer.test.tsx`, decision/review selectors in `index.css` |
| 4 | `src/components/ExecutiveSummary.tsx`, `MorningBriefing.tsx`, `AgentTeam.tsx`, `ActivityTimeline.tsx`, their page tests, `styles/overview-flow.css`, summary/agent/activity sections in `index.css` |
| 5 | `e2e/ui-refresh.spec.ts` (wave-2 cases), screenshots under `docs/reviews/shots/`, final selector audit |

Shared `src/pages/OverviewPage.tsx` is edited only by Task 4 (composition) and Task 5 (test id additions); no other task edits it.

### Task 0: Base and design gate

**Files:** none.

**Interfaces:**
- Consumes: `design/ui-refresh-wave2` (spec + decisions), `main`.
- Produces: an implementation worktree with the spec present.

- [ ] **Step 1: Verify the design artifacts**

Run:
```bash
test -f docs/superpowers/specs/2026-11-25-ui-refresh-wave2-overview-design.md && echo SPEC_PRESENT || echo SPEC_MISSING
```
Expected: `SPEC_PRESENT`. If the design branch has not been merged and the implementation worktree will be created from `main`, merge the design docs first.

- [ ] **Step 2: Create the implementation worktree**

```bash
git worktree add /Users/happy/.qoder/worktrees/app/b420ea/aria-conductor-wave2-impl -b feat/ui-refresh-wave2 design/ui-refresh-wave2
cd /Users/happy/.qoder/worktrees/app/b420ea/aria-conductor-wave2-impl
git status -sb
```
Expected: clean `## feat/ui-refresh-wave2`; the spec travels with the branch until its docs PR merges.

### Task 1: Decision queue and attention queue

**Files:**
- Modify: `src/components/DecisionQueue.tsx`, `src/components/AttentionQueue.tsx`, `src/components/__tests__/DecisionQueue.test.tsx`, `src/components/__tests__/AttentionQueue.test.tsx`
- Create: `src/components/overview/QueueSection.tsx` (shared flat section shell with title/count/collapse)
- Delete: `src/styles/decision-queue.css`, `src/styles/attention-queue.css` (after imports removed)

**Interfaces:**
- Consumes: the existing `DecisionRow`/`AttentionRow` props and the existing `data-testid`s.
- Produces: `QueueSection` with props `{ title, accent?, count, collapsed, testId, children }`; both queues keep their exported component signatures unchanged.

- [ ] **Step 1: Write the failing section test**

Create the test in `DecisionQueue.test.tsx`:
```tsx
it('renders the decision section with the token surface and the legacy hooks', () => {
  renderQueue({ rows: [row], collapsed: false, count: 1 });
  const section = screen.getByTestId('decision-queue');
  expect(section).toHaveAttribute('data-collapsed', 'false');
  expect(section.querySelector('.decision-queue__count')).toHaveTextContent('1');
  expect(section.querySelector('[data-slot="card"]')).not.toBeNull();
});
```
`renderQueue` is the helper already in the file; keep all existing assertions.

- [ ] **Step 2: Run it and confirm it fails**

Run: `pnpm vitest run src/components/__tests__/DecisionQueue.test.tsx`
Expected: FAIL on the `[data-slot="card"]` assertion.

- [ ] **Step 3: Implement `QueueSection`**

```tsx
// src/components/overview/QueueSection.tsx
import type { ReactNode } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';

export function QueueSection({ className, countClassName, title, accent, collapsedLabel, count, collapsed, testId, children }: {
  className: string; countClassName: string; title: string; accent?: string;
  collapsedLabel: string; count: number | null; collapsed: boolean;
  testId: string; children: ReactNode;
}) {
  if (collapsed) {
    return (
      <section className={className} data-testid={testId} data-collapsed="true" aria-label={title}>
        <h2 className="text-sm text-muted-foreground">{collapsedLabel}</h2>
      </section>
    );
  }
  return (
    <section className={className} data-testid={testId} data-collapsed="false" aria-label={title}>
      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2">
          <CardTitle className="text-base">{title}{accent && <span className="text-muted-foreground"> · {accent}</span>}</CardTitle>
          <Badge variant="secondary" className={countClassName}>{count ?? '—'}</Badge>
        </CardHeader>
        <CardContent className="space-y-2">{children}</CardContent>
      </Card>
    </section>
  );
}
```
Decision passes `className="decision-queue"`, `countClassName="decision-queue__count"`, `collapsedLabel="Needs your decision · No decisions waiting"`. Attention passes `className="attention-queue"`, `countClassName="attention-queue__count"`, `title="Needs attention"`, `accent="recovery"`, `collapsedLabel="Needs attention · Nothing needs attention"`. These exact strings are asserted by E2E.

- [ ] **Step 4: Restyle the rows**

Replace the row markup with shadcn `Card`/`Badge`/`Button` and token classes. Keep every existing test id, `data-risk`, accessible names ("Approve", "Deny", "Answer & continue", "Open review board"), busy/disabled logic, and the `ErrorNotice`/`StateNotice` branches. The count element keeps `.decision-queue__count`/`.attention-queue__count`.

- [ ] **Step 5: Remove the flow CSS**

```bash
grep -R "decision-queue.css\|attention-queue.css" src
```
Remove the imports, delete both files, then rerun the unit suites.

- [ ] **Step 6: Run the task gate**

```bash
pnpm vitest run src/components/__tests__/DecisionQueue.test.tsx src/components/__tests__/AttentionQueue.test.tsx
npx tsc --noEmit -p tsconfig.json
BASE_URL=http://localhost:5173 PLAYWRIGHT_BROWSER_CHANNEL=chromium npx playwright test e2e/decision-first-overview.spec.ts e2e/overview-dashboard.spec.ts --project=chromium
```
Expected: all PASS; if the stack is not running, mark E2E NOT RUN and start it before the final wave gate.

- [ ] **Step 7: Commit**

```bash
git add agent-control-tower/act-dashboard/src/components/DecisionQueue.tsx agent-control-tower/act-dashboard/src/components/AttentionQueue.tsx agent-control-tower/act-dashboard/src/components/overview/QueueSection.tsx agent-control-tower/act-dashboard/src/components/__tests__/DecisionQueue.test.tsx agent-control-tower/act-dashboard/src/components/__tests__/AttentionQueue.test.tsx
git commit -m "feat(dashboard): rebuild the decision and attention queues on tokens"
```

### Task 2: Board columns and cards

**Files:**
- Modify: `src/components/KanbanBoard.tsx`, `src/components/__tests__/KanbanBoard.test.tsx`
- Create: `src/components/kanban/BoardColumn.tsx`, `src/components/kanban/KanbanCard.tsx`
- Delete: `src/styles/kanban-flow.css`; remove board selectors from `index.css`
- Add (registry): shadcn `table` only if needed by other tasks; no new package

**Interfaces:**
- Consumes: existing `KanbanItem`, `KanbanStatus`, `LEGAL_DROPS`, mutation handlers, `draggingId`, `selectedLane`.
- Produces: `BoardColumn` props `{ column, items, narrow, draggingId, onDragStart, onDragEnd, onDrop, onOpenTask, ...actions }`; `KanbanCard` props `{ item, dragging, onOpen, ...actions }`.

- [ ] **Step 1: Write the failing column test**

```tsx
it('renders each column on a token card while keeping data-col', async () => {
  await renderBoard([]);
  const column = screen.getByTestId('lane-REVIEW');
  expect(column).toHaveAttribute('data-col', 'REVIEW');
  expect(column.querySelector('[data-slot="card"]')).not.toBeNull();
});
```
`renderBoard` is the helper already in `KanbanBoard.test.tsx`; empty data still renders every lane.

- [ ] **Step 2: Run it and confirm it fails**

Run: `pnpm vitest run src/components/__tests__/KanbanBoard.test.tsx`
Expected: FAIL, module not found.

- [ ] **Step 3: Extract and restyle**

Create `BoardColumn`/`KanbanCard` from the existing JSX inside `KanbanBoard.tsx:454-735`, moving the handlers as props. Keep:
- `className` `col-k` + `data-col` on the column and `data-testid="lane-<key>"` on the lane.
- `draggable`, `onDragStart`, `onDragEnd`, `onDragOver`, `onDrop` exactly where they are.
- `aria-label="Open task <title>"`, `Quick approve`, `Request changes`, `Quick deny`, `Expand review workspace`, cancel controls.
- `kanban-mobile`/`kanban-mobile-list`/`kanban-lane-select` and `Select lane`.
- create/clear/batch modals and `kanban-form-error` semantics.

- [ ] **Step 4: Run board tests and E2E**

```bash
pnpm vitest run src/components/__tests__/KanbanBoard.test.tsx
BASE_URL=http://localhost:5173 PLAYWRIGHT_BROWSER_CHANNEL=chromium npx playwright test e2e/kanban-board.spec.ts e2e/kanban-keyboard.spec.ts e2e/kanban-hitl.spec.ts e2e/mobile-flows.spec.ts e2e/approvals-decision-flow.spec.ts --project=chromium
```
Expected: all PASS, including 390px lane selector and no overflow.

- [ ] **Step 5: Remove the board CSS**

```bash
grep -R "kanban-flow.css" src
grep -R "className=.*\(col-k\|kanban-grid\|kanban-mobile\|batch-actions\|priority-seg\|cap-\)" src
```
Delete `kanban-flow.css`; remove only the board selectors whose JSX usage is gone from `index.css` (Kanban block ~187–343): `.kanban*`, `.col-k`, `.card*` that are board-only, `.batch-actions`, `.priority-seg`, `.cap-*`, `.gateline`, `.lane`. Do not remove `.panel`, `.pill`, `.btn` (shared elsewhere). Re-run `KanbanBoard.test.tsx` after each removal.

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-dashboard/src/components/KanbanBoard.tsx agent-control-tower/act-dashboard/src/components/kanban agent-control-tower/act-dashboard/src/components/__tests__/KanbanBoard.test.tsx agent-control-tower/act-dashboard/src/styles/index.css agent-control-tower/act-dashboard/src/styles/kanban-flow.css
git commit -m "feat(dashboard): rebuild the kanban board on token surfaces"
```

### Task 3: Decision panels and review workspace

**Files:**
- Modify: `src/components/ReviewPanels.tsx`, `src/components/ReviewWorkspace.tsx`, `src/components/__tests__/ReviewPanels.test.tsx`, `src/components/__tests__/ReviewWorkspace.test.tsx`, `src/components/__tests__/TaskDrawer.test.tsx`
- Remove decision/review selectors from `index.css`

**Interfaces:**
- Consumes: existing `DecisionPanel`, `ShortApprovalView`, `RunOutcomeChip`, `ReviewWorkspace` exports and the existing mutations.
- Produces: unchanged exports; visual markup only.

- [ ] **Step 1: Write the failing decision-zone test**

Add to `ReviewPanels.test.tsx`:
```tsx
it('renders the decision zone on a token surface while keeping the region name', () => {
  render(<DecisionPanel item={mkItem()} pendingAsks={[mkAsk()]} />);
  const zone = screen.getByRole('region', { name: 'Decision panel' });
  expect(zone).toHaveClass('decision-zone');
  expect(zone.querySelector('[data-slot="card"]')).not.toBeNull();
});
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `pnpm vitest run src/components/__tests__/ReviewPanels.test.tsx`
Expected: FAIL on the card assertion.

- [ ] **Step 3: Restyle**

Replace the panel/workspace markup with `Card`/`Button`/`Badge`/`Textarea` and token classes. Keep:
- `role="region" aria-label="Decision panel"`, `.decision-zone`, `.ask-card`.
- Question answer/finalize, approve/deny, request-changes, bulk approve excluding native and clarification asks, 403/409 remedy, in-flight guards.
- ReviewWorkspace Escape rules, Expand/back, run/evidence/artifact sections, and `panel review-workspace` hooks.
- TaskDrawer's embedded decision zone inherits the new visuals; do not change its chrome.

- [ ] **Step 4: Run the task gate**

```bash
pnpm vitest run src/components/__tests__/ReviewPanels.test.tsx src/components/__tests__/ReviewWorkspace.test.tsx src/components/__tests__/TaskDrawer.test.tsx
BASE_URL=http://localhost:5173 PLAYWRIGHT_BROWSER_CHANNEL=chromium npx playwright test e2e/kanban-hitl.spec.ts e2e/approvals-decision-flow.spec.ts e2e/ops-approval-surface.spec.ts --project=chromium
```

- [ ] **Step 5: Remove decision/review CSS**

Remove `.decision-zone`/`.ask-*`/`.dod-textarea`/`.dz-*`/`.rw-*`/`.rf-*`/`.run-result`/`.artifact-result` from `index.css` only when each selector has no remaining JSX usage; re-run the three suites after removal.

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-dashboard/src/components/ReviewPanels.tsx agent-control-tower/act-dashboard/src/components/ReviewWorkspace.tsx agent-control-tower/act-dashboard/src/components/__tests__/ReviewPanels.test.tsx agent-control-tower/act-dashboard/src/components/__tests__/ReviewWorkspace.test.tsx agent-control-tower/act-dashboard/src/components/__tests__/TaskDrawer.test.tsx agent-control-tower/act-dashboard/src/styles/index.css
git commit -m "feat(dashboard): restyle the decision panels and review workspace"
```

### Task 4: Summary, briefing, team and activity

**Files:**
- Modify: `src/components/ExecutiveSummary.tsx`, `MorningBriefing.tsx`, `AgentTeam.tsx`, `ActivityTimeline.tsx`, `src/pages/OverviewPage.tsx`, `src/pages/__tests__/OverviewPage.test.tsx`
- Delete: `src/styles/overview-flow.css`; remove summary/agent/activity sections from `index.css`
- Add (registry): `pnpm dlx shadcn@latest add table` if the activity table is not built from plain semantic markup

**Interfaces:**
- Consumes: `getSummary`, `getAgentTelemetry`, `getRecentActivity`, existing children composition.
- Produces: same exports; token Cards/Table; Overview composition unchanged except presentation.

- [ ] **Step 1: Write the failing summary test**

```tsx
it('renders the summary on token cards and keeps the honesty copy', async () => {
  summaryMock.mockResolvedValue(summary({ totalTokensBurned: 0, runsWithoutUsage: 3 }));
  ui();
  expect(await screen.findByText('not reported')).toBeInTheDocument();
  expect(screen.getByText(/3 runs reported no usage/)).toBeInTheDocument();
  expect(document.querySelector('[data-slot="card"]')).not.toBeNull();
});
```
`ui()` and `summary()` are the existing helpers in `pages/__tests__/OverviewPage.test.tsx`.

- [ ] **Step 2: Run it and confirm it fails**

Run: `pnpm vitest run src/pages/__tests__/OverviewPage.test.tsx`
Expected: FAIL on the card assertion.

- [ ] **Step 3: Restyle the four sections**

Use `Card`/`Badge`/`Table` with tokens. Preserve the exact honesty strings, counts, status classes, and `data-testid`s the page tests assert. Keep the KpiRow unchanged.

- [ ] **Step 4: Remove the legacy sections**

Delete `overview-flow.css` and the Executive/Kanban-adjacent/Agent/Activity blocks in `index.css` whose selectors are no longer used (`.stat`, `.summary`, `.agents`, `.avatar*`, `.tline*`, `.ev*`, `.section-h`, `.run-result` if Task 3 left it). Verify each with grep; keep `.panel`, `.pill`, `.btn`, `.accent` if other pages still use them.

- [ ] **Step 5: Run the task gate**

```bash
pnpm vitest run src/pages/__tests__/OverviewPage.test.tsx src/components/__tests__/DecisionQueue.test.tsx src/components/__tests__/AttentionQueue.test.tsx
BASE_URL=http://localhost:5173 PLAYWRIGHT_BROWSER_CHANNEL=chromium npx playwright test e2e/overview-dashboard.spec.ts e2e/decision-first-overview.spec.ts e2e/mobile-flows.spec.ts --project=chromium
```

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-dashboard/src/components/ExecutiveSummary.tsx agent-control-tower/act-dashboard/src/components/MorningBriefing.tsx agent-control-tower/act-dashboard/src/components/AgentTeam.tsx agent-control-tower/act-dashboard/src/components/ActivityTimeline.tsx agent-control-tower/act-dashboard/src/pages/OverviewPage.tsx agent-control-tower/act-dashboard/src/pages/__tests__/OverviewPage.test.tsx agent-control-tower/act-dashboard/src/styles/index.css agent-control-tower/act-dashboard/src/styles/overview-flow.css
git commit -m "feat(dashboard): rebuild the Overview summary sections on tokens"
```

### Task 5: Wave acceptance and cleanup audit

**Files:**
- Modify: `e2e/ui-refresh.spec.ts` (wave-2 overflow/contrast cases), `docs/reviews/shots/` (Overview screenshots)
- No product edits expected; a failing selector audit returns to its owning task.

**Interfaces:**
- Consumes: all previous tasks.
- Produces: W1–W6 evidence and the selector audit.

- [ ] **Step 1: Run the full local gate**

```bash
bash scripts/local-ci.sh affected
```
Expected: frontend gate green; backend NOT RUN (no backend change). If E2E is needed:
```bash
bash scripts/local-ci.sh e2e
```

- [ ] **Step 2: Run the 13 selector-dependent specs + keyboard**

```bash
BASE_URL=http://localhost:5173 PLAYWRIGHT_BROWSER_CHANNEL=chromium npx playwright test \
  e2e/kanban-board.spec.ts e2e/kanban-keyboard.spec.ts e2e/kanban-hitl.spec.ts \
  e2e/decision-first-overview.spec.ts e2e/overview-dashboard.spec.ts e2e/mobile-flows.spec.ts \
  e2e/approvals-decision-flow.spec.ts e2e/agent-dev-workflow-governance.spec.ts \
  e2e/sdd-workflow.spec.ts e2e/core-permissions.spec.ts e2e/journey-agent-run-report.spec.ts \
  e2e/journey-crew-deploy-run.spec.ts e2e/ops-approval-surface.spec.ts --project=chromium
```

- [ ] **Step 3: Selector audit**

```bash
for sel in col-k kanban-grid kanban-mobile kanban-mobile-list kanban-lane-select decision-zone ask-card decision-queue__count attention-queue__count; do
  echo "== $sel"; grep -R "className=.*${sel}" agent-control-tower/act-dashboard/src --include='*.tsx' || true
done
```
Every remaining selector must be intentional (compatibility hooks) or removed from CSS. `.panel`, `.pill`, `.btn`, `.accent` stay because other pages use them.

- [ ] **Step 4: Screenshots and contrast**

Capture Overview at 390/1024/1400 in both themes into `docs/reviews/shots/ui-refresh-wave2-overview-{width}-{theme}.png`; extend `e2e/ui-refresh.spec.ts` to measure queue risk/error, board status, and attention status contrast in both themes (≥4.5).

- [ ] **Step 5: Commit and report**

```bash
git add agent-control-tower/act-dashboard/e2e/ui-refresh.spec.ts docs/reviews/shots/ui-refresh-wave2-overview-*.png
git commit -m "test(dashboard): cover the Wave 2 Overview replacement"
```
Report W1–W6 PASS/FAIL/NOT VERIFIED with command output; do not claim an unrun viewport or contrast surface.

## Self-review

| Spec requirement | Task |
|---|---|
| W1 every Overview section rebuilt | 1–4 |
| W2 behaviour parity | 1–4 (existing suites) |
| W3 13 E2E + keyboard | 1–5 |
| W4 390/1024/1400 | 2, 4, 5 |
| W5 dark/light AA | 5 |
| W6 no backend/no dependency/local CI | Global constraints, 5 |

Placeholder scan: no TBD/TODO; the only external scaffolds are the `shadcn add` command and the `QueueSection`/`BoardColumn`/`KanbanCard` examples, each with an exact path and API.
