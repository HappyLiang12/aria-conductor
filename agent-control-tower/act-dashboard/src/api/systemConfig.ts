import client from './client';

export interface SystemConfig {
  configKey: string;
  configValue: string;
  description: string;
  updatedAt: string;
}

export async function listConfig(): Promise<SystemConfig[]> {
  const { data } = await client.get<SystemConfig[]>('/api/v1/system-config');
  return data;
}

export async function getConfig(key: string): Promise<SystemConfig> {
  const { data } = await client.get<SystemConfig>(`/api/v1/system-config/${key}`);
  return data;
}

export async function updateConfig(key: string, value: string): Promise<SystemConfig> {
  const { data } = await client.put<SystemConfig>(`/api/v1/system-config/${key}`, { value });
  return data;
}

export async function resetConfig(key: string): Promise<SystemConfig> {
  const { data } = await client.post<SystemConfig>(`/api/v1/system-config/${key}/reset`);
  return data;
}
