/**
 * C5: ACP permission asks on the Review decision surface.
 *
 * Wire facts under test (verified against the C3 backend):
 * - an ACP ask is `source: 'ACP_PERMISSION'` with `askType: 'APPROVAL'`;
 * - `displayJson` is a JSON string `{rawInputTruncated, grantable, toolName,
 *   rawInput, options[]}` already sanitized/redacted by the backend — the UI
 *   renders it verbatim;
 * - the decision goes through `approveApproval`/`rejectApproval` (`/decide`)
 *   regardless of `askType`; ACP receipts carry `decision` + `deliveryState`;
 * - a rejected decide answers 409 `{code, error}` (axios error, body reachable);
 * - C5-fix1: a decided ask leaves the PENDING list, so this panel keeps NO
 *   outcome record — the strip is derived from the card's ask list by the
 *   parent surfaces (`AcpDecidedStrip`). All this panel does with a success or
 *   a typed 409 is invalidate the three ask lists / show the error text.
 *
 * Harness mirrors ReviewPanels.test.tsx (the legacy-behavior pin, which must
 * stay untouched and green).
 */
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { DecisionPanel } from '../ReviewPanels';
import type { Approval, KanbanItem } from '../../types';

vi.mock('../../api/kanban', () => ({
  transitionKanbanItem: vi.fn(),
}));
vi.mock('../../api/approvals', () => ({
  answerAsk: vi.fn(),
  approveApproval: vi.fn(),
  rejectApproval: vi.fn(),
}));

import { approveApproval, rejectApproval, answerAsk } from '../../api/approvals';

const mockedApprove = vi.mocked(approveApproval);
const mockedReject = vi.mocked(rejectApproval);
const mockedAnswer = vi.mocked(answerAsk);

const item = {
  id: 'k-1',
  title: 'add CSV export',
  status: 'REVIEW',
  assignee: 'dev-agent',
  linkedRunId: 'run-abc',
} as KanbanItem;

beforeEach(() => vi.clearAllMocks());

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

function renderPanel(ui: ReactElement, qc: QueryClient = makeClient()) {
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>);
}

const future = () => new Date(Date.now() + 10 * 60_000).toISOString();

/** displayJson exactly as AcpPermissionCoordinator builds it (JSON string). */
function acpDisplay(overrides: Record<string, unknown> = {}) {
  return JSON.stringify({
    rawInputTruncated: false,
    grantable: true,
    toolName: 'Write',
    rawInput: 'file_path: /workspace/out.csv',
    options: [
      { optionId: 'opt-allow', kind: 'allow_once', name: 'Allow once' },
      { optionId: 'opt-deny', kind: 'reject_once', name: 'Deny' },
    ],
    ...overrides,
  });
}

function acpAsk(overrides: Partial<Approval> = {}): Approval {
  return {
    id: 'acp-1',
    runId: 'run-abc',
    toolCallId: null,
    status: 'PENDING',
    reason: 'qoder requests Write',
    requestedAt: new Date().toISOString(),
    decidedAt: null,
    expiresAt: future(),
    askType: 'APPROVAL',
    source: 'ACP_PERMISSION',
    displayJson: acpDisplay(),
    ...overrides,
  } as Approval;
}

function legacyAsk(overrides: Partial<Approval> = {}): Approval {
  return {
    id: 'legacy-1',
    askType: 'APPROVAL',
    content: 'spec v2',
    status: 'PENDING',
    ...overrides,
  } as Approval;
}

