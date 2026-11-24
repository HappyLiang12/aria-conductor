export const queryKeys = {
  summary: ['dashboard-summary'] as const,
  providers: ['adk-providers'] as const,
  providerHealth: (id: string) => ['adk-provider-health', id] as const,
  approvals: ['approvals'] as const,
  kanban: ['kanban-items'] as const,
  runs: ['runs'] as const,
  run: (id: string) => ['run', id] as const,
  agents: ['agents'] as const,
  knowledge: ['knowledge'] as const,
  reports: ['reports'] as const,
  workflows: ['workflows'] as const,
  notifications: ['notifications'] as const,
};
