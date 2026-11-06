# Decision-First UI/UX Repairs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This plan is not execution authorization: wait for explicit plan approval.

**Goal:** Ship the approved decision-first dashboard repairs for all 14 audited findings as small, independently testable frontend units, with disjoint file ownership so subagents can work concurrently.

**Architecture:** Frontend-only changes in `agent-control-tower/act-dashboard`. The Overview gains two new read-only sections driven by existing approvals/kanban/run/provider APIs; the existing Kanban, dialogs, feedback, chat, Ops, Knowledge and Configure surfaces receive targeted repairs. No new routes, no new dependencies, no backend/API/DB changes, no replacement design system.

**Tech Stack:** React 19, Vite 6, TypeScript 5.6, TanStack Query 5, react-router-dom 7, Vitest 2 + Testing Library, Playwright 1.61, existing CSS custom properties.

**Specs (read before coding):**
- `spark-output/context/flow-web.json` (desktop flows; 8 flows / 37 screens)
- `spark-output/context/flow-mobile.json` (mobile flows; 5 flows / 19 screens)
- `spark-output/context/brief.json` (approved direction and acceptance)
- `spark-output/context/audit.json` (audit-01..14 evidence)
- `docs/reviews/2026-10-24-uiux-delivery-ledger.json` (`detailed_design` and review history)

---

## Global Constraints

1. **Scope:** Only `agent-control-tower/act-dashboard/**`. Do not change Java, REST contracts, Flyway, WebSocket payloads, CI workflow files, lockfiles or dependencies.
2. **One writer per file.** A file listed in one lane is never edited by another lane. If a task needs a foreign file, stop and hand off to the Lead.
3. **No git by implementers.** Do not run `git add`, `git commit`, `git push`, `git checkout`, `git stash` or `git worktree`. The Lead commits at wave boundaries; pushes/PRs/merges require explicit user approval.
4. **Focused tests only for implementers.** Run only the task's focused Vitest file(s). The Lead/Release QA runs the full suite, coverage and build serially at wave boundaries. Never run build and verify concurrently.
5. **No installs.** `node_modules` is already present. Never run `pnpm install`, `npm install` or add packages.
6. **Secrets and dirty files.** Never read `.env`, credential files, runtime databases or unrelated workspaces. Never clean, stash, checkout or delete pre-existing files, screenshots, logs, `NUL` or generated `dist/`. Do not put real credentials in fixtures.
7. **Design system:** Reuse existing CSS variables and visual language. New styles live in the lane's new CSS file, use `var(--...)` tokens, and never add inline `gridTemplateColumns`, fixed pixel page widths or a new component framework.
8. **Honest state:** Every data surface distinguishes loading, successful empty, initial error, stale refresh and partial/unavailable data. No all-clear, health, ownership, token scope or success claim without authoritative data.
9. **Focus contract:** Destructive confirmations get Cancel-first focus, a Tab/Shift+Tab trap, Escape/Cancel without mutation and focus restoration. Quick deny and non-destructive dialogs keep their existing behavior. Live updates never steal focus.
10. **Permissions:** Card decisions and one-use native permission asks remain separate; no remembered grant and no batch action that includes a native ask.
11. **Errors and drafts:** Show the served error message before any generic fallback; preserve drafts on rejection; never invent backend validation rules.
12. **TDD and evidence:** Each task starts with a failing test, then the minimal implementation, then a focused green run. Handoffs include the exact command, counts and any NOT VERIFIED item. No "tests pass" without counts.
13. **Coverage:** Keep the existing Vitest thresholds (`lines 5`, `branches 60`, `functions 40`). Do not lower them or skip tests.
14. **Out of scope for this plan:** Finalize-run destructive classification, run deep links (`/chat?run=`), transcript-to-task opening, toast dismiss button, dialog failure retry lifecycle, Ops section anchors/retry, non-drag keyboard card movement, route/IA consolidation, backend permission enforcement, native app work, performance/contrast certification. These remain documented design targets or proposals; do not implement them silently.
15. **No release claim:** Implementation is complete only when the release gate in Task 15 passes; a merged PR or green local tests alone is not release.

---

## Concurrency and Ownership Map

| Lane | Writer(s) | Exclusive files | Tasks | May start |
|---|---|---|---|---|
| Lead | Delivery Lead | Ledger, plan, commits, integration | Task 0, 14, 15 | immediately after approval |
| A — Overview | Implementer A1 / A2 | `src/hooks/useDecisionQueue.ts`, `src/hooks/useAttentionQueue.ts`, `src/components/DecisionQueue.tsx`, `src/components/AttentionQueue.tsx`, `src/pages/OverviewPage.tsx`, `src/components/ExecutiveSummary.tsx`, `src/components/TopBar.tsx`, `src/styles/decision-queue.css`, `src/styles/attention-queue.css`, `src/styles/overview-flow.css`, matching tests | 2, 3, 4 | after Task 1 |
| B — Board | Implementer B | `src/components/KanbanBoard.tsx`, `src/components/ReviewPanels.tsx`, `src/hooks/useMediaQuery.ts`, `src/api/kanban.ts` (optional `details` fields only), `src/styles/kanban-flow.css`, matching tests | 5, 6 | after Task 1 |
| C — Interaction | Implementer C1 / C2 | `src/components/ConfirmDialog.tsx`, `src/components/DrawerContext.tsx` (read-only regression), `src/components/TaskDrawer.tsx`, `src/components/ReviewWorkspace.tsx`, `src/components/Toast.tsx`, `src/components/RailNav.tsx`, `src/styles/feedback-flow.css`, `src/styles/toast-flow.css`, matching tests | 7, 8 | after Task 1 |
| D — Secondary surfaces | Implementer D1 / D2 | `src/pages/ChatPage.tsx`, `src/pages/OpsPage.tsx`, `src/pages/KnowledgePage.tsx`, `src/components/ConfigureModal.tsx`, `src/styles/chat-flow.css`, `src/styles/configure-flow.css`, matching tests | 9, 10, 11, 12 | after Task 1 |
| E — Verification | Release QA | New files under `e2e/decision-first-*.spec.ts`, `e2e/kanban-keyboard.spec.ts`, `e2e/mobile-flows.spec.ts`, `e2e/config-knowledge-honesty.spec.ts` | 13 | after Wave 2 |
| R — Review | Independent Reviewer | none (read-only) | Wave reviews | after each wave |

**Wave schedule:**

- **Wave 0 (serial):** Task 0 baseline, Task 1 shared primitives. Lead commits.
- **Wave 1 (parallel, 5 writers):** A1 Task 2, A2 Task 3, B Task 5, C Task 7, D Task 9. Lead commits after focused green.
- **Wave 2 (parallel, up to 6 writers):** A Task 4 (after 2+3), B Task 6 (after 5), C Task 8 (after 7), D Task 10 + Task 11 (disjoint files), then D Task 12. Lead commits after focused green.
- **Wave 3:** E Task 13 E2E specs; R independent review of the full diff. Fix wave returns to the owning lane only.
- **Wave 4:** Lead Task 14 full gate (serial) and Release QA sign-off.
- **Wave 5:** Lead Task 15 clean-room, PR, hosted CI and release DoD; requires explicit user approval for push/PR/merge.

**Dispatch rules:** at most six implementers active at once; never two on the same file; each implementer receives the task brief, exact allowed files, acceptance commands and handoff format. Implementers must not edit another lane's CSS or tests.

---

## Finding-to-Task Map

| Finding | Repair | Task(s) |
|---|---|---|
| audit-01 | Overview stacks decisions, attention and support; board stays local | 4, 13 |
| audit-02 | Chat thread list / full-width transcript modes with Back context | 9, 13 |
| audit-03 | Ops two-column inline override removed; existing media rule stacks | 10, 13 |
| audit-04 | Honest loading/empty/error/stale/partial states; no false all-clear | 1, 2, 3, 4, 5 |
| audit-05 | Destructive Cancel-first dialog with trap and focus restore | 7, 13 |
| audit-06 | Toast live region, pause on hover/focus, no focused eviction | 8 |
| audit-07 | View only for a resolved destination | 8 |
| audit-08 | Rail items are native links with `aria-current=page` | 8 |
| audit-09 | Structured create errors and preserved drafts | 6 |
| audit-10 | Hash ownership removed; real owner or Unattributed | 11, 13 |
| audit-11 | Skills read-only, Gates reference-only, real settings preserved | 12, 13 |
| audit-12 | Run conversations naming and accurate participants | 9, 13 |
| audit-13 | Recorded tokens label and retained-record scope | 4 |
| audit-14 | Named keyboard-operable card opener | 6, 13 |

## Test Helpers

Use the harness that already exists in each file; do not invent a parallel one.

| File | Existing harness | How to use it |
|---|---|---|
| `src/components/__tests__/Toast.test.tsx` | `renderToast()` + module-level `emit(...)`, fake timers in `beforeEach` | Call `renderToast()`, then `emit(event)`. There is no `renderToastWithSubscribe`. |
| `src/pages/__tests__/KnowledgePage.test.tsx` | `ui()` returning `render(<QueryClientProvider>…)` | Call `ui()`; mock `listKnowledge`/`listAgents` per test. |
| `src/pages/__tests__/OpsPage.test.tsx` | `ui()` with axios adapter fixtures | Call `ui()`. |
| `src/pages/__tests__/ChatPage.mobile.test.tsx` (new) | none | Define one local `renderChat()` in the new file; do not create a shared harness. |
| `src/components/__tests__/ConfirmDialog.test.tsx` | local `setup(overrides)` | Extend `setup` with a `danger` override; keep the existing non-danger autofocus test. |

Do not weaken existing assertions and do not convert focused tests into snapshots. Any intentionally replaced assertion must be named in the handoff with old and new counts.

---

## Task 0: Preflight (Lead only)

**Files:** none.

- [ ] **Step 1: Verify baseline**

```bash
git status -sb
git log -5 --oneline
git diff --stat
```

Expected: only the known untracked audit/design artifacts; no tracked diff. Record the HEAD SHA in the ledger.

- [ ] **Step 2: Verify the installed toolchain without installing**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest --version
pnpm --dir agent-control-tower/act-dashboard exec playwright --version
```

Expected: Vitest 2.x, Playwright 1.61.x, exit 0. If either is missing, stop; do not install.

- [ ] **Step 3: Run the current focused regression baseline serially**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/components/__tests__/KanbanBoard.test.tsx src/components/__tests__/ConfirmDialog.test.tsx src/components/__tests__/Toast.test.tsx --no-file-parallelism
```

Expected: 81 passed, 0 failed (the known baseline). Record counts. Do not change assertions to make this pass.

- [ ] **Step 4: Create the implementation branch only after plan approval**

```bash
git switch -c feat/uiux-decision-first
```

Do not push. Do not create worktrees unless the user asks; disjoint files make a shared working tree safe.

---

## Task 1: Shared State and Focus Primitives (Wave 0, serial)

**Lane:** Lead + one implementer (foundation).
**Depends on:** Task 0.
**Files:**
- Create: `src/utils/queryState.ts`
- Create: `src/utils/stableOrder.ts`
- Create: `src/utils/focusReturn.ts`
- Create: `src/components/StateNotice.tsx`
- Create: `src/styles/state-notice.css`
- Test: `src/utils/__tests__/queryState.test.ts`
- Test: `src/utils/__tests__/stableOrder.test.ts`
- Test: `src/utils/__tests__/focusReturn.test.ts`
- Test: `src/components/__tests__/StateNotice.test.tsx`

**Interfaces produced (all later lanes consume these):**

