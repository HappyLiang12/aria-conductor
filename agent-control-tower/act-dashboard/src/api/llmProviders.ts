import client from './client';
import { applyOperatorHeaders } from './operatorSession';
import type { LlmProvider, CreateLlmProviderRequest, LlmProviderTestResult } from '../types';

export async function listProviders(): Promise<LlmProvider[]> {
  const { data } = await client.get<LlmProvider[]>('/api/v1/llm-providers');
  return data;
}

// Every mutation is operator-only server-side, so each one applies the stored
// operator session before the request (the two reads stay anonymous).

export async function createProvider(req: CreateLlmProviderRequest): Promise<LlmProvider> {
  applyOperatorHeaders();
  const { data } = await client.post<LlmProvider>('/api/v1/llm-providers', req);
  return data;
}

export async function updateProvider(id: string, req: Partial<CreateLlmProviderRequest>): Promise<LlmProvider> {
  applyOperatorHeaders();
  const { data } = await client.put<LlmProvider>(`/api/v1/llm-providers/${id}`, req);
  return data;
}

export async function deleteProvider(id: string): Promise<void> {
  applyOperatorHeaders();
  await client.delete(`/api/v1/llm-providers/${id}`);
}

export async function testProvider(id: string): Promise<LlmProviderTestResult> {
  applyOperatorHeaders();
  const { data } = await client.post<LlmProviderTestResult>(`/api/v1/llm-providers/${id}/test`);
  return data;
}

export async function activateProvider(id: string): Promise<LlmProvider> {
  applyOperatorHeaders();
  const { data } = await client.post<LlmProvider>(`/api/v1/llm-providers/${id}/activate`);
  return data;
}
