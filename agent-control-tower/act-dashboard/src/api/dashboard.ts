import client from './client';
import type { DashboardSummary, ActivityEvent, AgentTelemetry } from '../types';

export async function getSummary(): Promise<DashboardSummary> {
  const { data } = await client.get<DashboardSummary>('/api/v1/dashboard/summary');
  return data;
}

export async function getRecentActivity(): Promise<ActivityEvent[]> {
  const { data } = await client.get<ActivityEvent[]>('/api/v1/dashboard/activity');
  return data;
}

export async function getAgentTelemetry(): Promise<AgentTelemetry[]> {
  const { data } = await client.get<AgentTelemetry[]>('/api/v1/dashboard/agent-telemetry');
  return data;
}