```ts
export type QueryPhase = 'loading' | 'success' | 'error' | 'stale';
export function queryPhase(q: {
  data: unknown;
  isPending: boolean;
  isError: boolean;
  isSuccess: boolean;
}): QueryPhase;
export function allAuthoritative(phases: QueryPhase[]): boolean;

export function mergeStableOrder<T extends { id: string }>(
  previous: T[] | null,
  next: T[],
  locked: boolean,
): T[];

export function rememberFocus(): () => boolean;

export type StateNoticeKind = 'loading' | 'empty' | 'error' | 'stale' | 'partial';
export interface StateNoticeProps {
  kind: StateNoticeKind;
  title: string;
  message?: string;
  actionLabel?: string;
  onAction?: () => void;
}
export function StateNotice(props: StateNoticeProps): React.ReactElement;
```

- [ ] **Step 1: Write the failing utility tests**

```ts
// src/utils/__tests__/queryState.test.ts
import { describe, expect, it } from 'vitest';
import { allAuthoritative, queryPhase } from '../queryState';

describe('queryPhase', () => {
  it('is loading before the first response', () => {
    expect(queryPhase({ data: undefined, isPending: true, isError: false, isSuccess: false })).toBe('loading');
  });
  it('is error when the first response failed', () => {
    expect(queryPhase({ data: undefined, isPending: false, isError: true, isSuccess: false })).toBe('error');
  });
  it('is stale when a refetch failed but cached data exists', () => {
    expect(queryPhase({ data: [1], isPending: false, isError: true, isSuccess: false })).toBe('stale');
  });
  it('is success only for a settled successful query', () => {
    expect(queryPhase({ data: [], isPending: false, isError: false, isSuccess: true })).toBe('success');
  });
});

describe('allAuthoritative', () => {
  it('requires every source to be successful and non-empty', () => {
    expect(allAuthoritative(['success', 'success'])).toBe(true);
    expect(allAuthoritative(['success', 'stale'])).toBe(false);
    expect(allAuthoritative(['success', 'loading'])).toBe(false);
    expect(allAuthoritative([])).toBe(false);
  });
});
```

```ts
// src/utils/__tests__/stableOrder.test.ts
import { describe, expect, it } from 'vitest';
import { mergeStableOrder } from '../stableOrder';

const a = { id: 'a' };
const b = { id: 'b' };
const c = { id: 'c' };

it('keeps known rows in place and appends new rows while locked', () => {
  expect(mergeStableOrder([a, b], [b, c, a], true).map((x) => x.id)).toEqual(['a', 'b', 'c']);
});

it('follows the server order when unlocked', () => {
  expect(mergeStableOrder([a, b], [b, c, a], false).map((x) => x.id)).toEqual(['b', 'c', 'a']);
});
```

```ts
// src/utils/__tests__/focusReturn.test.ts
import { describe, expect, it } from 'vitest';
import { rememberFocus } from '../focusReturn';

it('restores the captured element while it is connected', () => {
  const button = document.createElement('button');
  document.body.append(button);
  button.focus();
  const restore = rememberFocus();
  button.blur();
  expect(restore()).toBe(true);
  expect(document.activeElement).toBe(button);
  button.remove();
});

it('reports failure when the captured element is gone', () => {
  const button = document.createElement('button');
  document.body.append(button);
  button.focus();
  const restore = rememberFocus();
  button.remove();
  expect(restore()).toBe(false);
});
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/utils/__tests__/queryState.test.ts src/utils/__tests__/stableOrder.test.ts src/utils/__tests__/focusReturn.test.ts --no-file-parallelism
```

Expected: FAIL because the modules do not exist.

- [ ] **Step 3: Implement the utilities**

```ts
// src/utils/queryState.ts
export type QueryPhase = 'loading' | 'success' | 'error' | 'stale';

export function queryPhase(q: {
  data: unknown;
  isPending: boolean;
  isError: boolean;
  isSuccess: boolean;
}): QueryPhase {
  if (q.isSuccess && !q.isError) return 'success';
  if (q.isError) return q.data === undefined || q.data === null ? 'error' : 'stale';
  return 'loading';
}

export function allAuthoritative(phases: QueryPhase[]): boolean {
  return phases.length > 0 && phases.every((p) => p === 'success');
}
```

```ts
// src/utils/stableOrder.ts
export function mergeStableOrder<T extends { id: string }>(
  previous: T[] | null,
  next: T[],
  locked: boolean,
): T[] {
  if (!locked || !previous) return next;
  const rank = new Map(previous.map((item, index) => [item.id, index]));
  const known = next
    .filter((item) => rank.has(item.id))
    .sort((a, b) => (rank.get(a.id) ?? 0) - (rank.get(b.id) ?? 0));
  const added = next.filter((item) => !rank.has(item.id));
  return [...known, ...added];
}
```

```ts
// src/utils/focusReturn.ts
export function rememberFocus(): () => boolean {
  const element = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  return () => {
    if (!element?.isConnected) return false;
    element.focus();
    return true;
  };
}
```

- [ ] **Step 4: Write the failing StateNotice test**

```tsx
// src/components/__tests__/StateNotice.test.tsx
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StateNotice } from '../StateNotice';

it('announces an error and exposes a real retry action', async () => {
  const onAction = vi.fn();
  render(
    <StateNotice
      kind="error"
      title="Decision queue unavailable"
      message="Network request failed"
      actionLabel="Retry"
      onAction={onAction}
    />,
  );
  expect(screen.getByRole('alert')).toHaveTextContent('Decision queue unavailable');
  await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
  expect(onAction).toHaveBeenCalledTimes(1);
});

it('uses status semantics for loading and empty states', () => {
  const { rerender } = render(<StateNotice kind="loading" title="Loading decisions" />);
  expect(screen.getByRole('status')).toHaveTextContent('Loading decisions');
  rerender(<StateNotice kind="empty" title="No decisions waiting" />);
  expect(screen.getByRole('status')).toHaveTextContent('No decisions waiting');
});
```

- [ ] **Step 5: Implement StateNotice and its CSS**

```tsx
// src/components/StateNotice.tsx
import type { ReactElement } from 'react';
import '../styles/state-notice.css';

export type StateNoticeKind = 'loading' | 'empty' | 'error' | 'stale' | 'partial';

export interface StateNoticeProps {
  kind: StateNoticeKind;
  title: string;
  message?: string;
  actionLabel?: string;
  onAction?: () => void;
}

export function StateNotice({
  kind,
  title,
  message,
  actionLabel,
  onAction,
}: StateNoticeProps): ReactElement {
  const role = kind === 'error' ? 'alert' : 'status';
  return (
    <div className={`state-notice state-notice--${kind}`} role={role} data-testid={`state-notice-${kind}`}>
      <strong>{title}</strong>
      {message && <p>{message}</p>}
      {actionLabel && onAction && (
        <button type="button" className="btn" onClick={onAction}>
          {actionLabel}
        </button>
      )}
    </div>
  );
}
```

```css
/* src/styles/state-notice.css */
.state-notice {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.03);
  color: var(--text-dim);
  font-size: 12px;
}
.state-notice p { margin: 0; min-width: 0; overflow-wrap: anywhere; }
.state-notice--error { border-color: var(--red); color: var(--text); }
.state-notice--stale, .state-notice--partial { border-color: var(--amber); }
.state-notice .btn { margin-left: auto; }
```

