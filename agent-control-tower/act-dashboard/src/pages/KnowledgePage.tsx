import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  listKnowledge,
  createKnowledge,
  batchReviewKnowledge,
  updateKnowledge,
  getKnowledgeYaml,
} from '../api/knowledge';
import { listAgents } from '../api/agents';
import { useWebSocketContext } from '../components/Layout';
import type {
  Agent,
  CreateKnowledgeRequest,
  KnowledgeItem,
  KnowledgeType,
} from '../types';

// ---------- static governance data ---------- //

interface FlowStep {
  num: string;
  ttl: string;
  desc: string;
}
const FLOW_STEPS: FlowStep[] = [
  { num: '01', ttl: 'Agent Draft',     desc: 'Agent captures a new skill, prompt or script as a draft artifact.' },
  { num: '02', ttl: 'Peer Review',     desc: 'Sibling agents validate logic, security and reuse signals.' },
  { num: '03', ttl: 'Human Approval',  desc: 'Governance lead signs off on policy, risk and access scope.' },
  { num: '04', ttl: 'Validated',       desc: 'Item is versioned, signed and access-scoped to consumers.' },
  { num: '05', ttl: 'Unified Library', desc: 'Indexed in the shared knowledge fabric; reusable everywhere.' },
];

interface AccessRow {
  area: string;
  hint: string;
  cells: { label: 'EDIT' | 'USE' | 'VIEW' | 'NONE'; cls: 'edit' | 'use' | 'view' | 'none' }[];
}
const ACCESS_HEADERS = ['Coder', 'QA', 'Orchestrator', 'Reviewer', 'Reporter', 'Sentinel'];
const ACCESS_ROWS: AccessRow[] = [
  {
    area: 'Skill Library',
    hint: 'Reusable agent skills & playbooks',
    cells: [
      { label: 'EDIT', cls: 'edit' },
      { label: 'USE',  cls: 'use'  },
      { label: 'EDIT', cls: 'edit' },
      { label: 'USE',  cls: 'use'  },
      { label: 'VIEW', cls: 'view' },
      { label: 'USE',  cls: 'use'  },
    ],
  },
  {
    area: 'Prompt Templates',
    hint: 'System prompts, role primers',
    cells: [
      { label: 'USE',  cls: 'use'  },
      { label: 'VIEW', cls: 'view' },
      { label: 'EDIT', cls: 'edit' },
      { label: 'EDIT', cls: 'edit' },
      { label: 'USE',  cls: 'use'  },
      { label: 'VIEW', cls: 'view' },
    ],
  },
  {
    area: 'Scripts & Tools',
    hint: 'Executable scripts, MCP tools',
    cells: [
      { label: 'EDIT', cls: 'edit' },
      { label: 'USE',  cls: 'use'  },
      { label: 'USE',  cls: 'use'  },
      { label: 'VIEW', cls: 'view' },
      { label: 'NONE', cls: 'none' },
      { label: 'EDIT', cls: 'edit' },
    ],
  },
  {
    area: 'Workflow Templates',
    hint: 'Multi-agent orchestration recipes',
    cells: [
      { label: 'USE',  cls: 'use'  },
      { label: 'USE',  cls: 'use'  },
      { label: 'EDIT', cls: 'edit' },
      { label: 'USE',  cls: 'use'  },
      { label: 'VIEW', cls: 'view' },
      { label: 'NONE', cls: 'none' },
    ],
  },
  {
    area: 'Sensitive / Restricted',
    hint: 'Customer data, secrets, audit trails',
    cells: [
      { label: 'NONE', cls: 'none' },
      { label: 'NONE', cls: 'none' },
      { label: 'VIEW', cls: 'view' },
      { label: 'EDIT', cls: 'edit' },
      { label: 'NONE', cls: 'none' },
      { label: 'VIEW', cls: 'view' },
    ],
  },
];

// ---------- helpers ---------- //

type ExtendedKnowledgeItem = KnowledgeItem & {
  agentId?: string;
  agentName?: string;
};

function stageClass(item: KnowledgeItem): string {
  if (item.status === 'PENDING') return 'review';
  return item.type.toLowerCase();
}

