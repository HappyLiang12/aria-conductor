import client from './client';
import type { WorkflowChain } from '../types';

export async function listWorkflows(): Promise<WorkflowChain[]> {
  const { data } = await client.get<WorkflowChain[]>('/api/v1/workflows');
  return data;
}

export async function cancelWorkflow(id: string): Promise<WorkflowChain> {
  const { data } = await client.post<WorkflowChain>(`/api/v1/workflows/${id}/cancel`);
  return data;
}

export async function retryWorkflow(id: string, stepIndex: number): Promise<WorkflowChain> {
  const { data } = await client.post<WorkflowChain>(`/api/v1/workflows/${id}/retry`, { stepIndex });
  return data;
}

export async function resubmitApproval(id: string): Promise<WorkflowChain> {
  const { data } = await client.post<WorkflowChain>(`/api/v1/workflows/${id}/resubmit-approval`);
  return data;
}

export async function deleteWorkflow(id: string): Promise<void> {
  await client.delete(`/api/v1/workflows/${id}`);
}

export async function mergeWorkflows(sourceIds: string[], name: string): Promise<WorkflowChain> {
  const { data } = await client.post<WorkflowChain>('/api/v1/workflows/merge', { sourceIds, name });
  return data;
}

export async function executeYaml(yamlContent: string, parameters?: Record<string, string>): Promise<any> {
  const { data } = await client.post('/api/v1/workflows/execute-yaml', { yamlContent, parameters });
  return data;
}
