import client from './client';
import type { Run, CreateRunRequest, SessionTrajectory, ToolCall } from '../types';

export async function listRuns(): Promise<Run[]> {
  const { data } = await client.get<Run[]>('/api/v1/runs');
  return data;
}

export async function getRun(id: string): Promise<Run> {
  const { data } = await client.get<Run>(`/api/v1/runs/${id}`);
  return data;
}

export async function createRun(req: CreateRunRequest): Promise<Run> {
  const { data } = await client.post<Run>('/api/v1/runs', req);
  return data;
}

export async function pauseRun(id: string): Promise<void> {
  await client.post(`/api/v1/runs/${id}/pause`);
}

export async function resumeRun(id: string): Promise<void> {
  await client.post(`/api/v1/runs/${id}/resume`);
}

export async function cancelRun(id: string): Promise<void> {
  await client.post(`/api/v1/runs/${id}/cancel`);
}

export async function getRunTrajectory(runId: string): Promise<SessionTrajectory[]> {
  const { data } = await client.get<SessionTrajectory[]>(`/api/v1/runs/${runId}/trajectory`);
  return data;
}

export async function getRunToolCalls(runId: string): Promise<ToolCall[]> {
  const { data } = await client.get<ToolCall[]>(`/api/v1/runs/${runId}/tool-calls`);
  return data;
}

export async function injectRunMessage(
  runId: string,
  content: string,
  role: string = 'user',
): Promise<{ id: string; turnNumber: number }> {
  const { data } = await client.post<{ id: string; turnNumber: number }>(
    `/api/v1/runs/${runId}/inject`,
    { content, role },
  );
  return data;
}