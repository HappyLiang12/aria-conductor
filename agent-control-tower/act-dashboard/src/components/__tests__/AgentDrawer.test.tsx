import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AgentDrawer } from '../AgentDrawer';
import type { WsEvent } from '../../types';

vi.mock('../../api/agents', () => ({
  getAgent: vi.fn().mockResolvedValue({
    id: 'a-1', name: 'SDD DEV Agent', role: 'dev', agentType: 'ADK',
    healthStatus: 'HEALTHY', description: '', model: '', provider: 'opencode',
    createdAt: '2026-01-01T00:00:00Z',
  }),
}));
vi.mock('../../api/runs', () => ({
  listRuns: vi.fn().mockResolvedValue([]),
  pauseRun: vi.fn().mockResolvedValue({}),
  resumeRun: vi.fn().mockResolvedValue({}),
  cancelRun: vi.fn().mockResolvedValue({}),
  injectRunMessage: vi.fn().mockResolvedValue({}),
}));

let mockCtx: { lastMessage: WsEvent | null; isConnected: boolean } = {
  lastMessage: null,
  isConnected: false,
};
vi.mock('../Layout', () => ({
  useWebSocketContext: () => mockCtx,
}));
vi.mock('../DrawerContext', () => ({
  useDrawerContext: () => ({
    state: {
      agentDrawer: { open: true, agentId: 'a-1' },
      taskDrawer: { open: false, itemId: null },
    },
    openTaskDrawer: vi.fn(),
    closeTaskDrawer: vi.fn(),
    openAgentDrawer: vi.fn(),
    closeAgentDrawer: vi.fn(),
  }),
}));

function ui() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <AgentDrawer />
    </QueryClientProvider>,
  );
}

function push(event: WsEvent) {
  mockCtx = { lastMessage: event, isConnected: true };
}

describe('AgentDrawer live stream (S2)', () => {
  beforeEach(() => {
    mockCtx = { lastMessage: null, isConnected: false };
  });

  it('renders parsed thinking detail from run.iteration events', async () => {
    const { rerender } = ui();
    await waitFor(() => expect(screen.getByRole('button', { name: /collapse/i })).toBeInTheDocument());

    act(() => {
      push({
        type: 'run.iteration',
        payload: {
          agentId: 'a-1', iteration: 1, maxIterations: 15,
          thinking: 'Locating the step scheduler in WorkflowService',
          toolCalls: [{ name: 'bash', arguments: 'rg -n scheduler', result: '3 matches' }],
        },
        timestamp: '2026-08-29T06:00:00Z',
      });
      rerender(
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <AgentDrawer />
        </QueryClientProvider>,
      );
    });

    // S2 contract: the parsed thinking must reach the screen (previously dropped).
    expect(screen.getByText(/Locating the step scheduler/)).toBeInTheDocument();
    expect(screen.getByText(/3 matches/)).toBeInTheDocument();
  });

  it('caps the stream window at 60 lines', async () => {
    const { rerender, container } = ui();
    await waitFor(() => expect(screen.getByRole('button', { name: /collapse/i })).toBeInTheDocument());
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    act(() => {
      for (let i = 0; i < 70; i++) {
        push({
          type: 'run.iteration',
          payload: { agentId: 'a-1', iteration: i, thinking: `line ${i}` },
          timestamp: `2026-08-29T06:00:${String(i % 60).padStart(2, '0')}Z`,
        });
        rerender(<QueryClientProvider client={qc}><AgentDrawer /></QueryClientProvider>);
      }
    });

    const lines = container.querySelectorAll('.stream .ln');
    expect(lines.length).toBeLessThanOrEqual(60);
    expect(lines.length).toBeGreaterThan(0);
  });

  it('renders run.progress events by kind and dedupes by seq (S11)', async () => {
    const { rerender, container } = ui();
    await waitFor(() => expect(screen.getByRole('button', { name: /collapse/i })).toBeInTheDocument());

    act(() => {
      push({ type: 'run.progress', payload: { runId: 'a-1', agentId: 'a-1', kind: 'THINKING', content: 'pump thinking line', seq: 1 }, timestamp: '2026-08-29T06:00:00Z' });
      rerender(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AgentDrawer /></QueryClientProvider>);
    });
    expect(screen.getByText(/pump thinking line/)).toBeInTheDocument();

    act(() => {
      push({ type: 'run.progress', payload: { runId: 'a-1', agentId: 'a-1', kind: 'TOOL_CALL', content: '', toolName: 'bash', seq: 2 }, timestamp: '2026-08-29T06:00:01Z' });
      rerender(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AgentDrawer /></QueryClientProvider>);
    });
    expect(screen.getAllByText(/bash/).length).toBeGreaterThan(0);

    // duplicate seq must not create a second line
    const linesBefore = container.querySelectorAll('.stream .ln').length;
    act(() => {
      push({ type: 'run.progress', payload: { runId: 'a-1', agentId: 'a-1', kind: 'TOOL_CALL', content: '', toolName: 'bash', seq: 2 }, timestamp: '2026-08-29T06:00:02Z' });
      rerender(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AgentDrawer /></QueryClientProvider>);
    });
    expect(container.querySelectorAll('.stream .ln').length).toBe(linesBefore);
  });

  it('collapse toggle hides the stream body but keeps the header', async () => {
    const { rerender, container } = ui();
    await waitFor(() => expect(screen.getByRole('button', { name: /collapse/i })).toBeInTheDocument());

    const toggle = screen.getByRole('button', { name: /collapse/i });
    act(() => { fireEvent.click(toggle); });
    rerender(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AgentDrawer /></QueryClientProvider>);

    const stream = container.querySelector('.stream');
    expect(stream).not.toBeNull();
    expect(stream!.classList.contains('collapsed')).toBe(true);
    // header stays visible for re-expansion
    expect(screen.getByRole('button', { name: /expand/i })).toBeInTheDocument();
  });
});
