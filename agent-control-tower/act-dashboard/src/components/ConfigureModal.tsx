import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { SettingsPage } from '../pages/SettingsPage';
import { SystemConfigPanel } from './SystemConfigPanel';
import { listSkills } from '../api/skills';

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

type Permission = 'RO' | 'USE' | 'RW';

interface SkillRow {
  name: string;
  category: string;
  description: string;
  permission: Permission;
}

// B2: Skills are loaded from the backend skill registry (GET /api/v1/skills), not hardcoded.

const PERMISSION_OPTIONS: Permission[] = ['RO', 'USE', 'RW'];

export function ConfigureModal() {
  const [open, setOpen] = useState(false);
  const [activeTab, setActiveTab] = useState<'gates' | 'skills' | 'llm' | 'config'>('gates');
  const [gates, setGates] = useState<Gate[]>(() => DEFAULT_GATES.map((g) => ({ ...g })));
  const [skills, setSkills] = useState<SkillRow[]>([]);

  // B2: Skills tab is backed by the real skill registry (GET /api/v1/skills), not hardcoded data.
  const { data: realSkills } = useQuery({ queryKey: ['skills'], queryFn: listSkills, enabled: open });
  useEffect(() => {
    setSkills((realSkills ?? []).map((s) => ({
      name: s.name,
      category: s.category ?? 'General',
      description: s.description ?? '',
      permission: 'USE' as Permission,
    })));
  }, [realSkills]);

  // Listen for the rail-nav custom event that opens the modal.
  useEffect(() => {
    const handler = () => setOpen(true);
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

  const toggleGate = (id: string) => {
    setGates((prev) => prev.map((g) => (g.id === id ? { ...g, on: !g.on } : g)));
  };

  const updateGate = <K extends keyof Gate>(id: string, key: K, value: Gate[K]) => {
    setGates((prev) => prev.map((g) => (g.id === id ? { ...g, [key]: value } : g)));
  };

  const updateSkill = (name: string, permission: Permission) => {
    setSkills((prev) => prev.map((s) => (s.name === name ? { ...s, permission } : s)));
  };

  const resetDefaults = () => {
    setGates(DEFAULT_GATES.map((g) => ({ ...g })));
    setSkills((realSkills ?? []).map((s) => ({
      name: s.name,
      category: s.category ?? 'General',
      description: s.description ?? '',
      permission: 'USE' as Permission,
    })));
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
            🧰 Skills &amp; Tools
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
        </div>

        <div className="tab-body">
          {activeTab === 'gates' && (
            <GatesPane
              gates={gates}
              onToggle={toggleGate}
              onChange={updateGate}
            />
          )}
          {activeTab === 'skills' && (
            <SkillsPane skills={skills} onChange={updateSkill} />
          )}
          {activeTab === 'llm' && (
            <div style={{ padding: '0', margin: '0' }}>
              <SettingsPage />
            </div>
          )}
          {activeTab === 'config' && (
            <SystemConfigPanel />
          )}
        </div>

        <footer>
          <div className="info">
            {activeTab === 'gates'
              ? `${gates.filter((g) => g.on).length} of ${gates.length} gates require human approval`
              : activeTab === 'skills'
              ? `${skills.length} tools configured · ${skills.filter((s) => s.permission === 'RW').length} with full access`
              : activeTab === 'llm'
              ? 'Configure LLM providers for AI capabilities'
              : 'Runtime settings stored in the database — changes take effect immediately'}
          </div>
          <div className="actions">
            {activeTab !== 'llm' && activeTab !== 'config' && (
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
  onToggle: (id: string) => void;
  onChange: <K extends keyof Gate>(id: string, key: K, value: Gate[K]) => void;
}

function GatesPane({ gates, onToggle, onChange }: GatesPaneProps) {
  const gateBetween = (from: string, to: string) =>
    gates.find((g) => g.from === from && g.to === to);

  return (
    <>
      <div style={{ background: 'rgba(245,158,11,0.12)', border: '1px solid var(--amber, #f59e0b)', borderRadius: 6, padding: '8px 10px', fontSize: 12, marginBottom: 12 }}>
        ⚠ Preview — these approval gates are illustrative and are <strong>not yet enforced</strong> by the backend.
      </div>
      <p style={{ color: 'var(--text-dim)', fontSize: 12.5, margin: '0 0 14px' }}>
        Configure where humans must approve before work flows to the next stage.
        Toggle a gate on to require sign-off, choose the approver role, and set an SLA.
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
              <select
                value={g.role}
                onChange={(e) => onChange(g.id, 'role', e.target.value)}
                disabled={!g.on}
              >
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
                disabled={!g.on}
                onChange={(e) => onChange(g.id, 'sla', Number(e.target.value) || 0)}
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
              tabIndex={0}
              onClick={() => onToggle(g.id)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  onToggle(g.id);
                }
              }}
            />
          </div>
        ))}
      </div>
    </>
  );
}

interface SkillsPaneProps {
  skills: SkillRow[];
  onChange: (name: string, permission: Permission) => void;
}

function SkillsPane({ skills, onChange }: SkillsPaneProps) {
  const lvlClass = (p: Permission) => (p === 'RW' ? 'lvl rw' : p === 'RO' ? 'lvl ro' : 'lvl');

  return (
    <>
      <p style={{ color: 'var(--text-dim)', fontSize: 12.5, margin: '0 0 12px' }}>
        Define which capabilities the agent fleet may invoke. Levels:{' '}
        <span className="lvl ro">RO</span> read-only ·{' '}
        <span className="lvl">USE</span> can execute ·{' '}
        <span className="lvl rw">RW</span> full read/write.
      </p>

      <div
        className="skill-table"
        style={{ gridTemplateColumns: '1.4fr 1fr 1fr' }}
      >
        <div className="row" style={{ display: 'contents' }}>
          <div className="th first">Skill</div>
          <div className="th">Category</div>
          <div className="th">Permission</div>
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
            <div className="cell">
              <span className={lvlClass(sk.permission)} style={{ marginRight: 8 }}>
                {sk.permission}
              </span>
              <select
                value={sk.permission}
                onChange={(e) => onChange(sk.name, e.target.value as Permission)}
                style={{
                  padding: '4px 6px',
                  borderRadius: 6,
                  background: 'rgba(0,0,0,.25)',
                  color: 'var(--text)',
                  border: '1px solid var(--line-2)',
                  fontSize: 11,
                  outline: 'none',
                }}
              >
                {PERMISSION_OPTIONS.map((p) => (
                  <option key={p} value={p}>
                    {p}
                  </option>
                ))}
              </select>
            </div>
          </div>
        ))}
      </div>
    </>
  );
}

export default ConfigureModal;
