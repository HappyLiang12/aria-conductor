import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { TaskDrawer } from '../TaskDrawer';
import { DrawerProvider, TASK_DRAWER_EVENT, useDrawerContext } from '../DrawerContext';
import type { Approval, KanbanItem } from '../../types';

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

import { getKanbanItem, transitionKanbanItem } from '../../api/kanban';
import {
  listAsksByKanbanItem,
  answerAsk,
  approveApproval,
  rejectApproval,
} from '../../api/approvals';

const mockedGetKanbanItem = vi.mocked(getKanbanItem);
const mockedTransition = vi.mocked(transitionKanbanItem);
const mockedListAsks = vi.mocked(listAsksByKanbanItem);
const mockedAnswerAsk = vi.mocked(answerAsk);
const mockedApproveApproval = vi.mocked(approveApproval);
const mockedRejectApproval = vi.mocked(rejectApproval);

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

function renderDrawer(opts: { pathname?: string } = {}) {
  const { pathname = '/' } = opts;
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const utils = render(
    <QueryClientProvider client={qc}>
      <DrawerProvider>
        {/* TaskDrawer mounts inside the app Router via Layout and reads the
            route for the overview-only Expand affordance (spec 10.3). */}
        <MemoryRouter initialEntries={[pathname]}>
          <TaskDrawer />
          <StateProbe />
        </MemoryRouter>
      </DrawerProvider>
    </QueryClientProvider>,
  );
  return { ...utils, qc };
}

/** Exposes DrawerContext state so tests can assert the in-place review target. */
function StateProbe() {
  const { state } = useDrawerContext();
  return (
    <div>
      <span data-testid="task-open">{String(state.taskDrawer.open)}</span>
      <span data-testid="review-target">{state.reviewTargetId ?? ''}</span>
    </div>
  );
}

/** The canonical open path: DrawerContext listens for the window event. */
function openTaskDrawerEvent(itemId = 'task-1') {
  act(() => {
    window.dispatchEvent(new CustomEvent(TASK_DRAWER_EVENT, { detail: { itemId } }));
  });
}

function decisionZone(): HTMLElement {
  return document.querySelector('.decision-zone') as HTMLElement;
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedGetKanbanItem.mockResolvedValue(mkItem());
  mockedTransition.mockImplementation(
    async (id, req) => ({ ...mkItem({ id }), ...req }),
  );
  mockedListAsks.mockResolvedValue([]);
  mockedAnswerAsk.mockResolvedValue(mkAsk());
  mockedApproveApproval.mockResolvedValue(mkAsk({ status: 'APPROVED' }));
  mockedRejectApproval.mockResolvedValue(mkAsk({ status: 'DENIED' }));
});