- [ ] **Step 6: Run the focused tests to verify green**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/utils/__tests__/queryState.test.ts src/utils/__tests__/stableOrder.test.ts src/utils/__tests__/focusReturn.test.ts src/components/__tests__/StateNotice.test.tsx --no-file-parallelism
```

Expected: 4 files passed, 0 failed. Record the exact counts.

- [ ] **Step 7: Handoff**

Report: files created, command, counts, exit 0, and "not wired into any page yet". Do not commit; the Lead commits after review.

---

## Task 2: Decision Queue Data and Component (Lane A, Wave 1)

**Lane:** A1.
**Depends on:** Task 1.
**Files:**
- Create: `src/hooks/useDecisionQueue.ts`
- Create: `src/components/DecisionQueue.tsx`
- Create: `src/styles/decision-queue.css`
- Test: `src/hooks/__tests__/useDecisionQueue.test.tsx`
- Test: `src/components/__tests__/DecisionQueue.test.tsx`

**Interfaces:**

```ts
export interface DecisionRow {
  id: string;               // approval id, the row identity
  approvalId: string;
  runId: string;
  taskId: string | null;
  title: string;
  state: string;            // kanban status or UNKNOWN
  askType: string;
  source: string;
  reason: string;
  requestedAt: string;
  isPermission: boolean;
}
export function useDecisionQueue(options: { locked: boolean }): {
  rows: DecisionRow[];
  phase: QueryPhase;
  partial: boolean;
  count: number | null;
  retry: () => void;
};
```

- [ ] **Step 1: Write the failing hook test**

Create a QueryClient wrapper and mock `listApprovals` / `listKanbanItems`. The test must prove:

1. one approval renders one row even when the joined card has `pendingAskCount: 2` (no double count);
2. `approval.kanbanItemId` wins over a run-derived match, and a run-derived match is the fallback when `kanbanItemId` is absent or points at a missing card;
3. an approval with no matching card shows `Run <short id>`, `taskId: null`, and is still actionable (direct decide, or inline answer for `source === 'CLARIFICATION'`) — not a permanently disabled row;
4. a failed kanban query with successful approvals yields `partial: true`, rows still render, and `count` is `null`;
5. `count` is `null` while loading, when error/stale, or when `partial`; a successful non-partial zero is the only zero.

```tsx
// src/hooks/__tests__/useDecisionQueue.test.tsx
import { describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { listApprovals } from '../../api/approvals';
import { listKanbanItems } from '../../api/kanban';
import { useDecisionQueue } from '../useDecisionQueue';

vi.mock('../../api/approvals', () => ({ listApprovals: vi.fn() }));
vi.mock('../../api/kanban', () => ({ listKanbanItems: vi.fn() }));
vi.mock('../../components/NativePermissionAsk', () => ({ nativePermissionOf: () => null }));

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

it('uses the approval id as the row identity and joins the card title', async () => {
  vi.mocked(listApprovals).mockResolvedValue([
    { id: 'ap-1', runId: 'run-abcdef12', status: 'PENDING', askType: 'REVIEW_REQUEST', source: 'LEGACY_GATE', content: 'Check output', requestedAt: '2026-10-27T10:00:00Z' },
  ] as never);
  vi.mocked(listKanbanItems).mockResolvedValue([
    { id: 'k-1', title: 'Fix pickup retry', status: 'REVIEW', linkedRunId: 'run-abcdef12', pendingAskCount: 2, lastError: null },
  ] as never);

  const { result } = renderHook(() => useDecisionQueue({ locked: false }), { wrapper });
  await waitFor(() => expect(result.current.phase).toBe('success'));
  expect(result.current.rows).toHaveLength(1);
  expect(result.current.rows[0]).toMatchObject({ id: 'ap-1', taskId: 'k-1', title: 'Fix pickup retry', state: 'REVIEW' });
  expect(result.current.count).toBe(1);
});
```

- [ ] **Step 2: Run RED**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/hooks/__tests__/useDecisionQueue.test.tsx --no-file-parallelism
```

Expected: FAIL because `useDecisionQueue` does not exist.

- [ ] **Step 3: Implement the hook**

Implementation contract:
- Query keys exactly `['approvals', 'PENDING', 'all']` and `['kanban-items']` so existing invalidation continues to work.
- `refetchInterval` 10s/12s as the existing ReviewQueue/board do.
- Join in this order: (a) `approval.kanbanItemId` → card by id; (b) card whose `linkedRunId === approval.runId`; (c) no card. Never use `pendingAskCount` to build rows.
- Sort by `requestedAt` ascending (oldest wait first), then apply `mergeStableOrder(previousRef.current, rows, locked)`.
- `reason` = `approval.content ?? approval.reason ?? 'Decision requested'`, clamped in the view, not in data.
- `isPermission` = `nativePermissionOf(approval) !== null`.
- `partial` = at least one contributing query settled successfully while at least one is `error` or `stale`.
- `phase`: `loading` if either source is loading; `error` if both failed with no data; `stale` if any source is stale; otherwise `success`.
- `count` = `rows.length` only when `phase === 'success' && !partial`; otherwise `null`. A failed join must never turn an unknown count into zero.
- `retry` refetches both queries.
- Rows with `taskId === null` remain in the list: non-clarification rows are decided directly, clarification rows are answered inline. Do not drop or disable them because the card join failed; the card was never required for the approval decision itself.

- [ ] **Step 4: Write the failing component test**

```tsx
// src/components/__tests__/DecisionQueue.test.tsx
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DecisionQueue } from '../DecisionQueue';

const row = {
  id: 'ap-1', approvalId: 'ap-1', runId: 'run-1', taskId: 'k-1', title: 'Fix pickup retry',
  state: 'REVIEW', askType: 'REVIEW_REQUEST', source: 'LEGACY_GATE', reason: 'Check output',
  requestedAt: '2026-10-27T10:00:00Z', isPermission: false,
};

it('renders the task, reason and a single primary action', async () => {
  const onActivate = vi.fn();
  render(
    <DecisionQueue
      rows={[row]} phase="success" partial={false} count={1}
      onRetry={vi.fn()} onActivate={onActivate} onDecide={vi.fn()} onAnswer={vi.fn()}
      collapsed={false}
    />,
  );
  expect(screen.getByText('Fix pickup retry')).toBeInTheDocument();
  expect(screen.getByText('Check output')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: /review/i }));
  expect(onActivate).toHaveBeenCalledWith(row);
});

it('never claims all clear while loading or unavailable', () => {
  const { rerender } = render(
    <DecisionQueue rows={[]} phase="loading" partial={false} count={null} onRetry={vi.fn()} onActivate={vi.fn()} onDecide={vi.fn()} onAnswer={vi.fn()} collapsed={false} />,
  );
  expect(screen.queryByText(/no decisions waiting/i)).not.toBeInTheDocument();
  expect(screen.getByText('—')).toBeInTheDocument();
  rerender(
    <DecisionQueue rows={[]} phase="error" partial={false} count={null} onRetry={vi.fn()} onActivate={vi.fn()} onDecide={vi.fn()} onAnswer={vi.fn()} collapsed={false} />,
  );
  expect(screen.getByRole('alert')).toHaveTextContent(/unavailable/i);
  expect(screen.queryByText(/no decisions waiting/i)).not.toBeInTheDocument();
});

it('keeps an unlinked approval actionable with direct decide and inline answer', async () => {
  const onDecide = vi.fn();
  const onAnswer = vi.fn();
  const decideRow = {
    ...row, id: 'ap-2', approvalId: 'ap-2', taskId: null, state: 'UNKNOWN',
    isPermission: false, source: 'LEGACY_GATE',
  };
  const askRow = {
    ...decideRow, id: 'ap-3', approvalId: 'ap-3', askType: 'QUESTION',
    source: 'CLARIFICATION', reason: 'Which environment?',
  };
  render(
    <DecisionQueue
      rows={[decideRow, askRow]} phase="success" partial={false} count={2}
      onRetry={vi.fn()} onActivate={vi.fn()} onDecide={onDecide} onAnswer={onAnswer}
      collapsed={false}
    />,
  );

  await userEvent.click(screen.getByRole('button', { name: 'Approve' }));
  expect(onDecide).toHaveBeenCalledWith(decideRow, true);
  await userEvent.click(screen.getByRole('button', { name: 'Deny' }));
  expect(onDecide).toHaveBeenCalledWith(decideRow, false);

  await userEvent.click(screen.getByRole('button', { name: 'Answer' }));
  await userEvent.type(screen.getByRole('textbox', { name: /answer/i }), 'staging');
  await userEvent.click(screen.getByRole('button', { name: /answer & continue/i }));
  expect(onAnswer).toHaveBeenCalledWith(askRow, 'staging');
});
```

- [ ] **Step 5: Run RED**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/components/__tests__/DecisionQueue.test.tsx --no-file-parallelism
```

Expected: FAIL because `DecisionQueue` does not exist.

- [ ] **Step 6: Implement the component**

Implementation contract:
- Props: `{ rows, phase, partial, count, retry, onActivate, onDecide, onAnswer, busyId?, actionError?, collapsed }`. `actionError` is `{ id: string; presented: PresentedError } | null`; `busyId` is `string | null`.
- Root `<section className="decision-queue" data-testid="decision-queue" data-collapsed={String(collapsed)} aria-labelledby="decision-queue-title">`.
- Header: `Waiting on you` plus count or `—`.
- `phase === 'loading'`: `StateNotice kind="loading" title="Loading decisions…"`, no empty copy.
- `phase === 'error'`: `StateNotice kind="error" title="Decision queue unavailable" actionLabel="Retry" onAction={onRetry}`.
- `phase === 'stale'` or `partial`: render the rows plus `StateNotice kind="stale"` with `Last updated HH:MM — refresh failed` and Retry.
- Successful empty: `StateNotice kind="empty" title="No decisions waiting"` with a successful refresh timestamp.
- `collapsed`: render only a one-line section with the same heading and `No decisions waiting`; only the parent may pass `collapsed=true` after all-clear.
- Row: title, `askType` pill, `state` pill, two-line reason, waited time, then actions by class:
  - `taskId !== null`, `isPermission` → `Review permission` → `onActivate(row)`
  - `taskId !== null`, `state === 'REVIEW'` → `Review` → `onActivate(row)`
  - `taskId !== null`, otherwise → `Answer` → `onActivate(row)`
  - `taskId === null`, `source !== 'CLARIFICATION'` → `Approve` and `Deny` → `onDecide(row, true/false)`, with the note `Task details unavailable — decide here.`
  - `taskId === null`, `source === 'CLARIFICATION'` → `Answer` toggles a local composer (textarea labelled `Answer`, `Answer & continue`) → `onAnswer(row, text)`; the draft lives in the row keyed by approval id and survives rejection.
- `busyId === row.id`: only that row's controls disable and show progress; no row is removed optimistically.
- `actionError?.id === row.id`: render `ErrorNotice presented={actionError.presented}` inside that row.
- No `onClick` on the row container.

- [ ] **Step 7: Add CSS and run GREEN**

`src/styles/decision-queue.css` uses `.decision-queue`, `.decision-row`, `.decision-row__meta`, `.decision-row__reason`; all colors via variables; no fixed page width; action button is full-width below 900px.

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/hooks/__tests__/useDecisionQueue.test.tsx src/components/__tests__/DecisionQueue.test.tsx --no-file-parallelism
```

Expected: 2 files passed, 0 failed.

- [ ] **Step 8: Handoff**

Report exact counts, the observed `Approval.kanbanItemId`/`linkedRunId` types, which join path each fixture exercised, and the taskless decide/answer coverage. No git.

---

## Task 3: Attention Queue Data and Component (Lane A, Wave 1, parallel with Task 2)

**Lane:** A2.
**Depends on:** Task 1.
**Files:**
- Create: `src/hooks/useAttentionQueue.ts`
- Create: `src/components/AttentionQueue.tsx`
- Create: `src/styles/attention-queue.css`
- Test: `src/hooks/__tests__/useAttentionQueue.test.tsx`
- Test: `src/components/__tests__/AttentionQueue.test.tsx`

**Interfaces:**

```ts
export type AttentionKind = 'pickup' | 'run' | 'provider';
export interface AttentionRow {
  id: string;
  kind: AttentionKind;
  title: string;
  reason: string;
  taskId?: string;
  runId?: string;
  cardStatus?: string;
}
export function useAttentionQueue(options: { locked: boolean }): {
  rows: AttentionRow[];
  phase: QueryPhase;
  partial: boolean;
  count: number | null;
  retry: () => void;
};
```

- [ ] **Step 1: Write the failing hook test**

Cover: a TODO card with `lastError` produces one pickup row; a REVIEW card with a pending ask and the same failure is not duplicated; a REVIEW card with **zero** pending asks does not hide its failed run; a failed run produces a run row when no pickup row exists; a mixed provider fleet yields rows only for the unhealthy providers; a successful empty set is only `success` when every contributing query settles.

```tsx
it('reports a TODO pickup failure with the real retry capability', async () => {
  vi.mocked(listKanbanItems).mockResolvedValue([
    { id: 'k-1', title: 'Fix pickup retry', status: 'TODO', lastError: 'port busy', linkedRunId: null, pendingAskCount: 0 },
  ] as never);
  vi.mocked(listRuns).mockResolvedValue([] as never);
  vi.mocked(listAdkProviders).mockResolvedValue([] as never);

  const { result } = renderHook(() => useAttentionQueue({ locked: false }), { wrapper });
  await waitFor(() => expect(result.current.phase).toBe('success'));
  expect(result.current.rows).toHaveLength(1);
  expect(result.current.rows[0]).toMatchObject({ kind: 'pickup', taskId: 'k-1', title: 'Fix pickup retry' });
});
```

- [ ] **Step 2: Run RED**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/hooks/__tests__/useAttentionQueue.test.tsx --no-file-parallelism
```

Expected: FAIL because the hook does not exist.

- [ ] **Step 3: Implement the hook**

Implementation contract:
- Queries: `['kanban-items']` (`listKanbanItems`), `['runs-all']` (`listRuns`), `['adk-providers']` (`listAdkProviders`) and per-provider `['adk-provider-health', id]` via `useQueries`.
- Pickup rows: cards with `lastError` and `status === 'TODO'`; `reason = lastError`.
- Run rows: runs with `status === 'FAILED'`. Skip only when the linked card already produced a pickup row or the linked card has `pendingAskCount > 0` (the decision queue owns real asks). A `REVIEW` card with zero pending asks does **not** suppress its failed run. Title falls back to `Run <short id>`.
- Provider rows: one row per provider whose health probe returned `healthy === false`. A mixed fleet shows the unhealthy providers only; healthy providers produce no row. Pending or errored probes produce no row and leave their evidence to the section notice.
- No fabricated severity. No attention row from a query failure; query failure is a StateNotice in the section.
- Apply `mergeStableOrder` with `locked`.
- `partial` = at least one contributing query (kanban, runs, provider list, provider probes) is `error`/`stale` while at least one is `success`. A probe that errored is missing evidence, not proof of health.
- `phase`: `loading` if any source is loading; `error` if every source failed with no data; `stale` if any source is stale; otherwise `success`.
- `count` = `rows.length` only when `phase === 'success' && !partial`; otherwise `null`.
- Sections with no rows and `count === 0` may only claim all clear after the parent's all-clear gate (Task 4), never from this hook alone.

- [ ] **Step 4: Write the failing component test**

Cover: rows show the reason and a keyboard-operable action; empty copy is absent during loading/error; `Retry pickup` calls the supplied callback; provider row exposes `Open Configure`; no `Retry` button claims to refetch an action that does not exist.

- [ ] **Step 5: Implement the component**

Props `{ rows, phase, partial, count, retry, onOpenTask, onRetryPickup, onOpenConfigure, collapsed }`. Render `section.attention-queue` with `data-testid="attention-queue"` and `data-collapsed={String(collapsed)}`; use `StateNotice` for loading/error/stale/empty; row action per kind:
- pickup: `Open task` (if taskId) and `Retry pickup (starts 1 run)`
- run: `Open task` (if taskId) and `Open run` only when a run route is real; otherwise omit
- provider: `Open Configure`
No batch action. The retry callback is only wired when the card is TODO; the parent supplies the mutation.

- [ ] **Step 6: Add CSS and run GREEN**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/hooks/__tests__/useAttentionQueue.test.tsx src/components/__tests__/AttentionQueue.test.tsx --no-file-parallelism
```

Expected: 2 files passed, 0 failed.

- [ ] **Step 7: Handoff**

Report counts, the exact provider-health rule used (including a mixed healthy/unhealthy fleet), and whether provider rows were exercised with fixtures or only type-checked.

---

## Task 4: Overview Integration, Summary States and Token Copy (Lane A, Wave 2)

**Lane:** A1 (after Tasks 2 and 3).
**Depends on:** Tasks 2, 3.
**Files:**
- Modify: `src/pages/OverviewPage.tsx`
- Modify: `src/components/ExecutiveSummary.tsx`
- Modify: `src/components/TopBar.tsx`
- Create: `src/styles/overview-flow.css`
- Test: `src/pages/__tests__/OverviewPage.test.tsx`
- Test: `src/components/__tests__/ExecutiveSummary.test.tsx`
- Test: `src/components/__tests__/TopBar.test.tsx`

**Changes:**
- Remove the inline `gridTemplateColumns` styles. Use `.overview-grid`, `.overview-main`, `.overview-support`, `.overview-strip` classes defined in `overview-flow.css`.
- DOM order: header is global; then `DecisionQueue`, `AttentionQueue`, `KanbanBoard`, then supporting `AgentTeam`, `ActivityTimeline`, `MorningBriefing`. Remove `ReviewQueue` from Overview only because every pending approval class is now actionable in `DecisionQueue` (task-backed review/answer/permission, taskless direct decide, taskless clarification inline answer). Keep `ReviewQueue` for Chat's run-scoped use.
- `useDecisionQueue` and `useAttentionQueue` run in `OverviewPage`; pass data to the presentational sections. `locked` is true when a drawer/review is open or a section has focus/hover.
- Decision actions: `onActivate` opens the review workspace for `REVIEW` and the task drawer otherwise; `onDecide` calls `approveApproval`/`rejectApproval`; `onAnswer` calls `answerAsk(id, { answer, approved: true })`. Every mutation calls `applyOperatorHeaders()`, invalidates `['approvals']`, `['kanban-items']` and `['dashboard-summary']`, and reports the served error inline through `actionError`. `busyId` tracks only the activated row.
- All-clear requires all four: `!decision.partial`, `!attention.partial`, `allAuthoritative([decision.phase, attention.phase])` and `decision.count === 0 && attention.count === 0`. A successful zero from one source plus a failed/partial/stale other source must never collapse. Only then pass `collapsed` to both sections.
- Review mode keeps the existing in-flow workspace and bottom strip; use classes, not inline grid. The strip stacks at narrow widths.
- `ExecutiveSummary`: remove the `Waiting on you` stat; the decision queue is the single count. Use `queryPhase` for summary/kanban/runs/knowledge/agents; show `—` and an honest detail (`Loading`, `Unavailable`, `Last known data; refresh failed`) instead of `All healthy`, `Nothing pending` or zeros. Keep real runtime composition. Token cell label becomes `Recorded tokens`, detail `Cumulative recorded input + output over retained records`.
- `TopBar`: label `Recorded tokens`. Derive `queryPhase` for the summary query and render each KPI via `—` for loading/unavailable, the real number for success, and the last-known number with `title="Last known data; refresh failed"` for stale. Zero is rendered only for an authoritative zero. Add stable test ids (`kpi-active-agents`, `kpi-running-runs`, `kpi-pending-approvals`, `kpi-tokens`). Keep the real provider badge behavior; the pending-approvals KPI remains a separate summary metric, not the decision queue count.

- [ ] **Step 1: Update the failing tests**

`OverviewPage.test.tsx`: assert DOM order (decision queue before attention queue before kanban), decision queue count/testids, all-clear only for two successful non-partial zero sources, no collapse for stale/error/partial (including a partial source with zero rows), no inline `gridTemplateColumns`, no `ReviewQueue`, and that a taskless approval's Approve/Deny/Answer handlers reach the API mutations.
`ExecutiveSummary.test.tsx`: loading/error/stale states show `—` and never `All healthy`/`Nothing pending`; `Waiting on you` stat is absent; token label/detail are exact; runtime composition remains.
`TopBar.test.tsx`: for every KPI, pending → `—`, error → `—`, stale → last-known number with the refresh-failed title, success zero → `0`, success value → value; `Recorded tokens` is rendered and `Tokens Today` is absent.

```tsx
// OverviewPage.test.tsx — mock the two hooks, keep the rest of the page real.
vi.mock('../../hooks/useDecisionQueue', () => ({ useDecisionQueue: vi.fn() }));
vi.mock('../../hooks/useAttentionQueue', () => ({ useAttentionQueue: vi.fn() }));
const decisionMock = vi.mocked(useDecisionQueue);
const attentionMock = vi.mocked(useAttentionQueue);

function setQueues(decision: Partial<ReturnType<typeof useDecisionQueue>>, attention: Partial<ReturnType<typeof useAttentionQueue>>) {
  decisionMock.mockReturnValue({ rows: [], phase: 'success', partial: false, count: 0, retry: vi.fn(), ...decision } as never);
  attentionMock.mockReturnValue({ rows: [], phase: 'success', partial: false, count: 0, retry: vi.fn(), ...attention } as never);
}

it('collapses decision sections only when both authoritative counts are zero', () => {
  setQueues({ phase: 'success', count: 0 }, { phase: 'success', count: 0 });
  const { rerender } = render(<OverviewPage />);
  expect(screen.getByTestId('decision-queue')).toHaveAttribute('data-collapsed', 'true');
  expect(screen.getByTestId('attention-queue')).toHaveAttribute('data-collapsed', 'true');

  setQueues({ phase: 'success', count: 0 }, { phase: 'stale', count: 2 });
  rerender(<OverviewPage />);
  expect(screen.getByTestId('decision-queue')).toHaveAttribute('data-collapsed', 'false');
  expect(screen.getByTestId('attention-queue')).toHaveAttribute('data-collapsed', 'false');
});

it('does not collapse when a source is partial even with zero rows', () => {
  setQueues(
    { phase: 'success', partial: false, count: 0 },
    { phase: 'success', partial: true, count: null },
  );
  render(<OverviewPage />);
  expect(screen.getByTestId('decision-queue')).toHaveAttribute('data-collapsed', 'false');
  expect(screen.getByTestId('attention-queue')).toHaveAttribute('data-collapsed', 'false');
});
```

- [ ] **Step 2: Run RED**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/pages/__tests__/OverviewPage.test.tsx src/components/__tests__/ExecutiveSummary.test.tsx src/components/__tests__/TopBar.test.tsx --no-file-parallelism
```

Expected: FAIL on the new assertions.

- [ ] **Step 3: Implement**

Use the hooks from Tasks 2/3. `onActivate` calls `openReviewMode(taskId)` for `REVIEW` and `openTaskDrawer(taskId)` otherwise; `onDecide` calls `approveApproval`/`rejectApproval`; `onAnswer` calls `answerAsk`. Taskless approvals keep those direct actions, so removing `ReviewQueue` never strands them. All decision mutations call `applyOperatorHeaders()` and invalidate `['approvals']`, `['kanban-items']` and `['dashboard-summary']`; on rejection set `actionError = { id, presented: presentError(err, 'Decision failed.') }` and keep every row.

Retry pickup calls `transitionKanbanItem(taskId, { status: 'IN_PROGRESS' })` and invalidates `['kanban-items']`, `['kanban']`, `['runs']`, `['runs-all']` and `['dashboard-summary']` (the attention hook reads `['runs-all']`, so invalidating only `['runs']` leaves it stale). On rejection show the served message inline in the attention row via a local `actionError` and do not remove the row. REVIEW cards with pending asks belong to the decision queue; the attention hook no longer suppresses a REVIEW card with zero asks.

Compute `allClear` exactly as `!decision.partial && !attention.partial && allAuthoritative([decision.phase, attention.phase]) && decision.count === 0 && attention.count === 0`. Do not derive it from row arrays or `?? 0`.

- [ ] **Step 4: Add CSS and run GREEN**

`overview-flow.css` contract:
- `.overview-grid` single column; `.overview-support` two columns at `min-width: 1100px`, one below.
- `.overview-strip` reflows to 2 columns at 1100px and one column below.
- Decision and attention sections are always full width.
- No page-level horizontal overflow; board overflow stays inside its own labeled region.
- Breakpoints: 1180 (knowledge), 1100 (support strip), 900 (chat/board/mobile), 640 (compact controls). Use the existing 375/390 targets.

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/pages/__tests__/OverviewPage.test.tsx src/components/__tests__/ExecutiveSummary.test.tsx src/components/__tests__/TopBar.test.tsx --no-file-parallelism
```

Expected: 3 files passed, 0 failed.

- [ ] **Step 5: Handoff**

Report the all-clear truth table, the exact summary labels, and the counts. Note any data join that could not be verified from types.

---

## Task 5: Board Query States and Mobile Lane Selector (Lane B, Wave 1)

**Lane:** B.
**Depends on:** Task 1.
**Files:**
- Modify: `src/components/KanbanBoard.tsx`
- Create: `src/hooks/useMediaQuery.ts`
- Create: `src/styles/kanban-flow.css`
- Test: `src/components/__tests__/KanbanBoard.test.tsx`
- Test: `src/hooks/__tests__/useMediaQuery.test.ts`

- [ ] **Step 1: Write the failing `useMediaQuery` test**

```ts
it('subscribes and unsubscribes to matchMedia', () => {
  const listeners: Array<() => void> = [];
  window.matchMedia = vi.fn().mockReturnValue({
    matches: true,
    addEventListener: (_: string, fn: () => void) => listeners.push(fn),
    removeEventListener: vi.fn(),
  }) as never;
  const { result, unmount } = renderHook(() => useMediaQuery('(max-width: 900px)'));
  expect(result.current).toBe(true);
  unmount();
  expect(window.matchMedia).toHaveBeenCalledWith('(max-width: 900px)');
});
```

- [ ] **Step 2: Run RED**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/hooks/__tests__/useMediaQuery.test.ts --no-file-parallelism
```

Expected: FAIL because the hook does not exist.

- [ ] **Step 3: Implement `useMediaQuery`**

Use `window.matchMedia` guarded for SSR/jsdom; subscribe with `addEventListener('change', ...)` when available, else legacy `addListener`; clean up. Return the current `matches`. No initial flash: initialize from `matchMedia(...).matches`.

- [ ] **Step 4: Add failing Kanban state tests**

In `KanbanBoard.test.tsx`:
- `listKanbanItems` pending → five lane headers render with skeleton placeholders and no "0 tasks" empty claim; `state-notice-loading` exists.
- rejected query → `state-notice-error` with a Retry button; `refetch` called on click; no empty lanes presented as real.
- resolved data then refetch rejection → stale notice plus the last known cards.
- at narrow width (`useMediaQuery` mocked true) → `select[aria-label="Select lane"]` exists, changing it renders only that lane's cards, and the desktop five-column `.kanban-grid` is absent.
- at narrow width, `New Item` is still visible and the lane list is a labeled region.

```tsx
it('does not turn a failed board query into empty lanes', async () => {
  vi.mocked(listKanbanItems).mockRejectedValueOnce(new Error('network down'));
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={qc}><DrawerProvider><KanbanBoard /></DrawerProvider></QueryClientProvider>);
  expect(await screen.findByTestId('state-notice-error')).toHaveTextContent(/could not load|failed/i);
  expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  expect(screen.queryByText('0 tasks')).not.toBeInTheDocument();
});

