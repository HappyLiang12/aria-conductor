import client from './client';
import type { AdkProviderInfo, AdkProviderHealth } from '../types';

export async function listAdkProviders(): Promise<AdkProviderInfo[]> {
  const { data } = await client.get<AdkProviderInfo[]>('/api/v1/adk/providers');
  return data;
}

export async function getAdkProviderHealth(id: string): Promise<AdkProviderHealth> {
  const { data } = await client.get<AdkProviderHealth>(`/api/v1/adk/providers/${id}/health`);
  return data;
}
