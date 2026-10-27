import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { SettingsPage } from '../pages/SettingsPage';
import { SystemConfigPanel } from './SystemConfigPanel';
import { RuntimeCredentialsCard } from './RuntimeCredentialsCard';
import { ConfirmDialog } from './ConfirmDialog';
import { listSkills } from '../api/skills';
import { applyFleetRuntime, listAgents } from '../api/agents';
import { listAdkProviders } from '../api/adk';
import { composeRuntimes } from '../utils/runtimeComposition';
import type { ExecutionMode } from '../types';
import '../styles/configure-flow.css';

/**
 * Pipeline stages shown in the Approval Gates tab.
 * Order is significant — gates sit between consecutive stages.
 */
interface Stage {
  id: string;
  name: string;
  icon: string;
}

const STAGES: Stage[] = [
  { id: 'draft', name: 'Draft', icon: '✎' },
  { id: 'dev', name: 'Dev', icon: '⚙' },
  { id: 'review', name: 'Code Review', icon: '👁' },
  { id: 'qa', name: 'QA', icon: '✓' },
  { id: 'security', name: 'Security', icon: '🛡' },
  { id: 'staging', name: 'Staging', icon: '🚉' },
  { id: 'production', name: 'Production', icon: '🚀' },
];

interface Gate {
  id: string;
  from: string;
  to: string;
  label: string;
  on: boolean;
  role: string;
  sla: number;
}

const APPROVER_ROLES = [
  'Tech Lead',
  'QA Lead',
  'Security Officer',
  'Release Manager',
  'Product Owner',
  'Compliance',
];

const DEFAULT_GATES: Gate[] = [
  { id: 'g-draft-dev', from: 'draft', to: 'dev', label: 'Draft → Dev', on: false, role: 'Tech Lead', sla: 8 },
  { id: 'g-dev-review', from: 'dev', to: 'review', label: 'Dev → Code Review', on: true, role: 'Tech Lead', sla: 4 },
  { id: 'g-review-qa', from: 'review', to: 'qa', label: 'Code Review → QA', on: true, role: 'Tech Lead', sla: 4 },
  { id: 'g-qa-security', from: 'qa', to: 'security', label: 'QA → Security', on: false, role: 'QA Lead', sla: 8 },
  { id: 'g-security-staging', from: 'security', to: 'staging', label: 'Security → Staging', on: true, role: 'Security Officer', sla: 12 },
  { id: 'g-staging-prod', from: 'staging', to: 'production', label: 'Staging → Production', on: true, role: 'Release Manager', sla: 24 },
];

interface SkillRow {
  name: string;
  category: string;
  description: string;
}

// B2: Skills are loaded from the backend skill registry (GET /api/v1/skills), not hardcoded.
// The registry has no permission field — the UI must not invent one (Task 12).

