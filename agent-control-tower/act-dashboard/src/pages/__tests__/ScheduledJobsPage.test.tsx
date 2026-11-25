import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ScheduledJobsPage } from '../ScheduledJobsPage';
import type { ScheduledJob } from '../../types';

vi.mock('../../api/ariaJobs', () => ({
  listJobs: vi.fn(),
  createJob: vi.fn(),
  updateJob: vi.fn(),
  deleteJob: vi.fn(),
  pauseJob: vi.fn(),
  resumeJob: vi.fn(),
}));

import { listJobs, deleteJob } from '../../api/ariaJobs';

const mockListJobs = listJobs as Mock;
const mockDeleteJob = deleteJob as Mock;

const JOB: ScheduledJob = {
  id: 'job-1',
  scheduleType: 'RECURRING',
  category: 'REMINDER',
  title: 'Daily summary',
  scheduleExpression: '0 9 * * *',
  notificationTitle: 'Daily brief ready',
  notificationBody: null,
  nextFireAt: '2026-09-13T09:00:00Z',
  lastFiredAt: null,
  status: 'ACTIVE',
  createdAt: '2026-09-01T09:00:00Z',
  updatedAt: '2026-09-01T09:00:00Z',
};

function ui() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <ScheduledJobsPage />
    </QueryClientProvider>,
  );
}

async function clickDelete() {
  const del = await screen.findByRole('button', { name: /Delete/ });
  await userEvent.click(del);
}

describe('ScheduledJobsPage delete confirmation (Task 10)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockListJobs.mockResolvedValue([JOB]);
    mockDeleteJob.mockResolvedValue(undefined);
  });

  it('deletes only after the operator confirms in the shared dialog', async () => {
    ui();
    await clickDelete();

    // Nothing may be deleted from the first click — the dialog only opens.
    expect(mockDeleteJob).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog', { name: /Delete/ })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(mockDeleteJob).not.toHaveBeenCalled();

    await clickDelete();
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(mockDeleteJob).toHaveBeenCalledTimes(1));
    // `deleteJob` is passed straight to useMutation, so it also receives the
    // mutation context as its second argument.
    expect(mockDeleteJob.mock.calls[0][0]).toBe('job-1');
    // Completion clears the pending state.
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('backdrop click dismisses the dialog without deleting', async () => {
    ui();
    await clickDelete();
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    fireEvent.click(document.querySelector('.modal-overlay') as HTMLElement);

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(mockDeleteJob).not.toHaveBeenCalled();
  });

  it('clears the pending state and reports a deletion failure through the page toast', async () => {
    mockDeleteJob.mockRejectedValue({
      response: { data: { message: 'Job is referenced by a running workflow.' } },
    });
    ui();
    await clickDelete();
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    expect(await screen.findByRole('status')).toHaveTextContent(
      'Job is referenced by a running workflow.',
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(mockDeleteJob).toHaveBeenCalledTimes(1);
  });
});

// ---------------------------------------------------------------------------
// Wave 2 C5: each job renders on the shadcn token card surface. The hooks the
// E2E spec pins (`.job-card`, `.job-card-title`, `.pill`, `.page-header h2`)
// and the schedule copy stay exactly where they were.
// ---------------------------------------------------------------------------
describe('ScheduledJobsPage token surfaces (Wave 2 C5)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockListJobs.mockResolvedValue([JOB]);
  });

  it('renders each job on a token card surface', async () => {
    ui();

    const title = await screen.findByText('Daily summary');
    const card = title.closest('[data-slot="card"]');
    expect(card).not.toBeNull();
    expect(card!.classList.contains('job-card')).toBe(true);
    expect(card).toHaveTextContent('0 9 * * *');
    expect(card).toHaveTextContent('Next:');
    expect(card).toHaveTextContent('Last:');
    expect(card!.querySelector('.pill')).toHaveTextContent('ACTIVE');
    // The header stays an h2 under .page-header (E2E `.page-header h2`).
    const header = document.querySelector('.page-header');
    expect(header?.querySelector('h2')).toHaveTextContent('Scheduled Jobs');
  });

  it('opens the create form on the token dialog surface', async () => {
    ui();

    await userEvent.click(screen.getByRole('button', { name: '+ New Job' }));

    const dialog = document.querySelector('.modal-dialog');
    expect(dialog).not.toBeNull();
    expect(dialog!.querySelector('h3')).toHaveTextContent('New Job');
    expect(dialog!.querySelector('input[data-slot="input"]')).not.toBeNull();
  });
});
