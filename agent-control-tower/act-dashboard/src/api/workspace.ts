import client from './client';
import type { WorkspaceDiff } from '../types';

export async function getWorkspaceDiff(runId: string): Promise<WorkspaceDiff> {
  const { data } = await client.get<WorkspaceDiff>(`/api/v1/runs/${runId}/workspace-diff`);
  return data;
}
