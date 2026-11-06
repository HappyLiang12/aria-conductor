import { describe, expect, it, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useMediaQuery } from '../useMediaQuery';

describe('useMediaQuery', () => {
  it('subscribes and unsubscribes to matchMedia', () => {
    const listeners: Array<() => void> = [];
    const removeEventListener = vi.fn();
    window.matchMedia = vi.fn().mockReturnValue({
      matches: true,
      addEventListener: (_: string, fn: () => void) => listeners.push(fn),
      removeEventListener,
    }) as never;

    const { result, unmount } = renderHook(() => useMediaQuery('(max-width: 900px)'));
    expect(result.current).toBe(true);
    expect(listeners).toHaveLength(1);
    unmount();
    expect(removeEventListener).toHaveBeenCalledTimes(1);
    expect(window.matchMedia).toHaveBeenCalledWith('(max-width: 900px)');
  });
});
