import client from './client';
import type { Agent, CreateAgentRequest, AgentTemplate } from '../types';

export async function listAgents(): Promise<Agent[]> {
  const { data } = await client.get<Agent[]>('/api/v1/agents');
  return data;
}

export async function getAgent(id: string): Promise<Agent> {
  const { data } = await client.get<Agent>(`/api/v1/agents/${id}`);
  return data;
}

export async function createAgent(req: CreateAgentRequest): Promise<Agent> {
  const { data } = await client.post<Agent>('/api/v1/agents', req);
  return data;
}

export async function updateAgent(id: string, req: Partial<CreateAgentRequest>): Promise<Agent> {
  const { data } = await client.put<Agent>(`/api/v1/agents/${id}`, req);
  return data;
}

export async function retireAgent(id: string): Promise<void> {
  await client.post(`/api/v1/agents/${id}/retire`);
}

export async function getTemplates(): Promise<AgentTemplate[]> {
  const { data } = await client.get<AgentTemplate[]>('/api/v1/agents/templates');
  return data;
}

export async function createFromTemplate(templateName: string): Promise<Agent> {
  const { data } = await client.post<Agent>(`/api/v1/agents/from-template/${templateName}`);
  return data;
}

export interface AdkTool {
  id: string;
  name: string;
  displayName?: string;
  description?: string;
  category?: string;
  enabled: boolean;
}

export async function listTools(): Promise<AdkTool[]> {
  const { data } = await client.get<AdkTool[]>('/api/v1/tools');
  return data;
}

export async function getAgentTools(id: string): Promise<AdkTool[]> {
  const { data } = await client.get<AdkTool[]>(`/api/v1/agents/${id}/tools`);
  return data;
}

export async function assignAgentTool(id: string, toolId: string): Promise<AdkTool[]> {
  const { data } = await client.post<AdkTool[]>(`/api/v1/agents/${id}/tools`, { toolId });
  return data;
}

export async function unassignAgentTool(id: string, toolId: string): Promise<AdkTool[]> {
  const { data } = await client.delete<AdkTool[]>(`/api/v1/agents/${id}/tools/${toolId}`);
  return data;
}
