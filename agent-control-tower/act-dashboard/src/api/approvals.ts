import client from './client';
import type {
  Approval,
  ApprovalDecision,
  ApprovalDecisionReceipt,
  ApprovalStatus,
} from '../types';

export async function listApprovals(status?: ApprovalStatus): Promise<Approval[]> {
  const { data } = await client.get<Approval[]>('/api/v1/approvals', {
    params: status ? { status } : undefined,
  });
  return data;
}

/**
 * One ask by id (`GET /api/v1/approvals/{id}`, spec A2). The WS
 * `approval.requested` frame carries no tool name, tier or policy, so the panel
 * reads the ask it names instead of inventing a card from the frame; and an ask
 * that left the PENDING list is read back to learn what it became. The endpoint
 * serves the same shape as the list (a 404 when the ask no longer exists).
 */
export async function getApproval(id: string): Promise<Approval> {
  const { data } = await client.get<Approval>(`/api/v1/approvals/${encodeURIComponent(id)}`);
  return data;
}

export async function decideApproval(
  id: string,
  decision: ApprovalDecision
): Promise<ApprovalDecisionReceipt> {
  const { data } = await client.post<ApprovalDecisionReceipt>(
    `/api/v1/approvals/${id}/decide`,
    decision
  );
  return data;
}

export async function approveApproval(id: string, reason?: string): Promise<ApprovalDecisionReceipt> {
  return decideApproval(id, { approved: true, reason });
}

export async function rejectApproval(id: string, reason?: string): Promise<ApprovalDecisionReceipt> {
  return decideApproval(id, { approved: false, reason });
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

/** Finalize a run parked in WAITING_INPUT (2026-10-05): ends it with the question preserved. */
export async function finalizeRun(runId: string): Promise<void> {
  await client.post(`/api/v1/runs/${runId}/finalize`);
}
