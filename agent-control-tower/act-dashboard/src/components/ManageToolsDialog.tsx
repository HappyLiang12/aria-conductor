import { useMemo } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  listTools, getAgentTools, assignAgentTool, unassignAgentTool,
  getAgentSkills, assignAgentSkill, unassignAgentSkill,
  getRoleDefaults, setAgentTools, setAgentSkills,
} from '../api/agents';
import { listSkills } from '../api/skills';
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

  const { data: allSkills } = useQuery({
    queryKey: ['skills'],
    queryFn: () => listSkills(),
    enabled: open,
  });

  const { data: assignedSkills } = useQuery({
    queryKey: ['agent-skills', agentId],
    queryFn: () => getAgentSkills(agentId),
    enabled: open,
  });

  const assignedIds = useMemo(
    () => new Set((assigned ?? []).map((t) => t.id)),
    [assigned],
  );
  const assignedSkillIds = useMemo(
    () => new Set((assignedSkills ?? []).map((s) => s.id)),
    [assignedSkills],
  );

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['agent-tools', agentId] });
    qc.invalidateQueries({ queryKey: ['agent-skills', agentId] });
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
  const assignSkillMut = useMutation({
    mutationFn: (skillId: string) => assignAgentSkill(agentId, skillId),
    onSuccess: invalidate,
  });
  const unassignSkillMut = useMutation({
    mutationFn: (skillId: string) => unassignAgentSkill(agentId, skillId),
    onSuccess: invalidate,
  });
  const applyDefaultsMut = useMutation({
    mutationFn: async () => {
      const defaults = await getRoleDefaults(agent?.role ?? '');
      await Promise.all([
        setAgentTools(agentId, defaults.tools.map((t) => t.id)),
        setAgentSkills(agentId, defaults.skills.map((s) => s.id)),
      ]);
    },
    onSuccess: invalidate,
  });

  const busy = assignMut.isPending || unassignMut.isPending
    || assignSkillMut.isPending || unassignSkillMut.isPending || applyDefaultsMut.isPending;

  const toggle = (toolId: string, on: boolean) => {
    if (on) assignMut.mutate(toolId);
    else unassignMut.mutate(toolId);
  };
  const toggleSkill = (skillId: string, on: boolean) => {
    if (on) assignSkillMut.mutate(skillId);
    else unassignSkillMut.mutate(skillId);
  };

  return (
    <>
      <div className={`mini-scrim ${open ? 'open' : ''}`} onClick={onClose} aria-hidden={!open} />
      {/* F3: closed dialogs must leave the a11y tree (opacity alone kept them
          focusable/visible to assistive tech). */}
      <div
        className={`mini-dialog ${open ? 'open' : ''}`}
        role="dialog"
        aria-modal="true"
        aria-hidden={!open}
        inert={!open}
      >
        <h3>Capabilities{agent ? ` · ${agent.name}` : ''}</h3>
        <p>Choose the tools and skills this agent may use. Only APPROVED + enabled items are listed.</p>
        <div style={{ fontSize: 12.5, fontWeight: 600, margin: '4px 0' }}>Tools</div>
        <div style={{ maxHeight: 240, overflowY: 'auto', margin: '4px 0' }}>
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
        <div style={{ fontSize: 12.5, fontWeight: 600, margin: '8px 0 4px' }}>Skills</div>
        <div style={{ maxHeight: 200, overflowY: 'auto', margin: '4px 0' }}>
          {(allSkills ?? []).filter((s) => s.enabled !== false && s.id).length === 0 && (
            <div style={{ color: 'var(--text-mute)', fontSize: 12 }}>
              No approved skills available yet.
            </div>
          )}
          {(allSkills ?? []).filter((s) => s.enabled !== false && s.id).map((s) => (
            <label
              key={s.id}
              style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '4px 2px', fontSize: 12.5 }}
            >
              <input
                type="checkbox"
                checked={assignedSkillIds.has(s.id!)}
                disabled={busy}
                onChange={(e) => toggleSkill(s.id!, e.target.checked)}
              />
              <span style={{ fontWeight: 600 }}>{s.name}</span>
              {s.category && <span style={{ color: 'var(--text-mute)' }}>· {s.category}</span>}
            </label>
          ))}
        </div>
        <div className="actions">
          <button
            type="button"
            className="btn"
            disabled={busy || !agent?.role}
            onClick={() => applyDefaultsMut.mutate()}
          >
            {applyDefaultsMut.isPending ? 'Applying…' : 'Apply role defaults'}
          </button>
          <button type="button" className="btn primary" onClick={onClose}>Done</button>
        </div>
      </div>
    </>
  );
}

export default ManageToolsDialog;
