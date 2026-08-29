import { describe, it, expect } from 'vitest';
import { formatTimestamp } from '../formatTime';

/**
 * F7 regression: one shared formatter for every absolute timestamp surface.
 * 24h "HH:mm" for today, "YYYY-MM-DD HH:mm" otherwise — no per-component
 * locale/12h/24h drift.
 */
describe('formatTimestamp', () => {
  it('renders invalid input as an em dash', () => {
    expect(formatTimestamp('not-a-date')).toBe('—');
    expect(formatTimestamp(undefined as unknown as string)).toBe('—');
  });

  it('renders same-day timestamps as 24h HH:mm', () => {
    const d = new Date();
    d.setHours(14, 5, 0, 0);
    expect(formatTimestamp(d.toISOString())).toBe('14:05');
  });

  it('renders same-day midnight-prefixed hours with leading zero', () => {
    const d = new Date();
    d.setHours(0, 7, 0, 0);
    expect(formatTimestamp(d.toISOString())).toBe('00:07');
  });

  it('renders other-day timestamps as YYYY-MM-DD HH:mm', () => {
    // A date far in the past relative to "now" regardless of test run day.
    expect(formatTimestamp('2026-01-02T03:04:00.000Z')).toMatch(
      /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/,
    );
  });

  it('never emits 12h AM/PM markers', () => {
    const d = new Date();
    d.setHours(22, 19, 0, 0);
    expect(formatTimestamp(d.toISOString())).not.toMatch(/AM|PM/);
  });
});
