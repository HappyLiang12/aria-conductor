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
import { apiErrorMessage } from '../api/operatorSession';
import { presentError } from '../api/errorPresentation';
import ErrorNotice from '../components/ErrorNotice';
import { StateNotice } from '../components/StateNotice';
import { useWebSocketContext } from '../components/Layout';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { PageHeader } from '../components/layout/PageHeader';
import { SectionCard } from '../components/layout/SectionCard';
import { Badge } from '../components/ui/badge';
import { Button } from '../components/ui/button';
import { Card } from '../components/ui/card';
import { Input } from '../components/ui/input';
import { Textarea } from '../components/ui/textarea';
import { formatTimestamp } from '../utils/formatTime';
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
  { num: '01', ttl: 'Draft',            desc: 'An agent or operator records a skill, prompt, script or template.' },
  { num: '02', ttl: 'Pending review',   desc: 'Stored items wait for a human decision; agents cannot use them yet.' },
  { num: '03', ttl: 'Approved / Rejected', desc: 'A human decides; only approved items reach prompts and query_knowledge.' },
  { num: '04', ttl: 'Retired',          desc: 'An approved item can be withdrawn and stops reaching agents.' },
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

function formatDate(iso: string): string {
  return formatTimestamp(iso);
}

export function formatVersion(version: string | number | null | undefined): string {
  if (version === null || version === undefined) return '—';
  const text = String(version).trim();
  if (text === '') return '—';
  return text.startsWith('v') ? text : `v${text}`;
}

/** Stable key for the bucket that owns every item without a real owner. */
const UNATTRIBUTED = '__unattributed__';

/**
 * Truthful ownership: an agent owns an item only when the item names that agent
 * and the agent exists. Missing/unmatched ownership is a fact about the data —
 * it must never be replaced by a hash-picked agent (that invented authority).
 */
function ownerOf(item: ExtendedKnowledgeItem, agents: Agent[]): Agent | null {
  if (!item.agentId) return null;
  return agents.find((a) => a.id === item.agentId) ?? null;
}

/** The single bucket key used by the space map and both filters. */
function ownerKeyOf(item: ExtendedKnowledgeItem, agents: Agent[]): string {
  return ownerOf(item, agents)?.id ?? UNATTRIBUTED;
}

// ---------- Page ---------- //

