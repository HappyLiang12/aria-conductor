import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { SettingsPage } from '../pages/SettingsPage';
import { SystemConfigPanel } from './SystemConfigPanel';
import { RuntimeCredentialsCard } from './RuntimeCredentialsCard';
import { GitCredentialCard } from './GitCredentialCard';
import { ConfirmDialog } from './ConfirmDialog';
import { listSkills } from '../api/skills';
import { applyFleetRuntime, listAgents } from '../api/agents';
import { listAdkProviders } from '../api/adk';
import { presentError } from '../api/errorPresentation';
import { ErrorNotice } from './ErrorNotice';
import { composeRuntimes } from '../utils/runtimeComposition';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import type { ExecutionMode } from '../types';

interface SkillRow {
  name: string;
  category: string;
  description: string;
}

// B2: Skills are loaded from the backend skill registry (GET /api/v1/skills), not hardcoded.
// The registry has no permission field — the UI must not invent one (Task 12).

export function ConfigureModal({ initialTab = 'skills' }: { initialTab?: string } = {}) {
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

  return (
    <>
      <div
        className={`modal-scrim${open ? ' open' : ''} fixed inset-0 z-[150] bg-black/50 backdrop-blur-sm transition-opacity`}
        onClick={close}
        aria-hidden={!open}
      />
      <div
        className={`modal${open ? ' open' : ''} z-[160] flex flex-col overflow-hidden rounded-2xl border border-border bg-card text-card-foreground shadow-2xl`}
        data-slot="dialog-content"
        role="dialog"
        aria-modal="true"
        aria-labelledby="configure-title"
        aria-hidden={!open}
        style={{ width: 'min(720px, 94vw)' }}
      >
        <header
          className="flex items-center gap-3 border-b border-border bg-muted/20 px-4.5 py-3.5"
          data-slot="dialog-header"
        >
          <h2 id="configure-title" className="text-sm tracking-wide text-muted-foreground uppercase">
            <b className="mr-2 text-base font-bold tracking-normal text-foreground normal-case">
              Configure
            </b>{' '}
            Governance &amp; Capabilities
          </h2>
          <span
            className="x ml-auto cursor-pointer rounded-lg px-2.5 py-1.5 text-muted-foreground hover:bg-muted hover:text-foreground"
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

        <div className="tabs flex gap-1 border-b border-border px-3.5 pt-2.5" role="tablist">
          <div
            className={`tab inline-flex cursor-pointer items-center gap-1.5 rounded-t-lg border border-transparent px-3.5 py-2 text-[12.5px] font-semibold text-muted-foreground hover:text-foreground${activeTab === 'skills' ? ' active border-border bg-card text-foreground' : ''}`}
            role="tab"
            aria-selected={activeTab === 'skills'}
            onClick={() => setActiveTab('skills')}
          >
            🧰 Skills
          </div>
          <div
            className={`tab inline-flex cursor-pointer items-center gap-1.5 rounded-t-lg border border-transparent px-3.5 py-2 text-[12.5px] font-semibold text-muted-foreground hover:text-foreground${activeTab === 'llm' ? ' active border-border bg-card text-foreground' : ''}`}
            role="tab"
            aria-selected={activeTab === 'llm'}
            onClick={() => setActiveTab('llm')}
          >
            🤖 LLM Providers
          </div>
          <div
            className={`tab inline-flex cursor-pointer items-center gap-1.5 rounded-t-lg border border-transparent px-3.5 py-2 text-[12.5px] font-semibold text-muted-foreground hover:text-foreground${activeTab === 'config' ? ' active border-border bg-card text-foreground' : ''}`}
            role="tab"
            aria-selected={activeTab === 'config'}
            onClick={() => setActiveTab('config')}
          >
            ⚙️ System Config
          </div>
          <div
            className={`tab inline-flex cursor-pointer items-center gap-1.5 rounded-t-lg border border-transparent px-3.5 py-2 text-[12.5px] font-semibold text-muted-foreground hover:text-foreground${activeTab === 'runtime' ? ' active border-border bg-card text-foreground' : ''}`}
            role="tab"
            aria-selected={activeTab === 'runtime'}
            onClick={() => setActiveTab('runtime')}
          >
            🧩 Runtime
          </div>
        </div>

        <div className="tab-body min-h-0 flex-1 overflow-y-auto px-5 py-4.5">
          {activeTab === 'skills' && (
            <SkillsPane skills={skills} />
          )}
          {activeTab === 'llm' && (
            <div className="m-0 p-0">
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
            <div className="m-0 p-0">
              <RuntimePane open={open} />
              {/* F8: the workflow git handoff credential (pack-git-0001) had no
                  editor; it lives with the other runtime credentials. */}
              <GitCredentialCard />
            </div>
          )}
        </div>

        <footer
          className="flex items-center justify-between border-t border-border bg-muted/20 px-4.5 py-3"
          data-slot="dialog-footer"
        >
          <div className="info text-[11.5px] text-muted-foreground">
            {activeTab === 'skills'
              ? `${skills.length} skills in the read-only catalog`
              : activeTab === 'llm'
              ? 'Configure LLM providers and the one-time Qoder runtime credential'
              : activeTab === 'runtime'
              ? 'One runtime for the whole fleet - applying updates every agent'
              : 'Runtime settings stored in the database — changes take effect immediately'}
          </div>
          <div className="actions flex gap-2">
            <Button type="button" className="btn primary" onClick={close}>
              Done
            </Button>
          </div>
        </footer>
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
      <div className="configure-reference-note mb-3 rounded-md border border-warning/40 bg-warning/10 px-2.5 py-2 text-xs text-warning">
        Read-only catalog. No skill permission is editable or enforced in this build.
      </div>

      <div
        className="skill-table grid text-xs"
        style={{ gridTemplateColumns: '1.4fr 1fr' }}
      >
        <div className="row" style={{ display: 'contents' }}>
          <div className="th first text-[10.5px] tracking-wider text-muted-foreground uppercase">
            Skill
          </div>
          <div className="th text-[10.5px] tracking-wider text-muted-foreground uppercase">
            Category
          </div>
        </div>
        {skills.map((sk) => (
          <div key={sk.name} className="row" style={{ display: 'contents' }}>
            <div className="cell skill-name" style={{ justifyContent: 'flex-start' }}>
              <div>
                <div className="font-semibold text-foreground">
                  <code style={{ fontFamily: 'inherit' }}>{sk.name}</code>
                </div>
                <div className="mt-px text-[10.5px] font-normal text-muted-foreground">
                  {sk.description}
                </div>
              </div>
            </div>
            <div className="cell" style={{ justifyContent: 'flex-start' }}>
              <Badge variant="outline" className="skill-tag">
                {sk.category}
              </Badge>
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
function RuntimePane({ open }: { open: boolean }) {
  const queryClient = useQueryClient();
  const agentsQuery = useQuery({
    queryKey: ['agents'],
    queryFn: listAgents,
    // F2 (T54): the modal's panes stay mounted while the modal is closed, so a
    // fleet read once at mount could be presented as the current composition
    // long after the fleet changed (live bar: the banner said qoder/HOST while
    // the API said opencode/SANDBOX). Every open is a fresh read of the
    // persisted configuration, and nothing is drawn from a cache alone.
    enabled: open,
    staleTime: 0,
    refetchOnMount: 'always',
  });
  const { data: providers } = useQuery({
    queryKey: ['adk-providers'],
    queryFn: listAdkProviders,
  });
  const [coreId, setCoreId] = useState('');
  const [mode, setMode] = useState<ExecutionMode | ''>('');
  const [confirmOpen, setConfirmOpen] = useState(false);
  // PUT /api/v1/agents/runtime is operator-only server-side, so the failure is
  // kept as the API error and rendered as a notice that names the fix.
  const [applyError, setApplyError] = useState<unknown | null>(null);

  const agents = agentsQuery.data;
  // An unread fleet is not an empty one, and an unread one is not the previous
  // read either: both are "unknown", and the pane says so instead of guessing.
  const fleetKnown = agents !== undefined;
  // T56 F1: the query keeps its last successful data when a refetch rejects, so
  // `fleetKnown` alone cannot say whether the fleet was read NOW. `isError` is
  // the failure of the latest attempt, with or without cached data: a cached
  // read behind it is the last known one, not the current one.
  const fleetReadFailed = agentsQuery.isError;
  const liveAgents = agents ?? [];
  const composition = composeRuntimes(liveAgents);
  /** The last read, for the two states that must not call it current. */
  const lastKnownSummary =
    composition.length === 0
      ? 'no agents were read'
      : composition.length === 1
        ? `${composition[0].runtime} x${composition[0].count}`
        : composition.map((c) => `${c.runtime} x${c.count}`).join(', ');
  const selectedProvider = (providers ?? []).find((p) => p.id === coreId);
  const modes: ExecutionMode[] =
    selectedProvider?.executionModes && selectedProvider.executionModes.length > 0
      ? selectedProvider.executionModes
      : RUNTIME_DOCUMENTED_MODES;

  const apply = useMutation({
    mutationFn: () => applyFleetRuntime(coreId, mode as ExecutionMode),
    onSuccess: () => {
      setConfirmOpen(false);
      setApplyError(null);
      void queryClient.invalidateQueries({ queryKey: ['agents'] });
      void queryClient.invalidateQueries({ queryKey: ['runs'] });
      void queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
      void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
    },
    onError: (err: unknown) => {
      setApplyError(err);
      // The confirmation is a modal with its own full-screen overlay, so a
      // notice rendered in this pane sits behind it: the operator's only
      // visible outcome was an unchanged dialog. Closing it puts the failure
      // (refusal or fault) in front of the operator, in the pane it concerns.
      setConfirmOpen(false);
    },
  });

  const canApply = coreId !== '' && mode !== '';
  const targetLabel = `${coreId}/${RUNTIME_MODE_LABELS[mode as ExecutionMode] ?? mode}`;
  const compositionText =
    fleetKnown && fleetReadFailed
      ? `The fleet could not be re-read — the last known composition (${lastKnownSummary}) `
        + 'may be out of date, and this banner does not claim it is current. Reopen Configure to retry.'
      : fleetReadFailed
        ? 'The fleet configuration could not be read — the current composition is unknown, '
          + 'so this banner claims nothing about what the agents run. Reopen Configure to retry.'
        : !fleetKnown
          ? 'Reading the fleet configuration…'
          : liveAgents.length === 0
            ? 'No agents yet - the first agent defines the fleet runtime.'
            : composition.length <= 1
              ? `Fleet runtime: ${composition[0].runtime} - ${composition[0].count} agent${composition[0].count === 1 ? '' : 's'} (uniform)`
              : `Mixed runtime - ${composition.map((c) => `${c.runtime} x${c.count}`).join(' - ')}; applying a pair below converges every agent.`;
  /**
   * The composition the confirmation quotes. It is the same read as the banner,
   * so the dialog can never claim a fleet the pane did not see — the previous
   * copy defaulted to `none x0`, which an unread fleet turned into a statement.
   * T56 F1: a failed re-read is quoted as the last known read, never as the
   * current composition.
   */
  const currentComposition =
    fleetKnown && fleetReadFailed
      ? `the last known composition (${lastKnownSummary}), which could not be re-read and may be out of date`
      : fleetReadFailed
        ? 'the current composition could not be read'
        : !fleetKnown
          ? 'the current composition is still being read'
          : composition.length <= 1
            ? `${composition[0]?.runtime ?? 'none'} x${composition[0]?.count ?? 0}`
            : composition.map((c) => `${c.runtime} x${c.count}`).join(', ');
  const fleetSettled = fleetKnown && !fleetReadFailed && !agentsQuery.isFetching;
  const bannerOk = fleetSettled && composition.length <= 1;

  return (
    <div className="m-0 p-0">
      <div
        className={`mb-3 rounded-md border px-2.5 py-2 text-xs ${
          bannerOk
            ? 'border-success/50 bg-success/10 text-foreground'
            : 'border-warning/50 bg-warning/10 text-foreground'
        }`}
      >
        {compositionText}
      </div>
      <div className="form-grid grid grid-cols-2 gap-3.5">
        <div className="form-field flex flex-col gap-1.5">
          <label htmlFor="runtime-core" className="text-xs text-muted-foreground">
            Core
          </label>
          <select
            id="runtime-core"
            className="h-8 w-full rounded-lg border border-input bg-transparent px-2.5 text-sm text-foreground"
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
        <div className="form-field flex flex-col gap-1.5">
          <label htmlFor="runtime-mode" className="text-xs text-muted-foreground">
            Execution mode
          </label>
          <select
            id="runtime-mode"
            className="h-8 w-full rounded-lg border border-input bg-transparent px-2.5 text-sm text-foreground"
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
      <p className="text-xs text-muted-foreground">
        Changes apply to every agent and take effect on new runs. A running or waiting run
        keeps the runtime it was frozen with; answering a waiting run continues it on the
        agent&apos;s current core.
      </p>
      {applyError != null && (
        <div className="mb-2">
          <ErrorNotice
            presented={presentError(applyError, 'The fleet runtime was not applied.')}
            operatorSession
            actionLabel="Applying the fleet runtime"
          />
        </div>
      )}
      <Button
        type="button"
        className="btn primary"
        disabled={!canApply || apply.isPending || agentsQuery.isFetching}
        onClick={() => setConfirmOpen(true)}
      >
        Apply to all agents
      </Button>

      <ConfirmDialog
        open={confirmOpen}
        title="Apply runtime - confirmation required"
        message={
          <>
            Every agent will run <strong>{targetLabel}</strong>. Current composition:{' '}
            {currentComposition}
            . New runs start on the new runtime immediately; running runs finish on the one
            they were frozen with.
          </>
        }
        danger={fleetKnown && composition.length > 1}
        onConfirm={() => {
          if (!canApply) return;
          apply.mutate();
        }}
        onCancel={() => setConfirmOpen(false)}
      />
    </div>
  );
}

