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
 * - a decided ask leaves the PENDING list, so the outcome lives in a durable
 *   strip below the ask cards, not only on the card.
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

    expect(screen.getByText('ACP permission')).toBeInTheDocument();
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

  it('shows Expired, disables both buttons and never calls the api once expired', async () => {
    renderPanel(
      <DecisionPanel
        item={item}
        pendingAsks={[acpAsk({ expiresAt: new Date(Date.now() - 60_000).toISOString() })]}
      />,
    );

    expect(screen.getByText('Expired')).toBeInTheDocument();
    const allow = screen.getByRole('button', { name: 'Allow once' });
    const deny = screen.getByRole('button', { name: 'Deny' });
    expect(allow).toBeDisabled();
    expect(deny).toBeDisabled();

    await userEvent.click(allow);
    await userEvent.click(deny);
    expect(mockedApprove).not.toHaveBeenCalled();
    expect(mockedReject).not.toHaveBeenCalled();
  });

  it('routes Allow once to /decide and shows the delivered outcome in the strip', async () => {
    mockedApprove.mockResolvedValue({
      approvalId: 'acp-1',
      approved: true,
      status: 'processed',
      decision: 'APPROVED',
      deliveryState: 'DELIVERED',
    });
    renderPanel(<DecisionPanel item={item} pendingAsks={[acpAsk()]} />);

    await userEvent.click(screen.getByRole('button', { name: 'Allow once' }));
    await waitFor(() => expect(mockedApprove).toHaveBeenCalledWith('acp-1', undefined));

    expect(await screen.findByText('approved · delivered')).toBeInTheDocument();
    expect(document.querySelector('.acp-outcome.ok')).not.toBeNull();
  });

  it('surfaces a typed 409 in the strip, never as success, and invalidates the ask lists', async () => {
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
    expect(document.querySelector('.acp-outcome.err')).not.toBeNull();
    expect(screen.queryByText(/approved ·/)).not.toBeInTheDocument();
    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['kanban'] });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['kanban-items'] });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['approvals'] });
    });
  });

  it('shows the ERROR chip when the rejection is not a typed axios body', async () => {
    mockedReject.mockRejectedValue(new Error('boom'));
    renderPanel(<DecisionPanel item={item} pendingAsks={[acpAsk()]} />);

    await userEvent.click(screen.getByRole('button', { name: 'Deny' }));

    expect(
      await screen.findByText('ERROR: Action rejected — please retry.'),
    ).toBeInTheDocument();
    expect(mockedReject).toHaveBeenCalledWith('acp-1', undefined);
  });

  it('shows a warn chip with Retry for FAILED delivery and re-decides on retry', async () => {
    mockedApprove.mockResolvedValue({
      approvalId: 'acp-1',
      approved: true,
      status: 'processed',
      decision: 'APPROVED',
      deliveryState: 'FAILED',
    });
    renderPanel(<DecisionPanel item={item} pendingAsks={[acpAsk()]} />);

    await userEvent.click(screen.getByRole('button', { name: 'Allow once' }));
    expect(await screen.findByText('approved · delivery failed')).toBeInTheDocument();
    expect(document.querySelector('.acp-outcome.warn')).not.toBeNull();

    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(mockedApprove).toHaveBeenCalledTimes(2));
    expect(mockedApprove).toHaveBeenNthCalledWith(2, 'acp-1', undefined);
    // The retry must reuse the original decision, not flip it.
    expect(mockedReject).not.toHaveBeenCalled();
  });

  it('renders the MISSING warn text without a Retry button', async () => {
    mockedApprove.mockResolvedValue({
      approvalId: 'acp-1',
      approved: true,
      status: 'processed',
      decision: 'APPROVED',
      deliveryState: 'MISSING',
    });
    renderPanel(<DecisionPanel item={item} pendingAsks={[acpAsk()]} />);

    await userEvent.click(screen.getByRole('button', { name: 'Allow once' }));
    expect(await screen.findByText('approved · no delivery recorded')).toBeInTheDocument();
    expect(document.querySelector('.acp-outcome.warn')).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });

  it('shows the denied outcome text for a DENIED decision', async () => {
    mockedReject.mockResolvedValue({
      approvalId: 'acp-1',
      approved: false,
      status: 'processed',
      decision: 'DENIED',
      deliveryState: 'CANCELLED',
    });
    renderPanel(<DecisionPanel item={item} pendingAsks={[acpAsk()]} />);

    await userEvent.click(screen.getByRole('button', { name: 'Deny' }));
    expect(await screen.findByText('denied · cancelled at the provider')).toBeInTheDocument();
    expect(document.querySelector('.acp-outcome.ok')).not.toBeNull();
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