it('shows a lane selector and one lane list at narrow widths', async () => {
  vi.mocked(useMediaQuery).mockReturnValue(true);
  vi.mocked(listKanbanItems).mockResolvedValue([
    baseItem({ id: 'k-1', title: 'Review me', status: 'REVIEW' }),
    baseItem({ id: 'k-2', title: 'Todo item', status: 'TODO' }),
  ] as never);
  render(/* same providers */);
  expect(await screen.findByRole('combobox', { name: /select lane/i })).toBeInTheDocument();
  expect(screen.queryByTestId('lane-BACKLOG')).not.toBeInTheDocument();
  await userEvent.selectOptions(screen.getByRole('combobox', { name: /select lane/i }), 'REVIEW');
  expect(screen.getByText('Review me')).toBeInTheDocument();
  expect(screen.queryByText('Todo item')).not.toBeInTheDocument();
});
```

- [ ] **Step 5: Run RED**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/components/__tests__/KanbanBoard.test.tsx --no-file-parallelism
```

Expected: FAIL on the new assertions.

- [ ] **Step 6: Implement**

- Destructure `{ data: items, isPending, isError, error, refetch, isFetching }`.
- Render `StateNotice` above the board for loading/error/stale. When there is no data and the query failed, do not render empty lanes.
- Extract the existing card JSX into `renderCard(item, { mobile })` inside the component so desktop columns and the mobile list share one markup path. This is a refactor; keep all existing drag/drop behavior for desktop.
- `const narrow = useMediaQuery('(max-width: 900px)')`; default selected lane `'TODO'`; if the selected lane has no cards and another lane does, keep TODO selected (do not auto-jump; the operator chooses).
- Mobile markup: `<div className="kanban-mobile" role="region" aria-label="Kanban board">`, `<label>Lane <select aria-label="Select lane">…</select></label>`, then a `<ul className="kanban-mobile-list">` of cards.
- `New Item` button stays above both layouts.
- Add `kanban-flow.css`: `.kanban-grid` local horizontal scroll at narrow widths if used, `.kanban-mobile-list`, `.kanban-lane-select`, skeleton rows, 44px targets. Do not change the global `.kanban` grid rules in `index.css`.
- Existing `div.card` click behavior is handled in Task 6; this task keeps its current onClick until Task 6 replaces it.

