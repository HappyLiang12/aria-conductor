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
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ReviewQueue from '../ReviewQueue';
import type { Approval } from '../../types';

vi.mock('../../api/approvals', () => ({
  listApprovals: vi.fn(),
  approveApproval: vi.fn(),
  rejectApproval: vi.fn(),
}));

import { listApprovals } from '../../api/approvals';

const mockedList = vi.mocked(listApprovals);

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
    // The explanation is the non-actionable affordance offered in place of the
    // refused approval: it must sit on the truncated ask's row — the one whose
    // Allow once is disabled. (A click on a disabled control cannot dispatch, so
    // clicking it would pin nothing.)
    expect(allowButtons[0].closest('.qitem')?.textContent).toContain('cannot be approved');
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
    // Same pairing as the truncated case: the explanation must sit on the
    // flag-missing ask's row — the one whose Allow once is disabled. (A click on
    // a disabled control cannot dispatch, so it would pin nothing.)
    expect(allowButtons[0].closest('.qitem')?.textContent).toContain('cannot be approved');
  });
});

describe('ReviewQueue — legacy rows gate on expiry (UX-6)', () => {
  /**
   * UX-6 regression pin: the expiry gate used to be scoped to ACP asks only, so a stale
   * PENDING legacy row past its `expiresAt` kept enabled Approve/Deny buttons even though
   * the backend would not honour the decision. `isAskExpired` gates every row that carries
   * an `expiresAt` — the disabled-state presentation matches the ACP rows.
   */
  it('disables Approve and Deny on a PENDING legacy row past its expiresAt', async () => {
    renderQueue([
      {
        id: 'legacy-expired',
        runId: 'run-abc',
        toolCallId: null,
        status: 'PENDING',
        reason: 'Agent requests approval to execute deploy',
        requestedAt: new Date().toISOString(),
        decidedAt: null,
        expiresAt: new Date(Date.now() - 60_000).toISOString(),
        toolName: 'deploy',
      } as Approval,
    ]);

    const approve = await screen.findByRole('button', { name: 'Approve' });
    expect(approve).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Deny' })).toBeDisabled();
  });

  // Control: the expiry gate must not over-reach — a fresh legacy row stays decidable.
  it('keeps a fresh legacy row decidable', async () => {
    renderQueue([
      {
        id: 'legacy-fresh',
        runId: 'run-abc',
        toolCallId: null,
        status: 'PENDING',
        reason: 'Agent requests approval to execute deploy',
        requestedAt: new Date().toISOString(),
        decidedAt: null,
        expiresAt: future(),
        toolName: 'deploy',
      } as Approval,
    ]);

    const approve = await screen.findByRole('button', { name: 'Approve' });
    expect(approve).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Deny' })).toBeEnabled();
  });
});