describe('DecisionPanel — ACP permission asks', () => {
  it('renders the badge, tool, preview and ACP controls for a pending ACP ask', () => {
    renderPanel(<DecisionPanel item={item} pendingAsks={[acpAsk()]} />);

    // The badge must carry the .pill.acp pair the e2e and the Ops row use.
    const badge = screen.getByText('ACP permission');
    expect(badge).toBeInTheDocument();
    expect(badge.className).toContain('pill');
    expect(badge.className).toContain('acp');
    expect(screen.getByText('Write')).toBeInTheDocument();
    expect(screen.getByText('file_path: /workspace/out.csv')).toBeInTheDocument();
    // Options summary is rendered as text lines from displayJson.options.
    expect(screen.getByText(/opt-allow/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Allow once' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Deny' })).toBeEnabled();
    // The legacy card pair + answer box must not render for the ACP ask.
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Answer for ask acp-1')).not.toBeInTheDocument();
  });

  it('omits Allow once, shows the hint, and keeps Deny enabled when no allow_once option exists', () => {
    renderPanel(
      <DecisionPanel
        item={item}
        pendingAsks={[
          acpAsk({
            displayJson: acpDisplay({
              grantable: false,
              options: [{ optionId: 'opt-deny', kind: 'reject_once', name: 'Deny' }],
            }),
          }),
        ]}
      />,
    );

    expect(screen.queryByRole('button', { name: 'Allow once' })).not.toBeInTheDocument();
    expect(screen.getByText('No allow-once option on this ask')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Deny' })).toBeEnabled();
  });

  it('does not offer Allow once for an undecidable (truncated) ask, while a decidable ask stays approvable', () => {
    renderPanel(
      <DecisionPanel
        item={item}
        pendingAsks={[
          acpAsk({
            id: 'acp-trunc',
            displayJson: acpDisplay({ rawInputTruncated: true, grantable: false }),
          }),
          acpAsk({ id: 'acp-decidable' }),
        ]}
      />,
    );

    const allowButtons = screen.getAllByRole('button', { name: 'Allow once' });
    expect(allowButtons).toHaveLength(2);
    // Asks render in list order: the truncated one cannot be approved, the plain one still can
    // (the backend refuses a truncated approval with UNDECIDABLE_ASK).
    expect(allowButtons[0]).toBeDisabled();
    expect(allowButtons[1]).toBeEnabled();
    // The explanation names the reason and the action that still works.
    expect(screen.getByText(/cannot be approved/)).toBeInTheDocument();
    expect(screen.getByText(/Deny still works/)).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Deny' })[0]).toBeEnabled();
    // The explanation is the non-actionable affordance offered in place of the
    // refused approval: it must sit on the undecidable ask's card — the one whose
    // Allow once is disabled. (A click on a disabled control cannot dispatch, so
    // clicking it would pin nothing.)
    expect(allowButtons[0].closest('.ask-card')?.textContent).toContain('cannot be approved');
  });

  it('treats an absent truncation flag as undecidable: only an explicit false is decidable', () => {
    renderPanel(
      <DecisionPanel
        item={item}
        pendingAsks={[
          // The backend predicate reads a missing flag as truncated (fail closed), so the ask
          // must not be approvable here either — only `rawInputTruncated: false` is decidable.
          acpAsk({
            id: 'acp-flag-missing',
            displayJson: acpDisplay({ rawInputTruncated: undefined }),
          }),
          acpAsk({ id: 'acp-explicit-false', displayJson: acpDisplay() }),
        ]}
      />,
    );

    const allowButtons = screen.getAllByRole('button', { name: 'Allow once' });
    expect(allowButtons).toHaveLength(2);
    expect(allowButtons[0]).toBeDisabled();
    expect(allowButtons[1]).toBeEnabled();
    expect(screen.getByText(/cannot be approved/)).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Deny' })[0]).toBeEnabled();
    // Same pairing as the truncated case: the explanation must sit on the
    // flag-missing ask's card — the one whose Allow once is disabled. (A click on
    // a disabled control cannot dispatch, so it would pin nothing.)
    expect(allowButtons[0].closest('.ask-card')?.textContent).toContain('cannot be approved');
  });

  it('treats an absent display as undecidable: no Allow once is offered, Deny still works', () => {
    renderPanel(
      <DecisionPanel item={item} pendingAsks={[acpAsk({ id: 'acp-no-display', displayJson: null })]} />,
    );

    expect(screen.queryByRole('button', { name: 'Allow once' })).not.toBeInTheDocument();
    expect(screen.getByText(/cannot be approved/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Deny' })).toBeEnabled();
  });

  it('shows Expired and disables both buttons on the expired ask card', () => {
    renderPanel(
      <DecisionPanel
        item={item}
        pendingAsks={[acpAsk({ expiresAt: new Date(Date.now() - 60_000).toISOString() })]}
      />,
    );

    expect(screen.getByText('Expired')).toBeInTheDocument();
    const allow = screen.getByRole('button', { name: 'Allow once' });
    const deny = screen.getByRole('button', { name: 'Deny' });
    // The expired marker and the two inert controls are the whole observable on
    // this surface, so the disabled pair is the pin — clicking a disabled control
    // cannot dispatch and would assert nothing.
    expect(allow).toBeDisabled();
    expect(deny).toBeDisabled();
  });

  it('routes Allow once to /decide, invalidates the ask lists and keeps no panel-local strip', async () => {
    mockedApprove.mockResolvedValue({
      approvalId: 'acp-1',
      approved: true,
      status: 'processed',
      decision: 'APPROVED',
      deliveryState: 'DELIVERED',
    });
    const qc = makeClient();
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    renderPanel(<DecisionPanel item={item} pendingAsks={[acpAsk()]} />, qc);

    await userEvent.click(screen.getByRole('button', { name: 'Allow once' }));
    await waitFor(() => expect(mockedApprove).toHaveBeenCalledWith('acp-1', undefined));

    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['kanban'] });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['kanban-items'] });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['approvals'] });
    });
    // C5-fix1: the strip is derived from the card ask list by the parent — the
    // panel must not grow its own (it would die with the next refetch).
    expect(document.querySelector('.acp-outcomes')).toBeNull();
    expect(screen.queryByText(/approved ·/)).not.toBeInTheDocument();
  });

  it('surfaces a typed 409 as the inline panel error, never as success, and invalidates the ask lists', async () => {
    mockedApprove.mockRejectedValue({
      isAxiosError: true,
      response: {
        status: 409,
        data: { code: 'ALREADY_DECIDED', error: 'ask was decided elsewhere' },
      },
    });
    const qc = makeClient();
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    renderPanel(<DecisionPanel item={item} pendingAsks={[acpAsk()]} />, qc);

    await userEvent.click(screen.getByRole('button', { name: 'Allow once' }));

    expect(
      await screen.findByText('ALREADY_DECIDED: ask was decided elsewhere'),
    ).toBeInTheDocument();
    expect(document.querySelector('.acp-outcome')).toBeNull();
    expect(screen.queryByText(/approved ·/)).not.toBeInTheDocument();
    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['kanban'] });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['kanban-items'] });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['approvals'] });
    });
  });

  it('falls back to the generic error text for an untyped ACP rejection', async () => {
    mockedReject.mockRejectedValue(new Error('boom'));
    renderPanel(<DecisionPanel item={item} pendingAsks={[acpAsk()]} />);

    await userEvent.click(screen.getByRole('button', { name: 'Deny' }));

    expect(
      await screen.findByText('Action rejected — please retry.'),
    ).toBeInTheDocument();
    expect(mockedReject).toHaveBeenCalledWith('acp-1', undefined);
  });

  it('Approve all iterates legacy asks only', async () => {
    mockedApprove.mockResolvedValue({
      approvalId: 'legacy-1',
      approved: true,
      status: 'processed',
    });
    renderPanel(<DecisionPanel item={item} pendingAsks={[legacyAsk(), acpAsk()]} />);

    await userEvent.click(screen.getByRole('button', { name: /approve all/i }));

    await waitFor(() => expect(mockedApprove).toHaveBeenCalledWith('legacy-1', undefined));
    expect(mockedApprove).toHaveBeenCalledTimes(1);
  });

  it('does not render Approve all when only ACP asks are pending', () => {
    renderPanel(<DecisionPanel item={item} pendingAsks={[acpAsk()]} />);
    expect(screen.queryByRole('button', { name: /approve all/i })).not.toBeInTheDocument();
  });

  it('renders a source-less ask exactly like the legacy card (no ACP affordances)', () => {
    renderPanel(<DecisionPanel item={item} pendingAsks={[legacyAsk()]} />);

    expect(screen.getByText(/spec v2/)).toBeInTheDocument();
    expect(screen.getByLabelText('Answer for ask legacy-1')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Approve' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Deny' })).toBeEnabled();
    expect(screen.queryByText('ACP permission')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Allow once' })).not.toBeInTheDocument();
    expect(mockedAnswer).not.toHaveBeenCalled();
  });
});
