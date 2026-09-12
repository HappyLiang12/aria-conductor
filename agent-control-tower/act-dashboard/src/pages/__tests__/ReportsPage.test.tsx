import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { render, screen, fireEvent, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReportsPage } from '../ReportsPage';
import type { ReportArtifact } from '../../types';

vi.mock('../../api/reports', () => ({
  listReports: vi.fn(),
  archiveReport: vi.fn(),
  generateReport: vi.fn(),
  amendReport: vi.fn(),
  regenerateReport: vi.fn(),
  getReportHtml: vi.fn(),
  reportHtmlUrl: vi.fn((id: string) => `/api/reports/${id}/html`),
}));
vi.mock('../../components/Layout', () => ({
  useWebSocketContext: () => ({ lastMessage: null, isConnected: true }),
}));

import { listReports, archiveReport, getReportHtml } from '../../api/reports';

const mockListReports = listReports as Mock;
const mockArchiveReport = archiveReport as Mock;
const mockGetReportHtml = getReportHtml as Mock;

const REPORT: ReportArtifact = {
  id: 'r-1',
  title: 'Q1 Agent Performance Brief',
  sourceRunId: null,
  owner: 'alice',
  sensitivity: 'internal',
  dataScope: 'runs:Q1',
  htmlPath: null,
  htmlUrl: '/api/reports/report/r-1/html',
  version: 1,
  status: 'GENERATED',
  createdAt: '2026-09-01T10:00:00Z',
  amendedAt: null,
  amendmentHistory: null,
};

function ui() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <ReportsPage />
    </QueryClientProvider>,
  );
}

/** Waits for the auto-selected report to enable the toolbar and clicks Delete. */
async function clickDelete() {
  const del = await screen.findByRole('button', { name: 'Delete' });
  await waitFor(() => expect(del).not.toBeDisabled());
  await userEvent.click(del);
  return del;
}

/**
 * Targets the shared ConfirmDialog by name: the page also keeps its closed
 * generate-report mini-dialog mounted with role="dialog".
 */
const confirmDialog = () => screen.queryByRole('dialog', { name: 'Delete this report?' });

describe('ReportsPage archive confirmation (Task 10)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockListReports.mockResolvedValue([REPORT]);
    mockGetReportHtml.mockResolvedValue('<html><body>brief</body></html>');
    mockArchiveReport.mockResolvedValue(undefined);
  });

  it('archives only after the operator confirms in the shared dialog', async () => {
    ui();
    await clickDelete();

    // Nothing may be archived from the first click — the dialog only opens.
    expect(mockArchiveReport).not.toHaveBeenCalled();
    expect(confirmDialog()).toBeInTheDocument();

    await userEvent.click(within(confirmDialog() as HTMLElement).getByRole('button', { name: 'Cancel' }));
    expect(confirmDialog()).not.toBeInTheDocument();
    expect(mockArchiveReport).not.toHaveBeenCalled();

    await clickDelete();
    await userEvent.click(within(confirmDialog() as HTMLElement).getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(mockArchiveReport).toHaveBeenCalledTimes(1));
    expect(mockArchiveReport).toHaveBeenCalledWith('r-1');
    // Completion clears the pending state.
    await waitFor(() => expect(confirmDialog()).not.toBeInTheDocument());
  });

  it('backdrop click dismisses the dialog without archiving', async () => {
    ui();
    await clickDelete();
    expect(confirmDialog()).toBeInTheDocument();

    fireEvent.click(document.querySelector('.modal-overlay') as HTMLElement);

    expect(confirmDialog()).not.toBeInTheDocument();
    expect(mockArchiveReport).not.toHaveBeenCalled();
  });

  it('clears the pending state and reports an archive failure', async () => {
    mockArchiveReport.mockRejectedValue(new Error('boom'));
    ui();
    await clickDelete();
    await userEvent.click(within(confirmDialog() as HTMLElement).getByRole('button', { name: 'Confirm' }));

    expect(await screen.findByText('Archive failed. Please retry.')).toBeInTheDocument();
    expect(confirmDialog()).not.toBeInTheDocument();
    expect(mockArchiveReport).toHaveBeenCalledTimes(1);
  });
});
