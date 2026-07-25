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

  it('shows a toast with a human-readable label for known event types', () => {
    setEvent({ type: 'run.started', payload: {}, timestamp: 't1' });
    render(<Toast />);

    expect(screen.getByText('[Run Started]')).toBeInTheDocument();
    expect(screen.getByText('run.started')).toBeInTheDocument();
  });

  it('falls back to the raw event type when no label exists', () => {
    setEvent({ type: 'custom.event', payload: {}, timestamp: 't1' });
    render(<Toast />);

    expect(screen.getByText('[custom.event]')).toBeInTheDocument();
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
    expect(screen.getByText('[Run Completed]')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(4999);
    });
    expect(screen.getByText('[Run Completed]')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(screen.queryByText('[Run Completed]')).not.toBeInTheDocument();
  });

  it('keeps at most 5 toasts on screen', () => {
    const { rerender } = render(<Toast />);
    for (let i = 0; i < 7; i++) {
      setEvent({ type: 'run.iteration', payload: { i }, timestamp: `t${i}` });
      rerender(<Toast />);
    }
    expect(screen.getAllByText('[Agent Iteration]')).toHaveLength(5);
  });
});
