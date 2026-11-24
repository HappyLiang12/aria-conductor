import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useWebSocketContext } from '../components/Layout';
import { queryKeys } from '../api/queryKeys';

const RUN_EVENTS = ['run.started', 'run.completed', 'run.waiting_input', 'run.progress', 'run.iteration'];
const APPROVAL_EVENTS = ['approval.requested', 'approval.decided', 'approval.expired'];

export function keysForAmbientEvent(type: string): readonly (readonly unknown[])[] {
  if (RUN_EVENTS.includes(type)) return [queryKeys.runs, queryKeys.summary];
  if (APPROVAL_EVENTS.includes(type)) return [queryKeys.approvals, queryKeys.summary];
  if (type.startsWith('kanban.')) return [queryKeys.kanban, queryKeys.summary];
  if (type.startsWith('knowledge.')) return [queryKeys.knowledge];
  if (type.startsWith('report.')) return [queryKeys.reports];
  if (type === 'workflow.advanced') return [queryKeys.workflows];
  if (type === 'agent.created') return [queryKeys.agents];
  return [];
}

export function useAmbientInvalidation(): void {
  const client = useQueryClient();
  const { subscribe } = useWebSocketContext();
  useEffect(() => subscribe((event) => {
    for (const key of keysForAmbientEvent(event.type)) {
      void client.invalidateQueries({ queryKey: key });
    }
  }).unsubscribe, [client, subscribe]);
}
