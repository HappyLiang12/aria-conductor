import { useState, useMemo, useEffect, useRef, useCallback } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { listRuns, getRunTrajectory, injectRunMessage } from '../api/runs';
import { listAgents } from '../api/agents';
import WorkflowStepper from '../components/WorkflowStepper';
import DelegationTree from '../components/DelegationTree';
import ReviewQueue from '../components/ReviewQueue';
import { useWebSocketContext } from '../components/Layout';
import { PageHeader } from '../components/layout/PageHeader';
import { Badge } from '../components/ui/badge';
import { Button } from '../components/ui/button';
import { Card, CardHeader } from '../components/ui/card';
import { Input } from '../components/ui/input';
import { Textarea } from '../components/ui/textarea';
import { apiErrorMessage } from '../api/operatorSession';
import { formatTimestamp } from '../utils/formatTime';
import { isRunLifecycleEvent } from '../utils/wsEvents';
import type { Run, Agent, SessionTrajectory } from '../types';

// ---------- Types ----------
interface ChatThread {
  id: string;
  title: string;
  participants: string[];
  participantRoles: string[];
  lastMessage: string;
  lastTimestamp: string;
  unreadCount: number;
  status: string;
}

interface ChatMessage {
  id: string;
  threadId: string;
  sender: string;
  senderRole: string; // visual class hint: dev | qa | ba | ver | sm | infra | rel | night | human
  content: string;
  timestamp: string;
  isHandoff?: boolean;
  isSystem?: boolean;
  isHuman?: boolean;
  isFresh?: boolean;
}

// ---------- Helpers ----------
const ROLE_AVATAR_MAP: Record<string, string> = {
  coder: 'dev',
  developer: 'dev',
  dev: 'dev',
  qa: 'qa',
  tester: 'qa',
  ba: 'ba',
  analyst: 'ba',
  reviewer: 'ver',
  verifier: 'ver',
  orchestrator: 'sm',
  sm: 'sm',
  scrum: 'sm',
  infra: 'infra',
  devops: 'infra',
  release: 'rel',
  rel: 'rel',
  night: 'night',
  watcher: 'night',
};

function avatarClass(role: string | undefined | null): string {
  if (!role) return 'dev';
  const k = role.toLowerCase();
  return ROLE_AVATAR_MAP[k] ?? 'dev';
}

function avatarInitials(name: string): string {
  if (!name) return '?';
  const cleaned = name.replace(/Agent|Runtime|Shift/gi, '').trim();
  const parts = cleaned.split(/\s+|[-_·]/).filter(Boolean);
  const letters = parts.map((p) => p[0]).join('').slice(0, 2);
  return (letters || name.slice(0, 2)).toUpperCase();
}

function formatTime(iso: string): string {
  return formatTimestamp(iso);
}

function nowIso(): string {
  return new Date().toISOString();
}

function shortId(id: string): string {
  return id.slice(0, 8);
}

function buildThreadTitle(run: Run, agent: Agent | undefined): string {
  const name = agent?.name ?? `Agent ${shortId(run.agentId)}`;
  const seed = (run.promptSeed || '').replace(/\s+/g, ' ').trim();
  const seedSnippet = seed.length > 64 ? `${seed.slice(0, 64)}…` : seed;
  return seedSnippet ? `${name} · ${seedSnippet}` : `${name} · Run ${shortId(run.id)}`;
}

function deriveMessages(
  threadId: string,
  trajectory: SessionTrajectory[] | undefined,
  agentName: string,
  agentRole: string,
): ChatMessage[] {
  if (!trajectory || trajectory.length === 0) return [];
  return trajectory.map((t, idx): ChatMessage => {
    const role = (t.role || '').toLowerCase();
    const isUser = role === 'user' || role === 'human';
    const isSystem = role === 'system';
    const isTool = role === 'tool';
    const isAssistant = role === 'assistant';

    let sender: string;
    let senderRole: string;
    if (isUser) {
      sender = 'Operator';
      senderRole = 'human';
    } else if (isSystem) {
      sender = 'System';
      senderRole = 'sm';
    } else if (isTool) {
      sender = 'Tool Bridge';
      senderRole = 'infra';
    } else if (isAssistant) {
      sender = agentName;
      senderRole = avatarClass(agentRole);
    } else {
      sender = t.role || 'Unknown';
      senderRole = avatarClass(t.role);
    }

    const prev = trajectory[idx - 1];
    const isHandoff = !!prev && prev.role !== t.role && (isAssistant || isTool);

    return {
      id: t.id,
      threadId,
      sender,
      senderRole,
      content: t.content || '',
      timestamp: t.createdAt,
      isHandoff: isHandoff && !isUser,
      isSystem,
      isHuman: isUser,
    };
  });
}

