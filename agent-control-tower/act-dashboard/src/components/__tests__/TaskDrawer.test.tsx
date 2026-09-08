import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { TaskDrawer } from '../TaskDrawer';
import { DrawerProvider, TASK_DRAWER_EVENT } from '../DrawerContext';
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

function renderDrawer() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const utils = render(
    <QueryClientProvider client={qc}>
      <DrawerProvider>
        <TaskDrawer />
      </DrawerProvider>
    </QueryClientProvider>,
  );
  return { ...utils, qc };
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

  it('shows no decision zone for a non-REVIEW card', async () => {
    mockedGetKanbanItem.mockResolvedValue(mkItem({ status: 'TODO' }));
    renderDrawer();
    openTaskDrawerEvent();

    await screen.findByText('Spec task');
    expect(screen.queryByText(/NEEDS YOUR DECISION/)).not.toBeInTheDocument();
    expect(document.querySelector('.decision-zone')).toBeNull();
    expect(screen.queryByRole('button', { name: /expand/i })).not.toBeInTheDocument();
  });

  it('expand renders the full-page review workspace; collapse returns to the drawer', async () => {
    const user = userEvent.setup();
    mockedListAsks.mockResolvedValue([
      mkAsk({ id: 'a1', askType: 'APPROVAL', content: 'spec v2' }),
    ]);
    renderDrawer();
    openTaskDrawerEvent();

    await screen.findByText(/NEEDS YOUR DECISION/);
    expect(document.querySelector('.review-fullpage')).toBeNull();

    await user.click(screen.getByRole('button', { name: /expand/i }));
    expect(document.querySelector('.review-fullpage')).not.toBeNull();
    expect(document.querySelector('.spec-review-markdown')).not.toBeNull();

    await user.click(screen.getByRole('button', { name: /collapse/i }));
    expect(document.querySelector('.review-fullpage')).toBeNull();
    // The collapsed drawer is still open with its decision zone.
    expect(screen.getByText(/NEEDS YOUR DECISION/)).toBeInTheDocument();
  });

  it('closes the full-page review once the card leaves REVIEW (ask resolved)', async () => {
    const user = userEvent.setup();
    // Initial load: REVIEW with one ask. After the ask resolves, the
    // invalidated queries refetch: item comes back DONE, asks come back [].
    mockedGetKanbanItem.mockResolvedValueOnce(mkItem());
    mockedGetKanbanItem.mockResolvedValue(mkItem({ status: 'DONE' }));
    mockedListAsks.mockResolvedValueOnce([
      mkAsk({ id: 'a1', askType: 'APPROVAL', content: 'ship it' }),
    ]);
    mockedListAsks.mockResolvedValue([]);
    renderDrawer();
    openTaskDrawerEvent();

    await screen.findByText(/NEEDS YOUR DECISION/);
    await user.click(screen.getByRole('button', { name: /expand/i }));
    const fullpage = document.querySelector('.review-fullpage') as HTMLElement;
    expect(fullpage).not.toBeNull();

    await user.click(within(fullpage.querySelector('.ask-card') as HTMLElement).getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(mockedApproveApproval).toHaveBeenCalledWith('a1', undefined));

    // The stale workspace must not linger with already-decided asks.
    await waitFor(() => expect(document.querySelector('.review-fullpage')).toBeNull());
    // The collapsed drawer itself stays open.
    expect(screen.getByText('Spec task')).toBeInTheDocument();
  });

  it('Escape closes the full-page review workspace but leaves the drawer open', async () => {
    const user = userEvent.setup();
    mockedListAsks.mockResolvedValue([
      mkAsk({ id: 'a1', askType: 'APPROVAL', content: 'ship it' }),
    ]);
    renderDrawer();
    openTaskDrawerEvent();

    await screen.findByText(/NEEDS YOUR DECISION/);
    await user.click(screen.getByRole('button', { name: /expand/i }));
    expect(document.querySelector('.review-fullpage')).not.toBeNull();

    // Escape originates inside the fullpage and bubbles to window, mirroring
    // a real keypress with focus in the review workspace. Only the expanded
    // mode may exit — the drawer itself must stay open.
    act(() => {
      (document.querySelector('.review-fullpage') as HTMLElement).dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
      );
    });

    expect(document.querySelector('.review-fullpage')).toBeNull();
    expect(screen.getByText(/NEEDS YOUR DECISION/)).toBeInTheDocument();
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

  it('Request changes sends the card back to TODO with the typed feedback', async () => {
    const user = userEvent.setup();
    mockedListAsks.mockResolvedValue([
      mkAsk({ id: 'a1', askType: 'APPROVAL', content: 'ship it' }),
    ]);
    renderDrawer();
    openTaskDrawerEvent();

    await screen.findByText(/NEEDS YOUR DECISION/);
    await user.type(screen.getByLabelText('Request-changes feedback'), 'fix the flaky test');
    await user.click(screen.getByRole('button', { name: /request changes/i }));

    await waitFor(() =>
      expect(mockedTransition).toHaveBeenCalledWith('task-1', {
        status: 'TODO',
        feedback: 'fix the flaky test',
      }),
    );
  });

  it('navigates to the next REVIEW sibling from the kanban-items cache', async () => {
    const user = userEvent.setup();
    mockedGetKanbanItem.mockResolvedValue(mkItem());
    const { qc } = renderDrawer();
    qc.setQueryData(['kanban-items'], [
      mkItem(),
      mkItem({ id: 'task-2', title: 'Second review' }),
      mkItem({ id: 'task-3', status: 'TODO' }),
    ]);
    openTaskDrawerEvent();

    await screen.findByText('Spec task');
    // task-1 is the first REVIEW sibling: prev disabled, next targets task-2.
    expect(screen.getByRole('button', { name: /prev/i })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: /next/i }));

    await waitFor(() => expect(mockedGetKanbanItem).toHaveBeenCalledWith('task-2'));
  });
});
