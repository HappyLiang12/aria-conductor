/**
 * C5-fix1 (R31): the decided-ask strip is a view of the card's ask list —
 * server truth — instead of panel memory, so it survives the panel unmount
 * that a decide's own refetch causes (the ask leaves PENDING and the parents
 * swap DecisionPanel for ShortApprovalView).
 *
 * These tests pin the strip's own contract: derivation from the list, the
 * shared wording/tone mapping, Retry semantics (recorded value, FAILED only)
 * and the row-scoped retry error.
 */
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AcpDecidedStrip } from '../AcpDecisionOutcomes';
import type { Approval } from '../../types';

vi.mock('../../api/approvals', () => ({
  approveApproval: vi.fn(),
  rejectApproval: vi.fn(),
}));

import { approveApproval, rejectApproval } from '../../api/approvals';

const mockedApprove = vi.mocked(approveApproval);
const mockedReject = vi.mocked(rejectApproval);

beforeEach(() => vi.clearAllMocks());

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

function renderStrip(asks: Approval[], qc: QueryClient = makeClient()) {
  return render(
    <QueryClientProvider client={qc}>
      <AcpDecidedStrip asks={asks} />
    </QueryClientProvider>,
  );
}

/** displayJson exactly as AcpPermissionCoordinator builds it (JSON string). */
function acpDisplay(toolName = 'Write') {
  return JSON.stringify({
    rawInputTruncated: false,
    grantable: true,
    toolName,
    rawInput: 'file_path: /workspace/out.csv',
    options: [
      { optionId: 'opt-allow', kind: 'allow_once', name: 'Allow once' },
      { optionId: 'opt-deny', kind: 'reject_once', name: 'Deny' },
    ],
  });
}

/** A non-PENDING (decided) ACP ask as `GET /approvals?kanbanItemId=` serves it. */
function decidedAcp(overrides: Partial<Approval> = {}): Approval {
  return {
    id: 'acp-1',
    runId: 'run-1',
    toolCallId: null,
    status: 'APPROVED',
    reason: 'qoder requests Write',
    requestedAt: '2026-09-17T10:00:00Z',
    decidedAt: '2026-09-17T10:05:00Z',
    expiresAt: '2026-09-17T10:10:00Z',
    askType: 'APPROVAL',
    source: 'ACP_PERMISSION',
    deliveryState: 'DELIVERED',
    displayJson: acpDisplay(),
    ...overrides,
  } as Approval;
}

