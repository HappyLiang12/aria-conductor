import client from './client';
import type { LlmProvider, CreateLlmProviderRequest, LlmProviderTestResult } from '../types';

export async function listProviders(): Promise<LlmProvider[]> {
  const { data } = await client.get<LlmProvider[]>('/api/v1/llm-providers');
  return data;
}

export async function createProvider(req: CreateLlmProviderRequest): Promise<LlmProvider> {
  const { data } = await client.post<LlmProvider>('/api/v1/llm-providers', req);
  return data;
}

export async function updateProvider(id: string, req: Partial<CreateLlmProviderRequest>): Promise<LlmProvider> {
  const { data } = await client.put<LlmProvider>(`/api/v1/llm-providers/${id}`, req);
  return data;
}

export async function deleteProvider(id: string): Promise<void> {
  await client.delete(`/api/v1/llm-providers/${id}`);
}

export async function testProvider(id: string): Promise<LlmProviderTestResult> {
  const { data } = await client.post<LlmProviderTestResult>(`/api/v1/llm-providers/${id}/test`);
  return data;
}

export async function activateProvider(id: string): Promise<LlmProvider> {
  const { data } = await client.post<LlmProvider>(`/api/v1/llm-providers/${id}/activate`);
  return data;
}
