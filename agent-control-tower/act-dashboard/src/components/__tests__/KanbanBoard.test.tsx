import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import KanbanBoard from '../KanbanBoard';
import type { KanbanItem, WsEvent } from '../../types';

vi.mock('../../api/kanban', () => ({
  listKanbanItems: vi.fn().mockImplementation(() => Promise.resolve(kanbanData)),
  createKanbanItem: vi.fn().mockResolvedValue({ id: 'k-1' }),
  transitionKanbanItem: vi.fn().mockResolvedValue({ id: 'k-1' }),
}));
vi.mock('../../api/housekeeping', () => ({
  scanHousekeeping: vi.fn(),
  executeHousekeeping: vi.fn().mockResolvedValue({ categories: [], executedAt: 't' }),
}));
vi.mock('../../api/agents', () => ({
  listAgents: vi.fn().mockResolvedValue([
    { id: 'a-1', name: 'DEV Agent', role: 'dev', agentType: 'ADK', healthStatus: 'HEALTHY', description: '', model: '', provider: 'opencode', createdAt: '2026-01-01T00:00:00Z' },
  ]),
  listAgentTemplates: vi.fn().mockResolvedValue([
    { id: 'ba-agent', label: 'BA Agent', role: 'BA', agentType: 'ADK', model: '', provider: 'langchain', adkProvider: null, description: null },
  ]),
}));

let kanbanData: unknown[] = [];

// Minimal KanbanItem factory for the new board/modal tests.
const baseItem = (overrides: Partial<KanbanItem> = {}): KanbanItem => ({
  id: 'k-1',
  title: 'Test task',
  description: null,
  status: 'TODO',
  priority: 'MEDIUM',
  assignee: null,
  labels: null,
  linkedRunId: null,
  linkedAgentId: null,
  agentTemplateId: null,
  lastError: null,
  pendingAskCount: null,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  ...overrides,
});

// Mutable WS context stub (Toast.test.tsx pattern) so tests can push events.
let mockCtx: { lastMessage: WsEvent | null; isConnected: boolean } = {
  lastMessage: null,
  isConnected: false,
};
vi.mock('../Layout', () => ({
  useWebSocketContext: () => mockCtx,
}));

function ui(qc: QueryClient) {
  return render(
    <QueryClientProvider client={qc}>
      <KanbanBoard />
    </QueryClientProvider>,
  );
}

describe('KanbanBoard column labels (F5 regression)', () => {
  it('renders no Cancelled / QA Gate columns — cancel is an action, not a column', () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    ui(qc);

    // The old label-hacked board had Cancelled/QA Gate lanes; the HITL redesign
    // renders five status columns only and cancel becomes a per-card action.
    expect(screen.queryByText(/Archived/i)).not.toBeInTheDocument();
    expect(screen.queryByText('QA Gate')).not.toBeInTheDocument();
  });
});

describe('KanbanBoard WS invalidation whitelist (S1)', () => {
  it('does not invalidate kanban-items for high-frequency run.progress', () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const spy = vi.spyOn(qc, 'invalidateQueries');
    const { rerender } = ui(qc);

    act(() => {
      for (let i = 0; i < 10; i++) {
        mockCtx = {
          lastMessage: { type: 'run.progress', payload: { runId: 'r-1', kind: 'thinking', seq: i }, timestamp: 't' },
          isConnected: true,
        };
        rerender(
          <QueryClientProvider client={qc}>
            <KanbanBoard />
          </QueryClientProvider>,
        );
      }
    });

    expect(spy.mock.calls.filter((c) => JSON.stringify(c[0]?.queryKey) === JSON.stringify(['kanban-items'])))
      .toHaveLength(0);
  });

  it('still reacts instantly to lifecycle and kanban events', () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const spy = vi.spyOn(qc, 'invalidateQueries');
    const { rerender } = ui(qc);

    act(() => {
      mockCtx = { lastMessage: { type: 'run.started', payload: { runId: 'r-1' }, timestamp: 't' }, isConnected: true };
      rerender(
        <QueryClientProvider client={qc}>
          <KanbanBoard />
        </QueryClientProvider>,
      );
    });
    act(() => {
      mockCtx = { lastMessage: { type: 'kanban.transitioned', payload: { itemId: 'k-1' }, timestamp: 't2' }, isConnected: true };
      rerender(
        <QueryClientProvider client={qc}>
          <KanbanBoard />
        </QueryClientProvider>,
      );
    });

    const kanbanCalls = spy.mock.calls.filter((c) => JSON.stringify(c[0]?.queryKey) === JSON.stringify(['kanban-items']));
    expect(kanbanCalls.length).toBeGreaterThanOrEqual(2);
  });
});