describe('AcpDecidedStrip — outcomes derived from the card ask list', () => {
  it('renders an APPROVED/DELIVERED row without Retry, as a labelled list', () => {
    const { container } = renderStrip([decidedAcp()]);

    expect(screen.getByText('approved · delivered')).toBeInTheDocument();
    expect(document.querySelector('.acp-outcome.ok')).not.toBeNull();
    expect(screen.getByText('Write')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'ACP decision outcomes' })).toBeInTheDocument();
    expect(container.querySelectorAll('[role="listitem"]')).toHaveLength(1);
  });

  it('renders nothing for an empty list and for a pending-only list', () => {
    const empty = renderStrip([]);
    expect(empty.container).toBeEmptyDOMElement();
    empty.unmount();

    const pendingOnly = renderStrip([decidedAcp({ status: 'PENDING', decidedAt: null })]);
    expect(pendingOnly.container).toBeEmptyDOMElement();
  });

  it('offers Retry for a FAILED delivery, re-approves and invalidates the three ask lists', async () => {
    mockedApprove.mockResolvedValue({
      approvalId: 'acp-1',
      approved: true,
      status: 'processed',
      decision: 'APPROVED',
      deliveryState: 'FAILED',
    });
    const qc = makeClient();
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    renderStrip([decidedAcp({ deliveryState: 'FAILED' })], qc);

    expect(screen.getByText('approved · delivery failed')).toBeInTheDocument();
    expect(document.querySelector('.acp-outcome.warn')).not.toBeNull();

    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(mockedApprove).toHaveBeenCalledWith('acp-1'));
    // The retry repeats the recorded decision, never flips it.
    expect(mockedReject).not.toHaveBeenCalled();
    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['kanban'] });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['kanban-items'] });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['approvals'] });
    });
  });

  it('retries a FAILED DENIED ask through reject (the recorded value)', async () => {
    mockedReject.mockResolvedValue({
      approvalId: 'acp-1',
      approved: false,
      status: 'processed',
      decision: 'DENIED',
      deliveryState: 'FAILED',
    });
    renderStrip([decidedAcp({ status: 'DENIED', deliveryState: 'FAILED' })]);

    expect(screen.getByText('denied · delivery failed')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(mockedReject).toHaveBeenCalledWith('acp-1'));
    expect(mockedApprove).not.toHaveBeenCalled();
  });

  it('renders DENIED/CANCELLED as denied · cancelled at the provider without Retry', () => {
    renderStrip([decidedAcp({ status: 'DENIED', deliveryState: 'CANCELLED' })]);

    expect(screen.getByText('denied · cancelled at the provider')).toBeInTheDocument();
    expect(document.querySelector('.acp-outcome.ok')).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });

  it('renders MISSING as no delivery recorded without Retry', () => {
    renderStrip([decidedAcp({ deliveryState: 'MISSING' })]);

    expect(screen.getByText('approved · no delivery recorded')).toBeInTheDocument();
    expect(document.querySelector('.acp-outcome.warn')).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });

  it('caps at the 5 most recent decided ACP asks and never renders legacy asks', () => {
    const asks: Approval[] = [1, 2, 3, 4, 5, 6].map((n) =>
      decidedAcp({
        id: `acp-${n}`,
        decidedAt: `2026-09-17T10:0${n}:00Z`,
        displayJson: acpDisplay(`Tool${n}`),
      }),
    );
    // A decided legacy gate ask in the list must never reach the strip.
    asks.push({
      id: 'legacy-1',
      status: 'APPROVED',
      requestedAt: '2026-09-17T23:00:00Z',
      decidedAt: '2026-09-17T23:00:00Z',
      source: 'LEGACY_GATE',
      deliveryState: 'DELIVERED',
    } as Approval);

    renderStrip(asks);

    expect(document.querySelectorAll('.acp-outcome')).toHaveLength(5);
    // acp-1 is the oldest of six: it fell off the 5-most-recent cap.
    expect(document.querySelector('[data-ask-id="acp-1"]')).toBeNull();
    for (const n of [2, 3, 4, 5, 6]) {
      expect(document.querySelector(`[data-ask-id="acp-${n}"]`)).not.toBeNull();
    }
    expect(screen.queryByText('LEGACY_GATE')).not.toBeInTheDocument();
    expect(document.querySelector('[data-ask-id="legacy-1"]')).toBeNull();
  });

  it('renders the typed code: message inline when a retry is rejected', async () => {
    mockedApprove.mockRejectedValue({
      isAxiosError: true,
      response: {
        status: 409,
        data: { code: 'ALREADY_DECIDED', error: 'ask was decided elsewhere' },
      },
    });
    renderStrip([decidedAcp({ deliveryState: 'FAILED' })]);

    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));

    expect(
      await screen.findByText('ALREADY_DECIDED: ask was decided elsewhere'),
    ).toBeInTheDocument();
    expect(document.querySelector('.acp-outcome.err')).not.toBeNull();
    // The row keeps showing the recorded outcome next to the retry error.
    expect(screen.getByText('approved · delivery failed')).toBeInTheDocument();
  });

  it('renders the generic wording when a retry fails without a typed axios body', async () => {
    mockedApprove.mockRejectedValue(new Error('boom'));
    renderStrip([decidedAcp({ deliveryState: 'FAILED' })]);

    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));

    expect(await screen.findByText('Action rejected — please retry.')).toBeInTheDocument();
    expect(document.querySelector('.acp-outcome.err')).not.toBeNull();
  });
});
