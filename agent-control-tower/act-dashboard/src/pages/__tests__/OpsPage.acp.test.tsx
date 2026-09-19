/**
 * F3/R4 ops-surface pin: the Pending Approvals queue must not offer an enabled
 * "Allow once" for an undecidable ACP ask (bridge-truncated input, or a display
 * record that cannot prove decidability) — the backend refuses that approval
 * with a typed 409 UNDECIDABLE_ASK before any transition or grant. Deny still
 * works.
 *
 * Fixtures/harness mirror ReviewPanels.acp.test.tsx so the three review
 * surfaces pin the same wire shapes.
 */
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import OpsPage from '../OpsPage';
import type { Approval, DashboardSummary } from '../../types';

vi.mock('../../api/ops', () => ({
  listRecentRuns: vi.fn(),
  getOpsSummary: vi.fn(),
  getOpsActivity: vi.fn(),
}));
vi.mock('../../api/approvals', () => ({
  listApprovals: vi.fn(),
  approveApproval: vi.fn(),
  rejectApproval: vi.fn(),
}));
vi.mock('../../api/agents', () => ({ listAgents: vi.fn() }));
// HousekeepingPanel reads the websocket context and scans on mount — out of scope here.
vi.mock('../../components/HousekeepingPanel', () => ({
  default: () => <div data-testid="housekeeping-panel" />,
}));

import { listApprovals } from '../../api/approvals';
import { listRecentRuns, getOpsSummary, getOpsActivity } from '../../api/ops';
import { listAgents } from '../../api/agents';

const mockedList = vi.mocked(listApprovals);

const SUMMARY: DashboardSummary = {
  activeAgents: 0,
  healthyAgents: 0,
  degradedAgents: 0,
  runningRuns: 0,
  pendingApprovals: 2,
  totalTokensBurned: 0,
};

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(listRecentRuns).mockResolvedValue([]);
  vi.mocked(getOpsSummary).mockResolvedValue(SUMMARY);
  vi.mocked(getOpsActivity).mockResolvedValue([]);
  vi.mocked(listAgents).mockResolvedValue([]);
});

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

function renderOps(asks: Approval[]) {
  mockedList.mockResolvedValue(asks);
  return render(
    <QueryClientProvider client={makeClient()}>
      <OpsPage />
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

describe('OpsPage — undecidable ACP asks in the pending queue', () => {
  it('disables Allow once with the explanation for a truncated ask, while a decidable ask stays approvable', async () => {
    renderOps([
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
    renderOps([
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
