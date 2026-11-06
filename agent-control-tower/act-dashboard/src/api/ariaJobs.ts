import client from './client';
import type { ScheduledJob, CreateScheduledJobRequest } from '../types';

export async function listJobs(params?: {
  category?: string;
  status?: string;
}): Promise<ScheduledJob[]> {
  const { data } = await client.get<ScheduledJob[]>('/api/v1/aria/jobs', { params });
  return data;
}

export async function createJob(req: CreateScheduledJobRequest): Promise<ScheduledJob> {
  const { data } = await client.post<ScheduledJob>('/api/v1/aria/jobs', req);
  return data;
}

export async function updateJob(id: string, req: CreateScheduledJobRequest): Promise<ScheduledJob> {
  const { data } = await client.put<ScheduledJob>(`/api/v1/aria/jobs/${id}`, req);
  return data;
}

export async function deleteJob(id: string): Promise<void> {
  await client.delete(`/api/v1/aria/jobs/${id}`);
}

export async function pauseJob(id: string): Promise<ScheduledJob> {
  const { data } = await client.patch<ScheduledJob>(`/api/v1/aria/jobs/${id}/pause`);
  return data;
}

export async function resumeJob(id: string): Promise<ScheduledJob> {
  const { data } = await client.patch<ScheduledJob>(`/api/v1/aria/jobs/${id}/resume`);
  return data;
}
