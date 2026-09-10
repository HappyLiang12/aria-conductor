import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReviewWorkspace } from '../ReviewWorkspace';
import { DrawerProvider, useDrawerContext } from '../DrawerContext';
import type { Approval, KanbanItem, Run } from '../../types';

vi.mock('../../api/kanban', () => ({
  getKanbanItem: vi.fn(),
  transitionKanbanItem: vi.fn(),
}));
vi.mock('../../api/approvals', () => ({
  listAsksByKanbanItem: vi.fn(),
  answerAsk: vi.fn(),
  approveApproval: vi.fn(),
  rejectApproval: vi.fn(),
}));
vi.mock('../../api/runs', () => ({
  getRun: vi.fn(),
}));

import { getKanbanItem } from '../../api/kanban';
import { listAsksByKanbanItem } from '../../api/approvals';
import { getRun } from '../../api/runs';

const mockedGetKanbanItem = vi.mocked(getKanbanItem);
const mockedListAsks = vi.mocked(listAsksByKanbanItem);
const mockedGetRun = vi.mocked(getRun);

function mkItem(over: Partial<KanbanItem> = {}): KanbanItem {
  return {
    id: 'task-1',
    title: 'Spec task',
    description: 'Write the spec',
    status: 'REVIEW',
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

function mkAsk(over: Partial<Approval> = {}): Approval {
  return {
    id: 'a1',
    runId: 'run-1',
    toolCallId: null,
    status: 'PENDING',
    reason: '',
    requestedAt: '2026-09-08T00:00:00Z',
    decidedAt: null,
    expiresAt: '2026-09-08T01:00:00Z',
    kanbanItemId: 'task-1',
    ...over,
  };
}

function mkRun(over: Partial<Run> = {}): Run {
  return {
    id: 'run-7',
    agentId: 'a-1',
    status: 'COMPLETED',
    promptSeed: 'Deliver the spec',
    maxIterations: 15,
    totalTokensUsed: 1234,
    iterationCount: 4,
    errorMessage: null,
    finalOutput: 'Delivered the spec deliverable',
    createdAt: '2026-09-08T00:00:00Z',
    completedAt: '2026-09-08T00:10:00Z',
    ...over,
  };
}

/** Mirrors OverviewPage's gating: the workspace mounts only while a target is set. */
function Harness() {
  const { state, openReviewMode } = useDrawerContext();
  return (
    <div>
      {state.reviewTargetId && <ReviewWorkspace itemId={state.reviewTargetId} />}
      <span data-testid="review-target">{state.reviewTargetId ?? ''}</span>
      <span data-testid="task-open">{String(state.taskDrawer.open)}</span>
      <span data-testid="task-item">{state.taskDrawer.itemId ?? ''}</span>
      <button data-testid="open-review" onClick={() => openReviewMode('task-1')} />
    </div>
  );
}

function renderWorkspace(seedItems?: KanbanItem[]) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  if (seedItems) qc.setQueryData(['kanban-items'], seedItems);
  const utils = render(
    <QueryClientProvider client={qc}>
      <DrawerProvider>
        <Harness />
      </DrawerProvider>
    </QueryClientProvider>,
  );
  return { ...utils, qc };
}

async function openReview() {
  const user = userEvent.setup();
  await user.click(screen.getByTestId('open-review'));
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedGetKanbanItem.mockResolvedValue(mkItem());
  mockedListAsks.mockResolvedValue([]);
  mockedGetRun.mockResolvedValue(mkRun());
});