// ---------- Component ----------
export default function ChatPage() {
  const queryClient = useQueryClient();
  const [activeThreadId, setActiveThreadId] = useState<string | null>(null);
  // Task 9: one pane at a time on narrow screens; desktop shows both.
  const [mobilePane, setMobilePane] = useState<'list' | 'thread'>('list');
  const [search, setSearch] = useState('');
  const [draft, setDraft] = useState('');
  const [injectedByThread, setInjectedByThread] = useState<Record<string, ChatMessage[]>>({});
  const [injectError, setInjectError] = useState<string | null>(null);
  const [readThreads, setReadThreads] = useState<Record<string, boolean>>({});
  const stackRef = useRef<HTMLDivElement | null>(null);
  // Submit lock: Ctrl+Enter spam must not fire duplicate inject POSTs.
  const injectingRef = useRef(false);

  const { data: runs, isLoading: runsLoading, error: runsError } = useQuery({
    queryKey: ['runs'],
    queryFn: listRuns,
  });

  const { data: agents } = useQuery({
    queryKey: ['agents'],
    queryFn: listAgents,
  });

  const agentMap = useMemo(() => {
    const m = new Map<string, Agent>();
    agents?.forEach((a) => m.set(a.id, a));
    return m;
  }, [agents]);

  // Build threads from runs
  const threads: ChatThread[] = useMemo(() => {
    if (!runs) return [];
    return runs.map((run): ChatThread => {
      const agent = agentMap.get(run.agentId);
      const operatorName = 'Operator';
      const agentName = agent?.name ?? `Agent ${shortId(run.agentId)}`;
      const agentRole = agent?.role ?? 'agent';
      return {
        id: run.id,
        title: buildThreadTitle(run, agent),
        participants: [operatorName, agentName],
        participantRoles: ['human', avatarClass(agentRole)],
        lastMessage: (run.promptSeed || '').slice(0, 120) || 'No transcript yet.',
        lastTimestamp: run.completedAt ?? run.createdAt,
        unreadCount: run.status === 'RUNNING' && !readThreads[run.id] ? 1 : 0,
        status: run.status,
      };
    });
  }, [runs, agentMap, readThreads]);

  const filteredThreads = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return threads;
    return threads.filter(
      (t) =>
        t.title.toLowerCase().includes(q) ||
        t.participants.join(' ').toLowerCase().includes(q) ||
        t.lastMessage.toLowerCase().includes(q),
    );
  }, [threads, search]);

  // Auto-select first thread once data arrives
  useEffect(() => {
    if (!activeThreadId && filteredThreads.length > 0) {
      setActiveThreadId(filteredThreads[0].id);
    }
  }, [filteredThreads, activeThreadId]);

  const activeThread = useMemo(
    () => threads.find((t) => t.id === activeThreadId) ?? null,
    [threads, activeThreadId],
  );

  const activeRun: Run | undefined = useMemo(
    () => runs?.find((r) => r.id === activeThreadId),
    [runs, activeThreadId],
  );
  const activeAgent: Agent | undefined = activeRun ? agentMap.get(activeRun.agentId) : undefined;

  // S4: live thread updates — lifecycle events refresh the thread list; events
  // for the active thread precisely invalidate its trajectory. run.progress is
  // excluded from list invalidation (S1 whitelist).
  const { lastMessage } = useWebSocketContext();
  useEffect(() => {
    if (!lastMessage) return;
    const t = lastMessage.type;
    if (isRunLifecycleEvent(t)) {
      queryClient.invalidateQueries({ queryKey: ['runs'] });
    }
    const runId = (lastMessage.payload?.runId as string | undefined) ?? '';
    if (runId && runId === activeThreadId
        && (t === 'run.iteration' || t === 'run.progress' || t === 'run.completed')) {
      queryClient.invalidateQueries({ queryKey: ['run-trajectory', runId] });
    }
  }, [lastMessage, activeThreadId, queryClient]);

  const {
    data: trajectory,
    isLoading: trajLoading,
    isError: trajError,
    refetch: refetchTrajectory,
  } = useQuery({
    queryKey: ['run-trajectory', activeThreadId],
    queryFn: () => getRunTrajectory(activeThreadId as string),
    enabled: !!activeThreadId,
    refetchInterval: activeRun?.status === 'RUNNING' ? 5000 : false,
  });

  const baseMessages: ChatMessage[] = useMemo(() => {
    if (!activeThread) return [];
    const agentName = activeAgent?.name ?? 'Assistant';
    const agentRole = activeAgent?.role ?? 'agent';
    return deriveMessages(activeThread.id, trajectory, agentName, agentRole);
  }, [activeThread, trajectory, activeAgent]);

  const messages: ChatMessage[] = useMemo(() => {
    if (!activeThread) return [];
    const injected = injectedByThread[activeThread.id] ?? [];
    return [...baseMessages, ...injected];
  }, [activeThread, baseMessages, injectedByThread]);

  // Auto-scroll on new messages or thread switch
  useEffect(() => {
    const node = stackRef.current;
    if (!node) return;
    node.scrollTop = node.scrollHeight;
  }, [messages.length, activeThreadId]);

  const handleSelectThread = useCallback((id: string) => {
    setActiveThreadId(id);
    setMobilePane('thread');
    setReadThreads((prev) => ({ ...prev, [id]: true }));
  }, []);

  const handleSend = useCallback(() => {
    const text = draft.trim();
    if (!text || !activeThread) return;
    if (injectingRef.current) return; // one POST per message
    injectingRef.current = true;
    const msg: ChatMessage = {
      id: `inject-${Date.now()}`,
      threadId: activeThread.id,
      sender: 'You · Human Operator',
      senderRole: 'human',
      content: text,
      timestamp: nowIso(),
      isHuman: true,
      isFresh: true,
    };
    setInjectedByThread((prev) => ({
      ...prev,
      [activeThread.id]: [...(prev[activeThread.id] ?? []), msg],
    }));
    setDraft('');

    // POST to inject endpoint using typed API call; invalidate trajectory on success.
    void (async () => {
      try {
        await injectRunMessage(activeThread.id, text);
        setInjectError(null);
        // Clear the optimistic message before refetching to avoid duplication
        setInjectedByThread((prev) => ({
          ...prev,
          [activeThread.id]: [],
        }));
        queryClient.invalidateQueries({ queryKey: ['run-trajectory', activeThread.id] });
      } catch (err) {
        // The optimistic bubble stays local; say plainly that it never reached the run.
        setInjectError(apiErrorMessage(err, 'The message was not delivered to the run.'));
      } finally {
        injectingRef.current = false;
      }
    })();
  }, [draft, activeThread, queryClient]);

  const onComposeKey = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault();
      handleSend();
    }
  };

  return (
    <div className="view-zone" data-view="chat">
      <div className="chat-page p-4 max-[900px]:p-0">
        <PageHeader
          title="Run conversations"
          description="Operator and agent transcript for each run"
        />

        {/* Wave 2 D2: the pane switch is driven by token breakpoint utilities
            instead of chat-flow.css. `group-data-[mobile-pane=…]` mirrors the
            data attribute the mobile E2E asserts, and the important marker is
            needed until E1 removes the legacy `.chat-shell` block from
            index.css (unlayered CSS outranks layered utilities). */}
        <div
          className="chat-shell group/chat grid gap-3.5 max-[900px]:h-auto! max-[900px]:min-h-0! max-[900px]:grid-cols-1! max-[900px]:gap-0!"
          data-testid="chat-shell"
          data-mobile-pane={mobilePane}
        >
          {/* ---------- Thread list ---------- */}
          <Card className="chat-list-panel min-h-0 min-w-0 gap-0 max-[900px]:h-[calc(100dvh-190px)]! max-[900px]:group-data-[mobile-pane=thread]/chat:hidden!">
            <CardHeader className="flex-row items-center justify-between gap-2 border-b border-border">
              <h2 className="text-sm font-semibold text-card-foreground">Threads</h2>
            </CardHeader>
            <div className="px-3 py-2">
              <Input
                className="ch-search"
                placeholder="Search threads…"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                aria-label="Search threads"
              />
            </div>
            <div className="chat-list-scroll min-h-0 flex-1 overflow-y-auto px-2 pb-2">
              {runsLoading && (
                <div className="px-3 py-3 text-xs text-muted-foreground">
                  Loading conversations…
                </div>
              )}
              {runsError && (
                <div className="px-3 py-3 text-xs text-destructive">
                  Failed to load threads.
                </div>
              )}
              {!runsLoading && !runsError && filteredThreads.length === 0 && (
                <div className="px-3 py-3 text-xs text-muted-foreground">
                  {threads.length === 0
                    ? 'No conversations yet. Start a run to begin a thread.'
                    : 'No threads match your search.'}
                </div>
              )}
              {filteredThreads.map((t) => (
                <ThreadRow
                  key={t.id}
                  thread={t}
                  active={t.id === activeThreadId}
                  onClick={() => handleSelectThread(t.id)}
                />
              ))}
            </div>
          </Card>

          {/* ---------- Transcript ---------- */}
          <Card className="chat-thread-panel min-h-0 min-w-0 gap-0 max-[900px]:h-[calc(100dvh-170px)]! max-[900px]:group-data-[mobile-pane=list]/chat:hidden!">
            <header className="flex flex-wrap items-center gap-2.5 border-b border-border px-3 py-2 max-[900px]:row-gap-1.5">
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="chat-back hidden max-[900px]:inline-flex"
                onClick={() => setMobilePane('list')}
              >
                ← Back to conversations
              </Button>
              <div className="min-w-0">
                <div className="h-ttl truncate text-sm font-semibold text-foreground">
                  {activeThread ? activeThread.title : 'Select a thread'}
                </div>
                <div className="h-parts mt-0.5 text-[11px] text-muted-foreground">
                  {activeThread ? activeThread.participants.join(' · ') : '—'}
                </div>
              </div>
              <Badge variant="outline" className="badge governed ml-auto">
                <span className="dot" /> audited
              </Badge>
            </header>

            {activeRun && (
              <div className="chat-run-context min-w-0 border-b border-border px-3 py-2">
                <WorkflowStepper runId={activeRun.id} />
                <DelegationTree runId={activeRun.id} agentName={activeAgent?.name} />
                <div className="chat-inline-approvals mt-1.5">
                  <ReviewQueue runId={activeRun.id} />
                </div>
              </div>
            )}

            <div className="chat-stack min-w-0 flex-1 overflow-y-auto px-4 py-3.5" ref={stackRef}>
              {!activeThread && (
                <div className="p-2 text-xs text-muted-foreground">
                  Pick a thread on the left to read the audited transcript.
                </div>
              )}
              {activeThread && trajLoading && messages.length === 0 && (
                <div className="p-2 text-xs text-muted-foreground">
                  Loading transcript…
                </div>
              )}
              {activeThread && trajError && (
                <div
                  role="alert"
                  className="error-state mb-2 flex items-center gap-2"
                >
                  Failed to load this transcript.
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="btn"
                    onClick={() => {
                      void refetchTrajectory();
                    }}
                  >
                    Retry
                  </Button>
                </div>
              )}
              {activeThread && !trajLoading && !trajError && messages.length === 0 && (
                <div className="p-2 text-xs text-muted-foreground">
                  No messages recorded yet. Inject a message below to bootstrap the thread.
                </div>
              )}
              {messages.map((m, idx) => {
                const prev = idx > 0 ? messages[idx - 1] : undefined;
                const showHandoffMarker =
                  !!prev && prev.sender !== m.sender && !m.isHuman && !m.isSystem;
                return (
                  <div key={m.id}>
                    {showHandoffMarker && (
                      <HandoffMarker from={prev!.sender} to={m.sender} />
                    )}
                    <MessageBubble msg={m} />
                  </div>
                );
              })}
            </div>

            {injectError && (
              <div role="alert" className="error-state mx-3 mb-2">
                {injectError}
              </div>
            )}
            <div className="chat-compose min-w-0">
              <div className="mb-1.5 text-[10.5px] tracking-wider text-muted-foreground uppercase">
                Inject message as Human Operator
              </div>
              <div className="row">
                <Textarea
                  value={draft}
                  onChange={(e) => setDraft(e.target.value)}
                  onKeyDown={onComposeKey}
                  placeholder={
                    activeThread
                      ? 'Inject a message into this thread (recorded as Human → all participants, ⌘/Ctrl + Enter to send)'
                      : 'Select a thread to inject a message…'
                  }
                  aria-label="Inject message"
                  disabled={!activeThread}
                />
                <Button
                  className="send"
                  onClick={handleSend}
                  disabled={!activeThread || !draft.trim()}
                  type="button"
                >
                  Send ▶
                </Button>
              </div>
            </div>
          </Card>
        </div>
      </div>
    </div>
  );
}