- [ ] **Step 7: Run GREEN**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/hooks/__tests__/useMediaQuery.test.ts src/components/__tests__/KanbanBoard.test.tsx --no-file-parallelism
```

Expected: 2 files passed, 0 failed. All existing 47 KanbanBoard tests must still pass or be updated only where the DOM intentionally changed; report any test count change.

- [ ] **Step 8: Handoff**

Report mobile/default lane behavior and any existing test modified. No git.

---

## Task 6: Card Keyboard Opener, Create Errors and Batch Copy (Lane B, Wave 2)

**Lane:** B (after Task 5; same file).
**Depends on:** Task 5.
**Files:**
- Modify: `src/components/KanbanBoard.tsx`
- Modify: `src/components/ReviewPanels.tsx`
- Modify: `src/api/kanban.ts` (extend the existing `KanbanRejection.details` type only)
- Test: `src/components/__tests__/KanbanBoard.test.tsx`
- Test: `src/components/__tests__/ReviewPanels.test.tsx`

- [ ] **Step 1: Add failing card keyboard tests**

- The card container no longer has an `onClick` that opens the drawer.
- A named button `Open task <title>` exists and opens the drawer with `Enter` and `Space`.
- Clicking the opener does not start a drag; the card remains `draggable` for pointer users.
- Nested quick actions still do not open the drawer.
- On mobile, the same named opener exists in the selected lane list.

- [ ] **Step 2: Run RED**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/components/__tests__/KanbanBoard.test.tsx --no-file-parallelism
```

Expected: FAIL on the new opener assertions.

- [ ] **Step 3: Implement the opener**

Replace the card body click with an explicit button:

```tsx
<button
  type="button"
  className="card-open"
  draggable={false}
  aria-label={`Open task ${item.title}`}
  onClick={(e) => {
    e.stopPropagation();
    dispatchOpenTaskDrawer(item.id);
  }}
>
  <div className="id">{item.id.slice(0, 8)}</div>
  <div className="t">{item.title}</div>
</button>
```

Keep `draggable`, `onDragStart`, `onDragEnd`, nested action buttons and the cancel button. Add `.card-open` styles to `kanban-flow.css`: full width, text-align left, transparent background, visible focus ring, no drag ghost.

- [ ] **Step 4: Add failing create-error tests**

Use the real 409 body served by `GlobalExceptionHandler` for an explicit, ineligible assignee (`KanbanService.validateDispatchTarget`): `{ code: 'AGENT_NOT_ELIGIBLE', message: 'Agent qa-bot cannot receive a card: [MISSING_RUNTIME]', details: { agentId: 'agent-1', reasons: ['MISSING_RUNTIME'] } }`. The axios rejection wrapper is `{ response: { status: 409, data: <body> } }`. Assert:
- the served message is visible and no generic `Request failed with status code 409` replaces it;
- every served `details.reasons` value is visible as a reason, and `details.agentId` is not turned into a fabricated agent name;
- the draft title/description stay in the open modal;
- closing and reopening restores the same draft for the session;
- a successful create clears the draft.

- [ ] **Step 5: Run RED and implement**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/components/__tests__/KanbanBoard.test.tsx --no-file-parallelism
```

- Extend `KanbanRejection.details` in `src/api/kanban.ts` with the real optional fields `agentId?: string` and `reasons?: string[]` alongside the existing `evaluated`/`excluded` shape. Type-only change; do not alter `presentError` or `ErrorNotice`.
- Add `type CreateErrorState = { presented: PresentedError; details?: KanbanRejection['details'] } | null` and `const [createError, setCreateError] = useState<CreateErrorState>(null)`.
- In the create mutation `onError`, read `(err as AxiosError<KanbanRejection>).response?.data?.details` and store `{ presented: presentError(err, 'Create failed.'), details }`.
- Render `<ErrorNotice presented={createError.presented} />`, then a local `create-error-details` block: `reasons` as a list and, for the batch shape, `excluded` entries as `name — reasons.join(', ')`. Render only fields the body actually carries; do not invent `askHint` or map enum codes to prose.
- Clear `createError` on success only. Keep the board-level transition `error` separate.

- [ ] **Step 6: Add failing batch-copy tests**

In `ReviewPanels.test.tsx`:
- `Approve all` label includes the batch count: `Approve all (2)` for two non-native asks.
- Native permission asks are excluded and the disclosure says so.
- `Request changes` states it starts 1 fresh run.
- In `KanbanBoard`, the existing batch buttons already include counts; assert `Accept all completed (N)`, `Rework all failed (N)`, `Cancel all (N)` and that the confirm copy includes the count and, for rework, the fresh-run count.

- [ ] **Step 7: Implement the copy changes and run GREEN**

Change `ReviewPanels.tsx` `Approve all` label to `✓ Approve all (${batchApprovable.length})`; add `title`/`aria-label` with the count; change `Request changes` visible copy to `Request changes (starts 1 fresh run)` in both `DecisionPanel` and `ShortApprovalView`. Do not change the underlying mutation semantics.

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/components/__tests__/KanbanBoard.test.tsx src/components/__tests__/ReviewPanels.test.tsx --no-file-parallelism
```

Expected: 2 files passed, 0 failed; all existing board tests updated only for the new opener/copy.

- [ ] **Step 8: Handoff**

Report the exact real 409 fixture shape used (`code`, `message`, `details.agentId`/`details.reasons`), the details rendering, draft behavior and exact batch labels. State explicitly that no `askHint`, no `errorPresentation.ts` change and no backend rule were invented.

---

## Task 7: Cancel-First Dialog and Drawer Focus (Lane C, Wave 1)

**Lane:** C1.
**Depends on:** Task 1.
**Files:**
- Modify: `src/components/ConfirmDialog.tsx`
- Modify: `src/components/TaskDrawer.tsx`
- Modify: `src/components/ReviewWorkspace.tsx`
- Create: `src/styles/feedback-flow.css`
- Test: `src/components/__tests__/ConfirmDialog.test.tsx`
- Test: `src/components/__tests__/TaskDrawer.test.tsx`
- Test: `src/components/__tests__/ReviewWorkspace.test.tsx`

**Ownership note:** Do not modify `DrawerContext.tsx`; focus restoration is implemented in the drawer/workspace components. The existing `DrawerContext.test.tsx` is a regression test only; run it, do not rewrite it.

**Behavior:**
- Danger dialog: Cancel receives initial focus. Activating the focused Cancel with Enter/Space dismisses with the cancel callback exactly once and never mutates. Tab order is Cancel→Confirm→Cancel; Shift+Tab reverses. Escape and Cancel dismiss without mutation.
- Non-danger dialog: Confirm keeps its existing initial focus; the same trap and restore apply.
- The opener is captured before any programmatic focus, so restoring works even when the dialog mounts already open.
- `role="dialog" aria-modal="true"` plus the focus trap keep background controls unreachable while open; the overlay blocks pointer input.
- Task drawer and review workspace: opening focuses the heading; closing restores the opener; Escape inside an editable control does not close.
- Quick deny and all non-destructive card actions are unchanged.

- [ ] **Step 1: Write failing dialog tests**

```tsx
it('focuses Cancel in a danger dialog and only Cancel fires on Enter', async () => {
  const user = userEvent.setup();
  const { onConfirm, onCancel } = setup({ danger: true });

  expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
  await user.keyboard('{Enter}');

  expect(onCancel).toHaveBeenCalledTimes(1);
  expect(onConfirm).not.toHaveBeenCalled();
});

it('keeps Confirm first for a non-danger dialog', () => {
  setup();
  expect(screen.getByRole('button', { name: 'Confirm' })).toHaveFocus();
});

it('traps Tab/Shift+Tab inside a danger dialog and never reaches the background', () => {
  const outside = document.createElement('button');
  outside.textContent = 'Outside';
  document.body.append(outside);
  outside.focus();

  setup({ danger: true });
  const cancel = screen.getByRole('button', { name: 'Cancel' });
  const confirm = screen.getByRole('button', { name: 'Confirm' });
  expect(screen.getByRole('dialog')).toHaveAttribute('aria-modal', 'true');
  expect(cancel).toHaveFocus();

  fireEvent.keyDown(cancel, { key: 'Tab' });
  expect(confirm).toHaveFocus();
  fireEvent.keyDown(confirm, { key: 'Tab' });
  expect(cancel).toHaveFocus();
  fireEvent.keyDown(cancel, { key: 'Tab', shiftKey: true });
  expect(confirm).toHaveFocus();
  fireEvent.keyDown(confirm, { key: 'Tab', shiftKey: true });
  expect(cancel).toHaveFocus();
  expect(outside).not.toHaveFocus();
});

it('restores the invoker after close even though the dialog mounted open', () => {
  const invoker = document.createElement('button');
  invoker.textContent = 'Open';
  document.body.append(invoker);
  invoker.focus();

  const { unmount } = setup({ danger: true });
  expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();

  unmount();
  expect(invoker).toHaveFocus();
});
```