describe('TaskDrawer review decision zone', () => {
  it('shows the decision zone for a REVIEW card with pending asks', async () => {
    mockedGetKanbanItem.mockResolvedValue(mkItem({ pendingAskCount: 2 }));
    mockedListAsks.mockResolvedValue([
      mkAsk({ id: 'a1', askType: 'APPROVAL', content: 'spec v2', contextMd: 'why this matters' }),
      mkAsk({ id: 'a2', askType: 'QUESTION', content: 'UTF-8 or BOM?' }),
    ]);
    renderDrawer();
    openTaskDrawerEvent();

    expect(await screen.findByText(/NEEDS YOUR DECISION/)).toBeInTheDocument();
    expect(screen.getByText(/spec v2/)).toBeInTheDocument();
    expect(screen.getByText(/UTF-8 or BOM\?/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /expand/i })).toBeInTheDocument();
  });

  it('shows the decision zone on an IN_PROGRESS card when it has pending asks', async () => {
    mockedGetKanbanItem.mockResolvedValue({ ...mkItem(), status: 'IN_PROGRESS', pendingAskCount: 1 });
    mockedListAsks.mockResolvedValue([
      mkAsk({ id: 'a9', askType: 'APPROVAL', content: 'gate approval mid-run', status: 'PENDING' }),
    ]);
    renderDrawer();
    openTaskDrawerEvent();

    expect(await screen.findByText(/NEEDS YOUR DECISION/)).toBeInTheDocument();
  });

  it('shows no decision zone for a non-REVIEW card', async () => {
    mockedGetKanbanItem.mockResolvedValue(mkItem({ status: 'TODO' }));
    renderDrawer();
    openTaskDrawerEvent();

    await screen.findByText('Spec task');
    expect(screen.queryByText(/NEEDS YOUR DECISION/)).not.toBeInTheDocument();
    expect(document.querySelector('.decision-zone')).toBeNull();
    expect(screen.queryByRole('button', { name: /expand/i })).not.toBeInTheDocument();
  });

  it('Expand collapses the drawer and sets the in-place review target (spec 10.3)', async () => {
    const user = userEvent.setup();
    mockedListAsks.mockResolvedValue([
      mkAsk({ id: 'a1', askType: 'APPROVAL', content: 'spec v2' }),
    ]);
    renderDrawer();
    openTaskDrawerEvent();

    await screen.findByText(/NEEDS YOUR DECISION/);
    expect(screen.getByTestId('review-target').textContent).toBe('');

    await user.click(screen.getByRole('button', { name: /expand/i }));
    // The drawer hands over to the in-place ReviewWorkspace: it closes itself
    // and the context records which card is under review.
    expect(screen.getByTestId('task-open').textContent).toBe('false');
    expect(screen.getByTestId('review-target').textContent).toBe('task-1');
  });

  it('Expand affordance renders on the overview route (spec 10.3)', async () => {
    mockedListAsks.mockResolvedValue([
      mkAsk({ id: 'a1', askType: 'APPROVAL', content: 'spec v2' }),
    ]);
    renderDrawer({ pathname: '/' });
    openTaskDrawerEvent();

    await screen.findByText(/NEEDS YOUR DECISION/);
    expect(screen.getByRole('button', { name: /expand/i })).toBeInTheDocument();
  });

  it('Expand affordance is hidden off the overview route (spec 10.3)', async () => {
    mockedListAsks.mockResolvedValue([
      mkAsk({ id: 'a1', askType: 'APPROVAL', content: 'spec v2' }),
    ]);
    // The ReviewWorkspace renders in-flow inside the Overview layout only; on
    // any other route Expand would create a stranded workspace, so the
    // affordance must not render there (hidden, not disabled).
    renderDrawer({ pathname: '/runs' });
    openTaskDrawerEvent();

    await screen.findByText(/NEEDS YOUR DECISION/);
    expect(screen.queryByRole('button', { name: /expand/i })).not.toBeInTheDocument();
  });

  it('Approve on an APPROVAL ask calls approveApproval (gate semantics), not answerAsk', async () => {
    const user = userEvent.setup();
    mockedListAsks.mockResolvedValue([
      mkAsk({ id: 'a1', askType: 'APPROVAL', content: 'ship it' }),
    ]);
    renderDrawer();
    openTaskDrawerEvent();

    const zone = await screen.findByText(/NEEDS YOUR DECISION/);
    const askCard = zone.closest('.decision-zone')!.querySelector('.ask-card') as HTMLElement;
    await user.click(within(askCard).getByRole('button', { name: 'Approve' }));

    await waitFor(() =>
      expect(mockedApproveApproval).toHaveBeenCalledWith('a1', undefined),
    );
    expect(mockedAnswerAsk).not.toHaveBeenCalled();
    expect(mockedRejectApproval).not.toHaveBeenCalled();
  });

  it('Approve on a QUESTION ask answers it with the typed answer', async () => {
    const user = userEvent.setup();
    mockedListAsks.mockResolvedValue([
      mkAsk({ id: 'a2', askType: 'QUESTION', content: 'UTF-8 or BOM?' }),
    ]);
    renderDrawer();
    openTaskDrawerEvent();

    const zone = await screen.findByText(/NEEDS YOUR DECISION/);
    const askCard = zone.closest('.decision-zone')!.querySelector('.ask-card') as HTMLElement;
    await user.type(within(askCard).getByLabelText('Answer for ask a2'), 'text');
    await user.click(within(askCard).getByRole('button', { name: 'Approve' }));

    await waitFor(() =>
      expect(mockedAnswerAsk).toHaveBeenCalledWith('a2', { approved: true, answer: 'text' }),
    );
    expect(mockedApproveApproval).not.toHaveBeenCalled();
  });

  it('Deny on an APPROVAL ask calls rejectApproval', async () => {
    const user = userEvent.setup();
    mockedListAsks.mockResolvedValue([
      mkAsk({ id: 'a1', askType: 'APPROVAL', content: 'ship it' }),
    ]);
    renderDrawer();
    openTaskDrawerEvent();

    const zone = await screen.findByText(/NEEDS YOUR DECISION/);
    const askCard = zone.closest('.decision-zone')!.querySelector('.ask-card') as HTMLElement;
    await user.click(within(askCard).getByRole('button', { name: 'Deny' }));

    await waitFor(() =>
      expect(mockedRejectApproval).toHaveBeenCalledWith('a1', undefined),
    );
    expect(mockedAnswerAsk).not.toHaveBeenCalled();
  });

  it('Approve all resolves every pending ask by its type', async () => {
    const user = userEvent.setup();
    mockedListAsks.mockResolvedValue([
      mkAsk({ id: 'a1', askType: 'APPROVAL', content: 'ship it' }),
      mkAsk({ id: 'a2', askType: 'QUESTION', content: 'UTF-8 or BOM?' }),
    ]);
    renderDrawer();
    openTaskDrawerEvent();

    await screen.findByText(/NEEDS YOUR DECISION/);
    await user.click(screen.getByRole('button', { name: /approve all/i }));

    await waitFor(() =>
      expect(mockedApproveApproval).toHaveBeenCalledWith('a1', undefined),
    );
    await waitFor(() =>
      expect(mockedAnswerAsk).toHaveBeenCalledWith(
        'a2',
        expect.objectContaining({ approved: true }),
      ),
    );
    expect(mockedRejectApproval).not.toHaveBeenCalled();
  });

  it('Request changes (short view) sends the card back to TODO with the typed feedback', async () => {
    const user = userEvent.setup();
    // Ask-less Review card: the short approval view carries the Request-changes affordance.
    mockedGetKanbanItem.mockResolvedValue(mkItem({ status: 'REVIEW' }));
    mockedListAsks.mockResolvedValue([]);
    renderDrawer();
    openTaskDrawerEvent();

    await screen.findByText(/Run completed/);
    await user.type(screen.getByLabelText('Request-changes feedback'), 'fix the flaky test');
    await user.click(screen.getByRole('button', { name: /request changes/i }));

    await waitFor(() =>
      expect(mockedTransition).toHaveBeenCalledWith('task-1', {
        status: 'TODO',
        feedback: 'fix the flaky test',
      }),
    );
  });

  it('short approval view waits for the asks query before rendering (no flash)', async () => {
    let resolveAsks!: (asks: Approval[]) => void;
    mockedGetKanbanItem.mockResolvedValue(mkItem({ status: 'REVIEW' }));
    mockedListAsks.mockReturnValue(new Promise<Approval[]>((res) => { resolveAsks = res; }));
    renderDrawer();
    openTaskDrawerEvent();

    // Item is loaded but asks are still in flight: the ask-less short view
    // must NOT flash in before we know the card truly has no asks.
    await screen.findByText('Spec task');
    expect(screen.queryByText(/Run completed/)).not.toBeInTheDocument();
    expect(document.querySelector('.decision-zone')).toBeNull();

    act(() => resolveAsks([]));
    expect(await screen.findByText(/Run completed/)).toBeInTheDocument();
  });
});