describe('KanbanBoard card click opens the TaskDrawer (regression)', () => {
  it('dispatches act:open-task-drawer with detail.itemId', async () => {
    kanbanData = [
      { id: 'k-9', title: 'Click me', priority: 'MEDIUM', status: 'TODO', linkedAgentId: null, assignee: null, labels: null },
    ];
    const received: Array<Record<string, unknown>> = [];
    const listener = (e: Event) => received.push((e as CustomEvent).detail);
    window.addEventListener('act:open-task-drawer', listener);
    try {
      const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
      const { container } = ui(qc);
      await act(async () => { await new Promise((r) => setTimeout(r, 30)); });

      const card = container.querySelector('[data-card="k-9"]') as HTMLElement;
      expect(card).not.toBeNull();
      act(() => { card.click(); });

      // DrawerContext reads detail.itemId — dispatching { id } left the drawer closed.
      expect(received).toHaveLength(1);
      expect(received[0]).toEqual({ itemId: 'k-9' });
    } finally {
      window.removeEventListener('act:open-task-drawer', listener);
    }
  });
});

describe('KanbanBoard quick-clear (H2)', () => {
  beforeEach(() => {
    mockCtx = { lastMessage: null, isConnected: false };
  });

  it('hides the clear button when no finished cards exist', async () => {
    kanbanData = [
      { id: 'k-1', title: 'active', priority: 'MEDIUM', status: 'IN_PROGRESS', linkedAgentId: null, assignee: null, labels: null },
    ];
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { queryByRole } = ui(qc);
    await act(async () => { await new Promise((r) => setTimeout(r, 30)); });

    expect(queryByRole('button', { name: /clear done & cancelled/i })).toBeNull();
  });

  it('clear button counts finished cards and gates execute behind confirm', async () => {
    kanbanData = [
      { id: 'k-d', title: 'done', priority: 'MEDIUM', status: 'DONE', linkedAgentId: null, assignee: null, labels: null },
      { id: 'k-c', title: 'cancelled', priority: 'MEDIUM', status: 'CANCELLED', linkedAgentId: null, assignee: null, labels: null },
    ];
    const { executeHousekeeping } = await import('../../api/housekeeping');
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { getByRole } = ui(qc);
    await act(async () => { await new Promise((r) => setTimeout(r, 30)); });

    const btn = getByRole('button', { name: /clear done & cancelled \(2\)/i });
    act(() => { btn.click(); });
    // confirm modal gates the destructive call
    expect(executeHousekeeping).not.toHaveBeenCalled();
    act(() => { getByRole('button', { name: /approve|confirm/i }).click(); });
    await act(async () => { await new Promise((r) => setTimeout(r, 30)); });
    expect(executeHousekeeping).toHaveBeenCalledWith(
      expect.objectContaining({ categories: ['kanban'], confirm: true }),
    );
  });
});

describe('KanbanBoard live move feedback (S6)', () => {
  beforeEach(() => {
    mockCtx = { lastMessage: null, isConnected: false };
    kanbanData = [
      { id: 'k-1', title: 'Fix pump cursor', priority: 'HIGH', status: 'IN_PROGRESS', linkedAgentId: 'a-1', assignee: null, labels: null },
    ];
  });
  afterEach(() => { vi.useRealTimers(); });

  it('shows agent attribution on the card from item data', async () => {
    kanbanData = [
      { id: 'k-2', title: 'Attributed task', priority: 'HIGH', status: 'IN_PROGRESS', linkedAgentId: 'a-1', assignee: null, labels: null },
    ];
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { container } = ui(qc);
    await act(async () => { await new Promise((r) => setTimeout(r, 30)); });

    const card = container.querySelector('[data-card="k-2"]');
    expect(card).not.toBeNull();
    expect(card!.textContent).toContain('DEV Agent');
  });

  it('flashes the moved card on kanban.transitioned and clears after ~1.2s', async () => {
    vi.useFakeTimers();
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { container, rerender } = ui(qc);
    await act(async () => { vi.advanceTimersByTime(50); });

    act(() => {
      mockCtx = { lastMessage: { type: 'kanban.transitioned', payload: { itemId: 'k-1', fromStatus: 'TODO', toStatus: 'IN_PROGRESS' }, timestamp: 't' }, isConnected: true };
      rerender(<QueryClientProvider client={qc}><KanbanBoard /></QueryClientProvider>);
    });
    const card = container.querySelector('[data-card="k-1"]');
    expect(card!.className).toMatch(/moving/);

    act(() => { vi.advanceTimersByTime(1300); });
    expect(container.querySelector('[data-card="k-1"]')!.className).not.toMatch(/moving/);
  });
});

