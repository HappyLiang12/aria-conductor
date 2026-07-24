import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ActivityTimeline from '../ActivityTimeline';
import type { ActivityEvent } from '../../types';

vi.mock('../../api/dashboard', () => ({
  getRecentActivity: vi.fn(),
}));

import { getRecentActivity } from '../../api/dashboard';

const getActivity = getRecentActivity as Mock;

function makeEvent(overrides: Partial<ActivityEvent> = {}): ActivityEvent {
  return {
    eventType: 'audit',
    resourceType: 'run',
    resourceId: 'abcdef1234567890',
    action: 'started',
    timestamp: '2026-07-25T10:30:00Z',
    ...overrides,
  };
}

function renderTimeline() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <ActivityTimeline />
    </QueryClientProvider>,
  );
}

describe('ActivityTimeline', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows a loading indicator while the query is in flight', () => {
    getActivity.mockReturnValue(new Promise(() => {}));
    renderTimeline();
    expect(screen.getByText('Loading activity…')).toBeInTheDocument();
  });

  it('shows an empty state when there are no events', async () => {
    getActivity.mockResolvedValue([]);
    renderTimeline();
    expect(await screen.findByText('No events yet — system is quiet.')).toBeInTheDocument();
  });

  it('shows an error state when the query fails', async () => {
    getActivity.mockRejectedValue(new Error('500'));
    renderTimeline();
    expect(await screen.findByText('Failed to load activity.')).toBeInTheDocument();
  });

  it('describes events with capitalized resource, short id and humanized action', async () => {
    getActivity.mockResolvedValue([
      makeEvent({ resourceType: 'run', resourceId: 'abcdef1234567890', action: 'started_run' }),
    ]);
    renderTimeline();

    expect(await screen.findByText('Run abcdef12 · started run')).toBeInTheDocument();
  });

  it('truncates long details to 60 characters in the description', async () => {
    const details = 'x'.repeat(100);
    getActivity.mockResolvedValue([makeEvent({ details })]);
    renderTimeline();

    const row = await screen.findByText((text) => text.includes('— '));
    expect(row.textContent).toContain(`— ${'x'.repeat(60)}`);
    expect(row.textContent).not.toContain('x'.repeat(61));
  });

  it('renders --:-- for unparseable timestamps', async () => {
    getActivity.mockResolvedValue([makeEvent({ timestamp: 'not-a-date' })]);
    renderTimeline();

    expect(await screen.findByText('--:--')).toBeInTheDocument();
  });

  it('applies variant classes for approval and failed-run events', async () => {
    getActivity.mockResolvedValue([
      makeEvent({ resourceType: 'approval', action: 'approve_requested', resourceId: 'ap111111' }),
      makeEvent({ resourceType: 'run', action: 'failed', resourceId: 'rn222222' }),
      makeEvent({ resourceType: 'knowledge', action: 'submitted', resourceId: 'kn333333' }),
    ]);
    const { container } = renderTimeline();

    await screen.findByText(/Approval ap111111/);
    expect(container.querySelector('.ev.app')).not.toBeNull();
    expect(container.querySelector('.ev.blk')).not.toBeNull();
    expect(container.querySelector('.ev.qa')).not.toBeNull();
  });

  it('caps the timeline at the 10 most recent events', async () => {
    getActivity.mockResolvedValue(
      Array.from({ length: 15 }, (_, i) => makeEvent({ resourceId: `event-${i}-padding` })),
    );
    const { container } = renderTimeline();

    await screen.findAllByText(/Run event-0/);
    expect(container.querySelectorAll('.ev')).toHaveLength(10);
  });
});
