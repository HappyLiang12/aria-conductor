import client from './client';
import type { WorkflowChain } from '../types';

export async function listWorkflows(): Promise<WorkflowChain[]> {
  const { data } = await client.get<WorkflowChain[]>('/api/v1/workflows');
  return data;
}

export async function getWorkflow(id: string): Promise<WorkflowChain> {
  const { data } = await client.get<WorkflowChain>(`/api/v1/workflows/${id}`);
  return data;
}

export interface CreateWorkflowStepDef {
  agentId: string;
  promptTemplate: string;
  maxIterations?: number;
}

export interface CreateWorkflowRequest {
  name: string;
  steps: CreateWorkflowStepDef[];
}

export async function createWorkflow(req: CreateWorkflowRequest): Promise<WorkflowChain> {
  const { data } = await client.post<WorkflowChain>('/api/v1/workflows', req);
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

export async function updateWorkflow(id: string, body: { name?: string; description?: string }): Promise<WorkflowChain> {
  const { data } = await client.put<WorkflowChain>(`/api/v1/workflows/${id}`, body);
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

export async function reuseWorkflow(templateId: string, parameters?: Record<string, string>): Promise<WorkflowChain> {
  const { data } = await client.post<WorkflowChain>(`/api/v1/workflows/templates/${templateId}/reuse`, { parameters });
  return data;
}
