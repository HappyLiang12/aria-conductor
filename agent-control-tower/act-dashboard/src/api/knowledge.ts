import client from './client';
import type { KnowledgeItem, CreateKnowledgeRequest, KnowledgeVersion, KnowledgeReviewRequest } from '../types';

export async function listKnowledge(): Promise<KnowledgeItem[]> {
  const { data } = await client.get<KnowledgeItem[]>('/api/v1/knowledge');
  return data;
}

export async function getKnowledge(id: string): Promise<KnowledgeItem> {
  const { data } = await client.get<KnowledgeItem>(`/api/v1/knowledge/${id}`);
  return data;
}

export async function createKnowledge(req: CreateKnowledgeRequest): Promise<KnowledgeItem> {
  const { data } = await client.post<KnowledgeItem>('/api/v1/knowledge', req);
  return data;
}

export async function retireKnowledge(id: string): Promise<void> {
  await client.post(`/api/v1/knowledge/${id}/retire`);
}

export async function reviewKnowledge(id: string, req: KnowledgeReviewRequest): Promise<KnowledgeItem> {
  const { data } = await client.post<KnowledgeItem>(`/api/v1/knowledge/${id}/review`, req);
  return data;
}

export async function promoteKnowledge(id: string): Promise<KnowledgeItem> {
  const { data } = await client.post<KnowledgeItem>(`/api/v1/knowledge/${id}/promote`);
  return data;
}

export async function getKnowledgeVersions(id: string): Promise<KnowledgeVersion[]> {
  const { data } = await client.get<KnowledgeVersion[]>(`/api/v1/knowledge/${id}/versions`);
  return data;
}

// PATCH-style facade: routes to /review (approve/reject) or /promote based on payload.
export interface UpdateKnowledgeRequest {
  status?: 'APPROVED' | 'REJECTED' | 'PROMOTED';
  reason?: string;
}
export async function updateKnowledge(id: string, req: UpdateKnowledgeRequest): Promise<KnowledgeItem> {
  if (req.status === 'PROMOTED') {
    return promoteKnowledge(id);
  }
  if (req.status === 'APPROVED' || req.status === 'REJECTED') {
    return reviewKnowledge(id, { decision: req.status, reason: req.reason });
  }
  // Fallback: no-op fetch
  return getKnowledge(id);
}

// Convenience: batched approve/reject. Returns settled results.
export async function batchReviewKnowledge(
  ids: string[],
  approved: boolean,
  reason?: string,
): Promise<PromiseSettledResult<KnowledgeItem>[]> {
  return Promise.allSettled(
    ids.map((id) =>
      reviewKnowledge(id, { decision: approved ? 'APPROVED' : 'REJECTED', reason }),
    ),
  );
}

export async function getKnowledgeYaml(id: string): Promise<string> {
  const { data } = await client.get<string>(`/api/v1/knowledge/${id}/yaml`, { responseType: 'text' });
  return data;
}