describe('KanbanBoard status board + DnD (Task 12)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockCtx = { lastMessage: null, isConnected: false };
  });

  const renderBoard = async (data: KanbanItem[]) => {
    kanbanData = data;
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const utils = ui(qc);
    await act(async () => { await new Promise((r) => setTimeout(r, 30)); });
    return utils;
  };

  const dropOn = (card: HTMLElement, lane: HTMLElement) => {
    fireEvent.dragStart(card);
    fireEvent.dragOver(lane);
    fireEvent.drop(lane);
  };

  it('renders five status-driven columns and NOT QA Gate / Cancelled', async () => {
    await renderBoard([
      baseItem({ status: 'BACKLOG' }),
      baseItem({ id: 'k-2', status: 'REVIEW' }),
    ]);
    expect(screen.getByText('Backlog')).toBeInTheDocument();
    expect(screen.getByText('Todo')).toBeInTheDocument();
    expect(screen.getByText('In Progress')).toBeInTheDocument();
    expect(screen.getByText('Review')).toBeInTheDocument();
    expect(screen.getByText('Done')).toBeInTheDocument();
    expect(screen.queryByText('QA Gate')).not.toBeInTheDocument();
    expect(screen.queryByText('Cancelled')).not.toBeInTheDocument();
  });

  it('shows the "{n} asks" badge on a REVIEW card with pendingAskCount', async () => {
    await renderBoard([baseItem({ id: 'k-3', status: 'REVIEW', pendingAskCount: 2 })]);
    expect(screen.getByText('2 asks')).toBeInTheDocument();
  });

  it('drop on a legal target calls transitionKanbanItem(id, {status: target})', async () => {
    const { transitionKanbanItem } = await import('../../api/kanban');
    vi.mocked(transitionKanbanItem).mockResolvedValueOnce(baseItem({ status: 'IN_PROGRESS' }));
    const { container } = await renderBoard([baseItem()]); // TODO card
    const card = container.querySelector('[data-card="k-1"]') as HTMLElement;
    dropOn(card, screen.getByTestId('lane-IN_PROGRESS'));
    await waitFor(() =>
      expect(transitionKanbanItem).toHaveBeenCalledWith('k-1', { status: 'IN_PROGRESS' }),
    );
  });

  it('drop on an ILLEGAL target (DONE card → TODO lane) does not call the API', async () => {
    const { transitionKanbanItem } = await import('../../api/kanban');
    const { container } = await renderBoard([
      baseItem({ id: 'k-done', title: 'Finished', status: 'DONE' }),
    ]);
    const card = container.querySelector('[data-card="k-done"]') as HTMLElement;
    dropOn(card, screen.getByTestId('lane-TODO'));
    await act(async () => { await new Promise((r) => setTimeout(r, 30)); });
    expect(transitionKanbanItem).not.toHaveBeenCalled();
  });

  it('transition failure snaps the card back and refetches the list', async () => {
    const { transitionKanbanItem } = await import('../../api/kanban');
    vi.mocked(transitionKanbanItem).mockRejectedValueOnce({ message: 'invalid transition' });
    await renderBoard([baseItem()]); // TODO card
    fireEvent.dragStart(screen.getByTestId('lane-TODO').querySelector('[data-card="k-1"]')!);
    const lane = screen.getByTestId('lane-IN_PROGRESS');
    fireEvent.dragOver(lane);
    fireEvent.drop(lane);

    // Optimistic move put the card in IN_PROGRESS; after the rejection the
    // query invalidation refetches and the card is back in its TODO lane.
    await waitFor(() => {
      expect(screen.getByTestId('lane-TODO').querySelector('[data-card="k-1"]')).not.toBeNull();
    });
    expect(await screen.findByText(/Move rejected/i)).toBeInTheDocument();
  });

  it('cancel button transitions to CANCELLED without opening the drawer', async () => {
    const { transitionKanbanItem } = await import('../../api/kanban');
    vi.mocked(transitionKanbanItem).mockResolvedValueOnce(baseItem({ status: 'CANCELLED' }));
    const received: Array<Record<string, unknown>> = [];
    const listener = (e: Event) => received.push((e as CustomEvent).detail);
    window.addEventListener('act:open-task-drawer', listener);
    try {
      await renderBoard([baseItem()]); // TODO card
      fireEvent.click(screen.getByTitle('Cancel task'));
      await waitFor(() =>
        expect(transitionKanbanItem).toHaveBeenCalledWith('k-1', { status: 'CANCELLED' }),
      );
      // stopPropagation: the card click that opens the TaskDrawer never fires.
      expect(received).toHaveLength(0);
    } finally {
      window.removeEventListener('act:open-task-drawer', listener);
    }
  });

  it('renders lastError on the card face', async () => {
    await renderBoard([baseItem({ lastError: 'Agent pickup failed: port 9300 busy' })]);
    expect(await screen.findByText(/port 9300 busy/)).toBeInTheDocument();
  });
});
