import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ExecutiveSummary from '../ExecutiveSummary';
import { TASK_DRAWER_EVENT } from '../DrawerContext';
import type { KanbanItem } from '../../types';

vi.mock('../../api/dashboard', () => ({ getSummary: vi.fn() }));
vi.mock('../../api/kanban', () => ({ listKanbanItems: vi.fn() }));
vi.mock('../../api/runs', () => ({ listRuns: vi.fn() }));
vi.mock('../../api/knowledge', () => ({ listKnowledge: vi.fn() }));

import { getSummary } from '../../api/dashboard';
import { listKanbanItems } from '../../api/kanban';
import { listRuns } from '../../api/runs';
import { listKnowledge } from '../../api/knowledge';

const mockedGetSummary = vi.mocked(getSummary);
const mockedListKanbanItems = vi.mocked(listKanbanItems);
const mockedListRuns = vi.mocked(listRuns);
const mockedListKnowledge = vi.mocked(listKnowledge);

function mkItem(over: Partial<KanbanItem> = {}): KanbanItem {
  return {
    id: 'k-1',
    title: 'card',
    description: null,
    status: 'TODO',
    priority: 'MEDIUM',
    assignee: null,
    labels: null,
    linkedRunId: null,
    linkedAgentId: null,
    createdAt: '2026-09-08T00:00:00Z',
    updatedAt: '2026-09-08T00:00:00Z',
    ...over,
  };
}

function renderSummary() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <ExecutiveSummary />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedGetSummary.mockResolvedValue({
    activeAgents: 0,
    runningRuns: 0,
    pendingApprovals: 0,
    totalTokensBurned: 0,
  });
  mockedListKanbanItems.mockResolvedValue([]);
  mockedListRuns.mockResolvedValue([]);
  mockedListKnowledge.mockResolvedValue([]);
});

describe('ExecutiveSummary Waiting-on-you signal', () => {
  it('aggregates pending asks across REVIEW cards and opens the first one on click', async () => {
    const user = userEvent.setup();
    mockedListKanbanItems.mockResolvedValue([
      mkItem({ id: 'r1', status: 'REVIEW', pendingAskCount: 2 }),
      mkItem({ id: 'r2', status: 'TODO' }),
    ]);
    renderSummary();

    const label = await screen.findByText(/Waiting on you/i);
    const stat = label.closest('.stat') as HTMLElement;
    // The label mounts before the kanban-items query resolves — wait for the
    // aggregated ask count, then assert the rest of the same committed render.
    await waitFor(() => expect(stat).toHaveTextContent('2'));
    expect(stat).toHaveTextContent('Review cards need a decision');
    // Amber styling + clickable affordance when work is waiting.
    expect(stat.className).toContain('amber');
    expect(stat.className).toContain('clickable');
    expect(stat).toHaveAttribute('role', 'button');

    // The canonical open path: the act:open-task-drawer window event.
    const events: Array<{ itemId?: string }> = [];
    const handler = (e: Event) => events.push((e as CustomEvent).detail);
    window.addEventListener(TASK_DRAWER_EVENT, handler);
    await user.click(stat);
    window.removeEventListener(TASK_DRAWER_EVENT, handler);

    expect(events[0]?.itemId).toBe('r1');
  });

  it('shows the clear state without review cards and renders no click affordance', async () => {
    mockedListKanbanItems.mockResolvedValue([mkItem({ id: 'r2', status: 'TODO' })]);
    renderSummary();

    const detail = await screen.findByText('Nothing pending');
    const stat = detail.closest('.stat') as HTMLElement;
    expect(stat).toHaveTextContent('0');
    expect(stat.className).not.toContain('amber');
    expect(stat.className).not.toContain('clickable');
  });
});
