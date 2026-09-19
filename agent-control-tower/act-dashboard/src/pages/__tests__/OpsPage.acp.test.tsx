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
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import OpsPage from '../OpsPage';
import type { Agent, Approval, DashboardSummary, Run } from '../../types';

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

/* -------------------------------------------------------------------------- */
/*  UX-6: legacy rows gate on expiry — no enabled Approve/Deny past expiresAt  */
/* -------------------------------------------------------------------------- */

describe('OpsPage — legacy rows gate on expiry (UX-6)', () => {
  /**
   * UX-6 regression pin: `expired` used to be scoped to ACP asks only, so a stale
   * PENDING legacy row past its `expiresAt` kept enabled Approve/Deny buttons even
   * though the backend would not honour the decision. `isAskExpired` gates every
   * row that carries an `expiresAt` — the disabled presentation matches ACP rows.
   */
  it('disables Approve and Deny on a PENDING legacy row past its expiresAt', async () => {
    renderOps([
      {
        id: 'legacy-expired',
        runId: 'run-abc',
        toolCallId: null,
        status: 'PENDING',
        reason: 'Agent requests approval to execute deploy',
        requestedAt: new Date().toISOString(),
        decidedAt: null,
        expiresAt: new Date(Date.now() - 60_000).toISOString(),
      },
    ]);

    const approve = await screen.findByRole('button', { name: '✓ Approve' });
    expect(approve).toBeDisabled();
    expect(screen.getByRole('button', { name: '✕ Deny' })).toBeDisabled();
  });

  // Control: the expiry gate must not over-reach — a fresh legacy row stays decidable.
  it('keeps a fresh legacy row decidable', async () => {
    renderOps([
      {
        id: 'legacy-fresh',
        runId: 'run-abc',
        toolCallId: null,
        status: 'PENDING',
        reason: 'Agent requests approval to execute deploy',
        requestedAt: new Date().toISOString(),
        decidedAt: null,
        expiresAt: future(),
      },
    ]);

    const approve = await screen.findByRole('button', { name: '✓ Approve' });
    expect(approve).toBeEnabled();
    expect(screen.getByRole('button', { name: '✕ Deny' })).toBeEnabled();
  });
});

/* -------------------------------------------------------------------------- */
/*  UX-2: requester resolution (runId → agentId bridge) + one line of context  */
/* -------------------------------------------------------------------------- */

const BRIDGE_RUN: Run = {
  id: 'run-abc',
  agentId: 'agent-9',
  status: 'RUNNING',
  promptSeed: 'Draft the Q3 vendor brief',
  maxIterations: 10,
  totalTokensUsed: 0,
  iterationCount: 0,
  errorMessage: null,
  finalOutput: null,
  createdAt: new Date().toISOString(),
  completedAt: null,
};

const REQUESTER: Agent = {
  id: 'agent-9',
  name: 'Atlas QA',
  description: '',
  agentType: 'ADK',
  role: 'qa',
  model: '',
  provider: '',
  healthStatus: 'HEALTHY',
  createdAt: '2026-01-01T00:00:00Z',
};

describe('OpsPage — requester resolution and row context (UX-2)', () => {
  beforeEach(() => {
    // The approval bridges to its requester through the run: an approval
    // carries runId, the run carries agentId, the agent list has the name.
    vi.mocked(listRecentRuns).mockResolvedValue([BRIDGE_RUN]);
    vi.mocked(listAgents).mockResolvedValue([REQUESTER]);
  });

  /** Scoped to the approval row: the run-history table legitimately shows the
   *  same agent name / promptSeed, so document-wide lookups cannot pin the fix.
   *  The matcher runs inside waitFor because the row renders before the runs
   *  query (the requester/context source) resolves. */
  const expectRowText = async (
    approvalId: string,
    matcher: (text: string) => void,
  ) => {
    await waitFor(() => {
      const row = document.querySelector(`[data-approval-id="${approvalId}"]`);
      expect(row).not.toBeNull();
      matcher(row!.textContent ?? '');
    });
  };

  it('resolves the queue requester via runId → agentId instead of "Unknown agent"', async () => {
    const { container } = renderOps([acpAsk()]); // runId 'run-abc' → run.agentId 'agent-9' → Atlas QA

    await expectRowText('acp-1', (t) => expect(t).toContain('Atlas QA'));
    expect(container.querySelector('[data-approval-id="acp-1"]')?.textContent).not.toContain('Unknown agent');
  });

  it('shows the backend-redacted rawInput as the ACP row context', async () => {
    renderOps([acpAsk()]);

    // Verbatim from displayJson — already sanitized by the backend.
    await expectRowText('acp-1', (t) => expect(t).toContain('file_path: /workspace/out.csv'));
  });

  it('falls back to the run promptSeed as the legacy-gate row context', async () => {
    renderOps([
      {
        id: 'legacy-1',
        runId: 'run-abc',
        toolCallId: null,
        status: 'PENDING',
        reason: 'Tool call requires sign-off',
        requestedAt: new Date().toISOString(),
        decidedAt: null,
        expiresAt: future(),
      },
    ]);

    await expectRowText('legacy-1', (t) => expect(t).toContain('Draft the Q3 vendor brief'));
  });
});
