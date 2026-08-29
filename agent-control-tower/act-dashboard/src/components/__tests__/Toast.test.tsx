import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import type { WsEvent } from '../../types';
import { Toast } from '../Toast';

// The Toast reads the shared WebSocket context from Layout; swap it for a
// mutable stub so each test can control the "last event" directly.
let mockCtx: { lastMessage: WsEvent | null; isConnected: boolean } = {
  lastMessage: null,
  isConnected: false,
};

vi.mock('../Layout', () => ({
  useWebSocketContext: () => mockCtx,
}));

function setEvent(event: WsEvent | null) {
  mockCtx = { lastMessage: event, isConnected: true };
}

describe('Toast', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    mockCtx = { lastMessage: null, isConnected: false };
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders nothing when no event has arrived', () => {
    const { container } = render(<Toast />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows a human-readable label without the raw event type for noteworthy events', () => {
    setEvent({ type: 'run.completed', payload: { status: 'FAILED' }, timestamp: 't1' });
    render(<Toast />);

    expect(screen.getByText('Run Failed')).toBeInTheDocument();
    // The machine-oriented event type must never be surfaced to users.
    expect(screen.queryByText('run.completed')).not.toBeInTheDocument();
  });

  it.each(['run.started', 'kanban.created', 'kanban.transitioned', 'run.iteration'])(
    'does not toast internal lifecycle event %s',
    (type) => {
      setEvent({ type, payload: {}, timestamp: 't1' });
      const { container } = render(<Toast />);
      expect(container).toBeEmptyDOMElement();
    },
  );

  it('does not toast unknown event types', () => {
    setEvent({ type: 'custom.event', payload: {}, timestamp: 't1' });
    const { container } = render(<Toast />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders aria.notification events with their title and a View action', () => {
    setEvent({
      type: 'aria.notification',
      payload: { id: 'n-1', title: 'Build finished' },
      timestamp: 't1',
    });
    render(<Toast />);

    expect(screen.getByText('Build finished')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'View' })).toBeInTheDocument();
  });

  it('uses a default title when the notification payload has none', () => {
    setEvent({ type: 'aria.notification', payload: { id: 'n-2' }, timestamp: 't1' });
    render(<Toast />);

    expect(screen.getByText('Notification')).toBeInTheDocument();
  });

  it('deduplicates aria.notification events by notification id', () => {
    setEvent({
      type: 'aria.notification',
      payload: { id: 'dup-1', title: 'Once only' },
      timestamp: 't1',
    });
    const { rerender } = render(<Toast />);
    expect(screen.getAllByText('Once only')).toHaveLength(1);

    // same notification id arrives again as a new event object
    setEvent({
      type: 'aria.notification',
      payload: { id: 'dup-1', title: 'Once only' },
      timestamp: 't2',
    });
    rerender(<Toast />);
    expect(screen.getAllByText('Once only')).toHaveLength(1);
  });

  it('dismisses a toast automatically after 5 seconds', () => {
    setEvent({ type: 'run.completed', payload: {}, timestamp: 't1' });
    render(<Toast />);
    expect(screen.getByText('Run Completed')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(4999);
    });
    expect(screen.getByText('Run Completed')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(screen.queryByText('Run Completed')).not.toBeInTheDocument();
  });

  // Regression: the dismiss timer of an older toast must survive the arrival
  // of newer events (previously the effect cleanup cancelled it, freezing the
  // whole toast stack on screen).
  it('dismisses each toast on its own schedule even when newer events arrive', () => {
    const { rerender } = render(<Toast />);

    setEvent({ type: 'run.completed', payload: { status: 'FAILED', n: 1 }, timestamp: 't1' });
    rerender(<Toast />);
    expect(screen.getByText('Run Failed')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(3000);
    });

    // A second noteworthy event arrives at t+3s; the first toast must still
    // expire at t+5s.
    setEvent({ type: 'approval.requested', payload: { n: 2 }, timestamp: 't2' });
    rerender(<Toast />);
    expect(screen.getByText('Approval Needed')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(2000); // t+5s
    });
    expect(screen.queryByText('Run Failed')).not.toBeInTheDocument();
    expect(screen.getByText('Approval Needed')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(3000); // t+8s
    });
    expect(screen.queryByText('Approval Needed')).not.toBeInTheDocument();
  });

  // Review P2-1: failures arrive as run.completed with payload.status, so the
  // label must reflect the terminal status instead of always saying Completed.
  it('labels run.completed with FAILED status as Run Failed', () => {
    setEvent({ type: 'run.completed', payload: { status: 'FAILED' }, timestamp: 't1' });
    render(<Toast />);

    expect(screen.getByText('Run Failed')).toBeInTheDocument();
    expect(screen.queryByText('Run Completed')).not.toBeInTheDocument();
  });

  it('keeps at most 5 toasts on screen', () => {
    const { rerender } = render(<Toast />);
    for (let i = 0; i < 7; i++) {
      setEvent({ type: 'run.completed', payload: { i }, timestamp: `t${i}` });
      rerender(<Toast />);
    }
    expect(screen.getAllByText('Run Completed')).toHaveLength(5);
  });
});
