/**
 * F3/R4 review-queue pin: an undecidable ACP ask (bridge-truncated input, or a
 * display record that cannot prove decidability) must not offer an enabled
 * "Allow once" — the backend refuses that approval with a typed 409
 * UNDECIDABLE_ASK before any transition or grant. Deny still works.
 *
 * Fixtures/harness mirror ReviewPanels.acp.test.tsx so the three review
 * surfaces pin the same wire shapes.
 */
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ReviewQueue from '../ReviewQueue';
import type { Approval } from '../../types';

vi.mock('../../api/approvals', () => ({
  listApprovals: vi.fn(),
  approveApproval: vi.fn(),
  rejectApproval: vi.fn(),
}));

import { listApprovals, approveApproval, rejectApproval } from '../../api/approvals';

const mockedList = vi.mocked(listApprovals);
const mockedApprove = vi.mocked(approveApproval);
const mockedReject = vi.mocked(rejectApproval);

beforeEach(() => vi.clearAllMocks());

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

function renderQueue(asks: Approval[]) {
  mockedList.mockResolvedValue(asks);
  return render(
    <QueryClientProvider client={makeClient()}>
      <ReviewQueue />
    </QueryClientProvider>,
  );
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

describe('ReviewQueue — undecidable ACP asks', () => {
  it('disables Allow once with the explanation for a truncated ask, while a decidable ask stays approvable', async () => {
    renderQueue([
      acpAsk({
        id: 'acp-trunc',
        displayJson: acpDisplay({ rawInputTruncated: true, grantable: false }),
      }),
      acpAsk({ id: 'acp-decidable' }),
    ]);

    const allowButtons = await screen.findAllByRole('button', { name: 'Allow once' });
    expect(allowButtons).toHaveLength(2);
    // Rows render in list order: the truncated ask cannot be approved (the backend
    // refuses it with UNDECIDABLE_ASK), the plain one still can.
    expect(allowButtons[0]).toBeDisabled();
    expect(allowButtons[1]).toBeEnabled();
    // The explanation names the reason and the action that still works.
    expect(screen.getByText(/cannot be approved/)).toBeInTheDocument();
    expect(screen.getByText(/Deny still works/)).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Deny' })[0]).toBeEnabled();

    await userEvent.click(allowButtons[0]);
    expect(mockedApprove).not.toHaveBeenCalled();
    expect(mockedReject).not.toHaveBeenCalled();
  });

  it('treats an absent truncation flag as undecidable: only an explicit false is decidable', async () => {
    renderQueue([
      // The backend predicate reads a missing flag as truncated (fail closed), so the ask
      // must not be approvable here either — only `rawInputTruncated: false` is decidable.
      acpAsk({
        id: 'acp-flag-missing',
        displayJson: acpDisplay({ rawInputTruncated: undefined }),
      }),
      acpAsk({ id: 'acp-explicit-false', displayJson: acpDisplay() }),
    ]);

    const allowButtons = await screen.findAllByRole('button', { name: 'Allow once' });
    expect(allowButtons).toHaveLength(2);
    expect(allowButtons[0]).toBeDisabled();
    expect(allowButtons[1]).toBeEnabled();
    expect(screen.getByText(/cannot be approved/)).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Deny' })[0]).toBeEnabled();

    await userEvent.click(allowButtons[0]);
    expect(mockedApprove).not.toHaveBeenCalled();
  });
});
