import client from './client';
import type { Approval, ApprovalStatus, Run, DashboardSummary, ActivityEvent } from '../types';

/**
 * Ops-surface API helpers — composes approvals, runs, and dashboard
 * summary endpoints into a single command-center module.
 */

export async function listApprovalsByStatus(status?: ApprovalStatus): Promise<Approval[]> {
  const params = status ? { status } : undefined;
  const { data } = await client.get<Approval[]>('/api/v1/approvals', { params });
  return data;
}

export async function approveApproval(id: string, reason?: string): Promise<Approval> {
  const { data } = await client.post<Approval>(`/api/v1/approvals/${id}/approve`, { reason });
  return data;
}

export async function rejectApproval(id: string, reason?: string): Promise<Approval> {
  const { data } = await client.post<Approval>(`/api/v1/approvals/${id}/reject`, { reason });
  return data;
}

export async function listRecentRuns(): Promise<Run[]> {
  const { data } = await client.get<Run[]>('/api/v1/runs');
  return data;
}

export async function getOpsSummary(): Promise<DashboardSummary> {
  const { data } = await client.get<DashboardSummary>('/api/v1/dashboard/summary');
  return data;
}

export async function getOpsActivity(): Promise<ActivityEvent[]> {
  const { data } = await client.get<ActivityEvent[]>('/api/v1/dashboard/activity');
  return data;
}