function avatarChar(name?: string): string {
  if (!name) return '·';
  return name.trim().charAt(0).toUpperCase();
}

function avatarStyle(seed: string): React.CSSProperties {
  // deterministic gradient from string hash
  let h = 0;
  for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) >>> 0;
  const a = h % 360;
  const b = (a + 48) % 360;
  return {
    width: 32,
    height: 32,
    borderRadius: 9,
    flex: '0 0 auto',
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    color: '#0c1228',
    fontWeight: 800,
    fontSize: 13,
    letterSpacing: '.5px',
    background: `linear-gradient(135deg, hsl(${a} 70% 68%), hsl(${b} 70% 56%))`,
    boxShadow: '0 0 0 1px rgba(255,255,255,.08), inset 0 0 8px rgba(255,255,255,.18)',
  };
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function ownerOf(item: ExtendedKnowledgeItem, agents: Agent[]): Agent | null {
  if (item.agentId) {
    const found = agents.find((a) => a.id === item.agentId);
    if (found) return found;
  }
  if (agents.length === 0) return null;
  // Stable fallback — distribute items across agents by hash of id.
  let h = 0;
  for (let i = 0; i < item.id.length; i++) h = (h * 17 + item.id.charCodeAt(i)) >>> 0;
  return agents[h % agents.length];
}

// ---------- Page ---------- //

