import client from './client';
import type {
  CreateKanbanItemRequest,
  KanbanItem,
  KanbanStatus,
  TransitionKanbanRequest,
  UpdateKanbanItemRequest,
} from '../types';

const BASE = '/api/v1/kanban/items';

// 409 body from GlobalExceptionHandler when a transition is refused. Malformed
// input still answers a plain 400 with only `message`, so every field is optional.
export interface KanbanRejection {
  code?: string;
  message?: string;
  details?: { evaluated?: number; excluded?: { name: string; reasons: string[] }[] };
}

export async function listKanbanItems(status?: KanbanStatus): Promise<KanbanItem[]> {
  const { data } = await client.get<KanbanItem[]>(BASE, {
    params: status ? { status } : undefined,
  });
  return data;
}

export async function getKanbanItem(id: string): Promise<KanbanItem> {
  const { data } = await client.get<KanbanItem>(`${BASE}/${encodeURIComponent(id)}`);
  return data;
}

export async function createKanbanItem(request: CreateKanbanItemRequest): Promise<KanbanItem> {
  const { data } = await client.post<KanbanItem>(BASE, request);
  return data;
}

export async function updateKanbanItem(
  id: string,
  request: UpdateKanbanItemRequest
): Promise<KanbanItem> {
  const { data } = await client.put<KanbanItem>(`${BASE}/${encodeURIComponent(id)}`, request);
  return data;
}

export async function transitionKanbanItem(
  id: string,
  request: TransitionKanbanRequest
): Promise<KanbanItem> {
  const { data } = await client.post<KanbanItem>(
    `${BASE}/${encodeURIComponent(id)}/transition`,
    request
  );
  return data;
}

export async function deleteKanbanItem(id: string): Promise<void> {
  await client.delete(`${BASE}/${encodeURIComponent(id)}`);
}
