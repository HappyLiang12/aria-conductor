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

/** Same endpoint as getTemplates — named for the Kanban new-task modal picker. */
export const listAgentTemplates = getTemplates;

export async function createFromTemplate(templateName: string): Promise<Agent> {
  const { data } = await client.post<Agent>(`/api/v1/agents/from-template/${templateName}`);
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
  skills: AdkSkill[];
}

/** Rule-based recommended skills for a role (used to pre-check the create UI). */
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

/** Idempotent bulk-replace: set the agent's skills to exactly these ids. */
export async function setAgentSkills(id: string, ids: string[]): Promise<AdkSkill[]> {
  const { data } = await client.put<AdkSkill[]>(`/api/v1/agents/${id}/skills`, { ids });
  return data;
}
