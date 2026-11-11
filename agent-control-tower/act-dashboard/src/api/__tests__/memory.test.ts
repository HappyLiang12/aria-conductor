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
import { listMemoryProposals } from '../memory';
import type { MemoryProposalView } from '../memory';

const get = client.get as Mock;

function row(over: Partial<MemoryProposalView> = {}): MemoryProposalView {
  return {
    id: 'f1',
    subjectType: 'FACT',
    entityId: 'ent-7',
    factKey: 'preferred_editor',
    valueText: 'vim',
    predicate: null,
    toEntityId: null,
    status: 'PENDING',
    origin: 'EXTRACTION',
    sensitivity: 'INTERNAL',
    confidence: null,
    createdAt: '2026-11-11T09:00:00Z',
    validFrom: null,
    ...over,
  };
}

describe('memory api — proposal listing', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // The global inbox is unchanged: without an entity id the request carries the
  // status alone — no empty `entityId` param the server would have to ignore.
  it('asks for the global inbox with the status alone', async () => {
    get.mockResolvedValue({ data: [row()] });

    await expect(listMemoryProposals()).resolves.toEqual([row()]);
    expect(get).toHaveBeenCalledWith('/api/v1/memory/proposals', {
      params: { status: 'PENDING' },
    });
  });

  it("asks for one entity's complete batch when given an entity id", async () => {
    const batch = [
      row(),
      row({ id: 'ent-7', subjectType: 'ENTITY', factKey: 'person:happy', valueText: 'Happy' }),
    ];
    get.mockResolvedValue({ data: batch });

    await expect(listMemoryProposals('PENDING', 'ent-7')).resolves.toEqual(batch);
    // Composes with `status`; the route answers the uncapped per-entity batch.
    expect(get).toHaveBeenCalledWith('/api/v1/memory/proposals', {
      params: { status: 'PENDING', entityId: 'ent-7' },
    });
  });
});
