import client from './client';
import type { Run, DashboardSummary, ActivityEvent } from '../types';

/**
 * Ops-surface API helpers — composes runs and dashboard summary endpoints into
 * a single command-center module. Approvals are deliberately NOT wrapped here:
 * this module used to carry its own `listApprovalsByStatus`/`approveApproval`
 * copies, and the drift between those copies and the canonical helpers is what
 * produced the Operations-page 404. Import them from ./approvals instead.
 */

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
