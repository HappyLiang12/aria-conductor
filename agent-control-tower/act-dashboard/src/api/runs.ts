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

/**
 * One persisted progress OBSERVATION, as `GET /api/v1/runs/{id}/progress` replays it.
 *
 * A row is an observation of one message part: the endpoint returns the ordered log,
 * so a part that grew between two polls appears twice with the SAME `partId` and an
 * increasing `seq`. The client is the single place that collapses those observations
 * into one stream line (see AgentDrawer's part merge), which is what makes a reopened
 * drawer show exactly what the live stream showed.
 *
 * `partId` is optional and nullable: rows persisted before the `part_id` column (V74)
 * and rows from a core that reports no part id carry none, and `seq` is then the only
 * identity the line has.
 */
export interface RunProgressEntry {
  id: string;
  runId: string;
  agentId: string;
  iteration: number;
  kind: string;
  seq: number;
  content: string;
  toolName: string | null;
  partId?: string | null;
  createdAt: string;
}

export async function getRunProgress(runId: string, afterSeq = 0): Promise<RunProgressEntry[]> {
  const { data } = await client.get<RunProgressEntry[]>(
    `/api/v1/runs/${runId}/progress?afterSeq=${afterSeq}`,
  );
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