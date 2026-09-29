import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { listAgents, createAgent, getTemplates, getRoleDefaults, setAgentTools, setAgentSkills, retireAgent } from '../api/agents';
import { getAgentTelemetry } from '../api/dashboard';
import { listAdkProviders, getAdkProviderHealth } from '../api/adk';
import { apiErrorMessage } from '../api/operatorSession';
import type {
  Agent,
  AgentCore,
  CreateAgentRequest,
  AgentTelemetry,
  ExecutionMode,
  WorkspaceMode,
} from '../types';
import { AgentCard } from '../components/AgentCard';
import { AgentCatalog } from '../components/AgentCatalog';
import { ManageToolsDialog } from '../components/ManageToolsDialog';
import { ConfirmDialog } from '../components/ConfirmDialog';
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
 * roster page never navigates away to a separate form. The dialog selects the
 * governed core explicitly from the provider inventory, the execution mode
 * through an explicit picker (Host is never preselected) and, for Host runs,
 * the workspace binding (worktree default, base ref, or an explicitly selected
 * Direct directory). A removed-core value renders as an explicit unsupported
 * state instead of silently falling back to another core.
 */

interface AddAgentForm {
  name: string;
  role: string;
  model: string;
  /** Core id from the live provider inventory; '' until one is selected. */
  adkProvider: string;
  executionMode: ExecutionMode;
  workspaceMode: WorkspaceMode;
  workspacePath: string;
  workspaceBaseRef: string;
  maxToolCallRounds: number;
}

const EMPTY_FORM: AddAgentForm = {
  name: '',
  role: 'dev',
  model: '',
  // No invented core: the dialog applies the inventory's declared default (or
  // asks for an explicit choice when none is declared).
  adkProvider: '',
  // The documented placement default; Host is never preselected.
  executionMode: 'SANDBOX',
  // Worktree is the default when a Host repository is configured.
  workspaceMode: 'WORKTREE',
  workspacePath: '',
  workspaceBaseRef: '',
  maxToolCallRounds: 15,
};

const EXECUTION_MODE_LABELS: Record<ExecutionMode, string> = {
  SANDBOX: 'Sandbox',
  HOST: 'Host',
};

/** The execution contract's documented pair; a core's declared list wins over it. */
const DOCUMENTED_EXECUTION_MODES: ExecutionMode[] = ['SANDBOX', 'HOST'];

/**
 * Explicit execution-mode selector. The trigger displays exactly the current
 * mode; choosing another mode — including Host — is an explicit action in the
 * opened list, so no mode is ever selected implicitly.
 */