- [ ] **Step 2: Run RED**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/components/__tests__/ConfirmDialog.test.tsx --no-file-parallelism
```

Expected: FAIL on Cancel initial focus, Enter-activates-Cancel, trap and restore. The existing non-danger autofocus test (`autofocuses the confirm control so keyboard users land on the safe default`, `setup()` without `danger`) must stay green; it is not the assertion being changed.

- [ ] **Step 3: Implement the dialog contract**

Remove `autoFocus` from both buttons and focus Cancel (danger) or Confirm (non-danger) programmatically.
- Capture the opener in the same `useLayoutEffect` **before** calling `.focus()`: `returnFocusRef.current = document.activeElement as HTMLElement | null`. Return a cleanup that restores it when connected; this covers both close and unmount.
- Reorder the danger actions so DOM order matches reading order: Cancel, then Confirm. Non-danger keeps Confirm, then Cancel.
- Add `ref`s for both buttons and a dialog-level `onKeyDown` Tab trap: focus the other button and `preventDefault()`. Do not rely on `inert` on ancestors (the dialog renders inside the app root); the trap plus `aria-modal` is the contract.
- Keep `aria-describedby` for the message, the Escape listener and backdrop click behavior. Escape must not double-fire `onCancel` when Cancel also activates; guard with a single pending-dismiss flag if needed.
- Non-danger dialogs keep Confirm first and still trap/restore.
- Do not add a second confirmation gate to Quick deny.

- [ ] **Step 4: Add failing drawer focus tests**

In `TaskDrawer.test.tsx` and `ReviewWorkspace.test.tsx`: opening focuses the heading (`tabIndex={-1}`), closing restores the trigger, and Escape inside a textarea does not close. For a removed trigger, focus falls to the drawer close button or a provided fallback, never `document.body`.

- [ ] **Step 5: Implement drawer restoration**

Capture `document.activeElement` when `open` becomes true; focus the heading with `requestAnimationFrame`. On close/unmount, restore if connected; otherwise focus the close button or the caller's fallback. Do not change the existing Escape guard. Add the shared focus styles to `feedback-flow.css` rather than editing `index.css`.

- [ ] **Step 6: Run GREEN**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/components/__tests__/ConfirmDialog.test.tsx src/components/__tests__/DrawerContext.test.tsx src/components/__tests__/TaskDrawer.test.tsx src/components/__tests__/ReviewWorkspace.test.tsx --no-file-parallelism
```

Expected: 4 files passed, 0 failed. Report the ConfirmDialog test count: four tests are added, the existing non-danger autofocus test is retained unchanged, and no assertion is deleted for green. `DrawerContext.test.tsx` is a regression run only.

- [ ] **Step 7: Handoff**

Report initial focus for danger vs non-danger, trap behavior, restore fallback, and the exact test counts.

---

## Task 8: Toast Lifecycle and Navigation Semantics (Lane C, Wave 2)

**Lane:** C2 (after Task 7; different files, may run in parallel with Task 7 if C1/C2 are separate).
**Depends on:** Task 1; can start with Task 7.
**Files:**
- Modify: `src/components/Toast.tsx`
- Modify: `src/components/RailNav.tsx`
- Create: `src/styles/toast-flow.css`
- Test: `src/components/__tests__/Toast.test.tsx`
- Test: `src/components/__tests__/RailNav.test.tsx` (new)

**Ownership note:** `feedback-flow.css` belongs to Task 7. Task 8 writes only `toast-flow.css`.

- [ ] **Step 1: Write failing Toast tests**

- The toast surface is a polite live region (`role="status"`, `aria-live="polite"`).
- Five toasts cap; a sixth evicts the oldest not focused.
- Hovering the stack pauses expiry; after 6s the toast remains; leaving resumes and it dismisses after the remaining time.
- A focused View action is never evicted.
- Unmapped notification types render no View action; mapped ones render View.
- A failed `run.completed` payload renders the truthful failure label, not a success label.

```tsx
it('is a polite live region and pauses expiry while hovered', () => {
  renderToast();
  emit({ type: 'run.completed', payload: { status: 'COMPLETED' }, timestamp: 't1' });
  const stack = screen.getByRole('status');
  expect(stack).toHaveAttribute('aria-live', 'polite');

  fireEvent.mouseEnter(stack);
  act(() => { vi.advanceTimersByTime(6000); });
  expect(screen.getByText(/run completed/i)).toBeInTheDocument();

  fireEvent.mouseLeave(stack);
  act(() => { vi.advanceTimersByTime(5000); });
  expect(screen.queryByText(/run completed/i)).not.toBeInTheDocument();
});

it('keeps a focused View action when a sixth toast arrives', () => {
  renderToast();
  for (let i = 0; i < 5; i += 1) {
    emit({ type: 'aria.notification', payload: { id: `n-${i}`, title: `Notice ${i}`, type: 'run.completed' } });
  }
  const viewButtons = screen.getAllByRole('button', { name: 'View' });
  viewButtons[4].focus();
  emit({ type: 'aria.notification', payload: { id: 'n-5', title: 'Notice 5', type: 'run.completed' } });
  expect(screen.getAllByRole('button', { name: 'View' })).toHaveLength(5);
  expect(screen.queryByText('Notice 0')).not.toBeInTheDocument();
  expect(viewButtons[4]).toHaveFocus();
});

it('offers no View action for an unmapped notification type', () => {
  const paths: string[] = [];
  render(
    inRouter(
      <WsStubProvider>
        <Toast />
        <LocationTracker paths={paths} />
      </WsStubProvider>,
    ),
  );
  emit({ type: 'aria.notification', payload: { id: 'n-2', title: 'Daily brief', type: 'brief' } });
  expect(screen.getByText('Daily brief')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'View' })).not.toBeInTheDocument();
  expect(paths).toEqual(['/']);
});
```

Intentionally rewrite the two existing tests in `Toast.test.tsx` that expect a no-op View: `does not navigate when the notification type has no mapped route` and `does not navigate when only the coarse resourceType is present`. Both must now assert that no View button renders and the path is unchanged. Record the old/new assertion pairs in the handoff; do not delete the routing-key coverage.

Use the existing `renderToast()` + `emit()` harness. Do not add `renderToastWithSubscribe`. The `beforeEach` already installs fake timers; use `fireEvent`/`act` rather than `userEvent` to avoid timer hangs.

```tsx
// RailNav.test.tsx
import { MemoryRouter, useLocation } from 'react-router-dom';

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{location.pathname}</output>;
}

it('renders route links with aria-current and keeps Configure as a button', async () => {
  render(<MemoryRouter initialEntries={['/']}><RailNav /><LocationProbe /></MemoryRouter>);
  expect(screen.getByRole('link', { name: /overview/i })).toHaveAttribute('aria-current', 'page');
  expect(screen.getByRole('button', { name: /open configuration/i })).toBeInTheDocument();
  await userEvent.click(screen.getByRole('link', { name: /chat/i }));
  expect(screen.getByTestId('location')).toHaveTextContent('/chat');
});
```

- [ ] **Step 2: Run RED**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/components/__tests__/Toast.test.tsx --no-file-parallelism
```

Expected: FAIL on live-region, pause and cap assertions.

- [ ] **Step 3: Implement Toast lifecycle**

Contract:
- Keep event filtering and `subscribe` behavior.
- In the `aria.notification` branch, attach `View` only when `routeForNotificationType(notifType)` resolves; never attach an action whose onClick can be a no-op. For ordinary events, keep the existing route-conditional behavior.
- Store `expiresAt`/remaining milliseconds per toast. Stack `onMouseEnter` pauses every toast and `onMouseLeave` resumes with each remaining duration; `onFocusCapture`/`onBlurCapture` pause/resume the toast containing the focused control.
- At cap, remove the oldest toast that does not contain `document.activeElement`; if none can be removed, append and keep the focused one. The sixth toast still renders; only a non-focused toast is evicted.
- Add `role="status" aria-live="polite"` to the container; do not move focus. Keep the container keyed to actual toast items so an empty stack renders nothing.
- Timers: one `setTimeout` per active toast; clear it on pause/eviction/unmount. Do not clear all timers when a new event arrives.
- Do not add a dismiss button in this plan (design target).
- Do not claim every toast is persisted; the bell is a separate surface.

- [ ] **Step 4: Write failing RailNav tests**

Render `RailNav` inside `MemoryRouter`; assert route items are links (`role="link"`), the active item has `aria-current="page"`, Configure remains a button, and clicking a link changes the location (or uses the router). Existing visual classes remain.

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/components/__tests__/RailNav.test.tsx --no-file-parallelism
```

Expected: FAIL because the file does not exist (or on `role="link"`/`aria-current` once scaffolded).

- [ ] **Step 5: Implement RailNav**

Replace `button` + `navigate` with `<Link to={item.path} className=... aria-current={isActive(item.path) ? 'page' : undefined}>`. Keep `Configure` as the action button. Remove the now-unused `useNavigate` import. Add only toast-specific CSS in `toast-flow.css`.

- [ ] **Step 6: Run GREEN**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/components/__tests__/Toast.test.tsx src/components/__tests__/RailNav.test.tsx --no-file-parallelism
```

Expected: 2 files passed, 0 failed. Existing Toast tests that assert the five-second dismissal remain valid unless they conflict with pause semantics. Exactly two existing tests are intentionally rewritten (the two no-op View tests); name them and report old/new assertion counts. Do not delete them.

- [ ] **Step 7: Handoff**

Report timer semantics, cap eviction rule, live-region behavior, the two rewritten no-op-View tests (old/new assertions) and the final counts.

---

## Task 9: Run Conversation Copy and Mobile Chat Modes (Lane D, Wave 1)

**Lane:** D1.
**Depends on:** Task 1.
**Files:**
- Modify: `src/pages/ChatPage.tsx`
- Create: `src/styles/chat-flow.css`
- Test: `src/pages/__tests__/ChatPage.mobile.test.tsx` (new)
- Modify: `src/pages/__tests__/ChatPage.live.test.tsx` if its copy assertions change.

**Changes:**
- Heading `Run conversations`; subtitle `Operator and agent transcript for each run`; participants joined with ` · ` or ` & `, not `↔`.
- No claim of agent-to-agent messaging. Keep the real transcript, tool events, persisted injection and `ReviewQueue` run context.
- Mobile mode: add `const [mobilePane, setMobilePane] = useState<'list'|'thread'>('list')`; selecting a thread sets `'thread'`; a `Back to conversations` button sets `'list'` and preserves the selected thread and draft. Desktop shows both panes; CSS hides one at `max-width: 900px` using `data-mobile-pane`.
- `chat-shell` gets `data-testid="chat-shell"` and `data-mobile-pane={mobilePane}`; no inline grid.
- Draft is not cleared by Back or thread selection in this plan (per-thread drafts remain a follow-up).
- Empty/loading/error transcript states remain; error includes Retry.

- [ ] **Step 1: Write failing mobile test**

Mock the runs API with two runs; mock trajectory. Render inside `MemoryRouter` and `QueryClientProvider`; set `window.matchMedia`/CSS not needed because the component uses state. Assert:
- initial list is visible and transcript has `data-mobile-pane="list"`;
- selecting a row sets `data-mobile-pane="thread"`;
- `Back to conversations` returns to list with the same row selected;
- typing a draft, pressing Back, reopening the thread keeps the draft (global draft behavior);
- heading is `Run conversations`; participants do not contain `↔`.

```tsx
it('opens thread mode and keeps the draft when going Back', async () => {
  renderChat();
  expect(screen.getByText('Run conversations')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: /run 1111/i }));
  expect(screen.getByTestId('chat-shell')).toHaveAttribute('data-mobile-pane', 'thread');
  await userEvent.type(screen.getByRole('textbox', { name: /inject message/i }), 'keep this draft');
  await userEvent.click(screen.getByRole('button', { name: /back to conversations/i }));
  expect(screen.getByTestId('chat-shell')).toHaveAttribute('data-mobile-pane', 'list');
  expect(screen.getByDisplayValue('keep this draft')).toBeInTheDocument();
});
```

- [ ] **Step 2: Run RED**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/pages/__tests__/ChatPage.mobile.test.tsx --no-file-parallelism
```

Expected: FAIL on the new copy/state assertions.

- [ ] **Step 3: Implement and add CSS**

