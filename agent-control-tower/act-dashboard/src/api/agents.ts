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

export interface AdkSkill {
  id: string;
  name: string;
  description?: string;
  template?: string;
  stage?: string;
}

export interface RoleDefaults {
  tools: AdkTool[];
  skills: AdkSkill[];
}

/** Rule-based recommended tools + skills for a role (used to pre-check the create/manage UIs). */
export async function getRoleDefaults(role: string): Promise<RoleDefaults> {
  const { data } = await client.get<RoleDefaults>(`/api/v1/agents/role-defaults/${encodeURIComponent(role)}`);
  return data;
}

export async function getAgentSkills(id: string): Promise<AdkSkill[]> {
  const { data } = await client.get<AdkSkill[]>(`/api/v1/agents/${id}/skills`);
  return data;
}

export async function assignAgentSkill(id: string, skillId: string): Promise<AdkSkill[]> {
  const { data } = await client.post<AdkSkill[]>(`/api/v1/agents/${id}/skills`, { skillId });
  return data;
}

export async function unassignAgentSkill(id: string, skillId: string): Promise<AdkSkill[]> {
  const { data } = await client.delete<AdkSkill[]>(`/api/v1/agents/${id}/skills/${skillId}`);
  return data;
}

/** Idempotent bulk-replace: set the agent's tools to exactly these ids. */
export async function setAgentTools(id: string, ids: string[]): Promise<AdkTool[]> {
  const { data } = await client.put<AdkTool[]>(`/api/v1/agents/${id}/tools`, { ids });
  return data;
}

/** Idempotent bulk-replace: set the agent's skills to exactly these ids. */
export async function setAgentSkills(id: string, ids: string[]): Promise<AdkSkill[]> {
  const { data } = await client.put<AdkSkill[]>(`/api/v1/agents/${id}/skills`, { ids });
  return data;
}
