import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { render, screen, act, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import NotificationBell from '../NotificationBell';
import type { Notification } from '../../types';

// The bell reads the shared WebSocket context from Layout; swap it for a stub
// so the component mounts without a socket and without the app shell.
vi.mock('../Layout', () => ({
  useWebSocketContext: () => ({ lastMessage: null, isConnected: false }),
}));

vi.mock('../../api/ariaNotifications', () => ({
  getUnreadCount: vi.fn(),
  listNotifications: vi.fn(),
  markRead: vi.fn(),
  markAllRead: vi.fn(),
}));

vi.mock('../../api/ariaConversations', () => ({
  composeSynthesis: vi.fn(),
}));

import { getUnreadCount, listNotifications, markRead } from '../../api/ariaNotifications';
import { composeSynthesis } from '../../api/ariaConversations';

const mockGetUnreadCount = getUnreadCount as Mock;
const mockListNotifications = listNotifications as Mock;
const mockMarkRead = markRead as Mock;
const mockComposeSynthesis = composeSynthesis as Mock;

function notification(overrides: Partial<Notification> = {}): Notification {
  return {
    id: 'n-1',
    type: 'run.completed',
    title: 'Run completed',
    body: null,
    resourceType: 'RUN',
    resourceId: 'run-1',
    jobId: null,
    isRead: false,
    createdAt: '2026-10-03T10:00:00Z',
    ...overrides,
  };
}

/** Tracks the router path so "the button never navigates" is directly assertable. */
function LocationTracker({ paths }: { paths: string[] }) {
  const location = useLocation();
  paths.push(location.pathname);
  return null;
}

async function openDropdown(items: Notification[]) {
  mockListNotifications.mockResolvedValue({
    content: items,
    totalElements: items.length,
    totalPages: 1,
  });
  const paths: string[] = [];
  render(
    <MemoryRouter initialEntries={['/']}>
      <LocationTracker paths={paths} />
      <NotificationBell />
    </MemoryRouter>,
  );
  await act(async () => {}); // flush the unread-count fetch
  fireEvent.click(screen.getByRole('button', { name: /Notifications/ }));
  await act(async () => {}); // flush the dropdown's list fetch
  return paths;
}

beforeEach(() => {
  vi.clearAllMocks();
  mockGetUnreadCount.mockResolvedValue({ unreadCount: 0 });
  mockMarkRead.mockResolvedValue(notification());
});

describe('NotificationBell one-click synthesis', () => {
  it('a run.batch.completed notification renders the 彙整 action and composes a synthesis prompt', async () => {
    const batch = notification({
      id: 'n-batch',
      type: 'run.batch.completed',
      title: '子任務批次完成（2 個：成功 2／失敗 0）',
      resourceType: 'CONVERSATION',
      resourceId: 'conv-42',
    });
    mockComposeSynthesis.mockResolvedValue({ prompt: 'composed synthesis prompt' });

    // Listen on the window rather than spying on dispatchEvent so the assertion
    // covers exactly what AriaPanel will receive.
    const events: CustomEvent<{ prompt: string }>[] = [];
    const listener = (e: Event) => events.push(e as CustomEvent<{ prompt: string }>);
    window.addEventListener('aria:compose', listener);
    try {
      const paths = await openDropdown([batch]);
      fireEvent.click(screen.getByRole('button', { name: '彙整' }));

      await waitFor(() => expect(mockComposeSynthesis).toHaveBeenCalledWith('conv-42'));
      await waitFor(() => expect(events).toHaveLength(1));
      expect(events[0].detail).toEqual({ prompt: 'composed synthesis prompt' });
      // Compose marks the notification read exactly once — the row's own click
      // handler must not double-handle the button press...
      await waitFor(() => expect(mockMarkRead).toHaveBeenCalledTimes(1));
      expect(mockMarkRead).toHaveBeenCalledWith('n-batch');
      // ...and the button never navigates away from the current page.
      expect(paths[paths.length - 1]).toBe('/');
    } finally {
      window.removeEventListener('aria:compose', listener);
    }
  });

  it('does not render the 彙整 action for other notification types', async () => {
    await openDropdown([notification({ type: 'run.completed' })]);
    expect(await screen.findByText('Run completed')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '彙整' })).not.toBeInTheDocument();
  });
});

describe('NotificationBell approval.expired rendering', () => {
  it('renders the expiry icon and keeps the feed intact', async () => {
    const expired = notification({
      id: 'n-expired',
      type: 'approval.expired',
      title: 'Approval expired',
      body: 'Approval a-1 expired without a decision (run ended). Tool call skipped: run_agent.',
      resourceType: 'APPROVAL',
      resourceId: 'a-1',
    });
    await openDropdown([expired]);
    expect(await screen.findByText('Approval expired')).toBeInTheDocument();
    expect(screen.getByText('⌛')).toBeInTheDocument();
  });
});