Implementation contract:
- In `handleSelectThread`, set `activeThreadId` and `setMobilePane('thread')`.
- Add `<button className="chat-back" onClick={() => setMobilePane('list')}>Back to conversations</button>` in the thread header; visible only at <=900px in CSS.
- CSS: base `.chat-shell` keeps `320px 1fr` only at desktop. At `max-width: 900px`, set `grid-template-columns: 1fr` (the existing fixed 320px track is why hiding one pane left the transcript at 320px) and give both panels `grid-column: 1 / -1`. Then `.chat-shell[data-mobile-pane="list"] .chat-thread-panel { display:none }`, `.chat-shell[data-mobile-pane="thread"] .chat-list-panel { display:none }`, `.chat-back { display:inline-flex }`. Desktop hides `.chat-back`.
- No page-level horizontal overflow at 375/390. jsdom does not apply media queries, so the unit test covers state and the width/no-overflow assertion lives in the Task 13 E2E spec.

- [ ] **Step 4: Run GREEN**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/pages/__tests__/ChatPage.mobile.test.tsx src/pages/__tests__/ChatPage.live.test.tsx --no-file-parallelism
```

Expected: 2 files passed, 0 failed. If `ChatPage.live.test.tsx` requires a live backend, report it as NOT RUN rather than weakening it.

- [ ] **Step 5: Handoff**

Report copy changes, mobile state transitions, whether the live test was runnable, and that transcript width/no-overflow is deferred to the Task 13 E2E check (jsdom cannot verify it).

---

## Task 10: Ops Single-Column Stack (Lane D, Wave 2)

**Lane:** D2.
**Depends on:** Task 1.
**Files:**
- Modify: `src/pages/OpsPage.tsx`
- Test: `src/pages/__tests__/OpsPage.test.tsx`

**Changes:**
- Remove the inline `style={{ gridTemplateColumns: 'minmax(0, 1.55fr) minmax(0, 1fr)' }}` from the `ops-grid-2` element. Let the existing `@media (max-width: 1100px) { .ops-grid-2 { grid-template-columns: 1fr; } }` rule apply.
- Keep all sections and actions. No new anchor/retry controls, no new CSS file; the existing classes already cover the stacked layout.

- [ ] **Step 1: Write failing test**

```tsx
it('does not pin the Ops columns inline', () => {
  const { container } = ui();
  const grid = container.querySelector('.ops-grid-2') as HTMLElement;
  expect(grid).not.toBeNull();
  expect(grid.style.gridTemplateColumns).toBe('');
});
```

- [ ] **Step 2: Run RED and implement**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/pages/__tests__/OpsPage.test.tsx --no-file-parallelism
```

Expected RED before removal; GREEN after. No CSS file is created by this task.

- [ ] **Step 3: Handoff**

Report the exact removed attribute and the test count.

---

## Task 11: Truthful Knowledge Ownership (Lane D, Wave 2)

**Lane:** D2 (parallel with Task 10; different files).
**Depends on:** Task 1.
**Files:**
- Modify: `src/pages/KnowledgePage.tsx`
- Test: `src/pages/__tests__/KnowledgePage.test.tsx`

**Changes:**
- `ownerOf` returns `null` when `agentId` is missing or unmatched; delete the hash fallback entirely.
- Add a stable Unattributed bucket key `'__unattributed__'` in `itemsByAgent`, seeded even when the agent list is empty; include every item with no owner. The bucket is not an agent and must never be rendered with an avatar/name of a real agent.
- Render an `Unattributed` space button whenever the bucket has at least one item. It shows the same Total/In Review/Live metrics and toggles `activeAgentId = '__unattributed__'`. The section heading reports real agents only, with a separate `· N unattributed` suffix when non-zero.
- `visiblePending` includes Unattributed items when the Unattributed filter is selected and excludes owned items.
- Replace every `'Shared'` fallback in owner display with `'Unattributed'`; missing ownership is not a permission claim.
- Per-agent spaces derive only from real ownership. Adding/reordering agents cannot reassign displayed ownership.
- Keep the existing agent filter (`activeAgentId`) and add an explicit `Unattributed` option. Use the existing `pill dim` styling; no new CSS file.
- Keep version formatting and the inline detail behavior. A `Close detail` control is a design target; do not add it here.

- [ ] **Step 1: Write failing tests**

- An item with a missing/unmatched `agentId` renders `Unattributed` in the pending list, approved list and detail.
- With two agents, adding a third unrelated agent does not change the owner shown for the item: it stays Unattributed, and no item migrates into the new agent's space or counts.
- The Unattributed space button renders with its metrics; clicking it filters the review queue to unowned items and clicking it again clears the filter.
- The `Total` values across every rendered space (agents plus Unattributed) sum exactly to the number of loaded items — no item is silently dropped.
- No item is counted under a hash-derived agent; no `'Shared'` owner label remains in the owner slots.

```tsx
// agentA/agentB use the existing Agent fixture shape in this file.
it('shows Unattributed for a missing owner and never invents one', async () => {
  vi.mocked(listKnowledge).mockResolvedValue([
    { id: 'item-1', name: 'Legacy import', type: 'DOC', status: 'APPROVED', agentId: 'missing-agent', currentVersion: '1', createdAt: '2026-10-01T00:00:00Z', description: '', sensitivity: 'INTERNAL' },
  ] as never);
  vi.mocked(listAgents).mockResolvedValue([agentA, agentB] as never);
  ui();
  expect(await screen.findByRole('button', { name: /unattributed/i })).toBeInTheDocument();
  expect((await screen.findAllByText('Unattributed')).length).toBeGreaterThan(0);
  expect(screen.queryByText('Shared')).not.toBeInTheDocument();
});
```

- [ ] **Step 2: Run RED**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/pages/__tests__/KnowledgePage.test.tsx --no-file-parallelism
```

Expected: FAIL on Unattributed assertions.

- [ ] **Step 3: Implement**

Delete the hash loop. Define `const UNATTRIBUTED = '__unattributed__'` and a single `ownerKeyOf(item, agents): string` used by the bucket map and both filters. Seed `itemsByAgent` with every real agent id **and** `UNATTRIBUTED`, then push each item by `ownerKeyOf`. Render the Unattributed button from the same map (conditional on `owned.length > 0`) inside `.knowledge-spaces`, and pass `activeAgentId` through unchanged. Update counts and filters; no item may be skipped when `ownerOf` is null. Keep the existing styling and layout; use the existing `pill dim` class for the Unattributed label and do not add a new CSS file.

- [ ] **Step 4: Run GREEN**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/pages/__tests__/KnowledgePage.test.tsx --no-file-parallelism
```

Expected: 1 file passed, 0 failed; report the test count.

---

## Task 12: Configure Honesty (Lane D, Wave 3)

**Lane:** D1 (after Tasks 9-11; no shared files with them).
**Depends on:** Task 1.
**Files:**
- Modify: `src/components/ConfigureModal.tsx`
- Create: `src/styles/configure-flow.css`
- Test: `src/components/__tests__/ConfigureModal.test.tsx`

**Changes:**
- Skills tab: render the real skill catalog only. Remove the fabricated permission model entirely — the `Permission` type, `SkillRow.permission`, `PERMISSION_OPTIONS`, `updateSkill`, the permission column, the per-row select and every `RO`/`USE`/`RW` pill. The `Skill` API type has no permission field, so showing `USE` invents authority. Show name, category and description plus the note `Read-only catalog. No skill permission is editable or enforced in this build.`
- Gates tab: keep the tab present and render it as a non-interactive reference. Keep the persistent note `Reference only — not enforced by the backend. This panel does not change runtime behavior.` Set every gate switch to `aria-disabled="true"`, remove its click/keydown handlers and set `tabIndex={-1}`; add `disabled` to every select and number input; do not call `onToggle`/`onChange`. Do not hide the tab — hiding real definitions is a larger information loss than disabling them.
- Preserve the real Runtime, LLM provider, credential and System Config tabs and their mutations exactly. Do not remove System Config. Do not add per-agent credential editors.
- Footer must not claim `X skills configured` or `X of Y gates require human approval`. Use `Reference only — gates are not enforced` on Gates and `${skills.length} skills in the read-only catalog` on Skills. Hide `Reset to defaults` on Gates and Skills (it would be a dead control); keep it on Runtime.
- Done closes without a saved-policy claim.

- [ ] **Step 1: Write failing tests**

- Skills: no permission select or static permission value exists; the read-only note is visible; skill name/category/description still render.
- Gates: the tab is present; every switch is `aria-disabled="true"`, selects/inputs are disabled, and clicking/Enter does not change `aria-checked`; the non-enforcement note is visible.
- Runtime Apply, LLM/Settings and System Config remain rendered; `SystemConfigPanel` still has save/reset controls; the real settings mutations still call their APIs when explicitly triggered.
- Footer never says permissions were saved or gates are configured; `Reset to defaults` is absent on Gates/Skills and present on Runtime.
- Done closes the modal.

```tsx
it('renders skills as a read-only catalog and keeps System Config', async () => {
  render(<ConfigureModal initialTab="skills" />);
  act(() => {
    window.dispatchEvent(new CustomEvent('act:open-configure', { detail: { tab: 'skills' } }));
  });
  await userEvent.click(screen.getByRole('tab', { name: /skills/i }));
  expect(screen.getByText(/read-only catalog/i)).toBeInTheDocument();
  expect(screen.queryByRole('combobox', { name: /permission/i })).not.toBeInTheDocument();
  expect(screen.queryByText(/^USE$/)).not.toBeInTheDocument();
  expect(screen.getByRole('tab', { name: /system config/i })).toBeInTheDocument();
});

it('keeps Gates present but disabled with an honest reference note', async () => {
  render(<ConfigureModal initialTab="gates" />);
  act(() => {
    window.dispatchEvent(new CustomEvent('act:open-configure', { detail: { tab: 'gates' } }));
  });
  expect(screen.getByText(/reference only/i)).toBeInTheDocument();
  const switches = screen.getAllByRole('switch');
  expect(switches.length).toBeGreaterThan(0);
  switches.forEach((s) => expect(s).toHaveAttribute('aria-disabled', 'true'));
  const before = switches.map((s) => s.getAttribute('aria-checked'));
  fireEvent.click(switches[0]);
  expect(switches.map((s) => s.getAttribute('aria-checked'))).toEqual(before);
  screen.getAllByRole('combobox').forEach((c) => expect(c).toBeDisabled());
});
```

