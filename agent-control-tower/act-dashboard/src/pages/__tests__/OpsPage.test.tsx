import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import OpsPage from '../OpsPage';
import client from '../../api/client';
import { OPERATOR_CSRF_HEADER, OPERATOR_SESSION_STORAGE_KEY } from '../../api/operatorSession';
import { formatTimestamp } from '../../utils/formatTime';
import type { Approval } from '../../types';

// ---------------------------------------------------------------------------
// Task 15 fix round 1 (I1): native permission asks reach the Ops pending
// surface too. It must render the normalized kind/expiry exactly like the
// Review panels, and its /decide calls must carry the operator session's CSRF
// header (the route is operator-only since Task 12).
// ---------------------------------------------------------------------------

vi.mock('../../api/ops', () => ({
  listRecentRuns: vi.fn().mockResolvedValue([]),
  getOpsSummary: vi.fn().mockResolvedValue({
    activeAgents: 0,
    runningRuns: 0,
    pendingApprovals: 0,
    totalTokensBurned: 0,
  }),
  getOpsActivity: vi.fn().mockResolvedValue([]),
}));
vi.mock('../../api/agents', () => ({
  listAgents: vi.fn().mockResolvedValue([]),
}));
vi.mock('../../components/HousekeepingPanel', () => ({ default: () => null }));

const NATIVE_ASK_REASON =
  'Native permission request req-42 from session sess-7 for tool write_file (NATIVE_TOOL)';
const NATIVE_ASK_EXPIRES_AT = '2026-09-22T12:05:00Z';

function nativeAsk(): Approval {
  return {
    id: 'a-native',
    runId: 'run-abc',
    toolCallId: null,
    status: 'PENDING',
    reason: NATIVE_ASK_REASON,
    requestedAt: '2026-09-22T11:55:00Z',
    decidedAt: null,
    expiresAt: NATIVE_ASK_EXPIRES_AT,
    askType: 'APPROVAL',
  } as Approval;
}

function gateAsk(): Approval {
  return {
    id: 'a-gate',
    runId: 'run-abc',
    toolCallId: 'tc-1',
    status: 'PENDING',
    reason: 'Task-level approval',
    requestedAt: '2026-09-22T11:55:00Z',
    decidedAt: null,
    expiresAt: NATIVE_ASK_EXPIRES_AT,
    askType: 'APPROVAL',
  } as Approval;
}

/** The shared axios adapter: serves the pending list and records every request. */
const originalAdapter = client.defaults.adapter;
const requests: InternalAxiosRequestConfig[] = [];

function serve(approvals: Approval[]) {
  requests.length = 0;
  client.defaults.adapter = async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    requests.push(config);
    if (config.method === 'get' && config.url === '/api/v1/approvals') {
      return { data: approvals, status: 200, statusText: 'OK', headers: {}, config };
    }
    if (config.method === 'post' && config.url?.startsWith('/api/v1/approvals/') && config.url.endsWith('/decide')) {
      const approvalId = config.url.split('/')[4];
      return {
        data: { approvalId, approved: true, status: 'processed' },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      };
    }
    throw new Error(`unexpected request: ${config.method} ${config.url}`);
  };
}

function ui() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <OpsPage />
    </QueryClientProvider>,
  );
}

describe('OpsPage normalized native asks (Task 15 fix round 1)', () => {
  beforeEach(() => {
    requests.length = 0;
    localStorage.clear();
    delete client.defaults.headers.common[OPERATOR_CSRF_HEADER];
  });

  afterEach(() => {
    client.defaults.adapter = originalAdapter;
    localStorage.clear();
    delete client.defaults.headers.common[OPERATOR_CSRF_HEADER];
  });

  it('renders the permission kind, tool, request and expiry of a native ask exactly as the panels do', async () => {
    serve([nativeAsk(), gateAsk()]);
    ui();

    expect(await screen.findByText('Native permission · NATIVE_TOOL')).toBeInTheDocument();
    expect(screen.getByText('tool write_file')).toBeInTheDocument();
    expect(screen.getByText('request req-42')).toBeInTheDocument();
    expect(
      screen.getByText(`expires ${formatTimestamp(NATIVE_ASK_EXPIRES_AT)}`),
    ).toBeInTheDocument();
    // A gate ask keeps its generic kind pill.
    expect(screen.getByText('Action')).toBeInTheDocument();
  });

  it('carries the operator session CSRF header on the native ask decision', async () => {
    serve([nativeAsk()]);
    localStorage.setItem(
      OPERATOR_SESSION_STORAGE_KEY,
      JSON.stringify({
        csrfToken: 'csrf-fixture-token',
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
      }),
    );
    ui();
    await screen.findByText('Native permission · NATIVE_TOOL');

    const card = document.querySelector('[data-approval-id="a-native"]') as HTMLElement;
    await userEvent.click(within(card).getByRole('button', { name: /approve/i }));

    await waitFor(() =>
      expect(requests.some((r) => r.url === '/api/v1/approvals/a-native/decide')).toBe(true),
    );
    const decide = requests.find((r) => r.url === '/api/v1/approvals/a-native/decide');
    expect(decide?.headers.get(OPERATOR_CSRF_HEADER)).toBe('csrf-fixture-token');
  });

  it('carries the operator session CSRF header on every decide, not only native asks', async () => {
    serve([gateAsk()]);
    localStorage.setItem(
      OPERATOR_SESSION_STORAGE_KEY,
      JSON.stringify({
        csrfToken: 'csrf-fixture-token',
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
      }),
    );
    ui();
    await screen.findByText('Task-level approval');

    const card = document.querySelector('[data-approval-id="a-gate"]') as HTMLElement;
    await userEvent.click(within(card).getByRole('button', { name: /deny/i }));

    await waitFor(() =>
      expect(requests.some((r) => r.url === '/api/v1/approvals/a-gate/decide')).toBe(true),
    );
    const decide = requests.find((r) => r.url === '/api/v1/approvals/a-gate/decide');
    expect(decide?.headers.get(OPERATOR_CSRF_HEADER)).toBe('csrf-fixture-token');
  });
});

