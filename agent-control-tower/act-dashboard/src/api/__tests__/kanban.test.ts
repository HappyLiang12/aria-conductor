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

import client from '../client';
import {
  listKanbanItems,
  getKanbanItem,
  createKanbanItem,
  updateKanbanItem,
  transitionKanbanItem,
  deleteKanbanItem,
} from '../kanban';
import type { CreateKanbanItemRequest, TransitionKanbanRequest, UpdateKanbanItemRequest } from '../../types';

const get = client.get as Mock;
const post = client.post as Mock;
const put = client.put as Mock;
const del = client.delete as Mock;

describe('kanban api', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('listKanbanItems GETs all items without params by default', async () => {
    get.mockResolvedValue({ data: [{ id: 'k-1' }] });

    await expect(listKanbanItems()).resolves.toEqual([{ id: 'k-1' }]);
    expect(get).toHaveBeenCalledWith('/api/v1/kanban/items', { params: undefined });
  });

  it('listKanbanItems filters by status when provided', async () => {
    get.mockResolvedValue({ data: [] });

    await listKanbanItems('IN_PROGRESS' as Parameters<typeof listKanbanItems>[0]);
    expect(get).toHaveBeenCalledWith('/api/v1/kanban/items', {
      params: { status: 'IN_PROGRESS' },
    });
  });

  it('getKanbanItem URL-encodes the item id', async () => {
    get.mockResolvedValue({ data: { id: 'a/b c' } });

    await expect(getKanbanItem('a/b c')).resolves.toEqual({ id: 'a/b c' });
    expect(get).toHaveBeenCalledWith('/api/v1/kanban/items/a%2Fb%20c');
  });

  it('createKanbanItem POSTs the request body', async () => {
    const req = { title: 'Ship tests' } as unknown as CreateKanbanItemRequest;
    post.mockResolvedValue({ data: { id: 'k-new', title: 'Ship tests' } });

    await expect(createKanbanItem(req)).resolves.toEqual({ id: 'k-new', title: 'Ship tests' });
    expect(post).toHaveBeenCalledWith('/api/v1/kanban/items', req);
  });

  it('updateKanbanItem PUTs to the encoded item URL', async () => {
    const req = { title: 'Renamed' } as unknown as UpdateKanbanItemRequest;
    put.mockResolvedValue({ data: { id: 'k-2', title: 'Renamed' } });

    await expect(updateKanbanItem('k-2', req)).resolves.toEqual({ id: 'k-2', title: 'Renamed' });
    expect(put).toHaveBeenCalledTimes(1);
    expect(put).toHaveBeenCalledWith('/api/v1/kanban/items/k-2', req);
  });

  it('transitionKanbanItem POSTs the transition request', async () => {
    const req = { targetStatus: 'DONE' } as unknown as TransitionKanbanRequest;
    post.mockResolvedValue({ data: { id: 'k-3', status: 'DONE' } });

    await expect(transitionKanbanItem('k-3', req)).resolves.toEqual({ id: 'k-3', status: 'DONE' });
    expect(post).toHaveBeenCalledWith('/api/v1/kanban/items/k-3/transition', req);
  });

  it('deleteKanbanItem DELETEs the encoded item URL', async () => {
    del.mockResolvedValue({ data: undefined });

    await expect(deleteKanbanItem('k#4')).resolves.toBeUndefined();
    expect(del).toHaveBeenCalledWith('/api/v1/kanban/items/k%234');
  });

  it('propagates transition failures to the caller', async () => {
    post.mockRejectedValue(
      Object.assign(new Error('Request failed with status code 409'), {
        response: { status: 409, data: { message: 'invalid transition' } },
      }),
    );

    await expect(
      transitionKanbanItem('k-5', { targetStatus: 'DONE' } as unknown as TransitionKanbanRequest),
    ).rejects.toThrow('409');
  });
});