- [ ] **Step 2: Run RED**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/components/__tests__/ConfigureModal.test.tsx --no-file-parallelism
```

Expected: FAIL on the read-only assertions.

- [ ] **Step 3: Implement**

Remove the permission model and unused handlers/imports (`PERMISSION_OPTIONS`, `updateSkill`, `toggleGate`, `updateGate`, `resetDefaults` only if no longer referenced) to satisfy TypeScript `noUnusedLocals`. Keep the gates data for the reference display and keep `GatesPane` mounted; render switches as inert reference rows and selects/inputs with `disabled`. Use a plain `.configure-reference-note` block for both notes. Add `.configure-reference-note` to `configure-flow.css`, not `index.css`. Do not change API modules or backend behavior. Do not hide the Gates tab and do not keep a disabled permission control in Skills.

- [ ] **Step 4: Run GREEN**

```bash
pnpm --dir agent-control-tower/act-dashboard exec vitest run src/components/__tests__/ConfigureModal.test.tsx --no-file-parallelism
```

Expected: 1 file passed, 0 failed; report the test count and confirm Gates is present-and-disabled (not hidden) with all reference controls inert.

---

## Task 13: Focused Playwright Coverage (Lane E, Wave 3)

**Lane:** Release QA (no product edits).
**Depends on:** Wave 2 complete.
**Files (new only):**
- `e2e/decision-first-overview.spec.ts`
- `e2e/kanban-keyboard.spec.ts`
- `e2e/mobile-flows.spec.ts`
- `e2e/config-knowledge-honesty.spec.ts`

**Rules:** Use `page.route` fixtures for `/api/v1/**`; no real backend mutations, no LLM calls, no credentials. Use 1440x900 for desktop and 390x844 for mobile. Never assert on timing sleeps; use locators.

- [ ] **Step 1: Write `decision-first-overview.spec.ts`**

Mock approvals (2 pending, one permission), kanban (one REVIEW card with 2 asks, one TODO card with `lastError`), dashboard summary, runs, agents. Assert:
- decision rows appear above the board; `Waiting on you` count equals approval rows, not `pendingAskCount`;
- an unlinked approval (no matching card) renders `Run <short id>` and direct `Approve`/`Deny` (or inline `Answer` for a clarification) instead of a dead control;
- a failed approvals route shows an error and no `No decisions waiting`;
- a stale refetch keeps rows and shows the refresh-failed notice;
- Review opens the in-flow workspace; Answer opens the task drawer;
- the permission row offers `Review permission`, not `Approve all`;
- the board only promotes after both zero sources succeed and neither is partial (use fixtures with zero/zero).

- [ ] **Step 2: Write `kanban-keyboard.spec.ts`**

Mock kanban. At 1440:
- Tab reaches `Open task <title>`; Enter opens the drawer; Escape closes and focus returns to the opener;
- a danger Cancel dialog opens with Cancel focused; Enter activates Cancel and dismisses with no mutation; Tab and Shift+Tab stay inside; Escape restores the trigger;
- Quick deny remains a single button.

- [ ] **Step 3: Write `mobile-flows.spec.ts`**

At 390x844:
- Overview has no document horizontal overflow; decision rows are above the board; `New Item` is reachable;
- the board lane selector changes the visible list; every card has a named opener;
- Chat shows the thread list first; opening a thread shows a full-width transcript (assert its rendered width is greater than 300px at 390px viewport) with `Back to conversations`; Back preserves the selected row; `document.documentElement.scrollWidth <= window.innerWidth`;
- Ops sections are single column; tables scroll locally if present.

- [ ] **Step 4: Write `config-knowledge-honesty.spec.ts`**

Mock config, skills, gates, knowledge, agents. Assert:
- Skills show no permission value or control, and the read-only note is visible;
- Gates is present with every switch `aria-disabled`, every select/input disabled, and the non-enforcement note visible;
- System Config, Runtime and LLM tabs remain;
- a knowledge item with no owner shows `Unattributed` in the list and detail; adding a new agent fixture does not change it;
- no `Shared` owner label exists.

- [ ] **Step 5: Run the focused specs**

Start the app the same way CI does; for a local UI-only run use the existing Vite server on 5174 and fixtures. Exact command:

```bash
BASE_URL=http://localhost:5174 pnpm --dir agent-control-tower/act-dashboard exec playwright test e2e/decision-first-overview.spec.ts e2e/kanban-keyboard.spec.ts e2e/mobile-flows.spec.ts e2e/config-knowledge-honesty.spec.ts --project=chromium --workers=1
```

Expected: all new specs pass; if the server/backend is unavailable, report NOT RUN with the exact reason; do not mark pass.

- [ ] **Step 6: Handoff**

Report the exact command, passed/failed counts, viewport, fixture intercept counts and any NOT VERIFIED item. No product edits.

---

## Task 14: Integration Gate and Independent Review (Lead + Release QA, Wave 4)

**Lane:** Lead/QA only.

- [ ] **Step 1: Verify no cross-lane conflicts**

```bash
git status --porcelain=v1
git diff --name-only
```

Every changed file must belong to the owning lane. Any duplicate ownership is a stop-the-line defect.

- [ ] **Step 2: Run the full frontend gate serially**

```bash
pnpm --dir agent-control-tower/act-dashboard test -- --coverage
pnpm --dir agent-control-tower/act-dashboard build
```

Expected: all tests pass, coverage thresholds unchanged, build exit 0. If coverage is below threshold, add tests, do not lower thresholds.

- [ ] **Step 3: Run the focused E2E specs again at final HEAD**

Use the CI-equivalent stack or the local Vite fixture server; record pass counts. Do not run build and verify concurrently.

- [ ] **Step 4: Independent Reviewer**

Dispatch a read-only reviewer over the full diff with:
- findings/flow spec traceability;
- one-writer-per-file verification;
- state honesty, focus contract, permission separation;
- test quality (no skipped/weakened assertions);
- lane-by-lane severity-tagged findings.
Fix only confirmed findings in the owning lane. Re-run the affected lane tests and then the full gate.

- [ ] **Step 5: Update the ledger**

Record: task commits (SHAs), exact commands/counts, review verdicts, residual risks, and any deferred design targets. Mark the design/implementation status accurately (implemented locally vs released).

---

## Task 15: Release Gate (Lead only; requires explicit user approval)

- [ ] **Step 1: Clean-room verification**

Create a temporary worktree from the implementation commit, run `pnpm install --frozen-lockfile`, the full test suite, and the build. Do not clean the user's working tree. Record the exact commands and counts.

- [ ] **Step 2: Push/PR only with explicit approval**

```bash
git push -u origin feat/uiux-decision-first
gh pr create --title "fix(uiux): decision-first dashboard repairs" --body-file docs/reviews/2026-10-27-uiux-pr-body.md
```

Create `docs/reviews/2026-10-27-uiux-pr-body.md` first with: summary, the 14 findings mapped to fixes, exact test commands/counts, screenshots/artifacts, deferred design targets and residual risks. Use the body file, not inline JSON/quotes. Do not push to main directly.

- [ ] **Step 3: Hosted CI green at HEAD**

Wait for and verify every required job: changes filter, frontend build + coverage, Java lanes (unaffected but must stay green), Playwright shards 1-4, packaging and docker-build. Read annotations before logs. Batch fixes into one push. No release claim while a run is pending or red.

- [ ] **Step 4: Artifact-first check**

At minimum: Overview decision/attention sections at 1440x900, 1024x768, 390x844; board keyboard opener; destructive dialog focus; chat mobile; Ops mobile; Configure honesty. Capture screenshots or run the E2E specs; record whether each is verified or NOT VERIFIED.

- [ ] **Step 5: Release DoD**

Working tree clean; full gate green at HEAD; PR CI green at HEAD; artifact checks recorded; ledger updated with commit SHA, run ID, counts and residual risks. If interrupted, report `implemented, not released` with the exact remaining commands.

---

## Handoff Format (every implementer)

Return only:

```
Task: <id and name>
Status: DONE / BLOCKED
Files changed: <exact list>
RED evidence: <command -> failure reason>
GREEN evidence: <command -> counts, exit 0>
Behavior delivered: <3-5 bullets tied to screen IDs>
Known limits / NOT VERIFIED: <bullets>
Git: none (Lead commits)
```

Reject a handoff that says only "tests pass" or omits counts. The Lead reviews the actual diff before committing.

## Reviewer Checklist (wave boundary)

- [ ] Every changed file belongs to one lane; no cross-lane edits.
- [ ] Every new data surface covers loading, empty, error, stale and partial states.
- [ ] No all-clear/health/ownership/token/success claim without authoritative data; no `?? 0` KPI fallback and no count when a source failed.
- [ ] Every pending approval is actionable after ReviewQueue leaves Overview (task-backed, taskless decide, inline clarification answer).
- [ ] Destructive dialogs are Cancel-first with trap and restore; Enter on Cancel cancels once without mutation; non-destructive dialogs unchanged; Quick deny unchanged.
- [ ] Native permission asks remain one-use and excluded from batches.
- [ ] No invented permission field, no-op View action, fake retry or fabricated ownership.
- [ ] Served errors precede generic fallback; served `details` render only when present; drafts survive rejection.
- [ ] No new dependency, route removal, backend edit or inline page-grid override.
- [ ] Tests assert behavior, not implementation details; no skips or weakened assertions; intentionally rewritten tests are named with counts.
- [ ] The focused and full gates were run at the final HEAD; results include counts.

## Plan Review Corrections (2026-10-27)

An independent read-only Plan Reviewer returned BLOCKED with 10 findings; all are resolved in this revision:

| ID | Severity | Finding | Resolution |
|---|---|---|---|
| F1 | P1 | Task 7 tests contradicted the approved Cancel-first policy (Enter on Cancel expected not to fire; Tab order wrong; no opener capture) | Task 7 replaces the tests: Enter activates Cancel once with no mutation, Tab Cancel→Confirm→Cancel, Shift+Tab reverses, opener captured in a layout effect before programmatic focus, trap asserted against an outside control |
| F2 | P1 | All-clear ignored `partial`/freshness; `count` fabricated a number when a source failed | Task 2/3 `count` is `null` unless success and non-partial; Task 4 requires `!partial` on both queues plus `allAuthoritative` plus zero/zero; partial-zero test added |
| F3 | P1 | Task 6 invented `askHint`/flat `details.reasons` and left the real `KanbanRejection` shape untyped | Task 6 uses the real 409 body `AGENT_NOT_ELIGIBLE` + `details.agentId`/`details.reasons`, extends the type in `src/api/kanban.ts`, renders served details locally, and removes the `askHint` assertion |
| F4 | P1 | Task 9 hid a chat pane but left the 320px grid track, so the transcript stayed 320px | Task 9 sets `grid-template-columns: 1fr` and `grid-column: 1 / -1` at ≤900px; Task 13 asserts transcript width and no document overflow |
| F5 | P1 | Task 12 displayed a fabricated `USE` permission and left a hidden-Gates alternative | Task 12 removes the permission model entirely and keeps Gates present with inert reference controls; no "absent" alternative remains |
| F6 | P2 | Task 11's Unattributed bucket was never rendered; a pending-row label alone satisfied the test | Task 11 renders the Unattributed space button, filters with it, and asserts the sum of all space totals equals loaded items plus an unrelated-agent membership case |
| F7 | P2 | Task 2/4 ignored `Approval.kanbanItemId`; removing ReviewQueue stranded unlinked approvals | Task 2 joins by `kanbanItemId` first, then `linkedRunId`; taskless approvals get direct Approve/Deny or an inline clarification answer; Task 4 wires the mutations and keeps ReviewQueue for Chat |
| F8 | P2 | Task 4 only relabeled tokens; TopBar KPI zeros stayed fabricated | Task 4 derives `queryPhase` for the summary query, renders `—`/last-known/real values per phase, adds test ids and loading/error/stale/zero tests for every KPI |
| F9 | P2 | Task 8 used a nonexistent `renderToastWithSubscribe` and left a conflicting no-op-View test | Task 8 uses the existing `renderToast()` + `emit()` harness and intentionally rewrites the two no-op-View tests with old/new assertions recorded |
| F10 | P2 | Task 3/4 invalidated the wrong runs key, hid ask-less REVIEW failures, and suppressed mixed provider fleets | Task 4 invalidates `['runs-all']` + `['dashboard-summary']`; Task 3 skips only real pending asks and emits one row per unhealthy provider with mixed-fleet coverage |

## Self-Review Notes (plan author)

- Spec coverage: all 14 findings map to Tasks 2-12; Task 13 verifies the user-facing flows; Task 14/15 enforce the release gate.
- Plan state after the review corrections: 16 tasks, 5 lanes, 10 reviewer findings resolved; no product code, branch, commit, push, PR or CI has run.
- Deferred on purpose: Finalize classification, run deep links, transcript-to-task, toast dismiss, dialog failure retry, Ops anchors, non-drag keyboard movement, route consolidation, backend permissions. These are recorded as design targets/proposals, not silently dropped.
- Type consistency: `QueryPhase`, `StateNoticeProps`, `DecisionRow`, `AttentionRow`, `DecisionQueue` props, `mergeStableOrder` and `rememberFocus` are defined once and consumed by later tasks with the same names; `KanbanRejection.details` is the single type extended by Task 6.
- No real credentials in fixtures; no `.env` reads; no dependency installs.