export function KnowledgePage() {
  const queryClient = useQueryClient();
  const { lastMessage } = useWebSocketContext();

  // queries
  const { data: itemsRaw, isLoading: loadingItems } = useQuery({
    queryKey: ['knowledge'],
    queryFn: listKnowledge,
  });
  const { data: agentsRaw } = useQuery({
    queryKey: ['agents'],
    queryFn: listAgents,
  });
  const items: ExtendedKnowledgeItem[] = useMemo(() => itemsRaw ?? [], [itemsRaw]);
  const agents: Agent[] = useMemo(() => agentsRaw ?? [], [agentsRaw]);

  // ui state
  const [activeAgentId, setActiveAgentId] = useState<string | null>(null);
  const [checkedIds, setCheckedIds] = useState<Set<string>>(new Set());
  const [search, setSearch] = useState('');
  const [selectedItemId, setSelectedItemId] = useState<string | null>(null);
  const [reviewReason, setReviewReason] = useState('');
  const [showSubmit, setShowSubmit] = useState(false);
  const [form, setForm] = useState<CreateKnowledgeRequest>({
    name: '',
    type: 'SKILL',
    description: '',
    content: '',
    sensitivity: 'INTERNAL',
  });
  const [formError, setFormError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [viewMode, setViewMode] = useState<'md' | 'yaml'>('md');

  // mutations
  const reviewMut = useMutation({
    mutationFn: (vars: { id: string; approved: boolean; reason?: string }) =>
      updateKnowledge(vars.id, {
        status: vars.approved ? 'APPROVED' : 'REJECTED',
        reason: vars.reason,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['knowledge'] });
      setReviewReason('');
    },
  });

  const batchMut = useMutation({
    mutationFn: (vars: { ids: string[]; approved: boolean; reason?: string }) =>
      batchReviewKnowledge(vars.ids, vars.approved, vars.reason),
    onSuccess: (results, vars) => {
      const fulfilled = results.filter((r) => r.status === 'fulfilled').length;
      setToast(`${vars.approved ? 'Approved' : 'Rejected'} ${fulfilled}/${vars.ids.length} item(s)`);
      setCheckedIds(new Set());
      queryClient.invalidateQueries({ queryKey: ['knowledge'] });
    },
  });

  const createMut = useMutation({
    mutationFn: createKnowledge,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['knowledge'] });
      setShowSubmit(false);
      setForm({ name: '', type: 'SKILL', description: '', content: '', sensitivity: 'INTERNAL' });
      setFormError(null);
      setToast('Knowledge submitted for review');
    },
    onError: () => setFormError('Submission failed. Please retry.'),
  });

  // realtime
  useEffect(() => {
    if (lastMessage?.type?.startsWith('knowledge.')) {
      queryClient.invalidateQueries({ queryKey: ['knowledge'] });
    }
  }, [lastMessage, queryClient]);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 2600);
    return () => clearTimeout(t);
  }, [toast]);

  // derived
  const pending = useMemo(() => items.filter((i) => i.status === 'PENDING'), [items]);
  const approved = useMemo(() => items.filter((i) => i.status === 'APPROVED'), [items]);

  const itemsByAgent = useMemo(() => {
    const map = new Map<string, ExtendedKnowledgeItem[]>();
    for (const a of agents) map.set(a.id, []);
    for (const it of items) {
      const owner = ownerOf(it, agents);
      if (!owner) continue;
      map.get(owner.id)?.push(it);
    }
    return map;
  }, [items, agents]);

  const visiblePending = useMemo(() => {
    if (!activeAgentId) return pending;
    return pending.filter((it) => ownerOf(it, agents)?.id === activeAgentId);
  }, [pending, agents, activeAgentId]);

  const filteredApproved = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return approved;
    return approved.filter(
      (i) =>
        i.name.toLowerCase().includes(q) ||
        i.description.toLowerCase().includes(q) ||
        i.type.toLowerCase().includes(q),
    );
  }, [approved, search]);

  const selected = useMemo(
    () => approved.find((i) => i.id === selectedItemId) ?? null,
    [approved, selectedItemId],
  );

  const yamlQuery = useQuery({
    queryKey: ['knowledge-yaml', selected?.id],
    queryFn: () => getKnowledgeYaml(selected!.id),
    enabled: !!selected && selected.type === 'WORKFLOW' && viewMode === 'yaml',
  });

  // handlers
  const toggleChecked = (id: string) => {
    setCheckedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };
  const toggleAllVisible = () => {
    setCheckedIds((prev) => {
      const allSelected = visiblePending.every((p) => prev.has(p.id));
      if (allSelected) return new Set();
      return new Set(visiblePending.map((p) => p.id));
    });
  };

  const submitNew = (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.name.trim()) {
      setFormError('Name is required');
      return;
    }
    if (!form.description.trim()) {
      setFormError('Description is required');
      return;
    }
    setFormError(null);
    createMut.mutate(form);
  };

  return (
    <div className="view-zone" data-view="knowledge" style={{ display: 'block', padding: 16 }}>
      {/* ---------- Header ---------- */}
      <div className="view-header">
        <div>
          <h1>Knowledge Governance</h1>
          <div className="sub">
            Lifecycle of every skill, prompt, script and template — from agent draft to unified library.
          </div>
        </div>
        <div className="actions">
          <button className="btn" onClick={() => queryClient.invalidateQueries({ queryKey: ['knowledge'] })}>
            ↻ Refresh
          </button>
          <button className="btn primary" onClick={() => setShowSubmit(true)}>
            + Submit New
          </button>
        </div>
      </div>

      {/* ---------- 1. Promotion Path ---------- */}
      <section className="panel" style={{ marginBottom: 16 }}>
        <h2>
          Promotion Path <span className="accent">/ governance flow</span>
        </h2>
        <div className="knowledge-flow">
          {FLOW_STEPS.map((s) => (
            <div key={s.num} className="kstep">
              <div className="num">Stage {s.num}</div>
              <div className="ttl">{s.ttl}</div>
              <div className="desc">{s.desc}</div>
            </div>
          ))}
        </div>
      </section>

      {/* ---------- 2. Per-Agent Knowledge Spaces ---------- */}
      <section className="panel" style={{ marginBottom: 16 }}>
        <h2>
          Per-Agent Spaces <span className="accent">/ {agents.length} agents</span>
        </h2>
        <div className="knowledge-spaces">
          {agents.length === 0 && (
            <div style={{ gridColumn: '1 / -1', padding: 24, color: 'var(--text-mute)', fontSize: 12, textAlign: 'center' }}>
              No agents registered yet. Create an agent to allocate a knowledge space.
            </div>
          )}
          {agents.map((a) => {
            const owned = itemsByAgent.get(a.id) ?? [];
            const draft = owned.filter((i) => i.status === 'DRAFT' || i.status === 'PENDING').length;
            const live = owned.filter((i) => i.status === 'APPROVED').length;
            const isActive = activeAgentId === a.id;
            return (
              <button
                type="button"
                key={a.id}
                className={`kspace ${isActive ? 'active' : ''}`}
                onClick={() => setActiveAgentId(isActive ? null : a.id)}
                style={{ textAlign: 'left', font: 'inherit', color: 'inherit' }}
              >
                <div className="top">
                  <div style={avatarStyle(a.id)}>{avatarChar(a.name)}</div>
                  <div>
                    <div className="name">{a.name}</div>
                    <div className="sub">{a.role || a.agentType}</div>
                  </div>
                </div>
                <div className="metrics">
                  <div>
                    <b>{owned.length}</b>
                    <span>Total</span>
                  </div>
                  <div>
                    <b>{draft}</b>
                    <span>In Review</span>
                  </div>
                  <div>
                    <b>{live}</b>
                    <span>Live</span>
                  </div>
                </div>
              </button>
            );
          })}
        </div>
      </section>

      {/* ---------- 3 + 4. Review queue + Unified library ---------- */}
      <div className="knowledge-grid">
        {/* Review queue */}
        <section className="panel">
          <h2>
            Submitted For Review{' '}
            <span className="accent">
              / {visiblePending.length} pending
              {activeAgentId ? ` · ${agents.find((a) => a.id === activeAgentId)?.name}` : ''}
            </span>
            {activeAgentId && (
              <button
                className="btn"
                style={{ marginLeft: 'auto', padding: '4px 8px', fontSize: 11 }}
                onClick={() => setActiveAgentId(null)}
              >
                Clear filter
              </button>
            )}
          </h2>

          <div className="knowledge-review">
            {/* Batch toolbar */}
            <div
              style={{
                display: 'flex',
                gap: 8,
                alignItems: 'center',
                paddingBottom: 10,
                borderBottom: '1px dashed var(--line)',
                marginBottom: 10,
              }}
            >
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11, color: 'var(--text-dim)' }}>
                <input
                  type="checkbox"
                  checked={visiblePending.length > 0 && visiblePending.every((p) => checkedIds.has(p.id))}
                  onChange={toggleAllVisible}
                  style={{ accentColor: 'var(--brand)' }}
                />
                Select all visible
              </label>
              <span style={{ fontSize: 11, color: 'var(--text-mute)' }}>
                {checkedIds.size} selected
              </span>
              <div style={{ marginLeft: 'auto', display: 'flex', gap: 6 }}>
                <button
                  className="btn"
                  disabled={checkedIds.size === 0 || batchMut.isPending}
                  onClick={() =>
                    batchMut.mutate({
                      ids: Array.from(checkedIds),
                      approved: true,
                      reason: reviewReason || 'Batch approved',
                    })
                  }
                  style={{ color: '#6fe2b6', borderColor: 'rgba(54,211,153,.4)' }}
                >
                  ✓ Batch Approve
                </button>
                <button
                  className="btn"
                  disabled={checkedIds.size === 0 || batchMut.isPending}
                  onClick={() =>
                    batchMut.mutate({
                      ids: Array.from(checkedIds),
                      approved: false,
                      reason: reviewReason || 'Batch rejected',
                    })
                  }
                  style={{ color: '#ff8d99', borderColor: 'rgba(255,107,122,.4)' }}
                >
                  ✗ Batch Reject
                </button>
              </div>
            </div>

            {/* Items */}
            <div style={{ display: 'grid', gap: 8 }}>
              {loadingItems && (
                <div style={{ color: 'var(--text-mute)', fontSize: 12, padding: '12px 0' }}>Loading…</div>
              )}
              {!loadingItems && visiblePending.length === 0 && (
                <div
                  style={{
                    color: 'var(--text-mute)',
                    fontSize: 12,
                    padding: '24px 0',
                    textAlign: 'center',
                    border: '1px dashed var(--line)',
                    borderRadius: 10,
                  }}
                >
                  Inbox zero. No items waiting for review.
                </div>
              )}
              {visiblePending.map((it) => {
                const owner = ownerOf(it, agents);
                const checked = checkedIds.has(it.id);
                return (
                  <div key={it.id} className="kitem" style={{ gridTemplateColumns: 'auto auto 1fr auto' }}>
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => toggleChecked(it.id)}
                      style={{ accentColor: 'var(--brand)' }}
                    />
                    <span className={`stage ${stageClass(it)}`}>{it.type}</span>
                    <div>
                      <div className="ttl">{it.name}</div>
                      <div className="desc">
                        {owner ? owner.name : 'Unattributed'} · submitted {formatDate(it.createdAt)}
                        {it.description ? ` · ${it.description.slice(0, 60)}${it.description.length > 60 ? '…' : ''}` : ''}
                      </div>
                    </div>
                    <div style={{ display: 'flex', gap: 6 }}>
                      <button
                        className="btn"
                        onClick={() => reviewMut.mutate({ id: it.id, approved: true, reason: reviewReason })}
                        disabled={reviewMut.isPending}
                        style={{ color: '#6fe2b6', borderColor: 'rgba(54,211,153,.4)' }}
                        title="Approve"
                      >
                        ✓
                      </button>
                      <button
                        className="btn"
                        onClick={() => reviewMut.mutate({ id: it.id, approved: false, reason: reviewReason || 'Rejected' })}
                        disabled={reviewMut.isPending}
                        style={{ color: '#ff8d99', borderColor: 'rgba(255,107,122,.4)' }}
                        title="Reject"
                      >
                        ✗
                      </button>
                      <button
                        className="btn"
                        onClick={() => setSelectedItemId(it.id)}
                        title="View"
                      >
                        ↗
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>

            {/* Reason */}
            {(visiblePending.length > 0 || checkedIds.size > 0) && (
              <div style={{ marginTop: 12 }}>
                <label
                  style={{
                    display: 'block',
                    color: 'var(--text-mute)',
                    fontSize: 9.5,
                    letterSpacing: '.7px',
                    textTransform: 'uppercase',
                    marginBottom: 4,
                  }}
                >
                  Review note (optional)
                </label>
                <input
                  value={reviewReason}
                  onChange={(e) => setReviewReason(e.target.value)}
                  placeholder="e.g. Approved per security review SR-2031"
                  style={{
                    width: '100%',
                    border: '1px solid var(--line-2)',
                    borderRadius: 9,
                    background: 'rgba(0,0,0,.22)',
                    color: 'var(--text)',
                    padding: '8px 10px',
                    font: 'inherit',
                    fontSize: 12,
                    outline: 'none',
                  }}
                />
              </div>
            )}
          </div>
        </section>

        {/* Unified knowledge space */}
        <section className="panel">
          <h2>
            Unified Knowledge Space <span className="accent">/ {filteredApproved.length} approved</span>
          </h2>

          <div className="kb-toolbar">
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search title, description, type…"
            />
            <span style={{ fontSize: 11, color: 'var(--text-mute)' }}>
              {approved.length} total
            </span>
          </div>

          <div className="kb-layout">
            <div className="unified-list">
              {filteredApproved.length === 0 && (
                <div
                  style={{
                    color: 'var(--text-mute)',
                    fontSize: 12,
                    padding: 16,
                    textAlign: 'center',
                    border: '1px dashed var(--line)',
                    borderRadius: 10,
                  }}
                >
                  {approved.length === 0 ? 'No approved knowledge yet.' : 'No items match your search.'}
                </div>
              )}
              {filteredApproved.map((it) => {
                const owner = ownerOf(it, agents);
                const isActive = selectedItemId === it.id;
                return (
                  <div
                    key={it.id}
                    className={`kitem selectable ${isActive ? 'active' : ''}`}
                    onClick={() => setSelectedItemId(it.id)}
                  >
                    <span className={`stage ${stageClass(it)}`}>{it.type}</span>
                    <div>
                      <div className="ttl">{it.name}</div>
                      <div className="desc">
                        v{it.currentVersion} · {owner ? owner.name : 'Shared'} · {formatDate(it.createdAt)}
                      </div>
                    </div>
                    <span style={{ fontSize: 10, color: 'var(--text-mute)', letterSpacing: '.5px' }}>
                      {it.sensitivity}
                    </span>
                  </div>
                );
              })}
            </div>

            <div className="kb-detail">
              {!selected && (
                <div style={{ color: 'var(--text-mute)', fontSize: 12, lineHeight: 1.6 }}>
                  <div className="ttl" style={{ color: 'var(--text)', marginBottom: 6 }}>
                    Select a knowledge item
                  </div>
                  Pick any approved entry on the left to inspect its content, lineage and access scope. Items in the unified
                  library are versioned, signed and indexed across the entire fleet.
                </div>
              )}
              {selected && (
                <>
                  <div className="ttl">{selected.name}</div>
                  <div className="meta">
                    <span className={`stage ${stageClass(selected)}`} style={{ marginRight: 6 }}>
                      {selected.type}
                    </span>
                    v{selected.currentVersion} · {ownerOf(selected, agents)?.name ?? 'Shared'} · approved{' '}
                    {formatDate(selected.createdAt)} · {selected.sensitivity}
                  </div>
                  {selected.type === 'WORKFLOW' && (
                    <div style={{ display: 'flex', gap: '8px', marginBottom: '8px' }}>
                      <button className={`btn ${viewMode === 'md' ? 'primary' : ''}`} onClick={() => setViewMode('md')}>Markdown</button>
                      <button className={`btn ${viewMode === 'yaml' ? 'primary' : ''}`} onClick={() => setViewMode('yaml')}>YAML</button>
                    </div>
                  )}
                  <div className="content">
                    {selected.type === 'WORKFLOW' && viewMode === 'yaml' ? (
                      <pre style={{ margin: 0, whiteSpace: 'pre-wrap', fontSize: 12 }}>
                        {yamlQuery.isLoading ? 'Loading YAML…' : yamlQuery.data || '(no YAML available)'}
                      </pre>
                    ) : (
                      selected.description || '(no description)'
                    )}
                  </div>
                  <div className="actions">
                    <button
                      className="btn"
                      onClick={() => updateKnowledge(selected.id, { status: 'PROMOTED' }).then(() =>
                        queryClient.invalidateQueries({ queryKey: ['knowledge'] }),
                      )}
                    >
                      ⤴ Promote
                    </button>
                    <button
                      className="btn"
                      onClick={() => navigator.clipboard?.writeText(selected.id)}
                    >
                      ⧉ Copy ID
                    </button>
                    <button className="btn" onClick={() => setSelectedItemId(null)}>
                      Close
                    </button>
                  </div>
                </>
              )}
            </div>
          </div>
        </section>
      </div>

      {/* ---------- 5. Access Control ---------- */}
      <section className="panel knowledge-wide">
        <h2>
          Access Control <span className="accent">/ permission matrix</span>
        </h2>
        <div className="knowledge-access">
          <div className="knowledge-access-table">
            <div className="cell th first">
              <b>Knowledge Area</b>
              <span>by agent role</span>
            </div>
            {ACCESS_HEADERS.map((h) => (
              <div className="cell th" key={h}>
                {h}
              </div>
            ))}
            {ACCESS_ROWS.map((row) => (
              <RowFragment key={row.area} row={row} />
            ))}
          </div>
        </div>
        <div className="knowledge-permission-legend">
          <span className="access-label edit">EDIT</span> read & write
          <span className="access-label use">USE</span> invoke only
          <span className="access-label view">VIEW</span> read-only
          <span className="access-label none">NONE</span> denied
        </div>
      </section>

      {/* ---------- Submit modal ---------- */}
      {showSubmit && (
        <>
          <div className="knowledge-pop-scrim open" onClick={() => setShowSubmit(false)} />
          <div className="knowledge-pop" style={{ transform: 'translate(-50%, -50%) scale(1)', opacity: 1, pointerEvents: 'auto', display: 'flex', flexDirection: 'column' }}>
            <div
              style={{
                padding: '14px 16px',
                borderBottom: '1px solid var(--line)',
                display: 'flex',
                alignItems: 'center',
                gap: 8,
              }}
            >
              <strong style={{ fontSize: 13, letterSpacing: '.6px' }}>Submit New Knowledge</strong>
              <span style={{ marginLeft: 'auto', fontSize: 11, color: 'var(--text-mute)' }}>
                Enters governance at <em>Stage 02 · Peer Review</em>
              </span>
              <button className="btn" onClick={() => setShowSubmit(false)} style={{ marginLeft: 6 }}>
                ✕
              </button>
            </div>
            <form onSubmit={submitNew} style={{ padding: 16, display: 'grid', gap: 12, overflow: 'auto' }}>
              <FormRow label="Title">
                <input
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                  placeholder="e.g. PR Review Checklist v2"
                  style={inputStyle}
                />
              </FormRow>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                <FormRow label="Type">
                  <select
                    value={form.type}
                    onChange={(e) => setForm({ ...form, type: e.target.value as KnowledgeType })}
                    style={inputStyle}
                  >
                    <option value="SKILL">Skill</option>
                    <option value="SCRIPT">Script</option>
                    <option value="PROMPT">Prompt</option>
                    <option value="TOOL">Tool</option>
                    <option value="TEMPLATE">Template</option>
                    <option value="GUIDELINE">Guideline</option>
                    <option value="WORKFLOW">Workflow</option>
                    <option value="SPEC">Spec</option>
                  </select>
                </FormRow>
                <FormRow label="Sensitivity">
                  <select
                    value={form.sensitivity}
                    onChange={(e) => setForm({ ...form, sensitivity: e.target.value })}
                    style={inputStyle}
                  >
                    <option value="PUBLIC">Public</option>
                    <option value="INTERNAL">Internal</option>
                    <option value="CONFIDENTIAL">Confidential</option>
                    <option value="RESTRICTED">Restricted</option>
                  </select>
                </FormRow>
              </div>
              <FormRow label="Description">
                <textarea
                  value={form.description}
                  onChange={(e) => setForm({ ...form, description: e.target.value })}
                  rows={2}
                  placeholder="Plain-language summary"
                  style={{ ...inputStyle, resize: 'vertical' }}
                />
              </FormRow>
              <FormRow label="Content">
                <textarea
                  value={form.content}
                  onChange={(e) => setForm({ ...form, content: e.target.value })}
                  rows={6}
                  placeholder="Body, code, instructions…"
                  style={{ ...inputStyle, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace', resize: 'vertical' }}
                />
              </FormRow>
              {formError && (
                <div style={{ color: '#ff8d99', fontSize: 12 }}>{formError}</div>
              )}
              <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
                <button type="button" className="btn" onClick={() => setShowSubmit(false)}>
                  Cancel
                </button>
                <button type="submit" className="btn primary" disabled={createMut.isPending}>
                  {createMut.isPending ? 'Submitting…' : 'Submit for Review'}
                </button>
              </div>
            </form>
          </div>
        </>
      )}

      {/* ---------- Toast ---------- */}
      {toast && (
        <div
          style={{
            position: 'fixed',
            bottom: 24,
            right: 24,
            zIndex: 200,
            padding: '10px 14px',
            border: '1px solid rgba(91,140,255,.4)',
            background: 'rgba(91,140,255,.14)',
            color: 'var(--text)',
            borderRadius: 10,
            fontSize: 12,
            backdropFilter: 'blur(8px)',
            boxShadow: '0 12px 32px rgba(0,0,0,.45)',
          }}
        >
          {toast}
        </div>
      )}
    </div>
  );
}

// ---------- Tiny presentational helpers ---------- //

const inputStyle: React.CSSProperties = {
  width: '100%',
  border: '1px solid var(--line-2)',
  borderRadius: 9,
  background: 'rgba(0,0,0,.22)',
  color: 'var(--text)',
  padding: '8px 10px',
  font: 'inherit',
  fontSize: 12.5,
  outline: 'none',
};

function FormRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label style={{ display: 'block' }}>
      <span
        style={{
          display: 'block',
          color: 'var(--text-mute)',
          fontSize: 9.5,
          letterSpacing: '.7px',
          textTransform: 'uppercase',
          marginBottom: 4,
        }}
      >
        {label}
      </span>
      {children}
    </label>
  );
}

function RowFragment({ row }: { row: AccessRow }) {
  return (
    <>
      <div className="cell first">
        <b>{row.area}</b>
        <span>{row.hint}</span>
      </div>
      {row.cells.map((c, i) => (
        <div className="cell" key={i}>
          <span className={`access-label ${c.cls}`}>{c.label}</span>
        </div>
      ))}
    </>
  );
}
