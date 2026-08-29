import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import KanbanBoard from '../KanbanBoard';
import type { WsEvent } from '../../types';

vi.mock('../../api/kanban', () => ({
  listKanbanItems: vi.fn().mockImplementation(() => Promise.resolve(kanbanData)),
  createKanbanItem: vi.fn().mockResolvedValue({ id: 'k-1' }),
}));
vi.mock('../../api/agents', () => ({
  listAgents: vi.fn().mockResolvedValue([
    { id: 'a-1', name: 'DEV Agent', role: 'dev', agentType: 'ADK', healthStatus: 'HEALTHY', description: '', model: '', provider: 'opencode', createdAt: '2026-01-01T00:00:00Z' },
  ]),
}));

let kanbanData: unknown[] = [];

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
  it('labels the CANCELLED column "Cancelled", not "Archived"', () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    ui(qc);

    // The same status is labelled "Cancelled" everywhere else (KanbanPage,
    // TaskDrawer); the overview board calling it "Archived" hid failed work.
    expect(screen.queryByText(/Archived/i)).not.toBeInTheDocument();
    expect(screen.getByText(/Cancelled/i)).toBeInTheDocument();
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

describe('KanbanBoard live move feedback (S6)', () => {
  beforeEach(() => {
    mockCtx = { lastMessage: null, isConnected: false };
    kanbanData = [
      { id: 'k-1', title: 'Fix pump cursor', priority: 'HIGH', status: 'IN_PROGRESS', linkedAgentId: 'a-1', assignee: null, labels: null },
    ];
  });
  afterEach(() => { vi.useRealTimers(); });

  it('shows agent attribution and BLOCKED badge from item data', async () => {
    kanbanData = [
      { id: 'k-2', title: 'Blocked task', priority: 'HIGH', status: 'BLOCKED', linkedAgentId: 'a-1', assignee: null, labels: null },
    ];
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { container } = ui(qc);
    await act(async () => { await new Promise((r) => setTimeout(r, 30)); });

    const card = container.querySelector('[data-card="k-2"]');
    expect(card).not.toBeNull();
    expect(card!.textContent).toContain('DEV Agent');
    expect(card!.textContent).toContain('BLOCKED');
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
