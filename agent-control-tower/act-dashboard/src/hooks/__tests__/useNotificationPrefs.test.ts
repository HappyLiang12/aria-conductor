import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useNotificationPrefs } from '../useNotificationPrefs';
import type { NotificationType } from '../../types';

const STORAGE_KEY = 'aria-notification-prefs';

const ALL_TYPES: NotificationType[] = [
  'run.completed',
  'run.failed',
  'approval.requested',
  'knowledge.submitted',
  'report.generated',
  'reminder',
  'monitor',
  'brief',
];

describe('useNotificationPrefs', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('starts with default prefs when storage is empty', () => {
    const { result } = renderHook(() => useNotificationPrefs());
    expect(result.current.prefs.showToast).toBe(true);
    expect(result.current.prefs.showBadge).toBe(true);
    for (const type of ALL_TYPES) {
      expect(result.current.prefs.typeFilters[type]).toBe(true);
    }
  });

  it('loads stored prefs and merges them over defaults', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ showToast: false }));
    const { result } = renderHook(() => useNotificationPrefs());
    expect(result.current.prefs.showToast).toBe(false);
    // untouched keys keep their default values
    expect(result.current.prefs.showBadge).toBe(true);
    expect(result.current.prefs.typeFilters['run.failed']).toBe(true);
  });

  it('falls back to defaults on malformed stored JSON', () => {
    localStorage.setItem(STORAGE_KEY, '{not-json');
    const { result } = renderHook(() => useNotificationPrefs());
    expect(result.current.prefs.showToast).toBe(true);
    expect(result.current.prefs.showBadge).toBe(true);
  });

  it('updatePref updates state and persists to localStorage', () => {
    const { result } = renderHook(() => useNotificationPrefs());
    act(() => {
      result.current.updatePref('showToast', false);
    });
    expect(result.current.prefs.showToast).toBe(false);
    const stored = JSON.parse(localStorage.getItem(STORAGE_KEY)!);
    expect(stored.showToast).toBe(false);
    expect(stored.showBadge).toBe(true);
  });

  it('toggleTypeFilter flips a single type filter and persists it', () => {
    const { result } = renderHook(() => useNotificationPrefs());
    act(() => {
      result.current.toggleTypeFilter('run.failed');
    });
    expect(result.current.prefs.typeFilters['run.failed']).toBe(false);
    // other filters remain untouched
    expect(result.current.prefs.typeFilters['run.completed']).toBe(true);
    const stored = JSON.parse(localStorage.getItem(STORAGE_KEY)!);
    expect(stored.typeFilters['run.failed']).toBe(false);

    act(() => {
      result.current.toggleTypeFilter('run.failed');
    });
    expect(result.current.prefs.typeFilters['run.failed']).toBe(true);
  });

  it('isTypeEnabled reflects the current filter state', () => {
    const { result } = renderHook(() => useNotificationPrefs());
    expect(result.current.isTypeEnabled('monitor')).toBe(true);
    act(() => {
      result.current.toggleTypeFilter('monitor');
    });
    expect(result.current.isTypeEnabled('monitor')).toBe(false);
  });

  it('isTypeEnabled defaults to true for types missing from the filter map', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ typeFilters: { reminder: false } }));
    const { result } = renderHook(() => useNotificationPrefs());
    // stored map replaced typeFilters wholesale — unknown keys resolve via ?? true
    expect(result.current.isTypeEnabled('brief')).toBe(true);
    expect(result.current.isTypeEnabled('reminder')).toBe(false);
  });

  it('a second hook instance picks up previously persisted prefs', () => {
    const first = renderHook(() => useNotificationPrefs());
    act(() => {
      first.result.current.updatePref('showBadge', false);
    });
    first.unmount();

    const second = renderHook(() => useNotificationPrefs());
    expect(second.result.current.prefs.showBadge).toBe(false);
  });
});
