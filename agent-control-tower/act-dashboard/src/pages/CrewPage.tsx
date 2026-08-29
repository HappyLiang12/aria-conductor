import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { listAgents, createAgent, getTemplates, getRoleDefaults, setAgentTools, setAgentSkills, retireAgent } from '../api/agents';
import { getAgentTelemetry } from '../api/dashboard';
import { listAdkProviders } from '../api/adk';
import type { Agent, CreateAgentRequest, AgentTemplate, AgentTelemetry, AdkProviderInfo } from '../types';
import { AgentCard } from '../components/AgentCard';
import { AgentCatalog } from '../components/AgentCatalog';
import { ManageToolsDialog } from '../components/ManageToolsDialog';
import { estimateCost } from '../utils/pricing';

interface CreateAgentError extends Error {
  agentCreated?: boolean;
}

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
  adkProvider: string;
  maxToolCallRounds: number;
}

const EMPTY_FORM: AddAgentForm = {
  name: '',
  role: 'dev',
  model: '',
  adkProvider: 'langchain',
  maxToolCallRounds: 15,
};

export default function CrewPage() {
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [form, setForm] = useState<AddAgentForm>(EMPTY_FORM);
  const [error, setError] = useState<string | null>(null);
  const [toolsAgent, setToolsAgent] = useState<Agent | null>(null);
  // H3 bulk retire selection.
  const [selectedAgents, setSelectedAgents] = useState<Set<string>>(new Set());
  const [retireBusy, setRetireBusy] = useState(false);
  const [retireNote, setRetireNote] = useState<string | null>(null);
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

  const { data: adkProviders } = useQuery({
    queryKey: ['adk-providers'],
    queryFn: listAdkProviders,
  });

  // Fall back to the built-in LangChain ADK option when the providers API is
  // empty or unavailable (option text keeps the 'LangChain' substring so the
  // existing E2E selectors that pick the langchain value stay compatible).
  const adkProviderOptions: AdkProviderInfo[] =
    adkProviders && adkProviders.length > 0
      ? adkProviders
      : [{ id: 'langchain', displayName: 'LangChain ADK', supportsTaskExecution: false, isDefault: true }];

  // Pre-check the role's recommended tools + skills when the dialog opens or the role changes.
  // Reset to the (possibly still-loading) defaults so a stale prior-role selection is never persisted.
  useEffect(() => {
    if (!dialogOpen) return;
    setSelectedTools(new Set((roleDefaults?.tools ?? []).map((t) => t.id)));
    setSelectedSkills(new Set((roleDefaults?.skills ?? []).map((s) => s.id)));
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
    mutationFn: async (body: CreateAgentRequest): Promise<Agent> => {
      const agent = await createAgent(body);
      try {
        // Persist the confirmed recommendations via the bulk-replace endpoints.
        await Promise.all([
          setAgentTools(agent.id, [...selectedTools]),
          setAgentSkills(agent.id, [...selectedSkills]),
        ]);
      } catch (e) {
        // The agent WAS created; only applying recommendations failed. Surface that
        // accurately (and let onSuccess reveal the agent) instead of "Failed to create".
        const err = (e instanceof Error ? e : new Error(String(e))) as CreateAgentError;
        err.agentCreated = true;
        throw err;
      }
      return agent;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agents'], refetchType: 'active' });
      setDialogOpen(false);
      setForm(EMPTY_FORM);
      setError(null);
    },
    onError: (err: unknown) => {
      const e = err as CreateAgentError;
      if (e?.agentCreated) {
        queryClient.invalidateQueries({ queryKey: ['agents'], refetchType: 'active' });
        setDialogOpen(false);
        setForm(EMPTY_FORM);
        setError('Agent created, but applying the recommended tools/skills failed. Adjust them in Manage Tools.');
      } else {
        setError(e?.message ?? 'Failed to create agent');
      }
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

  // H3: one-click preset for obvious leftovers (e2e test agents / unhealthy).
  const selectLeftovers = () => {
    const ids = activeAgents
      .filter((a) => a.name.startsWith('e2e-') || a.healthStatus === 'UNHEALTHY')
      .map((a) => a.id);
    setSelectedAgents(new Set(ids));
  };

  const retireSelected = async () => {
    setRetireBusy(true);
    setRetireNote(null);
    const failures: string[] = [];
    for (const id of [...selectedAgents]) {
      try {
        await retireAgent(id);
      } catch (e) {
        failures.push(`${id.slice(0, 8)}: ${(e as Error)?.message ?? 'failed'}`);
      }
    }
    setSelectedAgents(new Set());
    setRetireBusy(false);
    setRetireNote(
      failures.length ? `⚠ Some retires failed — ${failures.join('; ')}` : '🧹 Selected agents retired.',
    );
    queryClient.invalidateQueries({ queryKey: ['agents'] });
  };

  const openDialog = () => {
    setForm(EMPTY_FORM);
    setError(null);
    setSelectedTools(new Set());
    setSelectedSkills(new Set());
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
      adkProvider: form.adkProvider || selectedTemplate?.adkProvider || 'langchain',
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
            <button type="button" className="btn" onClick={selectLeftovers}>
              select leftovers
            </button>
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
                <AgentCard
                  key={a.id}
                  agent={a}
                  telemetry={telemetryByAgent.get(a.id)}
                  onManageTools={setToolsAgent}
                  selected={selectedAgents.has(a.id)}
                  onSelect={() =>
                    setSelectedAgents((prev) => toggle(prev, a.id, !prev.has(a.id)))
                  }
                />
              ))}
            </div>
          )}
          {selectedAgents.size > 0 && (
            <div
              style={{
                display: 'flex', gap: 10, alignItems: 'center', marginTop: 12,
                border: '1px dashed rgba(94,234,212,.4)', borderRadius: 10, padding: '8px 12px',
              }}
            >
              <span>{selectedAgents.size} selected</span>
              <span style={{ flex: 1 }} />
              <button className="btn danger" disabled={retireBusy} onClick={retireSelected}>
                Retire selected…
              </button>
            </div>
          )}
          {retireNote && <div style={{ marginTop: 8, fontSize: 11.5 }}>{retireNote}</div>}
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
        aria-hidden={!dialogOpen}
        inert={!dialogOpen}
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
            onChange={(e) => {
              const role = e.target.value;
              // Template-driven form: back-fill the template's ADK provider
              // (fallback 'langchain') when a role/template is applied.
              const template = templates?.find((t) => t.role === role);
              setForm((prev) => ({
                ...prev,
                role,
                adkProvider: template?.adkProvider || 'langchain',
              }));
            }}
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

          <label htmlFor="add-agent-adk-provider">ADK Provider</label>
          <select
            id="add-agent-adk-provider"
            value={form.adkProvider}
            onChange={(e) => setForm({ ...form, adkProvider: e.target.value })}
          >
            {adkProviderOptions.map((p) => (
              <option key={p.id} value={p.id}>{p.displayName}</option>
            ))}
          </select>

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