// ---------- Sub-components ----------
function ThreadRow({
  thread,
  active,
  onClick,
}: {
  thread: ChatThread;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <div
      className={`chat-ch cursor-pointer rounded-lg border border-transparent px-2.5 py-2 transition-colors hover:bg-muted/50 focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none${active ? ' active border-border bg-muted/60' : ''}`}
      onClick={onClick}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onClick();
        }
      }}
    >
      <div className="mb-1 flex items-center gap-1.5">
        {thread.participantRoles.slice(0, 3).map((r, i) => (
          <span
            key={i}
            className={`av ${r} inline-block size-[18px] rounded-full border border-border ${r === 'human' ? 'bg-foreground/90' : 'bg-primary'} ${i === 0 ? '' : '-ml-1.5'}`}
            aria-hidden
          />
        ))}
        <div className="ttl ml-1 truncate text-xs font-semibold text-foreground">
          {thread.title}
        </div>
      </div>
      <div className="preview line-clamp-2 text-[11px] text-muted-foreground">
        {thread.lastMessage}
      </div>
      <div className="meta mt-1 flex items-center gap-1.5 text-[10px] text-muted-foreground">
        <span>{thread.participants.length} participants</span>
        <span>·</span>
        <span>{formatTime(thread.lastTimestamp)}</span>
        <span>·</span>
        <span className="lowercase">{thread.status.toLowerCase()}</span>
        {thread.unreadCount > 0 && (
          <Badge variant="default" className="un ml-auto tabular-nums">
            {thread.unreadCount}
          </Badge>
        )}
      </div>
    </div>
  );
}