function ExecutionModePicker({
  value,
  modes,
  disabled,
  onChange,
}: {
  value: ExecutionMode;
  modes: ExecutionMode[];
  disabled: boolean;
  onChange: (mode: ExecutionMode) => void;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div style={{ position: 'relative' }}>
      <span
        id="add-agent-execution-mode-label"
        style={{ display: 'block', fontSize: 10.5, color: 'var(--text-dim)', margin: '8px 0 4px', letterSpacing: '.8px', textTransform: 'uppercase' }}
      >
        Execution mode
      </span>
      <button
        type="button"
        id="add-agent-execution-mode"
        className="mode-picker-trigger"
        aria-labelledby="add-agent-execution-mode-label"
        aria-haspopup="listbox"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => setOpen((prev) => !prev)}
        style={{
          width: '100%', padding: '8px 10px', borderRadius: 8, textAlign: 'left',
          background: 'rgba(0,0,0,.25)', color: 'var(--text)', border: '1px solid var(--line-2)',
          font: 'inherit', fontSize: 13, cursor: 'pointer',
        }}
      >
        {EXECUTION_MODE_LABELS[value]}
      </button>
      {open && (
        <div
          role="listbox"
          aria-label="Execution modes"
          style={{
            position: 'absolute', left: 0, right: 0, top: '100%', zIndex: 20,
            background: '#131b32', border: '1px solid var(--line-2)', borderRadius: 8,
            boxShadow: '0 12px 30px rgba(0,0,0,.5)', padding: 4,
          }}
        >
          {modes.map((mode) => (
            <button
              key={mode}
              type="button"
              role="option"
              aria-selected={mode === value}
              onClick={() => {
                onChange(mode);
                setOpen(false);
              }}
              style={{
                display: 'block', width: '100%', textAlign: 'left', padding: '7px 8px',
                borderRadius: 6, border: 'none', background: mode === value ? 'rgba(91,140,255,.16)' : 'transparent',
                color: 'var(--text)', font: 'inherit', fontSize: 13, cursor: 'pointer',
              }}
            >
              {EXECUTION_MODE_LABELS[mode]}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

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
  // Bulk retire is destructive, so it is gated behind a confirmation: the
  // Retire selected… control only opens this flag, never the retire itself.
  const [confirmingRetire, setConfirmingRetire] = useState(false);
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

  const { data: adkProviders, isLoading: coreInventoryLoading } = useQuery({
    queryKey: ['adk-providers'],
    queryFn: listAdkProviders,
  });

  // The live provider inventory is the only core list: no built-in fallback,
  // so a removed core can never be offered as a selectable option.
  const coreInventoryAvailable = (adkProviders?.length ?? 0) > 0;
  const selectedCoreInfo = (adkProviders ?? []).find((p) => p.id === form.adkProvider);

  // Apply the inventory's declared default core explicitly; an operator's (or a
  // template's) own value is never overwritten.
  useEffect(() => {
    if (!dialogOpen || form.adkProvider !== '') return;
    const declaredDefault = (adkProviders ?? []).find((p) => p.isDefault)?.id;
    if (!declaredDefault) return;
    setForm((prev) => (prev.adkProvider === '' ? { ...prev, adkProvider: declaredDefault } : prev));
  }, [dialogOpen, adkProviders, form.adkProvider]);

  // Readiness of the selected core: configuration/process health, probed
  // without agent context (mode-aware in the sense that the pair being
  // validated is named; the probe itself is core-scoped).
  const { data: coreHealth, isLoading: coreHealthLoading, isError: coreHealthError } = useQuery({
    queryKey: ['adk-provider-health', form.adkProvider],
    queryFn: () => getAdkProviderHealth(form.adkProvider),
    enabled: !!selectedCoreInfo,
    retry: false,
  });

  // Modes the selected core declares; when the inventory declares none the
  // documented pair is offered and the backend admission policy stays the
  // authority that rejects an unsupported pair with its exact message.
  const supportedModes: ExecutionMode[] =
    selectedCoreInfo?.executionModes && selectedCoreInfo.executionModes.length > 0
      ? selectedCoreInfo.executionModes
      : DOCUMENTED_EXECUTION_MODES;

  const trimmedWorkspacePath = form.workspacePath.trim();
  const trimmedWorkspaceBaseRef = form.workspaceBaseRef.trim();

  /**
   * The blocking selection problem of the current form, or null. Messages
   * mirror the backend admission policy's wording so the same rejection reads
   * identically whether it is caught here or returned by the server.
   */
  const selectionError: string | null = coreInventoryLoading
    ? null
    : !coreInventoryAvailable
      ? 'Agent core inventory unavailable — no governed core was reported.'
      : form.adkProvider === ''
        ? 'Select an agent core'
        : !selectedCoreInfo
          ? `Unsupported agent core: ${form.adkProvider}`
          : !supportedModes.includes(form.executionMode)
            ? `Unsupported execution mode: ${form.adkProvider}/${form.executionMode}`
            : form.executionMode === 'HOST' && trimmedWorkspacePath === ''
              ? form.workspaceMode === 'WORKTREE'
                ? 'Host worktree mode requires a repository path'
                : 'Direct mode requires an explicitly selected directory'
              : null;

  // The inventory is the only core source: before it answers, no core has been
  // selected and a submit would carry an empty `adkProvider`, so submission
  // stays closed until the loading resolves (unlike `selectionError`, which is
  // deliberately null while loading).
  const submissionBlocked = coreInventoryLoading || selectionError !== null;

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
        // The admission policy's exact rejection ("Unsupported agent core: …",
        // "Unsupported execution mode: …", "Direct workspace requires …") is
        // surfaced instead of a generic message.
        setError(apiErrorMessage(e, e?.message ?? 'Failed to create agent'));
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
  const leftoverAgents = useMemo(
    () => activeAgents.filter((a) => a.name.startsWith('e2e-') || a.healthStatus === 'UNHEALTHY'),
    [activeAgents],
  );

  const selectLeftovers = () => {
    if (leftoverAgents.length === 0) {
      setRetireNote('🧹 No leftover agents found (e2e-* / UNHEALTHY).');
      return;
    }
    setRetireNote(null);
    setSelectedAgents(new Set(leftoverAgents.map((a) => a.id)));
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

  // Retire runs only from the confirmation's Confirm handler. Clearing the gate
  // first makes a double-fire impossible, and clearing again on completion
  // (success or failure) guarantees the dialog is never left stuck open.
  const confirmRetire = async () => {
    setConfirmingRetire(false);
    try {
      await retireSelected();
    } finally {
      setConfirmingRetire(false);
    }
  };

  const cancelRetire = () => setConfirmingRetire(false);

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
    if (submissionBlocked) {
      // The blocking selection problem is already rendered in the dialog and the
      // submit control is disabled while it is present; the still-loading
      // inventory is likewise not submittable (no core could be named yet).
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
      // Guarded by `selectionError`: the id was validated against the live
      // provider inventory, which is the same source the admission policy uses.
      adkProvider: form.adkProvider as AgentCore,
      executionMode: form.executionMode,
      config: { maxToolCallRounds: form.maxToolCallRounds },
    };
    if (form.executionMode === 'HOST') {
      // The workspace binding only travels with a Host run; a sandbox run has
      // no workspace selection to carry.
      body.workspaceMode = form.workspaceMode;
      body.workspacePath = trimmedWorkspacePath;
      if (form.workspaceMode === 'WORKTREE' && trimmedWorkspaceBaseRef !== '') {
        body.workspaceBaseRef = trimmedWorkspaceBaseRef;
      }
    }
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
              🧹 Select Leftovers ({leftoverAgents.length})
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
              <button
                className="btn danger"
                disabled={retireBusy}
                onClick={() => setConfirmingRetire(true)}
              >
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
        style={{ maxHeight: '86vh', overflowY: 'auto' }}
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
              // Template-driven form: carry the template's core verbatim. A
              // removed core is kept (and flagged unsupported) — never
              // substituted for another; a template without a core leaves the
              // choice to the inventory default / the operator.
              const template = templates?.find((t) => t.role === role);
              setForm((prev) => ({
                ...prev,
                role,
                adkProvider: template?.adkProvider ?? '',
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

          <label htmlFor="add-agent-core">Agent core</label>
          <select
            id="add-agent-core"
            value={form.adkProvider}
            onChange={(e) => setForm({ ...form, adkProvider: e.target.value })}
          >
            {form.adkProvider === '' && (
              <option value="" disabled>Select an agent core…</option>
            )}
            {form.adkProvider !== '' && !selectedCoreInfo && (
              // A stored/removed core is shown as unsupported, never remapped.
              <option value={form.adkProvider}>{form.adkProvider} — unsupported core</option>
            )}
            {(adkProviders ?? []).map((p) => (
              <option key={p.id} value={p.id}>{p.displayName}</option>
            ))}
          </select>

          <ExecutionModePicker
            value={form.executionMode}
            modes={supportedModes}
            disabled={!selectedCoreInfo}
            onChange={(mode) =>
              setForm((prev) => ({
                ...prev,
                executionMode: mode,
                // Leaving Host drops nothing; entering Direct clears a base ref
                // so a stale ref can never be submitted with a Direct run.
                workspaceBaseRef: mode === 'HOST' ? prev.workspaceBaseRef : '',
              }))
            }
          />

          {selectedCoreInfo && (
            <div
              style={{
                marginTop: 8, fontSize: 11.5,
                color: coreHealthError || coreHealth?.healthy === false ? 'var(--amber)' : 'var(--text-mute)',
              }}
            >
              {coreHealthLoading
                ? `Probing ${selectedCoreInfo.displayName}…`
                : coreHealth?.healthy
                  ? `Readiness: ${selectedCoreInfo.displayName}/${form.executionMode} — the core answered its health probe.`
                  : `Readiness: ${selectedCoreInfo.displayName}/${form.executionMode} — the core did not answer its health probe; launches may fail admission.`}
            </div>
          )}

          {form.executionMode === 'HOST' && (
            <>
              <label htmlFor="add-agent-workspace-mode">Host workspace mode</label>
              <select
                id="add-agent-workspace-mode"
                value={form.workspaceMode}
                onChange={(e) => {
                  const workspaceMode = e.target.value as WorkspaceMode;
                  setForm((prev) => ({
                    ...prev,
                    workspaceMode,
                    // Base ref applies only to a worktree.
                    workspaceBaseRef: workspaceMode === 'DIRECT' ? '' : prev.workspaceBaseRef,
                  }));
                }}
              >
                <option value="WORKTREE">Worktree (managed from the admitted repository)</option>
                <option value="DIRECT">Direct (an explicitly selected trusted directory)</option>
              </select>

              {form.workspaceMode === 'WORKTREE' ? (
                <>
                  <label htmlFor="add-agent-workspace-path">Repository path</label>
                  <input
                    id="add-agent-workspace-path"
                    type="text"
                    value={form.workspacePath}
                    placeholder="e.g. /srv/repos/atlas"
                    onChange={(e) => setForm({ ...form, workspacePath: e.target.value })}
                  />
                  <label htmlFor="add-agent-workspace-base-ref">Base ref (optional)</label>
                  <input
                    id="add-agent-workspace-base-ref"
                    type="text"
                    value={form.workspaceBaseRef}
                    placeholder="e.g. main"
                    onChange={(e) => setForm({ ...form, workspaceBaseRef: e.target.value })}
                  />
                </>
              ) : (
                <>
                  <label htmlFor="add-agent-workspace-path">Direct directory</label>
                  <input
                    id="add-agent-workspace-path"
                    type="text"
                    value={form.workspacePath}
                    placeholder="Trusted backend-local directory"
                    onChange={(e) => setForm({ ...form, workspacePath: e.target.value })}
                  />
                  <div style={{ marginTop: 6, fontSize: 11, color: 'var(--text-mute)' }}>
                    Direct is never inferred from the browser's location and never falls back to
                    a worktree; it leases exactly the directory you name here.
                  </div>
                </>
              )}
            </>
          )}

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

          {selectionError && (
            <div style={{ marginTop: 10, color: 'var(--amber)', fontSize: 11.5 }}>{selectionError}</div>
          )}
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
              disabled={createMutation.isPending || submissionBlocked}
            >
              {createMutation.isPending ? 'Hiring…' : 'Hire Agent'}
            </button>
          </div>
        </form>
      </div>

      <ManageToolsDialog agent={toolsAgent} onClose={() => setToolsAgent(null)} />

      {/* Bulk retire confirmation — shared destructive-action dialog (Task 10) */}
      <ConfirmDialog
        open={confirmingRetire}
        title="Retire selected agents?"
        message={
          <>
            You are about to retire <strong>{selectedAgents.size}</strong> agent(s). They will be
            removed from the active crew.
          </>
        }
        danger
        onConfirm={confirmRetire}
        onCancel={cancelRetire}
      />
    </section>
  );
}

function formatTokens(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(2) + 'M';
  if (n >= 1_000)     return (n / 1_000).toFixed(1) + 'k';
  return String(n);
}
