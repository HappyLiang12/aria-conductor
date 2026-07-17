import client from './client';
import type {
  CreateEvidenceRequest,
  DoDRecord,
  DoDStatusResponse,
  EvidenceItem,
  InitDoDRequest,
  SubmitReviewRequest,
} from '../types';

const BASE = '/api/v1/dod';

export async function initDoD(request: InitDoDRequest): Promise<DoDRecord> {
  const { data } = await client.post<DoDRecord>(`${BASE}/init`, request);
  return data;
}

export async function submitReview(request: SubmitReviewRequest): Promise<DoDRecord> {
  const { data } = await client.post<DoDRecord>(`${BASE}/review`, request);
  return data;
}

export async function getDoDStatus(taskId: string): Promise<DoDStatusResponse> {
  const { data } = await client.get<DoDStatusResponse>(`${BASE}/${encodeURIComponent(taskId)}`);
  return data;
}

export async function listEvidence(taskId: string): Promise<EvidenceItem[]> {
  const { data } = await client.get<EvidenceItem[]>(`${BASE}/${encodeURIComponent(taskId)}/evidence`);
  return data;
}

export async function addEvidence(
  taskId: string,
  request: CreateEvidenceRequest
): Promise<EvidenceItem> {
  const { data } = await client.post<EvidenceItem>(
    `${BASE}/${encodeURIComponent(taskId)}/evidence`,
    request
  );
  return data;
}