export function KnowledgePage() {
  const queryClient = useQueryClient();
  const { lastMessage } = useWebSocketContext();

  // queries
  const { data: itemsRaw, isLoading: loadingItems, error: itemsError } = useQuery({
    queryKey: ['knowledge'],
    queryFn: () => listKnowledge(),
  });
  const {
    data: agentsRaw,
    isLoading: agentsLoading,
    error: agentsError,
    refetch: refetchAgents,
  } = useQuery({
    queryKey: ['agents'],
    queryFn: listAgents,
  });
  const items: ExtendedKnowledgeItem[] = useMemo(() => itemsRaw ?? [], [itemsRaw]);
  const agents: Agent[] = useMemo(() => agentsRaw ?? [], [agentsRaw]);
  // Ownership labels and buckets are only as trustworthy as the agent list. A
  // first-load failure means ownership is UNKNOWN, not "nobody owns anything":
  // every item would otherwise be mislabelled Unattributed.
  const ownershipKnown = agentsRaw !== undefined;

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
  // Pending review awaiting user confirmation; single and batch flows both go
  // through the confirm dialog so no mutation fires on a stray click.
  const [confirmReview, setConfirmReview] = useState<{ ids: string[]; approved: boolean; batch: boolean } | null>(null);
  // Pending promote awaiting user confirmation — promotion is irreversible for
  // the operator, so it must not fire on a stray click either.
  const [confirmingPromote, setConfirmingPromote] = useState(false);

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
      setConfirmReview(null);
    },
    onError: (err) =>
      setToast(apiErrorMessage(err, (err as Error)?.message ?? 'Review failed.')),
  });

  const batchMut = useMutation({
    mutationFn: (vars: { ids: string[]; approved: boolean; reason?: string }) =>
      batchReviewKnowledge(vars.ids, vars.approved, vars.reason),
    onSuccess: (results, vars) => {
      const fulfilled = results.filter((r) => r.status === 'fulfilled').length;
      setToast(`${vars.approved ? 'Approved' : 'Rejected'} ${fulfilled}/${vars.ids.length} item(s)`);
      setCheckedIds(new Set());
      queryClient.invalidateQueries({ queryKey: ['knowledge'] });
      setConfirmReview(null);
    },
    onError: (err) =>
      setToast(apiErrorMessage(err, (err as Error)?.message ?? 'Review failed.')),
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
    onError: (err: unknown) => setFormError(apiErrorMessage(err, 'Submission failed.')),
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
    // Seeded even with no agents: unowned items still need a home, and the
    // bucket must never borrow a real agent's identity.
    map.set(UNATTRIBUTED, []);
    for (const it of items) {
      map.get(ownerKeyOf(it, agents))?.push(it);
    }
    return map;
  }, [items, agents]);

  const visiblePending = useMemo(() => {
    if (!ownershipKnown || !activeAgentId) return pending;
    return pending.filter((it) => ownerKeyOf(it, agents) === activeAgentId);
  }, [pending, agents, activeAgentId, ownershipKnown]);

  const unattributedItems = itemsByAgent.get(UNATTRIBUTED) ?? [];

  /** Owner display label; unavailable ownership never reads as "Unattributed". */
  const ownerLabelOf = (item: ExtendedKnowledgeItem): string =>
    ownerOf(item, agents)?.name ?? (ownershipKnown ? 'Unattributed' : 'ownership unavailable');

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

  const handleConfirmReview = () => {
    if (!confirmReview) return;
    const { ids, approved, batch } = confirmReview;
    if (batch) {
      batchMut.mutate({
        ids,
        approved,
        reason: reviewReason || (approved ? 'Batch approved' : 'Batch rejected'),
      });
    } else if (approved) {
      reviewMut.mutate({ id: ids[0], approved: true, reason: reviewReason });
    } else {
      reviewMut.mutate({ id: ids[0], approved: false, reason: reviewReason || 'Rejected' });
    }
    // Dialog closes in onSuccess only — on failure it stays open so the user
    // can retry or cancel, and the error surfaces via toast.
  };

  const handleConfirmPromote = () => {
    if (!selected) return;
    const target = selected;
    // Closed immediately: the action is settled, so the dialog must not linger
    // over it. The finally re-clears (idempotent) so a failure can never leave
    // it stranded open.
    setConfirmingPromote(false);
    updateKnowledge(target.id, { status: 'PROMOTED' })
      .then(() => queryClient.invalidateQueries({ queryKey: ['knowledge'] }))
      .catch((err) => setToast(apiErrorMessage(err, 'Promote failed.')))
      .finally(() => setConfirmingPromote(false));
  };

  const spacesCount = ownershipKnown
    ? `${agents.length} agents${unattributedItems.length > 0 ? ` · ${unattributedItems.length} unattributed` : ''}`
    : 'ownership unavailable';
  const reviewCount = `/ ${visiblePending.length} pending${
    activeAgentId
      ? ` · ${activeAgentId === UNATTRIBUTED ? 'Unattributed' : agents.find((a) => a.id === activeAgentId)?.name}`
      : ''
  }`;

  return (
    <div className="view-zone space-y-4 p-4" data-view="knowledge">
      <PageHeader
        title="Knowledge Governance"
        description="Status lifecycle for every skill, prompt, script and template — draft to retirement."
        actions={
          <>
            <Button
              variant="outline"
              onClick={() => queryClient.invalidateQueries({ queryKey: ['knowledge'] })}
            >
              ↻ Refresh
            </Button>
            <Button onClick={() => setShowSubmit(true)}>+ Submit New</Button>
          </>
        }
      />

      {itemsError && (
        <ErrorNotice presented={presentError(itemsError, 'Failed to load the knowledge library.')} />
      )}

      {/* ---------- 1. Promotion Path ---------- */}
      <section className="panel">
        <SectionCard title="Promotion Path / governance flow">
          <div className="knowledge-flow grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            {FLOW_STEPS.map((s) => (
              <Card key={s.num} size="sm" className="kstep p-3">
                <div className="num text-[10px] font-semibold tracking-wider text-muted-foreground uppercase">
                  Stage {s.num}
                </div>
                <div className="ttl mt-1 text-sm font-semibold text-foreground">{s.ttl}</div>
                <div className="desc mt-1 text-xs leading-relaxed text-muted-foreground">{s.desc}</div>
              </Card>
            ))}
          </div>
        </SectionCard>
      </section>

      {/* ---------- 2. Per-Agent Knowledge Spaces ---------- */}
      <section className="panel">
        <SectionCard title="Per-Agent Spaces" count={`/ ${spacesCount}`}>
          <div className="knowledge-spaces grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {!ownershipKnown && (
              <div className="sm:col-span-2 xl:col-span-3" data-testid="ownership-unavailable">
                {agentsLoading ? (
                  <StateNotice kind="loading" title="Loading agent spaces…" />
                ) : (
                  <StateNotice
                    kind="error"
                    title="Agent ownership unavailable"
                    message={apiErrorMessage(agentsError, 'Items are listed without ownership labels.')}
                    actionLabel="Retry"
                    onAction={() => {
                      void refetchAgents();
                    }}
                  />
                )}
              </div>
            )}
            {ownershipKnown && agents.length === 0 && (
              <div className="rounded-lg border border-dashed border-border p-6 text-center text-xs text-muted-foreground sm:col-span-2 xl:col-span-3">
                No agents registered yet. Create an agent to allocate a knowledge space.
              </div>
            )}
            {ownershipKnown && agents.map((a) => {
              const owned = itemsByAgent.get(a.id) ?? [];
              const draft = owned.filter((i) => i.status === 'DRAFT' || i.status === 'PENDING').length;
              const live = owned.filter((i) => i.status === 'APPROVED').length;
              const isActive = activeAgentId === a.id;
              return (
                <button
                  type="button"
                  key={a.id}
                  className={`kspace flex flex-col gap-3 rounded-xl bg-card p-4 text-left text-card-foreground ring-1 ring-foreground/10 transition-colors hover:ring-ring ${
                    isActive ? 'active ring-2 ring-primary' : ''
                  }`}
                  aria-pressed={isActive}
                  onClick={() => setActiveAgentId(isActive ? null : a.id)}
                >
                  <div className="top flex items-center gap-3">
                    <div className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-secondary text-sm font-extrabold tracking-wide text-secondary-foreground">
                      {avatarChar(a.name)}
                    </div>
                    <div className="min-w-0">
                      <div className="name truncate text-sm font-semibold text-foreground">{a.name}</div>
                      <div className="sub truncate text-xs text-muted-foreground">{a.role || a.agentType}</div>
                    </div>
                  </div>
                  <div className="metrics grid grid-cols-3 gap-2">
                    <div className="rounded-lg bg-muted/50 p-2">
                      <b className="block text-sm text-foreground">{owned.length}</b>
                      <span className="text-[10px] tracking-wide text-muted-foreground uppercase">Total</span>
                    </div>
                    <div className="rounded-lg bg-muted/50 p-2">
                      <b className="block text-sm text-foreground">{draft}</b>
                      <span className="text-[10px] tracking-wide text-muted-foreground uppercase">In Review</span>
                    </div>
                    <div className="rounded-lg bg-muted/50 p-2">
                      <b className="block text-sm text-foreground">{live}</b>
                      <span className="text-[10px] tracking-wide text-muted-foreground uppercase">Live</span>
                    </div>
                  </div>
                </button>
              );
            })}
            {ownershipKnown && unattributedItems.length > 0 && (() => {
              const owned = unattributedItems;
              const draft = owned.filter((i) => i.status === 'DRAFT' || i.status === 'PENDING').length;
              const live = owned.filter((i) => i.status === 'APPROVED').length;
              const isActive = activeAgentId === UNATTRIBUTED;
              return (
                <button
                  type="button"
                  className={`kspace flex flex-col gap-3 rounded-xl bg-card p-4 text-left text-card-foreground ring-1 ring-foreground/10 transition-colors hover:ring-ring ${
                    isActive ? 'active ring-2 ring-primary' : ''
                  }`}
                  aria-pressed={isActive}
                  onClick={() => setActiveAgentId(isActive ? null : UNATTRIBUTED)}
                >
                  <div className="top flex items-center gap-3">
                    {/* Never a real agent's avatar or name: this bucket means
                        "no owner recorded", not shared authority. */}
                    <div className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-muted text-sm font-extrabold tracking-wide text-muted-foreground">
                      {avatarChar('Unattributed')}
                    </div>
                    <div className="min-w-0">
                      <div className="name">
                        <Badge variant="outline" className="pill dim">Unattributed</Badge>
                      </div>
                      <div className="sub text-xs text-muted-foreground">No owner recorded</div>
                    </div>
                  </div>
                  <div className="metrics grid grid-cols-3 gap-2">
                    <div className="rounded-lg bg-muted/50 p-2">
                      <b className="block text-sm text-foreground">{owned.length}</b>
                      <span className="text-[10px] tracking-wide text-muted-foreground uppercase">Total</span>
                    </div>
                    <div className="rounded-lg bg-muted/50 p-2">
                      <b className="block text-sm text-foreground">{draft}</b>
                      <span className="text-[10px] tracking-wide text-muted-foreground uppercase">In Review</span>
                    </div>
                    <div className="rounded-lg bg-muted/50 p-2">
                      <b className="block text-sm text-foreground">{live}</b>
                      <span className="text-[10px] tracking-wide text-muted-foreground uppercase">Live</span>
                    </div>
                  </div>
                </button>
              );
            })()}
          </div>
        </SectionCard>
      </section>

      {/* ---------- 3 + 4. Review queue + Unified library ---------- */}
      <div className="knowledge-grid grid gap-4 xl:grid-cols-2">
        {/* Review queue */}
        <section className="panel">
          <SectionCard
            title="Submitted For Review"
            count={reviewCount}
            actions={
              activeAgentId ? (
                <Button size="xs" variant="ghost" onClick={() => setActiveAgentId(null)}>
                  Clear filter
                </Button>
              ) : undefined
            }
          >
            <div className="knowledge-review space-y-3">
              {/* Batch toolbar */}
              <div className="flex flex-wrap items-center gap-3 border-b border-dashed border-border pb-3">
                <label className="flex items-center gap-1.5 text-xs text-muted-foreground">
                  <input
                    type="checkbox"
                    className="accent-primary"
                    checked={visiblePending.length > 0 && visiblePending.every((p) => checkedIds.has(p.id))}
                    onChange={toggleAllVisible}
                  />
                  Select all visible
                </label>
                <span className="text-xs text-muted-foreground">{checkedIds.size} selected</span>
                <div className="ml-auto flex gap-1.5">
                  <Button
                    size="sm"
                    variant="outline"
                    className="text-emerald-700 dark:text-emerald-400"
                    disabled={checkedIds.size === 0 || batchMut.isPending}
                    onClick={() =>
                      setConfirmReview({
                        ids: Array.from(checkedIds),
                        approved: true,
                        batch: true,
                      })
                    }
                  >
                    ✓ Batch Approve
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    className="text-destructive"
                    disabled={checkedIds.size === 0 || batchMut.isPending}
                    onClick={() =>
                      setConfirmReview({
                        ids: Array.from(checkedIds),
                        approved: false,
                        batch: true,
                      })
                    }
                  >
                    ✗ Batch Reject
                  </Button>
                </div>
              </div>

              {/* Items */}
              <div className="grid gap-2">
                {loadingItems && (
                  <div className="py-3 text-xs text-muted-foreground">Loading…</div>
                )}
                {!loadingItems && !itemsError && visiblePending.length === 0 && (
                  <div className="rounded-lg border border-dashed border-border py-6 text-center text-xs text-muted-foreground">
                    Inbox zero. No items waiting for review.
                  </div>
                )}
                {visiblePending.map((it) => {
                  const checked = checkedIds.has(it.id);
                  return (
                    <Card
                      key={it.id}
                      size="sm"
                      className="kitem grid grid-cols-[auto_auto_1fr_auto] items-center gap-3 p-3"
                    >
                      <input
                        type="checkbox"
                        className="accent-primary"
                        checked={checked}
                        onChange={() => toggleChecked(it.id)}
                      />
                      <Badge variant="secondary" className={`stage ${stageClass(it)}`}>
                        {it.type}
                      </Badge>
                      <div className="min-w-0">
                        <div className="ttl truncate text-sm font-medium text-foreground">{it.name}</div>
                        <div className="desc truncate text-xs text-muted-foreground">
                          {ownerLabelOf(it)} · submitted {formatDate(it.createdAt)}
                          {it.description ? ` · ${it.description.slice(0, 60)}${it.description.length > 60 ? '…' : ''}` : ''}
                        </div>
                      </div>
                      <div className="flex gap-1.5">
                        <Button
                          size="icon-sm"
                          variant="outline"
                          className="text-emerald-700 dark:text-emerald-400"
                          onClick={() => setConfirmReview({ ids: [it.id], approved: true, batch: false })}
                          disabled={reviewMut.isPending}
                          title="Approve"
                        >
                          ✓
                        </Button>
                        <Button
                          size="icon-sm"
                          variant="outline"
                          className="text-destructive"
                          onClick={() => setConfirmReview({ ids: [it.id], approved: false, batch: false })}
                          disabled={reviewMut.isPending}
                          title="Reject"
                        >
                          ✗
                        </Button>
                        <Button
                          size="icon-sm"
                          variant="ghost"
                          onClick={() => setSelectedItemId(it.id)}
                          title="View"
                        >
                          ↗
                        </Button>
                      </div>
                    </Card>
                  );
                })}
              </div>

              {/* Reason */}
              {(visiblePending.length > 0 || checkedIds.size > 0) && (
                <div className="space-y-1">
                  <label
                    htmlFor="knowledge-review-reason"
                    className="block text-[10px] font-medium tracking-wider text-muted-foreground uppercase"
                  >
                    Review note (optional)
                  </label>
                  <Input
                    id="knowledge-review-reason"
                    value={reviewReason}
                    onChange={(e) => setReviewReason(e.target.value)}
                    placeholder="e.g. Approved per security review SR-2031"
                  />
                </div>
              )}
            </div>
          </SectionCard>
        </section>

        {/* Unified knowledge space */}
        <section className="panel">
          <SectionCard title="Unified Knowledge Space" count={`/ ${filteredApproved.length} approved`}>
            <div className="kb-toolbar mb-3 flex flex-wrap items-center gap-3">
              <Input
                className="max-w-xs"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search title, description, type…"
              />
              <span className="text-xs text-muted-foreground">{approved.length} total</span>
            </div>

            <div className="kb-layout grid gap-3 lg:grid-cols-2">
              <div className="unified-list grid content-start gap-2">
                {!itemsError && filteredApproved.length === 0 && (
                  <div className="rounded-lg border border-dashed border-border p-4 text-center text-xs text-muted-foreground">
                    {approved.length === 0 ? 'No approved knowledge yet.' : 'No items match your search.'}
                  </div>
                )}
                {filteredApproved.map((it) => {
                  const isActive = selectedItemId === it.id;
                  return (
                    <Card
                      key={it.id}
                      size="sm"
                      className={`kitem selectable cursor-pointer p-3 ${
                        isActive ? 'active ring-2 ring-primary' : ''
                      }`}
                      onClick={() => setSelectedItemId(it.id)}
                    >
                      <div className="flex items-center gap-3">
                        <Badge variant="secondary" className={`stage ${stageClass(it)}`}>
                          {it.type}
                        </Badge>
                        <div className="min-w-0 flex-1">
                          <div className="ttl truncate text-sm font-medium text-foreground">{it.name}</div>
                          <div className="desc text-xs text-muted-foreground">
                            {formatVersion(it.currentVersion)} · {ownerLabelOf(it)} · {formatDate(it.createdAt)}
                          </div>
                        </div>
                        <span className="text-[10px] tracking-wide text-muted-foreground uppercase">
                          {it.sensitivity}
                        </span>
                      </div>
                    </Card>
                  );
                })}
              </div>

              <div className="kb-detail rounded-lg bg-muted/30 p-3 ring-1 ring-foreground/5">
                {!selected && (
                  <div className="text-xs leading-relaxed text-muted-foreground">
                    <div className="ttl mb-1.5 text-sm font-semibold text-foreground">
                      Select a knowledge item
                    </div>
                    Pick any approved entry on the left to inspect its content, lineage and access scope. Items in the unified
                    library are versioned, signed and indexed across the entire fleet.
                  </div>
                )}
                {selected && (
                  <>
                    <div className="ttl text-sm font-semibold text-foreground">{selected.name}</div>
                    <div className="meta mt-2 mb-2 flex flex-wrap items-center gap-2 text-xs leading-relaxed text-muted-foreground">
                      <Badge variant="secondary" className={`stage ${stageClass(selected)}`}>
                        {selected.type}
                      </Badge>
                      <span>
                        {formatVersion(selected.currentVersion)} · {ownerLabelOf(selected)} · approved{' '}
                        {formatDate(selected.createdAt)} · {selected.sensitivity}
                      </span>
                    </div>
                    {selected.type === 'WORKFLOW' && (
                      <div className="mb-2 flex gap-2">
                        <Button
                          size="xs"
                          variant={viewMode === 'md' ? 'default' : 'outline'}
                          onClick={() => setViewMode('md')}
                        >
                          Markdown
                        </Button>
                        <Button
                          size="xs"
                          variant={viewMode === 'yaml' ? 'default' : 'outline'}
                          onClick={() => setViewMode('yaml')}
                        >
                          YAML
                        </Button>
                      </div>
                    )}
                    <div className="content max-h-40 overflow-auto rounded-lg border border-border bg-background/60 p-3 text-sm leading-relaxed whitespace-pre-wrap text-foreground">
                      {selected.type === 'WORKFLOW' && viewMode === 'yaml' ? (
                        <pre className="m-0 text-xs whitespace-pre-wrap">
                          {yamlQuery.isLoading ? 'Loading YAML…' : yamlQuery.data || '(no YAML available)'}
                        </pre>
                      ) : (
                        selected.description || '(no description)'
                      )}
                    </div>
                    <div className="actions mt-3 flex flex-wrap gap-2">
                      <Button variant="outline" onClick={() => setConfirmingPromote(true)}>
                        ⤴ Promote
                      </Button>
                      <Button
                        variant="outline"
                        onClick={() => navigator.clipboard?.writeText(selected.id)}
                      >
                        ⧉ Copy ID
                      </Button>
                      <Button variant="ghost" onClick={() => setSelectedItemId(null)}>
                        Close
                      </Button>
                    </div>
                  </>
                )}
              </div>
            </div>
          </SectionCard>
        </section>
      </div>

      {/* ---------- Review confirm dialog ---------- */}
      {confirmReview && (
        <div
          className="modal-overlay fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4 backdrop-blur-sm"
          onClick={() => setConfirmReview(null)}
        >
          <div
            className="modal-dialog w-full max-w-md rounded-xl bg-card p-5 text-card-foreground ring-1 ring-foreground/10"
            role="dialog"
            aria-modal="true"
            aria-label="Confirm Review"
            onClick={(e) => e.stopPropagation()}
          >
            <h3 className="text-base font-semibold text-foreground">Confirm Review</h3>
            {confirmReview.approved ? (
              <p className="mt-2 text-sm text-muted-foreground">
                You are about to <strong className="text-foreground">approve</strong> {confirmReview.ids.length} item(s).
                They will be published to the unified knowledge library.
              </p>
            ) : (
              <p className="mt-2 text-sm text-muted-foreground">
                You are about to <strong className="text-foreground">reject</strong> {confirmReview.ids.length} item(s).
                They will be returned to the submitting agent.
              </p>
            )}
            {reviewReason && (
              <div className="approval-reason-preview mt-2 rounded-lg bg-muted p-2 text-xs text-muted-foreground">
                Reason: {reviewReason}
              </div>
            )}
            <div className="modal-actions mt-4 flex justify-end gap-2">
              <Button
                variant={confirmReview.approved ? 'default' : 'destructive'}
                onClick={handleConfirmReview}
                disabled={reviewMut.isPending || batchMut.isPending}
              >
                {reviewMut.isPending || batchMut.isPending
                  ? 'Processing...'
                  : confirmReview.approved
                    ? 'Confirm Approve'
                    : 'Confirm Reject'}
              </Button>
              <Button variant="outline" onClick={() => setConfirmReview(null)}>Cancel</Button>
            </div>
          </div>
        </div>
      )}

      {/* ---------- Promote confirm dialog (shared, Task 10) ---------- */}
      <ConfirmDialog
        open={confirmingPromote && selected !== null}
        title="Confirm Promote"
        message={
          <>
            You are about to <strong>promote</strong> {selected?.name} ({formatVersion(selected?.currentVersion)}). It will
            leave the approved library and move up the promotion path.
          </>
        }
        onConfirm={handleConfirmPromote}
        onCancel={() => setConfirmingPromote(false)}
      />

      {/* ---------- Submit modal ---------- */}
      {showSubmit && (
        <>
          <div
            className="knowledge-pop-scrim open fixed inset-0 z-40 bg-black/50 backdrop-blur-sm"
            onClick={() => setShowSubmit(false)}
          />
          <div
            className="knowledge-pop fixed top-1/2 left-1/2 z-50 flex max-h-[85vh] w-[min(560px,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 flex-col overflow-hidden rounded-xl bg-card text-card-foreground ring-1 ring-foreground/10"
            role="dialog"
            aria-modal="true"
            aria-label="Submit New Knowledge"
          >
            <div className="flex flex-wrap items-center gap-2 border-b border-border px-4 py-3">
              <strong className="text-sm font-semibold tracking-wide text-foreground">Submit New Knowledge</strong>
              <span className="ml-auto text-xs text-muted-foreground">
                Stored as <em>Pending</em> — a human must approve it before agents can use it.
              </span>
              <Button variant="ghost" size="icon-sm" onClick={() => setShowSubmit(false)} className="ml-1.5">
                ✕
              </Button>
            </div>
            <form onSubmit={submitNew} className="grid gap-3 overflow-auto p-4">
              <FormRow label="Title" htmlFor="knowledge-title">
                <Input
                  id="knowledge-title"
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                  placeholder="e.g. PR Review Checklist v2"
                />
              </FormRow>
              <div className="grid grid-cols-2 gap-3">
                <FormRow label="Type" htmlFor="knowledge-type">
                  <select
                    id="knowledge-type"
                    className="h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm text-foreground outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30"
                    value={form.type}
                    onChange={(e) => setForm({ ...form, type: e.target.value as KnowledgeType })}
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
                <FormRow label="Sensitivity" htmlFor="knowledge-sensitivity">
                  <select
                    id="knowledge-sensitivity"
                    className="h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm text-foreground outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30"
                    value={form.sensitivity}
                    onChange={(e) => setForm({ ...form, sensitivity: e.target.value as CreateKnowledgeRequest['sensitivity'] })}
                  >
                    <option value="PUBLIC">Public</option>
                    <option value="INTERNAL">Internal</option>
                    <option value="CONFIDENTIAL">Confidential</option>
                    <option value="RESTRICTED">Restricted</option>
                  </select>
                </FormRow>
              </div>
              <FormRow label="Description" htmlFor="knowledge-description">
                <Textarea
                  id="knowledge-description"
                  value={form.description}
                  onChange={(e) => setForm({ ...form, description: e.target.value })}
                  rows={2}
                  placeholder="Plain-language summary"
                  className="resize-y"
                />
              </FormRow>
              <FormRow label="Content" htmlFor="knowledge-content">
                <Textarea
                  id="knowledge-content"
                  value={form.content}
                  onChange={(e) => setForm({ ...form, content: e.target.value })}
                  rows={6}
                  placeholder="Body, code, instructions…"
                  className="resize-y font-mono"
                />
              </FormRow>
              {formError && (
                <div className="text-xs text-destructive" role="alert">{formError}</div>
              )}
              <div className="flex justify-end gap-2">
                <Button type="button" variant="outline" onClick={() => setShowSubmit(false)}>
                  Cancel
                </Button>
                <Button type="submit" disabled={createMut.isPending}>
                  {createMut.isPending ? 'Submitting…' : 'Submit for Review'}
                </Button>
              </div>
            </form>
          </div>
        </>
      )}

      {/* ---------- Toast ---------- */}
      {toast && (
        <div
          role="status"
          className="fixed right-6 bottom-6 z-[200] rounded-lg border border-primary/40 bg-primary/10 px-3.5 py-2.5 text-xs text-foreground shadow-lg backdrop-blur-md"
        >
          {toast}
        </div>
      )}
    </div>
  );
}

// ---------- Tiny presentational helpers ---------- //

function FormRow({
  label,
  htmlFor,
  children,
}: {
  label: string;
  htmlFor?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="block">
      <label
        htmlFor={htmlFor}
        className="mb-1 block text-[10px] font-medium tracking-wider text-muted-foreground uppercase"
      >
        {label}
      </label>
      {children}
    </div>
  );
}
