import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act, within, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import OverviewPage from '../OverviewPage';
import type { DrawerState } from '../../components/DrawerContext';
import type { DecisionRow } from '../../hooks/useDecisionQueue';
import { useDecisionQueue } from '../../hooks/useDecisionQueue';
import { useAttentionQueue } from '../../hooks/useAttentionQueue';
import { approveApproval, rejectApproval } from '../../api/approvals';

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

function setQueues(
  decision: Partial<ReturnType<typeof useDecisionQueue>> = {},
  attention: Partial<ReturnType<typeof useAttentionQueue>> = {},
) {
  decisionMock.mockReturnValue({
    rows: [], phase: 'success', partial: false, count: 0, retry: vi.fn(), ...decision,
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
    source: 'LEGACY_GATE',
    reason: 'Decide this run',
    requestedAt: '2026-10-27T08:00:00Z',
    isPermission: false,
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
