import { describe, expect, it } from 'vitest';
import { allAuthoritative, queryPhase } from '../queryState';

describe('queryPhase', () => {
  it('is loading before the first response', () => {
    expect(queryPhase({ data: undefined, isPending: true, isError: false, isSuccess: false })).toBe('loading');
  });
  it('is error when the first response failed', () => {
    expect(queryPhase({ data: undefined, isPending: false, isError: true, isSuccess: false })).toBe('error');
  });
  it('is stale when a refetch failed but cached data exists', () => {
    expect(queryPhase({ data: [1], isPending: false, isError: true, isSuccess: false })).toBe('stale');
  });
  it('is success only for a settled successful query', () => {
    expect(queryPhase({ data: [], isPending: false, isError: false, isSuccess: true })).toBe('success');
  });
});

describe('allAuthoritative', () => {
  it('requires every source to be successful and non-empty', () => {
    expect(allAuthoritative(['success', 'success'])).toBe(true);
    expect(allAuthoritative(['success', 'stale'])).toBe(false);
    expect(allAuthoritative(['success', 'loading'])).toBe(false);
    expect(allAuthoritative([])).toBe(false);
  });
});
