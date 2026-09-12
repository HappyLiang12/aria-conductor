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
    mockDeleteJob.mockRejectedValue(new Error('boom'));
    ui();
    await clickDelete();
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Operation failed: boom');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(mockDeleteJob).toHaveBeenCalledTimes(1);
  });
});
