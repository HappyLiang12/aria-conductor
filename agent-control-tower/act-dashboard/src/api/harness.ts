import client from './client';
import type { HarnessProfile } from '../types';

export async function listHarnessProfiles(): Promise<HarnessProfile[]> {
  const { data } = await client.get<HarnessProfile[]>('/api/v1/harness-profiles');
  return data;
}

export async function getHarnessProfile(name: string): Promise<HarnessProfile> {
  const { data } = await client.get<HarnessProfile>(`/api/v1/harness-profiles/${name}`);
  return data;
}
