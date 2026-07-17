import { useMemo } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listTools, getAgentTools, assignAgentTool, unassignAgentTool } from '../api/agents';
import type { Agent } from '../types';

/**
 * ManageToolsDialog — assign / unassign approved tools to a specific agent.
 *
 * Lists all APPROVED + enabled tools (GET /api/v1/tools) and marks the ones
 * currently assigned to the agent (GET /api/v1/agents/{id}/tools). Toggling a
 * checkbox calls the assign/unassign endpoints. The user has full control over
 * the agent's toolset via this UI.
 */
interface Props {
  agent: Agent | null;
  onClose: () => void;
}

export function ManageToolsDialog({ agent, onClose }: Props) {
  const qc = useQueryClient();
  const open = !!agent;
  const agentId = agent?.id ?? '';

  const { data: allTools } = useQuery({
    queryKey: ['tools'],
    queryFn: listTools,
    enabled: open,
  });

  const { data: assigned } = useQuery({
    queryKey: ['agent-tools', agentId],
    queryFn: () => getAgentTools(agentId),
    enabled: open,
  });

  const assignedIds = useMemo(
    () => new Set((assigned ?? []).map((t) => t.id)),
    [assigned],
  );

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['agent-tools', agentId] });
    qc.invalidateQueries({ queryKey: ['agents'] });
  };

  const assignMut = useMutation({
    mutationFn: (toolId: string) => assignAgentTool(agentId, toolId),
    onSuccess: invalidate,
  });
  const unassignMut = useMutation({
    mutationFn: (toolId: string) => unassignAgentTool(agentId, toolId),
    onSuccess: invalidate,
  });

  const busy = assignMut.isPending || unassignMut.isPending;

  const toggle = (toolId: string, on: boolean) => {
    if (on) assignMut.mutate(toolId);
    else unassignMut.mutate(toolId);
  };

  return (
    <>
      <div className={`mini-scrim ${open ? 'open' : ''}`} onClick={onClose} aria-hidden={!open} />
      <div className={`mini-dialog ${open ? 'open' : ''}`} role="dialog" aria-modal="true">
        <h3>Assign Tools{agent ? ` · ${agent.name}` : ''}</h3>
        <p>Select which tools this agent may use. Only APPROVED + enabled tools are listed.</p>
        <div style={{ maxHeight: 360, overflowY: 'auto', margin: '8px 0' }}>
          {(allTools ?? []).length === 0 && (
            <div style={{ color: 'var(--text-mute)', fontSize: 12 }}>
              No approved tools available yet.
            </div>
          )}
          {(allTools ?? []).map((t) => (
            <label
              key={t.id}
              style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '4px 2px', fontSize: 12.5 }}
            >
              <input
                type="checkbox"
                checked={assignedIds.has(t.id)}
                disabled={busy}
                onChange={(e) => toggle(t.id, e.target.checked)}
              />
              <span style={{ fontWeight: 600 }}>{t.displayName || t.name}</span>
              {t.category && <span style={{ color: 'var(--text-mute)' }}>· {t.category}</span>}
            </label>
          ))}
        </div>
        <div className="actions">
          <button type="button" className="btn primary" onClick={onClose}>Done</button>
        </div>
      </div>
    </>
  );
}

export default ManageToolsDialog;
