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

import { listReports, archiveReport, getReportHtml, regenerateReport } from '../../api/reports';

const mockListReports = listReports as Mock;
const mockArchiveReport = archiveReport as Mock;
const mockGetReportHtml = getReportHtml as Mock;
const mockRegenerateReport = regenerateReport as Mock;

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

// Task 33 / Item 5: regenerate and archive hit routes gated operator-only in a
// merged lane, but their failures still rendered as plain toolbar text. They
// use the same notice idiom as every other gated caller now: a 401/403 is the
// operator-session notice, anything else keeps the ordinary error path.
describe('ReportsPage gated mutations (Task 33)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockListReports.mockResolvedValue([REPORT]);
    mockGetReportHtml.mockResolvedValue('<html><body>brief</body></html>');
    mockArchiveReport.mockResolvedValue(undefined);
    mockRegenerateReport.mockResolvedValue(REPORT);
  });

  async function clickRegenerate() {
    const button = await screen.findByRole('button', { name: /Regenerate/ });
    await waitFor(() => expect(button).not.toBeDisabled());
    await userEvent.click(button);
  }

  async function confirmArchive() {
    await clickDelete();
    await userEvent.click(within(confirmDialog() as HTMLElement).getByRole('button', { name: 'Confirm' }));
  }

  it.each([401, 403])('renders the operator-session notice for a %i regenerate', async (status) => {
    mockRegenerateReport.mockRejectedValue({
      response: { status, data: { message: 'REGENERATE_RAW_SERVER_TEXT' } },
    });
    ui();

    await clickRegenerate();

    const notice = await screen.findByTestId('operator-session-notice');
    expect(notice).toHaveTextContent(/regenerating a report is operator-only/i);
    expect(screen.queryByText(/REGENERATE_RAW_SERVER_TEXT/)).not.toBeInTheDocument();
    expect(screen.queryByText('Regeneration failed.')).not.toBeInTheDocument();
  });

  it.each([401, 403])('renders the operator-session notice for a %i archive', async (status) => {
    mockArchiveReport.mockRejectedValue({
      response: { status, data: { message: 'ARCHIVE_RAW_SERVER_TEXT' } },
    });
    ui();

    await confirmArchive();

    const notice = await screen.findByTestId('operator-session-notice');
    expect(notice).toHaveTextContent(/archiving a report is operator-only/i);
    expect(screen.queryByText(/ARCHIVE_RAW_SERVER_TEXT/)).not.toBeInTheDocument();
    expect(screen.queryByText('Archive failed. Please retry.')).not.toBeInTheDocument();
    expect(confirmDialog()).not.toBeInTheDocument();
  });

  // Positive controls: a 500 is a fault, not an authority problem, and success
  // must not leave a notice behind.
  it('keeps the ordinary error path for a 500 regenerate', async () => {
    mockRegenerateReport.mockRejectedValue({
      response: { status: 500, data: { message: 'report engine unavailable' } },
    });
    ui();

    await clickRegenerate();

    expect(await screen.findByText('report engine unavailable')).toBeInTheDocument();
    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
  });

  it('keeps the ordinary error path for a 500 archive', async () => {
    mockArchiveReport.mockRejectedValue({
      response: { status: 500, data: { message: 'archive store unavailable' } },
    });
    ui();

    await confirmArchive();

    expect(await screen.findByText('archive store unavailable')).toBeInTheDocument();
    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
  });

  it('shows no notice after a successful regenerate or archive', async () => {
    ui();

    await clickRegenerate();
    await waitFor(() => expect(mockRegenerateReport).toHaveBeenCalledWith('r-1'));
    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();

    await confirmArchive();
    await waitFor(() => expect(mockArchiveReport).toHaveBeenCalledWith('r-1'));
    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
  });
});

const ARCHIVED_REPORT: ReportArtifact = {
  ...REPORT,
  id: 'r-2',
  title: 'Legacy Q4 Brief',
  status: 'ARCHIVED',
  createdAt: '2026-08-01T10:00:00Z',
};

const showArchivedToggle = () => screen.getByRole('checkbox', { name: 'Show archived' });

