import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { listAgents, createAgent, getTemplates, getRoleDefaults, setAgentTools, setAgentSkills } from '../api/agents';
import { getAgentTelemetry } from '../api/dashboard';
import type { Agent, CreateAgentRequest, AgentTemplate, AgentTelemetry } from '../types';
import { AgentCard } from '../components/AgentCard';
import { AgentCatalog } from '../components/AgentCatalog';
import { ManageToolsDialog } from '../components/ManageToolsDialog';
import { estimateCost } from '../utils/pricing';

/**
 * Crew view — a "control room" for the agent roster.
 *
 * Layout:
 *  1. View header with title + "Add Agent" CTA.
 *  2. Live cost banner derived from the current roster.
 *  3. Active agent grid (rendered as <AgentCard>).
 *  4. Hire-from-catalog template grid (<AgentCatalog>).
 *
 * Add-Agent flow uses the .mini-scrim / .mini-dialog convention so the
 * roster page never navigates away to a separate form.
 */

interface AddAgentForm {
  name: string;
  role: string;
  model: string;
  maxToolCallRounds: number;
}

const EMPTY_FORM: AddAgentForm = {
  name: '',
  role: 'dev',
  model: '',
  maxToolCallRounds: 15,
};

export default function CrewPage() {
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [form, setForm] = useState<AddAgentForm>(EMPTY_FORM);
  const [error, setError] = useState<string | null>(null);
  const [toolsAgent, setToolsAgent] = useState<Agent | null>(null);
  const [selectedTools, setSelectedTools] = useState<Set<string>>(new Set());
  const [selectedSkills, setSelectedSkills] = useState<Set<string>>(new Set());

  const toggle = (prev: Set<string>, id: string, on: boolean) => {
    const next = new Set(prev);
    if (on) next.add(id); else next.delete(id);
    return next;
  };

  const { data: agents, isLoading, error: queryError } = useQuery({
    queryKey: ['agents'],
    queryFn: listAgents,
  });

  const { data: templates } = useQuery({
    queryKey: ['agent-templates'],
    queryFn: getTemplates,
  });

  const { data: roleDefaults } = useQuery({
    queryKey: ['role-defaults', form.role],
    queryFn: () => getRoleDefaults(form.role),
    enabled: dialogOpen && !!form.role,
  });

  // Pre-check the role's recommended tools + skills when the dialog opens or the role changes.
  useEffect(() => {
    if (!dialogOpen || !roleDefaults) return;
    setSelectedTools(new Set(roleDefaults.tools.map((t) => t.id)));
    setSelectedSkills(new Set(roleDefaults.skills.map((s) => s.id)));
  }, [dialogOpen, roleDefaults]);

  const {
    data: telemetryList,
    isError: telemetryError,
  } = useQuery({
    queryKey: ['agent-telemetry'],
    queryFn: getAgentTelemetry,
    refetchInterval: 30_000,
    retry: 2,
  });

  const createMutation = useMutation({
    mutationFn: async (body: CreateAgentRequest) => {
      const agent = await createAgent(body);
      // Persist the confirmed recommendations via the bulk-replace endpoints.
      await Promise.all([
        setAgentTools(agent.id, [...selectedTools]),
        setAgentSkills(agent.id, [...selectedSkills]),
      ]);
      return agent;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agents'], refetchType: 'active' });
      setDialogOpen(false);
      setForm(EMPTY_FORM);
      setError(null);
    },
    onError: (err: unknown) => {
      const message = err instanceof Error ? err.message : 'Failed to create agent';
      setError(message);
    },
  });

  const activeAgents: Agent[] = useMemo(
    () => (agents ?? []).filter((a) => a.healthStatus !== 'RETIRED'),
    [agents],
  );

  const telemetryByAgent = useMemo(() => {
    const map = new Map<string, AgentTelemetry>();
    if (telemetryList) {
      for (const t of telemetryList) {
        map.set(t.agentId, t);
      }
    }
    return map;
  }, [telemetryList]);

  const costSummary = useMemo(() => {
    let totalTokens = 0;
    let totalSpend = 0;
    for (const a of activeAgents) {
      if (a.healthStatus === 'UNHEALTHY') continue;
      const t = telemetryByAgent.get(a.id);
      const tokens = t?.totalTokensToday ?? 0;
      totalTokens += tokens;
      totalSpend += estimateCost(tokens);
    }
    return {
      totalTokens,
      totalSpend,
      onlineCount: activeAgents.filter(a => a.healthStatus !== 'UNHEALTHY').length,
      idleCount: activeAgents.filter(a => a.healthStatus === 'DEGRADED').length,
    };
  }, [activeAgents, telemetryByAgent]);

  const openDialog = () => {
    setForm(EMPTY_FORM);
    setError(null);
    setDialogOpen(true);
  };

  const closeDialog = () => {
    if (createMutation.isPending) return;
    setDialogOpen(false);
    setError(null);
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const name = form.name.trim();
    if (!name) {
      setError('Name is required');
      return;
    }
    const selectedTemplate = templates?.find((t) => t.role === form.role);
    const body: CreateAgentRequest = {
      name,
      agentType: selectedTemplate?.agentType || 'NATIVE',
      role: form.role,
      model: form.model.trim() || selectedTemplate?.model || undefined,
      provider: selectedTemplate?.provider || 'openai',
      description: selectedTemplate?.description,
      adkProvider: 'langchain',
      config: { maxToolCallRounds: form.maxToolCallRounds },
    };
    createMutation.mutate(body);
  };

  return (
    <section className="view-zone" data-view="crew" style={{ display: 'block' }}>
      <div style={{ padding: 16 }}>
        <div className="view-header">
          <div>
            <h1>👥 Crew</h1>
            <div className="sub">
              Active agents, workload signals, and a hiring catalog of pre-configured roles.
            </div>
          </div>
          <div className="actions">
            <button type="button" className="btn primary" onClick={openDialog}>
              + Add Agent
            </button>
          </div>
        </div>

        {/* Cost banner */}
        <section className="panel" style={{ marginBottom: 16 }}>
          <h2>Roster Cost <span className="accent">· today</span></h2>
          {telemetryError && (
            <div style={{ fontSize: 11, color: 'var(--amber)', marginBottom: 8 }}>
              ⚠ Telemetry temporarily unavailable — showing cached or zero values
            </div>
          )}
          <div className="crew-cost">
            <div className="cell">
              <span className="l">Active</span>
              <span className="v">{activeAgents.length}</span>
              <span className="d">{costSummary.onlineCount} reachable · {costSummary.idleCount} idle</span>
            </div>
            <div className="cell tok">
              <span className="l">Tokens · today</span>
              <span className="v">{formatTokens(costSummary.totalTokens)}</span>
              <span className="d">aggregate across crew</span>
            </div>
            <div className="cell spend">
              <span className="l">Estimated spend</span>
              <span className="v">${costSummary.totalSpend.toFixed(2)}</span>
              <span className="d">@ ~$0.012 / 1k tokens</span>
            </div>
            <div className="cell idle">
              <span className="l">Hiring slots</span>
              <span className="v">{Math.max(0, 12 - activeAgents.length)}</span>
              <span className="d">soft cap · adjustable in Settings</span>
            </div>
          </div>
        </section>

        {/* Active agents */}
        <section className="panel">
          <h2>
            Active Agents
            <span className="accent">· {activeAgents.length} on crew</span>
          </h2>
          {isLoading && (
            <div className="crew-empty">Loading crew…</div>
          )}
          {queryError && !isLoading && (
            <div className="crew-empty" style={{ color: 'var(--red)' }}>
              Failed to load agents. Retry from the rail.
            </div>
          )}
          {!isLoading && !queryError && activeAgents.length === 0 && (
            <div className="crew-empty">
              No agents on the crew yet. Hire one from the catalog below or click <strong>+ Add Agent</strong>.
            </div>
          )}
          {!isLoading && activeAgents.length > 0 && (
            <div className="crew-grid">
              {activeAgents.map((a) => (
                <AgentCard key={a.id} agent={a} telemetry={telemetryByAgent.get(a.id)} onManageTools={setToolsAgent} />
              ))}
            </div>
          )}
        </section>

        {/* Catalog */}
        <section className="panel" style={{ marginTop: 16 }}>
          <h2>Hire from Catalog <span className="accent">· deploy a pre-configured role</span></h2>
          <AgentCatalog />
        </section>
      </div>

      {/* Add Agent mini dialog */}
      <div
        className={`mini-scrim ${dialogOpen ? 'open' : ''}`}
        onClick={closeDialog}
        aria-hidden={!dialogOpen}
      />
      <div
        className={`mini-dialog ${dialogOpen ? 'open' : ''}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby="add-agent-title"
      >
        <h3 id="add-agent-title">Add Agent</h3>
        <p>Provision a new agent on the crew. You can refine its prompt in the drawer afterwards.</p>
        <form onSubmit={handleSubmit}>
          <label htmlFor="add-agent-name">Name</label>
          <input
            id="add-agent-name"
            type="text"
            value={form.name}
            placeholder="e.g. Atlas-7 · Release Steward"
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            autoFocus
          />

          <label htmlFor="add-agent-role">Role</label>
          <select
            id="add-agent-role"
            value={form.role}
            onChange={(e) => setForm({ ...form, role: e.target.value })}
          >
            {(templates ?? []).map((t) => (
              <option key={t.id} value={t.role}>{t.label}</option>
            ))}
          </select>

          <label htmlFor="add-agent-model">Model <span style={{ textTransform: 'none', color: 'var(--text-mute)' }}>(optional)</span></label>
          <input
            id="add-agent-model"
            type="text"
            value={form.model}
            placeholder="e.g. gpt-4o-mini"
            onChange={(e) => setForm({ ...form, model: e.target.value })}
          />

          <div style={{ marginTop: 12 }}>
            <label>Recommended tools <span style={{ textTransform: 'none', color: 'var(--text-mute)' }}>· {selectedTools.size} selected</span></label>
            <div style={{ maxHeight: 132, overflowY: 'auto', border: '1px solid var(--border, #2a2a2a)', borderRadius: 6, padding: 6 }}>
              {(roleDefaults?.tools ?? []).length === 0 && (
                <div style={{ color: 'var(--text-mute)', fontSize: 12 }}>No default tools for this role.</div>
              )}
              {(roleDefaults?.tools ?? []).map((t) => (
                <label key={t.id} style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 12.5, padding: '2px 0' }}>
                  <input
                    type="checkbox"
                    checked={selectedTools.has(t.id)}
                    onChange={(e) => setSelectedTools((p) => toggle(p, t.id, e.target.checked))}
                  />
                  <span>{t.displayName || t.name}</span>
                </label>
              ))}
            </div>
          </div>

          <div style={{ marginTop: 10 }}>
            <label>Recommended skills <span style={{ textTransform: 'none', color: 'var(--text-mute)' }}>· {selectedSkills.size} selected</span></label>
            <div style={{ maxHeight: 120, overflowY: 'auto', border: '1px solid var(--border, #2a2a2a)', borderRadius: 6, padding: 6 }}>
              {(roleDefaults?.skills ?? []).length === 0 && (
                <div style={{ color: 'var(--text-mute)', fontSize: 12 }}>No default skills for this role yet.</div>
              )}
              {(roleDefaults?.skills ?? []).map((s) => (
                <label key={s.id} style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 12.5, padding: '2px 0' }}>
                  <input
                    type="checkbox"
                    checked={selectedSkills.has(s.id)}
                    onChange={(e) => setSelectedSkills((p) => toggle(p, s.id, e.target.checked))}
                  />
                  <span>{s.name}</span>
                </label>
              ))}
            </div>
          </div>

          {error && (
            <div style={{ marginTop: 10, color: 'var(--red)', fontSize: 11.5 }}>{error}</div>
          )}

          <div className="actions">
            <button
              type="button"
              className="btn"
              onClick={closeDialog}
              disabled={createMutation.isPending}
            >
              Cancel
            </button>
            <button
              type="submit"
              className="btn primary"
              disabled={createMutation.isPending}
            >
              {createMutation.isPending ? 'Hiring…' : 'Hire Agent'}
            </button>
          </div>
        </form>
      </div>

      <ManageToolsDialog agent={toolsAgent} onClose={() => setToolsAgent(null)} />
    </section>
  );
}

function formatTokens(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(2) + 'M';
  if (n >= 1_000)     return (n / 1_000).toFixed(1) + 'k';
  return String(n);
}