function MessageBubble({ msg }: { msg: ChatMessage }) {
  const cls = [
    'msg flex gap-2.5 rounded-lg border border-border bg-muted/20 px-3 py-2',
    msg.isHandoff ? 'handoff' : '',
    msg.isSystem ? 'system' : '',
    msg.isHuman ? 'human bg-primary/10' : '',
    msg.isFresh ? 'new' : '',
  ]
    .filter(Boolean)
    .join(' ');
  return (
    <div className={cls}>
      <div className={`av ${msg.senderRole || 'dev'} flex size-7 shrink-0 items-center justify-center rounded-full bg-muted text-[10px] font-semibold text-foreground`}>
        {avatarInitials(msg.sender)}
      </div>
      <div className="min-w-0">
        <div className="head mb-1 flex items-baseline gap-2">
          <span className="who text-xs font-semibold text-foreground">{msg.sender}</span>
          <span className="when text-[10.5px] text-muted-foreground">
            {formatTime(msg.timestamp)}
          </span>
        </div>
        <div className="text break-words whitespace-pre-wrap text-foreground">
          {msg.content || <em>(empty)</em>}
        </div>
      </div>
    </div>
  );
}

function HandoffMarker({ from, to }: { from: string; to: string }) {
  return (
    <div
      className="my-2.5 flex items-center gap-2 text-[10px] tracking-wider text-muted-foreground uppercase"
      aria-label={`Handoff from ${from} to ${to}`}
    >
      <span className="h-px flex-1 bg-border" />
      <span className="text-warning">↪ handoff · {from} → {to}</span>
      <span className="h-px flex-1 bg-border" />
    </div>
  );
}