/** The toolbar's selected-report title (the preview iframe also carries the title attr). */
const toolbarTitle = (title: string) => screen.getByText(title, { selector: '.report-title' });
const queryToolbarTitle = (title: string) => screen.queryByText(title, { selector: '.report-title' });

describe('ReportsPage archived visibility (Delete soft-archive)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGetReportHtml.mockResolvedValue('<html><body>brief</body></html>');
    mockArchiveReport.mockResolvedValue(undefined);
  });

  it('hides ARCHIVED reports by default and auto-selects from the visible list', async () => {
    // Archived first: selection must skip it, not land on the hidden row.
    mockListReports.mockResolvedValue([ARCHIVED_REPORT, REPORT]);
    ui();

    expect(await screen.findByRole('button', { name: /Q1 Agent Performance Brief/ })).toBeInTheDocument();
    expect(screen.queryByText('Legacy Q4 Brief')).not.toBeInTheDocument();

    // Auto-selection picked the first VISIBLE report, not the archived head.
    expect(toolbarTitle('Q1 Agent Performance Brief')).toBeInTheDocument();

    // The toggle exists and starts off, keeping the archived row hidden.
    expect(showArchivedToggle()).not.toBeChecked();
    expect(screen.queryByText('Legacy Q4 Brief')).not.toBeInTheDocument();
  });

  it('reveals archived reports via the toggle and hides them again when off', async () => {
    mockListReports.mockResolvedValue([REPORT, ARCHIVED_REPORT]);
    ui();

    await screen.findByRole('button', { name: /Q1 Agent Performance Brief/ });
    const toggle = showArchivedToggle();
    expect(toggle).not.toBeChecked();
    expect(screen.queryByText('Legacy Q4 Brief')).not.toBeInTheDocument();

    await userEvent.click(toggle);
    expect(toggle).toBeChecked();

    // The archived row now renders and keeps its ARCHIVED status chip.
    const archivedTab = await screen.findByRole('button', { name: /Legacy Q4 Brief/ });
    expect(within(archivedTab).getByText('ARCHIVED')).toBeInTheDocument();

    await userEvent.click(toggle);
    await waitFor(() => expect(screen.queryByText('Legacy Q4 Brief')).not.toBeInTheDocument());
  });

  it('falls back to the first visible report when the selection becomes hidden', async () => {
    mockListReports.mockResolvedValue([REPORT, ARCHIVED_REPORT]);
    ui();

    const toggle = await screen.findByRole('checkbox', { name: 'Show archived' });
    await userEvent.click(toggle);

    // Select the archived report while it is revealed.
    await userEvent.click(await screen.findByRole('button', { name: /Legacy Q4 Brief/ }));
    expect(toolbarTitle('Legacy Q4 Brief')).toBeInTheDocument();

    // Hiding it again cannot leave a hidden selection: fall back to the first visible.
    await userEvent.click(toggle);
    await waitFor(() => expect(toolbarTitle('Q1 Agent Performance Brief')).toBeInTheDocument());
    expect(queryToolbarTitle('Legacy Q4 Brief')).not.toBeInTheDocument();
  });

  it('removes the archived report from the list after a confirmed archive', async () => {
    const other: ReportArtifact = { ...REPORT, id: 'r-3', title: 'Ops Weekly Brief', owner: 'bob' };
    mockListReports
      .mockResolvedValueOnce([REPORT, other])
      .mockResolvedValue([{ ...REPORT, status: 'ARCHIVED' }, other]);
    ui();

    await clickDelete();
    await userEvent.click(within(confirmDialog() as HTMLElement).getByRole('button', { name: 'Confirm' }));

    await waitFor(() => expect(mockArchiveReport).toHaveBeenCalledWith('r-1'));
    expect(mockArchiveReport).toHaveBeenCalledTimes(1);

    // The refetched list hides the archived row; the remaining report takes over.
    await waitFor(() => expect(screen.queryByText('Q1 Agent Performance Brief')).not.toBeInTheDocument());
    await waitFor(() => expect(toolbarTitle('Ops Weekly Brief')).toBeInTheDocument());

    // Still hidden by default: the toggle is available, unchecked.
    expect(showArchivedToggle()).not.toBeChecked();
  });
});
