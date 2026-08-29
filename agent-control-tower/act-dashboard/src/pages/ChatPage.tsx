import { useState, useMemo, useEffect, useRef, useCallback } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { listRuns, getRunTrajectory, injectRunMessage } from '../api/runs';
import { listAgents } from '../api/agents';
import WorkflowStepper from '../components/WorkflowStepper';
import DelegationTree from '../components/DelegationTree';
import ReviewQueue from '../components/ReviewQueue';
import { useWebSocketContext } from '../components/Layout';
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
  const [search, setSearch] = useState('');
  const [draft, setDraft] = useState('');
  const [injectedByThread, setInjectedByThread] = useState<Record<string, ChatMessage[]>>({});
  const [readThreads, setReadThreads] = useState<Record<string, boolean>>({});
  const stackRef = useRef<HTMLDivElement | null>(null);

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

  const { data: trajectory, isLoading: trajLoading } = useQuery({
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
    setReadThreads((prev) => ({ ...prev, [id]: true }));
  }, []);

  const handleSend = useCallback(() => {
    const text = draft.trim();
    if (!text || !activeThread) return;
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
        // Clear the optimistic message before refetching to avoid duplication
        setInjectedByThread((prev) => ({
          ...prev,
          [activeThread.id]: [],
        }));
        queryClient.invalidateQueries({ queryKey: ['run-trajectory', activeThread.id] });
      } catch {
        /* offline-friendly: keep local injection only */
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
      <div style={{ padding: 16 }}>
        <div className="view-header">
          <div>
            <h1>💬 Agent ↔ Agent Conversations</h1>
            <div className="sub">
              Inter-agent handoffs, clarifications and reviews — fully auditable. Humans can inject
              messages into any thread.
            </div>
          </div>
        </div>

        <div className="chat-shell">
          {/* ---------- Thread list ---------- */}
          <div className="chat-list-panel">
            <header>Threads</header>
            <input
              className="ch-search"
              placeholder="Search threads…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              aria-label="Search threads"
            />
            <div className="chat-list-scroll">
              {runsLoading && (
                <div style={{ padding: 14, color: 'var(--text-dim)', fontSize: 12 }}>
                  Loading conversations…
                </div>
              )}
              {runsError && (
                <div style={{ padding: 14, color: 'var(--red, #ff97a3)', fontSize: 12 }}>
                  Failed to load threads.
                </div>
              )}
              {!runsLoading && !runsError && filteredThreads.length === 0 && (
                <div style={{ padding: 14, color: 'var(--text-mute)', fontSize: 12 }}>
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
          </div>

          {/* ---------- Message panel ---------- */}
          <div className="chat-thread-panel">
            <header>
              <div>
                <div className="h-ttl">
                  {activeThread ? activeThread.title : 'Select a thread'}
                </div>
                <div className="h-parts">
                  {activeThread ? activeThread.participants.join(' ↔ ') : '—'}
                </div>
              </div>
              <span className="badge governed" style={{ marginLeft: 'auto' }}>
                <span className="dot" /> audited
              </span>
            </header>

            {activeRun && (
              <div
                className="chat-run-context"
                style={{ padding: '8px 12px', borderBottom: '1px solid var(--line-2, #26304d)' }}
              >
                <WorkflowStepper runId={activeRun.id} />
                <DelegationTree runId={activeRun.id} agentName={activeAgent?.name} />
                <div className="chat-inline-approvals" style={{ marginTop: 6 }}>
                  <ReviewQueue runId={activeRun.id} />
                </div>
              </div>
            )}

            <div className="chat-stack" ref={stackRef}>
              {!activeThread && (
                <div style={{ color: 'var(--text-mute)', fontSize: 12, padding: 8 }}>
                  Pick a thread on the left to read the audited transcript.
                </div>
              )}
              {activeThread && trajLoading && messages.length === 0 && (
                <div style={{ color: 'var(--text-dim)', fontSize: 12, padding: 8 }}>
                  Loading transcript…
                </div>
              )}
              {activeThread && !trajLoading && messages.length === 0 && (
                <div style={{ color: 'var(--text-mute)', fontSize: 12, padding: 8 }}>
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

            <div className="chat-compose">
              <div
                style={{
                  fontSize: 10.5,
                  textTransform: 'uppercase',
                  letterSpacing: 1,
                  color: 'var(--text-mute)',
                  marginBottom: 6,
                }}
              >
                Inject message as Human Operator
              </div>
              <div className="row">
                <textarea
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
                <button
                  className="send"
                  onClick={handleSend}
                  disabled={!activeThread || !draft.trim()}
                  type="button"
                >
                  Send ▶
                </button>
              </div>
            </div>
          </div>
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
      className={`chat-ch${active ? ' active' : ''}`}
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
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
        {thread.participantRoles.slice(0, 3).map((r, i) => (
          <span
            key={i}
            className={`av ${r}`}
            style={{
              width: 18,
              height: 18,
              borderRadius: '50%',
              background:
                r === 'human'
                  ? 'linear-gradient(135deg, #ffffff, #c0caf0)'
                  : 'linear-gradient(135deg, var(--brand), var(--accent))',
              display: 'inline-block',
              border: '1px solid var(--line-2)',
              marginLeft: i === 0 ? 0 : -6,
            }}
            aria-hidden
          />
        ))}
        <div className="ttl" style={{ marginLeft: 4 }}>
          {thread.title}
        </div>
      </div>
      <div className="preview">{thread.lastMessage}</div>
      <div className="meta">
        <span>{thread.participants.length} agents</span>
        <span>·</span>
        <span>{formatTime(thread.lastTimestamp)}</span>
        <span>·</span>
        <span style={{ textTransform: 'lowercase' }}>{thread.status.toLowerCase()}</span>
        {thread.unreadCount > 0 && <span className="un">{thread.unreadCount}</span>}
      </div>
    </div>
  );
}

function MessageBubble({ msg }: { msg: ChatMessage }) {
  const cls = [
    'msg',
    msg.isHandoff ? 'handoff' : '',
    msg.isSystem ? 'system' : '',
    msg.isHuman ? 'human' : '',
    msg.isFresh ? 'new' : '',
  ]
    .filter(Boolean)
    .join(' ');
  return (
    <div className={cls}>
      <div className={`av ${msg.senderRole || 'dev'}`}>{avatarInitials(msg.sender)}</div>
      <div>
        <div className="head">
          <span className="who">{msg.sender}</span>
          <span className="when">{formatTime(msg.timestamp)}</span>
        </div>
        <div className="text">{msg.content || <em>(empty)</em>}</div>
      </div>
    </div>
  );
}

function HandoffMarker({ from, to }: { from: string; to: string }) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        margin: '10px 0 8px',
        fontSize: 10,
        textTransform: 'uppercase',
        letterSpacing: 1.2,
        color: 'var(--text-mute)',
      }}
      aria-label={`Handoff from ${from} to ${to}`}
    >
      <span
        style={{
          flex: 1,
          height: 1,
          background:
            'linear-gradient(90deg, transparent, var(--line-2), transparent)',
        }}
      />
      <span style={{ color: '#ffd884' }}>↪ handoff · {from} → {to}</span>
      <span
        style={{
          flex: 1,
          height: 1,
          background:
            'linear-gradient(90deg, transparent, var(--line-2), transparent)',
        }}
      />
    </div>
  );
}
