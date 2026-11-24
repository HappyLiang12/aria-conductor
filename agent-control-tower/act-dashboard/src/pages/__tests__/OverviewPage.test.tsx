import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act, within, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import OverviewPage from '../OverviewPage';
import type { DrawerState } from '../../components/DrawerContext';
import type { DecisionRow } from '../../hooks/useDecisionQueue';
import type { DashboardSummary } from '../../types';
import { useDecisionQueue } from '../../hooks/useDecisionQueue';
import { useAttentionQueue } from '../../hooks/useAttentionQueue';
import { approveApproval, rejectApproval } from '../../api/approvals';
import { getSummary } from '../../api/dashboard';

vi.mock('../../components/ExecutiveSummary', () => ({ default: () => <div data-testid="exec-summary" /> }));
vi.mock('../../components/KanbanBoard', () => ({ default: () => <div data-testid="kanban-board" /> }));
vi.mock('../../components/AgentTeam', () => ({ default: () => <div data-testid="agent-team" /> }));
vi.mock('../../components/ActivityTimeline', () => ({ default: () => <div data-testid="activity-timeline" /> }));
vi.mock('../../components/MorningBriefing', () => ({ default: () => <div data-testid="morning-briefing" /> }));
vi.mock('../../components/ReviewWorkspace', () => ({
  ReviewWorkspace: ({ itemId }: { itemId: string }) => (
    <section className="review-workspace" data-testid="review-workspace">{itemId}</section>
  ),
}));
vi.mock('../../hooks/useDecisionQueue', () => ({ useDecisionQueue: vi.fn() }));
vi.mock('../../hooks/useAttentionQueue', () => ({ useAttentionQueue: vi.fn() }));
vi.mock('../../api/approvals', () => ({
  approveApproval: vi.fn(),
  rejectApproval: vi.fn(),
  answerAsk: vi.fn(),
}));
vi.mock('../../api/dashboard', () => ({ getSummary: vi.fn() }));

let mockState: DrawerState = {
  taskDrawer: { open: false, itemId: null },
  agentDrawer: { open: false, agentId: null },
  reviewTargetId: null,
};
const openTaskDrawer = vi.fn();
const openReviewMode = vi.fn();
vi.mock('../../components/DrawerContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../components/DrawerContext')>();
  return {
    ...actual,
    useDrawerContext: () => ({ state: mockState, openTaskDrawer, openReviewMode }),
  };
});

const decisionMock = vi.mocked(useDecisionQueue);
const attentionMock = vi.mocked(useAttentionQueue);
const summaryMock = vi.mocked(getSummary);

function summary(overrides: Partial<DashboardSummary> = {}): DashboardSummary {
  return {
    activeAgents: 3,
    healthyAgents: 2,
    degradedAgents: 1,
    runningRuns: 2,
    pendingApprovals: 1,
    totalTokensBurned: 4200,
    runsWithoutUsage: 0,
    ...overrides,
  };
}

function setQueues(
  decision: Partial<ReturnType<typeof useDecisionQueue>> = {},
  attention: Partial<ReturnType<typeof useAttentionQueue>> = {},
) {
  decisionMock.mockReturnValue({
    rows: [], phase: 'success', partial: false, count: 0, reviewCount: 0, retry: vi.fn(), ...decision,
  } as never);
  attentionMock.mockReturnValue({
    rows: [], phase: 'success', partial: false, count: 0, retry: vi.fn(), ...attention,
  } as never);
}

function ui() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <OverviewPage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  mockState = {
    taskDrawer: { open: false, itemId: null },
    agentDrawer: { open: false, agentId: null },
    reviewTargetId: null,
  };
  openTaskDrawer.mockReset();
  openReviewMode.mockReset();
  summaryMock.mockReset();
  summaryMock.mockResolvedValue(summary());
  setQueues();
});

