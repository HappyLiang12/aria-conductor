import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import ReviewQueue from '../ReviewQueue';
import client from '../../api/client';
import { OPERATOR_CSRF_HEADER, OPERATOR_SESSION_STORAGE_KEY } from '../../api/operatorSession';
import { formatTimestamp } from '../../utils/formatTime';
import type { Approval } from '../../types';

vi.mock('../DiffPreview', () => ({ default: () => null }));

// ---------------------------------------------------------------------------
// Task 15 fix round 1 (I1): native permission asks reach this global pending
// surface too. It must render the normalized kind/expiry exactly like the
// Review panels, and its /decide calls must carry the operator session's CSRF
// header (the route is operator-only since Task 12).
// ---------------------------------------------------------------------------

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

/**
 * review-flow-ux Task 9: a card REVIEW_REQUEST carries its text in `content`
 * (backend: `KanbanReviewAskCreator` — "Run … completed (FAILED) - awaiting
 * your review: …") while `reason` is null.
 */
const REVIEW_REQUEST_CONTENT =
  'Run 1234abcd completed (FAILED) - awaiting your review: Fix the flaky parser test';

function reviewRequestAsk(): Approval {
  return {
    id: 'a-review',
    runId: 'run-abc',
    toolCallId: null,
    status: 'PENDING',
    reason: null as unknown as string,
    requestedAt: '2026-09-22T11:55:00Z',
    decidedAt: null,
    expiresAt: NATIVE_ASK_EXPIRES_AT,
    askType: 'REVIEW_REQUEST',
    content: REVIEW_REQUEST_CONTENT,
  } as Approval;
}

/** A non-native ask with neither `content` nor `reason` (neutral-sentence corner). */
function bareAsk(): Approval {
  return {
    id: 'a-bare',
    runId: 'run-abc',
    toolCallId: null,
    status: 'PENDING',
    reason: null as unknown as string,
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
      <ReviewQueue />
    </QueryClientProvider>,
  );
}

describe('ReviewQueue normalized native asks (Task 15 fix round 1)', () => {
  beforeEach(() => {
    requests.length = 0;
    sessionStorage.clear();
    delete client.defaults.headers.common[OPERATOR_CSRF_HEADER];
  });

  afterEach(() => {
    client.defaults.adapter = originalAdapter;
    sessionStorage.clear();
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
    // A gate ask is non-native: review-flow-ux Task 9 labels such rows 'Review'.
    expect(screen.getByText('Review')).toBeInTheDocument();
  });

  it('carries the operator session CSRF header on the native ask decision', async () => {
    serve([nativeAsk()]);
    sessionStorage.setItem(
      OPERATOR_SESSION_STORAGE_KEY,
      JSON.stringify({
        csrfToken: 'csrf-fixture-token',
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
      }),
    );
    ui();
    await screen.findByText('Native permission · NATIVE_TOOL');

    await userEvent.click(screen.getByRole('button', { name: 'Approve' }));

    await waitFor(() =>
      expect(requests.some((r) => r.url === '/api/v1/approvals/a-native/decide')).toBe(true),
    );
    const decide = requests.find((r) => r.url === '/api/v1/approvals/a-native/decide');
    expect(decide?.headers.get(OPERATOR_CSRF_HEADER)).toBe('csrf-fixture-token');
  });

  it('carries the operator session CSRF header on every decide, not only native asks', async () => {
    serve([gateAsk()]);
    sessionStorage.setItem(
      OPERATOR_SESSION_STORAGE_KEY,
      JSON.stringify({
        csrfToken: 'csrf-fixture-token',
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
      }),
    );
    ui();
    await screen.findByText('Review');

    await userEvent.click(screen.getByRole('button', { name: 'Deny' }));

    await waitFor(() =>
      expect(requests.some((r) => r.url === '/api/v1/approvals/a-gate/decide')).toBe(true),
    );
    const decide = requests.find((r) => r.url === '/api/v1/approvals/a-gate/decide');
    expect(decide?.headers.get(OPERATOR_CSRF_HEADER)).toBe('csrf-fixture-token');
  });
});

// ---------------------------------------------------------------------------
// review-flow-ux Task 9: non-native rows must tell the truth — an accurate
// 'Review' label and the ask content (a REVIEW_REQUEST's text lives in
// `content` with a null `reason`), never the generic null-reason fallback.
// Native permission rows keep their pill + facts rendering.
// ---------------------------------------------------------------------------

describe('ReviewQueue non-native rows (review-flow-ux Task 9)', () => {
  beforeEach(() => {
    requests.length = 0;
    sessionStorage.clear();
    delete client.defaults.headers.common[OPERATOR_CSRF_HEADER];
  });

  afterEach(() => {
    client.defaults.adapter = originalAdapter;
    sessionStorage.clear();
    delete client.defaults.headers.common[OPERATOR_CSRF_HEADER];
  });

  it('renders a REVIEW_REQUEST row with its accurate label and ask content, not the stale fallback', async () => {
    serve([reviewRequestAsk()]);
    ui();

    expect(await screen.findByText(REVIEW_REQUEST_CONTENT)).toBeInTheDocument();
    expect(screen.getByText('Review')).toHaveClass('pill', 'warn');
    expect(
      screen.queryByText('Awaiting human verification before tool execution proceeds.'),
    ).not.toBeInTheDocument();
  });

  it('keeps the native pill, facts and reason rendering untouched (regression guard)', async () => {
    serve([nativeAsk()]);
    ui();

    expect(await screen.findByText('Native permission · NATIVE_TOOL')).toBeInTheDocument();
    expect(screen.getByText('tool write_file')).toBeInTheDocument();
    expect(screen.getByText('request req-42')).toBeInTheDocument();
    expect(
      screen.getByText(`expires ${formatTimestamp(NATIVE_ASK_EXPIRES_AT)}`),
    ).toBeInTheDocument();
    expect(screen.getByText(NATIVE_ASK_REASON)).toBeInTheDocument();
  });

  it('shows the neutral sentence when both content and reason are absent', async () => {
    serve([bareAsk()]);
    ui();

    expect(
      await screen.findByText('Review requested — open the run for context.'),
    ).toBeInTheDocument();
    expect(
      screen.queryByText('Awaiting human verification before tool execution proceeds.'),
    ).not.toBeInTheDocument();
  });
});
