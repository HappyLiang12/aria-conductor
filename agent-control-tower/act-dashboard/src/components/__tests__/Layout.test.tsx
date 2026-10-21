import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { WebSocketContext, ConnectionBanner } from '../Layout';

function renderBanner(isConnected: boolean) {
  const value = {
    lastMessage: null,
    isConnected,
    subscribe: () => ({ unsubscribe: () => {} }),
  };
  const view = render(
    <WebSocketContext.Provider value={value}>
      <ConnectionBanner />
    </WebSocketContext.Provider>,
  );
  const setConnected = (next: boolean) =>
    view.rerender(
      <WebSocketContext.Provider value={{ ...value, isConnected: next }}>
        <ConnectionBanner />
      </WebSocketContext.Provider>,
    );
  return { ...view, setConnected };
}

describe('ConnectionBanner', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('stays hidden for a blip shorter than the grace period', () => {
    const { setConnected } = renderBanner(false);

    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(screen.queryByRole('status')).toBeNull();

    setConnected(true);
    act(() => {
      vi.advanceTimersByTime(10000);
    });
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('warns only after three seconds offline', () => {
    renderBanner(false);

    act(() => {
      vi.advanceTimersByTime(2999);
    });
    expect(screen.queryByRole('status')).toBeNull();

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(screen.getByRole('status')).toHaveTextContent('Live updates paused — reconnecting…');
  });

  it('clears the warning as soon as the feed reconnects', () => {
    const { setConnected } = renderBanner(false);

    act(() => {
      vi.advanceTimersByTime(3000);
    });
    expect(screen.getByRole('status')).toBeInTheDocument();

    setConnected(true);
    expect(screen.queryByRole('status')).toBeNull();
  });
});