describe('OverviewPage decision-first layout', () => {
  it('renders queues before the board and keeps the support column', () => {
    const { container } = ui();
    const queue = screen.getByTestId('decision-queue');
    const attention = screen.getByTestId('attention-queue');
    const board = screen.getByTestId('kanban-board');
    expect(queue.compareDocumentPosition(attention) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(attention.compareDocumentPosition(board) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByTestId('agent-team')).toBeInTheDocument();
    expect(screen.queryByTestId('review-queue')).not.toBeInTheDocument();
    expect(container.querySelector('.overview-grid')).not.toBeNull();
  });

  it('leads with the page heading and the KPI row above the decision queue', async () => {
    ui();
    expect(screen.getByRole('heading', { level: 1, name: 'Overview' })).toBeInTheDocument();
    const kpi = await screen.findByTestId('kpi-row');
    const queue = screen.getByTestId('decision-queue');
    expect(kpi.compareDocumentPosition(queue) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('renders four KPI cards from the dashboard summary', async () => {
    ui();
    const kpi = await screen.findByTestId('kpi-row');
    expect(within(kpi).getAllByTestId(/^kpi-card-/)).toHaveLength(4);
    await waitFor(() =>
      expect(within(kpi).getByTestId('kpi-card-recorded-tokens')).toHaveTextContent('4,200'),
    );
    expect(within(kpi).getByTestId('kpi-card-active-agents')).toHaveTextContent('3');
    expect(within(kpi).getByTestId('kpi-card-active-agents')).toHaveTextContent('2 healthy · 1 degraded');
    expect(within(kpi).getByTestId('kpi-card-running-runs')).toHaveTextContent('2');
    expect(within(kpi).getByTestId('kpi-card-pending-approvals')).toHaveTextContent('1');
  });

  it('renders not reported for a zero token total beside silent runs', async () => {
    summaryMock.mockResolvedValue(summary({ totalTokensBurned: 0, runsWithoutUsage: 3 }));
    ui();
    const card = await screen.findByTestId('kpi-card-recorded-tokens');
    await waitFor(() => expect(card).toHaveTextContent('not reported'));
    expect(card).toHaveTextContent('3 runs reported no usage');
    expect(card).not.toHaveTextContent('0');
  });

  it('keeps a positive total but names the runs that reported no usage', async () => {
    summaryMock.mockResolvedValue(summary({ totalTokensBurned: 1234, runsWithoutUsage: 1 }));
    ui();
    const card = await screen.findByTestId('kpi-card-recorded-tokens');
    await waitFor(() => expect(card).toHaveTextContent('1,234'));
    expect(card).toHaveTextContent('1 run reported no usage');
  });

  it('does not pin the page grid inline', () => {
    const { container } = ui();
    const layout = container.querySelector('.layout') as HTMLElement;
    expect(layout.style.gridTemplateColumns).toBe('');
    expect(layout.classList.contains('overview-grid')).toBe(true);
  });

  it('collapses both queues only for two authoritative zero sources', () => {
    setQueues({ phase: 'success', count: 0 }, { phase: 'success', count: 0 });
    const { rerender } = ui();
    expect(screen.getByTestId('decision-queue')).toHaveAttribute('data-collapsed', 'true');
    expect(screen.getByTestId('attention-queue')).toHaveAttribute('data-collapsed', 'true');

    setQueues({ phase: 'success', count: 0 }, { phase: 'stale', count: 2 });
    rerender(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><OverviewPage /></QueryClientProvider>);
    expect(screen.getByTestId('decision-queue')).toHaveAttribute('data-collapsed', 'false');
    expect(screen.getByTestId('attention-queue')).toHaveAttribute('data-collapsed', 'false');
  });

  it('does not collapse when a source is partial even with zero rows', () => {
    setQueues(
      { phase: 'success', partial: false, count: 0 },
      { phase: 'success', partial: true, count: null },
    );
    ui();
    expect(screen.getByTestId('decision-queue')).toHaveAttribute('data-collapsed', 'false');
    expect(screen.getByTestId('attention-queue')).toHaveAttribute('data-collapsed', 'false');
  });

  it('stays open for post-run reviews and links to the board', () => {
    const scrollIntoView = vi.fn();
    Object.defineProperty(Element.prototype, 'scrollIntoView', {
      writable: true,
      value: scrollIntoView,
    });
    setQueues({ phase: 'success', count: 0, reviewCount: 2 });
    ui();

    const queue = screen.getByTestId('decision-queue');
    expect(queue).toHaveAttribute('data-collapsed', 'false');
    expect(within(queue).getByText(/2 runs awaiting review/i)).toBeInTheDocument();
    fireEvent.click(within(queue).getByRole('button', { name: /open review board/i }));
    expect(scrollIntoView).toHaveBeenCalled();
  });

  it('renders the review workspace in the main column with a three-widget strip', () => {
    mockState = {
      taskDrawer: { open: false, itemId: 'k-1' },
      agentDrawer: { open: false, agentId: null },
      reviewTargetId: 'k-1',
    };
    const { container } = ui();

    expect(container.querySelector('.review-workspace')).not.toBeNull();
    const strip = container.querySelector('.expanded-strip');
    expect(strip).not.toBeNull();
    expect(strip!.querySelectorAll('[data-testid]')).toHaveLength(3);
    expect(container.querySelector('[data-testid="kanban-board"]')).toBeNull();
    expect(container.querySelector('[data-testid="exec-summary"]')).toBeNull();
    expect(container.querySelectorAll('.col')).toHaveLength(1);
    const layout = container.querySelector('.layout') as HTMLElement;
    expect(layout.classList.contains('overview-grid--review')).toBe(true);
    expect(layout.style.gridTemplateColumns).toBe('');
  });

  const decisionRow = (id: string): DecisionRow => ({
    id,
    approvalId: id,
    runId: 'run-1',
    taskId: null,
    title: `Run ${id}`,
    state: 'UNKNOWN',
    askType: 'APPROVAL',
    approvalType: 'TOOL_CALL',
    source: 'LEGACY_GATE',
    reason: 'Decide this run',
    requestedAt: '2026-10-27T08:00:00Z',
    isPermission: false,
    riskTier: null,
    toolName: null,
    expiresAt: null,
    blocksRunSlot: false,
  });

  it('stays locked while focus remains in the queues after the pointer leaves', () => {
    setQueues({ rows: [decisionRow('ap-a')], count: 1 });
    ui();
    const queue = screen.getByTestId('decision-queue');
    const approve = within(queue).getByRole('button', { name: 'Approve' });

    act(() => {
      fireEvent.mouseEnter(queue);
      approve.focus();
    });
    expect(decisionMock).toHaveBeenLastCalledWith({ locked: true });

    // Pointer gone, focus still inside: rows must not re-sort under the thumb.
    fireEvent.mouseLeave(queue);
    expect(decisionMock).toHaveBeenLastCalledWith({ locked: true });

    act(() => approve.blur());
    expect(decisionMock).toHaveBeenLastCalledWith({ locked: false });
  });

  it('keeps each row busy for its own in-flight decision', async () => {
    setQueues({ rows: [decisionRow('ap-a'), decisionRow('ap-b')], count: 2 });
    let resolveA: (value: unknown) => void = () => {};
    vi.mocked(approveApproval).mockImplementation(
      () => new Promise((resolve) => { resolveA = resolve; }) as never,
    );
    vi.mocked(rejectApproval).mockResolvedValue({} as never);

    ui();
    const rowA = screen.getByTestId('decision-row-ap-a');
    const rowB = screen.getByTestId('decision-row-ap-b');

    fireEvent.click(within(rowA).getByRole('button', { name: 'Approve' }));
    await waitFor(() =>
      expect(within(rowA).getByRole('button', { name: 'Approve' })).toBeDisabled(),
    );

    fireEvent.click(within(rowB).getByRole('button', { name: 'Deny' }));
    await waitFor(() => expect(within(rowB).getByRole('button', { name: 'Deny' })).not.toBeDisabled());

    // B settling must not clear A's in-flight state and re-open its buttons.
    expect(within(rowA).getByRole('button', { name: 'Approve' })).toBeDisabled();

    await act(async () => {
      resolveA({});
    });
  });
});
