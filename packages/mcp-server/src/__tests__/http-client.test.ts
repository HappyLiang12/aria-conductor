import { describe, it, expect } from 'vitest';
import { qs, toJsonResult, ActHttpError } from '../http-client.js';

describe('qs()', () => {
  it('returns empty string for empty params', () => {
    expect(qs({})).toBe('');
  });

  it('builds query string from single param', () => {
    expect(qs({ status: 'RUNNING' })).toBe('?status=RUNNING');
  });

  it('builds query string from multiple params', () => {
    const result = qs({ agentId: 'abc', status: 'DONE' });
    expect(result).toContain('agentId=abc');
    expect(result).toContain('status=DONE');
    expect(result).toMatch(/^\?/);
  });

  it('skips undefined values', () => {
    expect(qs({ agentId: undefined, status: 'PENDING' })).toBe('?status=PENDING');
  });

  it('returns empty when all values are undefined', () => {
    expect(qs({ a: undefined, b: undefined })).toBe('');
  });
});

describe('toJsonResult()', () => {
  it('serializes an object to JSON', () => {
    const result = toJsonResult({ hello: 'world' });
    expect(result.content).toHaveLength(1);
    expect(result.content[0].type).toBe('text');
    expect(JSON.parse(result.content[0].text)).toEqual({ hello: 'world' });
  });

  it('passes through a string as-is', () => {
    const result = toJsonResult('hello');
    expect(result.content[0].text).toBe('hello');
  });

  it('serializes arrays', () => {
    const result = toJsonResult([1, 2, 3]);
    expect(JSON.parse(result.content[0].text)).toEqual([1, 2, 3]);
  });

  it('serializes null', () => {
    const result = toJsonResult(null);
    expect(result.content[0].text).toBe('');
  });

  it('serializes undefined', () => {
    const result = toJsonResult(undefined);
    expect(result.content[0].text).toBe('');
  });
});

describe('ActHttpError', () => {
  it('stores status and message', () => {
    const err = new ActHttpError(404, 'Not Found');
    expect(err.status).toBe(404);
    expect(err.message).toContain('404');
    expect(err.message).toContain('Not Found');
    expect(err.name).toBe('ActHttpError');
  });

  it('stores optional details', () => {
    const err = new ActHttpError(500, 'Server Error', { reason: 'db down' });
    expect(err.details).toEqual({ reason: 'db down' });
  });

  it('is an instance of Error', () => {
    const err = new ActHttpError(400, 'Bad Request');
    expect(err).toBeInstanceOf(Error);
  });
});
