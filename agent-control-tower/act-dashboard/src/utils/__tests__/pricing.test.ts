import { describe, it, expect } from 'vitest';
import { COST_PER_1K_TOKENS, estimateCost } from '../pricing';

describe('pricing', () => {
  it('exposes the documented cost per 1k tokens', () => {
    expect(COST_PER_1K_TOKENS).toBe(0.012);
  });

  it('charges exactly one unit price for 1000 tokens', () => {
    expect(estimateCost(1000)).toBeCloseTo(0.012, 10);
  });

  it('costs nothing for zero tokens', () => {
    expect(estimateCost(0)).toBe(0);
  });

  it('scales linearly below 1k tokens', () => {
    expect(estimateCost(500)).toBeCloseTo(0.006, 10);
    expect(estimateCost(1)).toBeCloseTo(0.000012, 10);
  });

  it('scales linearly for large token counts', () => {
    expect(estimateCost(1_000_000)).toBeCloseTo(12, 8);
    expect(estimateCost(250_000)).toBeCloseTo(3, 8);
  });

  it('stays consistent with the exported constant', () => {
    const tokens = 12_345;
    expect(estimateCost(tokens)).toBeCloseTo((tokens / 1000) * COST_PER_1K_TOKENS, 10);
  });
});
