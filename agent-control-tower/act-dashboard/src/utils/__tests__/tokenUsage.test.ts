import { describe, expect, it } from 'vitest';
import {
  TOKEN_USAGE_NOT_REPORTED,
  formatTokenCount,
  tokenUsageText,
} from '../tokenUsage';

/**
 * F9 (SDD hardening): real qoder runs reported `totalTokensUsed = 0` because the
 * core never reported usage, and the dashboard painted that as a real zero.
 * The api lane will send null when usage is unknown; the UI must render an
 * explicit "not reported" state then — and still show a genuine 0.
 */
describe('token usage display', () => {
  it('renders a reported count with grouping', () => {
    expect(formatTokenCount(1234)).toBe('1,234');
    expect(tokenUsageText(1234)).toBe('1,234 tokens');
  });

  it('shows a genuine zero as a reported zero', () => {
    expect(formatTokenCount(0)).toBe('0');
    expect(tokenUsageText(0)).toBe('0 tokens');
  });

  it('reports absence as not reported instead of fabricating zero', () => {
    expect(formatTokenCount(null)).toBeNull();
    expect(formatTokenCount(undefined)).toBeNull();
    expect(formatTokenCount(Number.NaN)).toBeNull();
    expect(tokenUsageText(null)).toBe(TOKEN_USAGE_NOT_REPORTED);
    expect(tokenUsageText(undefined)).toBe(TOKEN_USAGE_NOT_REPORTED);
  });
});