describe('ReviewWorkspace (in-place expand, spec 10.3)', () => {
  it('renders the markdown spec pane and the decision rail with asks', async () => {
    mockedListAsks.mockResolvedValue([
      mkAsk({ id: 'a1', askType: 'REVIEW_REQUEST', content: 'run completed - review the output' }),
    ]);
    const { container } = renderWorkspace();
    await openReview();

    const ws = await screen.findByTestId('review-workspace');
    expect(ws).not.toBeNull();
    // Header carries the card identity.
    expect(screen.getByText(/TASK · TASK-1/)).toBeInTheDocument();
    expect(screen.getByText('Spec task')).toBeInTheDocument();
    expect(screen.getByText(/NEEDS YOUR DECISION/)).toBeInTheDocument();
    // The spec pane shows the first pending ask's content over the description.
    const spec = container.querySelector('.rf-spec .spec-review-markdown') as HTMLElement;
    expect(spec.textContent).toContain('run completed');
  });

  it('spec rail shows the linked run status and output (D4)', async () => {
    mockedGetKanbanItem.mockResolvedValue(mkItem({ linkedRunId: 'run-7' }));
    mockedGetRun.mockResolvedValue(mkRun({ finalOutput: 'Reviewed deliverable output' }));
    renderWorkspace();
    await openReview();
    await screen.findByText('Spec task');

    expect(await screen.findByText('COMPLETED')).toBeInTheDocument();
    expect(mockedGetRun).toHaveBeenCalledWith('run-7');
    const output = document.querySelector('.run-result .spec-review-markdown') as HTMLElement;
    expect(output).not.toBeNull();
    expect(output.textContent).toContain('Reviewed deliverable output');
  });

  it('spec rail has no run section when the card has no linked run (D4)', async () => {
    mockedGetKanbanItem.mockResolvedValue(mkItem({ linkedRunId: null }));
    renderWorkspace();
    await openReview();
    await screen.findByText('Spec task');

    expect(document.querySelector('.run-result')).toBeNull();
    expect(mockedGetRun).not.toHaveBeenCalled();
  });

  it('shows the ShortApprovalView when the card has no asks (no empty state)', async () => {
    mockedListAsks.mockResolvedValue([]);
    renderWorkspace();
    await openReview();

    expect(await screen.findByText(/Run completed/)).toBeInTheDocument();
    expect(screen.queryByText(/NEEDS YOUR DECISION/)).not.toBeInTheDocument();
    expect(screen.queryByText(/No pending asks/)).not.toBeInTheDocument();
  });

  it('Collapse returns to the drawer on the same card', async () => {
    const user = userEvent.setup();
    renderWorkspace();
    await openReview();
    await screen.findByTestId('review-workspace');
    expect(screen.getByTestId('task-open').textContent).toBe('false');

    await user.click(screen.getByRole('button', { name: /collapse/i }));
    expect(screen.getByTestId('review-target').textContent).toBe('');
    expect(screen.getByTestId('task-open').textContent).toBe('true');
    expect(screen.getByTestId('task-item').textContent).toBe('task-1');
    expect(screen.queryByTestId('review-workspace')).toBeNull();
  });

  it('does not render the ShortApprovalView while the asks query is in flight', async () => {
    // Mirrors the drawer's guard: until the asks query succeeds, the empty
    // pendingAsks array is NOT evidence that the card has no asks — the rail
    // must stay empty rather than flash the short view during load.
    let resolveAsks!: (asks: Approval[]) => void;
    mockedListAsks.mockImplementation(
      () => new Promise<Approval[]>((res) => { resolveAsks = res; }),
    );
    renderWorkspace();
    await openReview();
    // Wait past the loading shell: the item is rendered, asks still pending.
    await screen.findByText('Spec task');
    expect(screen.queryByText(/Run completed/)).not.toBeInTheDocument();
    expect(screen.queryByText(/NEEDS YOUR DECISION/)).not.toBeInTheDocument();

    // Asks resolve empty: NOW the short view may appear.
    await act(async () => {
      resolveAsks([]);
    });
    expect(await screen.findByText(/Run completed/)).toBeInTheDocument();
  });

  it('renders an error shell with a Collapse button when the item query fails', async () => {
    const user = userEvent.setup();
    mockedGetKanbanItem.mockRejectedValue(new Error('404'));
    renderWorkspace();
    await openReview();

    // A dead-end spinner is not acceptable: the operator needs the message
    // AND a way back to the board/drawer.
    expect(
      await screen.findByText('Failed to load task. It may have been deleted.'),
    ).toBeInTheDocument();
    const collapse = screen.getByRole('button', { name: /collapse/i });
    expect(collapse).toBeInTheDocument();

    await user.click(collapse);
    expect(screen.getByTestId('review-target').textContent).toBe('');
    expect(screen.getByTestId('task-open').textContent).toBe('true');
    expect(screen.queryByTestId('review-workspace')).toBeNull();
  });

  it('Escape collapses the workspace and reopens the drawer on the same card', async () => {
    renderWorkspace();
    await openReview();
    await screen.findByText('Spec task');

    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    });
    await waitFor(() => expect(screen.getByTestId('review-target').textContent).toBe(''));
    expect(screen.getByTestId('task-open').textContent).toBe('true');
    expect(screen.getByTestId('task-item').textContent).toBe('task-1');
    expect(screen.queryByTestId('review-workspace')).toBeNull();
  });

  it('Escape while typing in a workspace textarea does not discard the draft', async () => {
    const user = userEvent.setup();
    renderWorkspace();
    await openReview();
    await screen.findByText('Spec task');
    const feedback = await screen.findByLabelText('Request-changes feedback');
    await user.type(feedback, 'keep my draft');

    // Escape pressed while the draft textarea has focus: the event target is
    // the textarea, and both the workspace and the draft must survive.
    act(() => {
      feedback.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
      );
    });
    expect(screen.getByTestId('review-target').textContent).toBe('task-1');
    expect(screen.getByTestId('review-workspace')).not.toBeNull();
    expect((feedback as HTMLTextAreaElement).value).toBe('keep my draft');

    // Escape from the body (target = window) still collapses to the drawer.
    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    });
    await waitFor(() => expect(screen.getByTestId('review-target').textContent).toBe(''));
    expect(screen.queryByTestId('review-workspace')).toBeNull();
  });

  it('focuses the Collapse button when the workspace opens (keyboard entry point)', async () => {
    renderWorkspace();
    await openReview();
    const collapse = await screen.findByRole('button', { name: /collapse/i });
    expect(document.activeElement).toBe(collapse);
  });

  it('auto-exits when the card leaves REVIEW (refetch returns a non-REVIEW status)', async () => {
    mockedGetKanbanItem
      .mockResolvedValueOnce(mkItem())
      .mockResolvedValue(mkItem({ status: 'DONE' }));
    const { qc } = renderWorkspace();
    await openReview();
    // Wait for the item itself to load (the loading shell shares the testid).
    await screen.findByText('Spec task');
    expect(screen.getByTestId('review-target').textContent).toBe('task-1');

    // The post-decision invalidation refetches the item: it now reads DONE and
    // the workspace must leave review mode on its own. The cache update lands
    // via a passive effect, so wait for the state change.
    await act(async () => {
      await qc.refetchQueries({ queryKey: ['kanban'] });
    });
    await waitFor(() => expect(screen.getByTestId('review-target').textContent).toBe(''));
    expect(screen.queryByTestId('review-workspace')).toBeNull();
  });

  it('next navigates to the following REVIEW sibling in place (drawer stays collapsed)', async () => {
    const user = userEvent.setup();
    mockedGetKanbanItem.mockImplementation(async (id: string) =>
      id === 'task-2' ? mkItem({ id: 'task-2', title: 'Second review' }) : mkItem(),
    );
    renderWorkspace([
      mkItem(),
      mkItem({ id: 'task-2', title: 'Second review' }),
      mkItem({ id: 'task-3', status: 'TODO' }),
    ]);
    await openReview();
    await screen.findByText('Spec task');

    // task-1 is the first REVIEW sibling: prev disabled, next targets task-2.
    expect(screen.getByRole('button', { name: /prev/i })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: /next/i }));

    await waitFor(() => expect(mockedGetKanbanItem).toHaveBeenCalledWith('task-2'));
    expect(await screen.findByText('Second review')).toBeInTheDocument();
    // Still inside the workspace: the target moved, the drawer stays collapsed.
    expect(screen.getByTestId('review-target').textContent).toBe('task-2');
    expect(screen.getByTestId('task-open').textContent).toBe('false');
  });

  it('prev navigates back to the preceding REVIEW sibling', async () => {
    const user = userEvent.setup();
    mockedGetKanbanItem.mockImplementation(async (id: string) =>
      id === 'task-2' ? mkItem({ id: 'task-2', title: 'Second review' }) : mkItem(),
    );
    renderWorkspace([
      mkItem(),
      mkItem({ id: 'task-2', title: 'Second review' }),
    ]);
    await openReview();
    await screen.findByText('Spec task');
    await user.click(screen.getByRole('button', { name: /next/i }));
    expect(await screen.findByText('Second review')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /next/i })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: /prev/i }));
    expect(await screen.findByText('Spec task')).toBeInTheDocument();
    expect(screen.getByTestId('review-target').textContent).toBe('task-1');
    expect(screen.getByTestId('task-open').textContent).toBe('false');
  });
});