export function ConfigureModal({ initialTab = 'gates' }: { initialTab?: string } = {}) {
  const [open, setOpen] = useState(false);
  const [activeTab, setActiveTab] = useState<string>(initialTab);

  // B2: Skills tab is backed by the real skill registry (GET /api/v1/skills), not hardcoded data.
  const { data: realSkills } = useQuery({ queryKey: ['skills'], queryFn: () => listSkills(), enabled: open });
  const skills: SkillRow[] = (realSkills ?? []).map((s) => ({
    name: s.name,
    category: s.category ?? 'General',
    description: s.description ?? '',
  }));

  // Listen for the rail-nav custom event that opens the modal. The event may
  // carry { detail: { tab } } to open directly on a specific tab (the Overview
  // runtime card uses it).
  useEffect(() => {
    const handler = (e: Event) => {
      setOpen(true);
      const tab = (e as CustomEvent<{ tab?: string }>).detail?.tab;
      if (tab) setActiveTab(tab);
    };
    window.addEventListener('act:open-configure', handler);
    return () => window.removeEventListener('act:open-configure', handler);
  }, []);

  // Escape key closes the modal when it is open.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  // Sync the body class so global CSS can react if needed (rail-nav already sets it).
  useEffect(() => {
    if (open) document.body.classList.add('configure-open');
    else document.body.classList.remove('configure-open');
    return () => document.body.classList.remove('configure-open');
  }, [open]);

  const close = () => setOpen(false);

  // Gates and skills are reference data in this build; there is no editable
  // policy state to reset. The control stays on Runtime only (Task 12).
  const resetDefaults = () => {
    /* no editable local policy state: gates/skills come from the build and the
       real skill registry, and the runtime pair is re-read from the backend. */
  };

  return (
    <>
      <div
        className={`modal-scrim${open ? ' open' : ''}`}
        onClick={close}
        aria-hidden={!open}
      />
      <div
        className={`modal${open ? ' open' : ''}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby="configure-title"
        aria-hidden={!open}
        style={{ width: 'min(720px, 94vw)' }}
      >
        <header>
          <h2 id="configure-title">
            <b>Configure</b> Governance &amp; Capabilities
          </h2>
          <span
            className="x"
            role="button"
            tabIndex={0}
            aria-label="Close configuration"
            onClick={close}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') close();
            }}
          >
            ✕
          </span>
        </header>

        <div className="tabs" role="tablist">
          <div
            className={`tab${activeTab === 'gates' ? ' active' : ''}`}
            role="tab"
            aria-selected={activeTab === 'gates'}
            onClick={() => setActiveTab('gates')}
          >
            🛡 Approval Gates
          </div>
          <div
            className={`tab${activeTab === 'skills' ? ' active' : ''}`}
            role="tab"
            aria-selected={activeTab === 'skills'}
            onClick={() => setActiveTab('skills')}
          >
            🧰 Skills
          </div>
          <div
            className={`tab${activeTab === 'llm' ? ' active' : ''}`}
            role="tab"
            aria-selected={activeTab === 'llm'}
            onClick={() => setActiveTab('llm')}
          >
            🤖 LLM Providers
          </div>
          <div
            className={`tab${activeTab === 'config' ? ' active' : ''}`}
            role="tab"
            aria-selected={activeTab === 'config'}
            onClick={() => setActiveTab('config')}
          >
            ⚙️ System Config
          </div>
          <div
            className={`tab${activeTab === 'runtime' ? ' active' : ''}`}
            role="tab"
            aria-selected={activeTab === 'runtime'}
            onClick={() => setActiveTab('runtime')}
          >
            🧩 Runtime
          </div>
        </div>

        <div className="tab-body">
          {activeTab === 'gates' && (
            <GatesPane gates={DEFAULT_GATES} />
          )}
          {activeTab === 'skills' && (
            <SkillsPane skills={skills} />
          )}
          {activeTab === 'llm' && (
            <div style={{ padding: '0', margin: '0' }}>
              <SettingsPage />
              {/* One-time Qoder runtime credential setup lives next to the LLM
                  provider config; the card carries its own top-margin
                  separation (matching the modal's margin-based block spacing). */}
              <RuntimeCredentialsCard />
            </div>
          )}
          {activeTab === 'config' && (
            <SystemConfigPanel />
          )}
          {activeTab === 'runtime' && (
            <RuntimePane />
          )}
        </div>

        <footer>
          <div className="info">
            {activeTab === 'gates'
              ? 'Reference only — gates are not enforced'
              : activeTab === 'skills'
              ? `${skills.length} skills in the read-only catalog`
              : activeTab === 'llm'
              ? 'Configure LLM providers and the one-time Qoder runtime credential'
              : activeTab === 'runtime'
              ? 'One runtime for the whole fleet - applying updates every agent'
              : 'Runtime settings stored in the database — changes take effect immediately'}
          </div>
          <div className="actions">
            {activeTab === 'runtime' && (
              <button type="button" className="btn" onClick={resetDefaults}>
                Reset to defaults
              </button>
            )}
            <button type="button" className="btn primary" onClick={close}>
              Done
            </button>
          </div>
        </footer>
      </div>
    </>
  );
}

interface GatesPaneProps {
  gates: Gate[];
}

function GatesPane({ gates }: GatesPaneProps) {
  const gateBetween = (from: string, to: string) =>
    gates.find((g) => g.from === from && g.to === to);

  return (
    <>
      <div className="configure-reference-note">
        Reference only — not enforced by the backend. This panel does not change runtime behavior.
      </div>
      <p style={{ color: 'var(--text-dim)', fontSize: 12.5, margin: '0 0 14px' }}>
        These gate definitions are shown for reference so the intended approval
        policy stays visible; no control here can enable or change a runtime gate.
      </p>

      {/* Pipeline visualisation: stage → gate → stage */}
      <div
        className="pipeline"
        style={{
          display: 'flex',
          alignItems: 'stretch',
          gap: 4,
          marginBottom: 18,
        }}
      >
        {STAGES.map((stage, idx) => {
          const next = STAGES[idx + 1];
          const gate = next ? gateBetween(stage.id, next.id) : undefined;
          return (
            <span
              key={stage.id}
              style={{ display: 'contents' }}
            >
              <div className="stage" style={{ flex: 1 }}>
                <div style={{ fontSize: 14, marginBottom: 2 }} aria-hidden>
                  {stage.icon}
                </div>
                <div className="nm">{stage.name}</div>
              </div>
              {next && (
                <div className={`gate-arrow${gate?.on ? ' gate-on' : ''}`}>
                  <svg viewBox="0 0 18 14" aria-hidden>
                    <path
                      d="M0 7 L13 7 M9 2 L14 7 L9 12"
                      stroke="currentColor"
                      fill="none"
                      strokeWidth={2}
                      strokeDasharray={gate?.on ? '0' : '3 2'}
                    >
                      {gate?.on && (
                        <animate
                          attributeName="stroke-dashoffset"
                          from="0"
                          to="-10"
                          dur="1.4s"
                          repeatCount="indefinite"
                        />
                      )}
                    </path>
                  </svg>
                  <div className="glabel">{gate?.on ? `🛡 ${gate.role}` : 'auto'}</div>
                </div>
              )}
            </span>
          );
        })}
      </div>

      <div className="gate-list">
        {gates.map((g) => (
          <div key={g.id} className={`gate-row${g.on ? ' on' : ' off'}`}>
            <div>
              <div className="ttl">{g.label}</div>
              <div className="sub">
                {g.on
                  ? 'Human approval required before work flows on'
                  : 'Auto-flow — agents proceed without human gate'}
              </div>
            </div>
            <div>
              <div className="col-label">Approver</div>
              <select value={g.role} disabled>
                {APPROVER_ROLES.map((r) => (
                  <option key={r} value={r}>
                    {r}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <div className="col-label">SLA (hours)</div>
              <input
                type="number"
                min={1}
                max={168}
                value={g.sla}
                disabled
                readOnly
              />
            </div>
            <div>
              <div className="col-label">{g.on ? 'Required' : 'Optional'}</div>
              <div style={{ fontSize: 11, color: 'var(--text-dim)' }}>
                {g.on ? 'Blocks until approved' : 'Skipped automatically'}
              </div>
            </div>
            <div
              className={`switch${g.on ? ' on' : ''}`}
              role="switch"
              aria-checked={g.on}
              aria-disabled="true"
              tabIndex={-1}
            />
          </div>
        ))}
      </div>
    </>
  );
}

interface SkillsPaneProps {
  skills: SkillRow[];
}

function SkillsPane({ skills }: SkillsPaneProps) {
  return (
    <>
      <div className="configure-reference-note">
        Read-only catalog. No skill permission is editable or enforced in this build.
      </div>

      <div className="skill-table" style={{ gridTemplateColumns: '1.4fr 1fr' }}>
        <div className="row" style={{ display: 'contents' }}>
          <div className="th first">Skill</div>
          <div className="th">Category</div>
        </div>
        {skills.map((sk) => (
          <div key={sk.name} className="row" style={{ display: 'contents' }}>
            <div className="cell skill-name" style={{ justifyContent: 'flex-start' }}>
              <div>
                <div>
                  <code style={{ fontFamily: 'inherit' }}>{sk.name}</code>
                </div>
                <div
                  style={{
                    color: 'var(--text-dim)',
                    fontSize: 10.5,
                    fontWeight: 400,
                    marginTop: 1,
                  }}
                >
                  {sk.description}
                </div>
              </div>
            </div>
            <div className="cell" style={{ justifyContent: 'flex-start' }}>
              <span className="skill-tag">{sk.category}</span>
            </div>
          </div>
        ))}
      </div>
    </>
  );
}

export default ConfigureModal;

/** The documented placement pair; a core's declared list wins over it. */
const RUNTIME_DOCUMENTED_MODES: ExecutionMode[] = ['SANDBOX', 'HOST'];

const RUNTIME_MODE_LABELS: Record<ExecutionMode, string> = {
  SANDBOX: 'Sandbox',
  HOST: 'Host',
};

/**
 * Fleet runtime governance (2026-10-11): ONE core/mode pair for every agent.
 * There is deliberately no per-agent editing here — the operator picks a pair
 * and applies it to the whole fleet; the backend hard-rejects any per-agent
 * write that would mix the fleet (409), so this pane is the only converging
 * action.
 */
function RuntimePane() {
  const queryClient = useQueryClient();
  const { data: agents } = useQuery({ queryKey: ['agents'], queryFn: listAgents });
  const { data: providers } = useQuery({
    queryKey: ['adk-providers'],
    queryFn: listAdkProviders,
  });
  const [coreId, setCoreId] = useState('');
  const [mode, setMode] = useState<ExecutionMode | ''>('');
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const liveAgents = agents ?? [];
  const composition = composeRuntimes(liveAgents);
  const selectedProvider = (providers ?? []).find((p) => p.id === coreId);
  const modes: ExecutionMode[] =
    selectedProvider?.executionModes && selectedProvider.executionModes.length > 0
      ? selectedProvider.executionModes
      : RUNTIME_DOCUMENTED_MODES;

  const apply = useMutation({
    mutationFn: () => applyFleetRuntime(coreId, mode as ExecutionMode),
    onSuccess: () => {
      setConfirmOpen(false);
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ['agents'] });
      void queryClient.invalidateQueries({ queryKey: ['runs'] });
      void queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
      void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
    },
    onError: (err: unknown) => setError(`Apply failed: ${String((err as Error)?.message ?? err)}`),
  });

  const canApply = coreId !== '' && mode !== '';
  const targetLabel = `${coreId}/${RUNTIME_MODE_LABELS[mode as ExecutionMode] ?? mode}`;
  const compositionText =
    liveAgents.length === 0
      ? 'No agents yet - the first agent defines the fleet runtime.'
      : composition.length <= 1
        ? `Fleet runtime: ${composition[0].runtime} - ${composition[0].count} agent${composition[0].count === 1 ? '' : 's'} (uniform)`
        : `Mixed runtime - ${composition.map((c) => `${c.runtime} x${c.count}`).join(' - ')}; applying a pair below converges every agent.`;

  return (
    <div style={{ padding: '0', margin: '0' }}>
      <div
        style={{
          background: composition.length <= 1 ? 'rgba(34,197,94,0.12)' : 'rgba(245,158,11,0.12)',
          border: `1px solid ${composition.length <= 1 ? '#22c55e' : 'var(--amber, #f59e0b)'}`,
          borderRadius: 6,
          padding: '8px 10px',
          fontSize: 12,
          marginBottom: 12,
        }}
      >
        {compositionText}
      </div>
      <div className="form-grid">
        <div className="form-field">
          <label htmlFor="runtime-core">Core</label>
          <select
            id="runtime-core"
            value={coreId}
            onChange={(e) => {
              setCoreId(e.target.value);
              setMode('');
            }}
          >
            <option value="">Select a core...</option>
            {(providers ?? []).map((p) => (
              <option key={p.id} value={p.id}>
                {p.displayName} ({p.id})
              </option>
            ))}
          </select>
        </div>
        <div className="form-field">
          <label htmlFor="runtime-mode">Execution mode</label>
          <select
            id="runtime-mode"
            value={mode}
            disabled={coreId === ''}
            onChange={(e) => setMode(e.target.value as ExecutionMode)}
          >
            <option value="">Select a mode...</option>
            {modes.map((m) => (
              <option key={m} value={m}>
                {RUNTIME_MODE_LABELS[m]}
              </option>
            ))}
          </select>
        </div>
      </div>
      <p style={{ color: 'var(--text-muted)', fontSize: 12 }}>
        Changes apply to every agent and take effect on new runs. A running or waiting run
        keeps the runtime it was frozen with; answering a waiting run continues it on the
        agent&apos;s current core.
      </p>
      {error && (
        <div style={{ color: '#f87171', fontSize: 12, marginBottom: 8 }}>{error}</div>
      )}
      <button
        type="button"
        className="btn primary"
        disabled={!canApply || apply.isPending}
        onClick={() => setConfirmOpen(true)}
      >
        Apply to all agents
      </button>

      <ConfirmDialog
        open={confirmOpen}
        title="Apply runtime - confirmation required"
        message={
          <>
            Every agent will run <strong>{targetLabel}</strong>. Current composition:{' '}
            {composition.length <= 1
              ? `${composition[0]?.runtime ?? 'none'} x${composition[0]?.count ?? 0}`
              : composition.map((c) => `${c.runtime} x${c.count}`).join(', ')}
            . New runs start on the new runtime immediately; running runs finish on the one
            they were frozen with.
          </>
        }
        danger={composition.length > 1}
        onConfirm={() => {
          if (!canApply) return;
          apply.mutate();
        }}
        onCancel={() => setConfirmOpen(false)}
      />
    </div>
  );
}

