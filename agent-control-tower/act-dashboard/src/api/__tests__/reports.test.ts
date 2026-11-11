import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';

vi.mock('../client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  },
}));

vi.mock('../operatorSession', () => ({ applyOperatorHeaders: vi.fn() }));

import client from '../client';
import { applyOperatorHeaders } from '../operatorSession';
import { listReports, regenerateReport, archiveReport } from '../reports';

const get = client.get as Mock;
const post = client.post as Mock;
const del = client.delete as Mock;
const applyHeaders = applyOperatorHeaders as Mock;

/**
 * The report mutations the REST authority lane gated are operator-only
 * server-side, so they must carry the locally established operator session like
 * every other gated mutation. `generate`/`amend` stay ungated on purpose: each
 * has a worker-write MCP twin, so a run worker is expected to request it.
 */
describe('reports api operator boundary', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('regenerateReport applies the operator session before the POST', async () => {
    post.mockResolvedValue({ data: { id: 'r-1' } });

    await regenerateReport('r-1');

    expect(applyHeaders).toHaveBeenCalledTimes(1);
    expect(post).toHaveBeenCalledWith('/api/v1/reports/r-1/regenerate', {});
    expect(applyHeaders.mock.invocationCallOrder[0]).toBeLessThan(
      post.mock.invocationCallOrder[0],
    );
  });

  it('archiveReport applies the operator session before the DELETE', async () => {
    del.mockResolvedValue({ data: undefined });

    await archiveReport('r-1');

    expect(applyHeaders).toHaveBeenCalledTimes(1);
    expect(del).toHaveBeenCalledWith('/api/v1/reports/r-1');
    expect(applyHeaders.mock.invocationCallOrder[0]).toBeLessThan(
      del.mock.invocationCallOrder[0],
    );
  });

  // Positive control: the inventory read is not gated, so it must not attach the
  // session — the header is for authority, not for every report request.
  it('the report inventory read does not touch the operator session', async () => {
    get.mockResolvedValue({ data: [] });

    await listReports();

    expect(applyHeaders).not.toHaveBeenCalled();
    expect(get).toHaveBeenCalledWith('/api/v1/reports');
  });
});
