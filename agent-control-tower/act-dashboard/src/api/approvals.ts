import client from './client';
import type { Approval, ApprovalDecision, ApprovalStatus } from '../types';

export async function listApprovals(status?: ApprovalStatus): Promise<Approval[]> {
  const { data } = await client.get<Approval[]>('/api/v1/approvals', {
    params: status ? { status } : undefined,
  });
  return data;
}

export async function decideApproval(id: string, decision: ApprovalDecision): Promise<Approval> {
  const { data } = await client.post<Approval>(`/api/v1/approvals/${id}/decide`, decision);
  return data;
}

export async function approveApproval(id: string, reason?: string): Promise<Approval> {
  try {
    const { data } = await client.post<Approval>(`/api/v1/approvals/${id}/approve`, { reason });
    return data;
  } catch (err) {
    // Fallback to /decide endpoint if /approve is not available
    return decideApproval(id, { approved: true, reason });
  }
}

export async function rejectApproval(id: string, reason?: string): Promise<Approval> {
  try {
    const { data } = await client.post<Approval>(`/api/v1/approvals/${id}/reject`, { reason });
    return data;
  } catch (err) {
    return decideApproval(id, { approved: false, reason });
  }
}

/** Per-card asks (HITL): approvals/questions attached to a kanban item. */
export async function listAsksByKanbanItem(kanbanItemId: string): Promise<Approval[]> {
  const { data } = await client.get<Approval[]>('/api/v1/approvals', {
    params: { kanbanItemId },
  });
  return data;
}

/** Answer an ask: free-text answer and/or an approve/deny decision. */
export async function answerAsk(
  id: string,
  payload: { answer?: string; approved?: boolean; reason?: string }
): Promise<Approval> {
  const { data } = await client.post<Approval>(`/api/v1/approvals/${id}/answer`, payload);
  return data;
}