// Task 10: the two-column Ops layout must collapse to one column at 1100px.
// The stylesheet already has that rule; an inline track list on the grid would
// beat it at every width, so the inline style must stay gone.
describe('OpsPage responsive stacking (Task 10)', () => {
  beforeEach(() => {
    localStorage.clear();
    serve([]);
  });

  afterEach(() => {
    client.defaults.adapter = originalAdapter;
    localStorage.clear();
  });

  it('does not pin the Ops columns inline', () => {
    const { container } = ui();
    const grid = container.querySelector('.ops-grid-2') as HTMLElement;
    expect(grid).not.toBeNull();
    expect(grid.style.gridTemplateColumns).toBe('');
  });
});

// ---------------------------------------------------------------------------
// Task 37 / Item 6 (T35 audit row 14): the App KPIs pending panel is the Ops
// approve/deny surface. A refusal used to reach the operator as an error toast
// reading "Approve failed. Retry." — server text, no remedy, and advice that
// cannot work when the route refused for want of the operator.
//
// Row 14 also claimed an "answer" refusal at :420. There is no answer mutation
// on this page: a CLARIFICATION row points at the kanban card instead. That row
// of the audit was wrong and nothing was invented for it.
// ---------------------------------------------------------------------------

/** The shared adapter, with the decide route answering the given failure. */
function serveDecideFailure(approvals: Approval[], status: number, message: string) {
  requests.length = 0;
  client.defaults.adapter = async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    requests.push(config);
    if (config.method === 'get' && config.url === '/api/v1/approvals') {
      return { data: approvals, status: 200, statusText: 'OK', headers: {}, config };
    }
    throw Object.assign(new Error('request failed'), {
      response: { status, data: { message }, statusText: 'Refused', headers: {}, config },
    });
  };
}

describe('OpsPage operator refusals (Task 37)', () => {
  beforeEach(() => {
    requests.length = 0;
    localStorage.clear();
    delete client.defaults.headers.common[OPERATOR_CSRF_HEADER];
  });

  afterEach(() => {
    client.defaults.adapter = originalAdapter;
    localStorage.clear();
    delete client.defaults.headers.common[OPERATOR_CSRF_HEADER];
  });

  it('shows the operator notice in the pending panel for a refused approve, not a retry toast', async () => {
    serveDecideFailure([gateAsk()], 403, 'OPS_RAW_SERVER_TEXT');
    ui();

    const panel = await screen.findByRole('region', { name: /pending approvals/i });
    await userEvent.click(await within(panel).findByRole('button', { name: /approve/i }));

    const notice = await within(panel).findByTestId('operator-session-notice');
    expect(notice).toBeVisible();
    expect(notice).toHaveTextContent(/approving the ask is operator-only/i);
    expect(notice).toHaveTextContent(/loopback requests are the operator automatically/i);
    // Neither the server's wording nor the dead-end "Retry." advice survives.
    expect(screen.queryByText(/OPS_RAW_SERVER_TEXT/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Approve failed/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Retry\./)).not.toBeInTheDocument();
  });

  it('names denying for a refused deny', async () => {
    serveDecideFailure([gateAsk()], 401, 'OPS_RAW_SERVER_TEXT');
    ui();

    const panel = await screen.findByRole('region', { name: /pending approvals/i });
    await userEvent.click(await within(panel).findByRole('button', { name: /deny/i }));

    expect(await within(panel).findByTestId('operator-session-notice'))
      .toHaveTextContent(/denying the ask is operator-only/i);
  });

  // Positive control: a 500 is a fault, not an authority problem.
  it('keeps the server text in a toast for a 500 approve and shows no notice', async () => {
    serveDecideFailure([gateAsk()], 500, 'approval store unavailable');
    ui();

    const panel = await screen.findByRole('region', { name: /pending approvals/i });
    await userEvent.click(await within(panel).findByRole('button', { name: /approve/i }));

    expect(await screen.findByText('approval store unavailable')).toBeInTheDocument();
    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
  });
});
