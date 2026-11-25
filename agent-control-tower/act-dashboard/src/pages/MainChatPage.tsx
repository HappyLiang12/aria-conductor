import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useAui, useAuiState, type AssistantClient, type MessageState, type ThreadMessageLike } from '@assistant-ui/react';
import { streamMessage } from '../api/aria';
import {
  deleteConversation,
  getConversationTimeline,
  getLatestConversation,
  listConversations,
  type ConversationListItem,
  type TimelineEntry,
} from '../api/ariaConversations';
import { cancelRun } from '../api/runs';
import { useSlashCommands } from '../hooks/useSlashCommands';
import SlashCommandMenu from '../components/SlashCommandMenu';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { StateNotice } from '../components/StateNotice';
import { ConversationSidebar } from '../components/main-chat/ConversationSidebar';
import { ProgressTimeline } from '../components/main-chat/ProgressTimeline';
import { useLiveProgress } from '../components/main-chat/useLiveProgress';
import { createAriaAdapter, type AriaAdapter } from '../chat/ariaAdapter';
import { AriaRuntimeProvider } from '../chat/AriaRuntimeProvider';
import { AriaThread } from '../chat/AriaThread';

const CLIENT_TIMEOUT_MS = 600_000; // Matches the backend SSE timeout (aria.sse.timeout.ms).

interface ChatHistoryRow {
  role: string;
  content: string;
}

interface Notice {
  title: string;
  message: string;
}

function entryId(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `entry-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * The prior-turn context of one request. The runtime appends the operator's
 * message before the adapter opens the stream and the turn travels as
 * `message`, so the trailing user row is never part of `history`.
 *
 * A failed turn's error text is an artefact of the error surface, not something
 * Aria said: replaying it as assistant context would make the next turn answer
 * its own failure. The pre-rebuild page dropped every errored timeline row; the
 * runtime marks both those seeds and a live adapter failure `incomplete/error`,
 * so that status is the same filter here.
 */
function historyRows(messages: readonly MessageState[]): ChatHistoryRow[] {
  const rows = messages.filter((message) => {
    if (message.role !== 'assistant' || message.status?.type !== 'incomplete') return true;
    return message.status.reason !== 'error';
  }).map((message) => ({
    role: message.role,
    content: message.content
      .filter((part) => part.type === 'text')
      .map((part) => (part.type === 'text' ? part.text : ''))
      .join('\n'),
  }));
  const last = rows[rows.length - 1];
  return last && last.role === 'user' ? rows.slice(0, -1) : rows;
}

/**
 * The persisted conversation as assistant-ui seed messages. A recorded error
 * stays an errored message, so the thread renders it through its own error
 * surface instead of pretending the turn succeeded.
 */
function toSeedMessages(entries: TimelineEntry[]): ThreadMessageLike[] {
  return entries.map((entry, index) => ({
    id: `${entry.runId || 'timeline'}-${index}`,
    role: entry.role === 'user' ? 'user' : 'assistant',
    content: entry.content ?? '',
    createdAt: new Date(entry.timestamp),
    // assistant-ui only carries a status on the assistant side.
    ...(entry.role === 'user'
      ? {}
      : {
        status: entry.error
          ? { type: 'incomplete', reason: 'error' } as const
          : { type: 'complete', reason: 'stop' } as const,
      }),
  }));
}

/**
 * Bridges the runtime the provider owns to the page: the page's composer sends
 * through `auiRef`, and the thread's run state / history flow back out. It has
 * to live inside the provider, so it is rendered as a child, not a prop.
 */
function RuntimeBridge({ auiRef, onThread, onHistory }: {
  auiRef: { current: AssistantClient | null };
  onThread: (running: boolean, messageCount: number) => void;
  onHistory: (rows: ChatHistoryRow[]) => void;
}) {
  const aui = useAui();
  const running = useAuiState((state) => state.thread.isRunning);
  const messages = useAuiState((state) => state.thread.messages);

  useEffect(() => {
    auiRef.current = aui;
    return () => { auiRef.current = null; };
  }, [aui, auiRef]);

  useEffect(() => {
    onThread(running, messages.length);
  }, [messages.length, onThread, running]);

  useEffect(() => {
    onHistory(historyRows(messages));
  }, [messages, onHistory]);

  return null;
}

export default function MainChatPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [conversations, setConversations] = useState<ConversationListItem[]>([]);
  const [listLoading, setListLoading] = useState(true);
  const [listError, setListError] = useState<string | null>(null);
  const [conversationId, setConversationId] = useState<string | null>(null);
  // A new chat needs a conversation id before the adapter opens its request; the
  // backend takes the id it is given, so this draft id becomes the real one.
  const [draftId, setDraftId] = useState(entryId);
  const [entries, setEntries] = useState<TimelineEntry[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState<string | null>(null);
  // Bumped whenever persisted history changes, so the provider remounts and the
  // runtime re-seeds from it (initialMessages is a mount-time value).
  const [historyEpoch, setHistoryEpoch] = useState(0);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [messageCount, setMessageCount] = useState(0);
  const [activeRunId, setActiveRunId] = useState<string | null>(null);
  const [finalReceived, setFinalReceived] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(
    () => typeof window !== 'undefined' && window.innerWidth <= 900,
  );
  const [pendingDelete, setPendingDelete] = useState<string | null>(null);

  const { progress, replay } = useLiveProgress(activeRunId);

  const auiRef = useRef<AssistantClient | null>(null);
  const adapterRef = useRef<AriaAdapter | null>(null);
  // Set by Stop, cleared when the next turn opens. `streamMessage` reads it on
  // the close tail so a Stop racing the server's own close is not reported as
  // "Connection closed unexpectedly".
  const cancelledRef = useRef(false);
  const historyRef = useRef<ChatHistoryRow[]>([]);
  const conversationIdRef = useRef<string | null>(null);
  const activeRunIdRef = useRef<string | null>(null);
  const pendingSkillIdRef = useRef<string | null>(null);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const loadTokenRef = useRef(0);
  const lastSearchRef = useRef<string | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const adoptRef = useRef<(id: string) => void>(() => {});
  const wasRunningRef = useRef(false);

  const slash = useSlashCommands(true);
  const pendingSkillId = slash.pendingSkill?.id ?? null;

  const refreshConversations = useCallback(async () => {
    try {
      const rows = await listConversations();
      setConversations(rows);
      setListError(null);
    } catch (err) {
      setListError(err instanceof Error ? err.message : String(err));
    } finally {
      setListLoading(false);
    }
  }, []);

  /** Adopt the id the first turn was sent under, once, without reloading history. */
  const adoptConversation = useCallback((id: string) => {
    if (conversationIdRef.current) return;
    conversationIdRef.current = id;
    setConversationId(id);
    lastSearchRef.current = new URLSearchParams({ conversationId: id }).toString();
    setSearchParams({ conversationId: id }, { replace: true });
  }, [setSearchParams]);

  useEffect(() => {
    adoptRef.current = adoptConversation;
  }, [adoptConversation]);

  const openConversation = useCallback(async (id: string) => {
    const token = ++loadTokenRef.current;
    conversationIdRef.current = id;
    setConversationId(id);
    lastSearchRef.current = new URLSearchParams({ conversationId: id }).toString();
    setSearchParams({ conversationId: id }, { replace: true });
    setHistoryLoading(true);
    setHistoryError(null);
    setNotice(null);
    setEntries([]);
    try {
      const timeline = await getConversationTimeline(id);
      if (token !== loadTokenRef.current) return;
      setEntries(timeline);
      setHistoryEpoch((value) => value + 1);
    } catch (err) {
      if (token !== loadTokenRef.current) return;
      setHistoryError(err instanceof Error ? err.message : String(err));
      setEntries([]);
      setHistoryEpoch((value) => value + 1);
    } finally {
      if (token === loadTokenRef.current) setHistoryLoading(false);
    }
  }, [setSearchParams]);

  const startNewChat = useCallback(() => {
    if (busy) return;
    conversationIdRef.current = null;
    activeRunIdRef.current = null;
    setConversationId(null);
    setDraftId(entryId());
    lastSearchRef.current = '';
    setSearchParams({}, { replace: true });
    setEntries([]);
    setHistoryEpoch((value) => value + 1);
    setHistoryError(null);
    setNotice(null);
    setInput('');
    setActiveRunId(null);
    setMessageCount(0);
    setFinalReceived(false);
    historyRef.current = [];
  }, [busy, setSearchParams]);

  useEffect(() => {
    let cancelled = false;
    const requested = searchParams.get('conversationId');
    lastSearchRef.current = searchParams.toString();
    (async () => {
      await refreshConversations();
      if (cancelled) return;
      if (requested) {
        await openConversation(requested);
        return;
      }
      try {
        const latest = await getLatestConversation();
        if (!cancelled && latest) {
          await openConversation(latest.conversationId);
        }
      } catch (err) {
        if (!cancelled) setHistoryError(err instanceof Error ? err.message : String(err));
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // The floating panel's expand control navigates to /aria?conversationId=...
  // while this page may already be mounted. Only a real URL change is adopted:
  // this effect also runs when conversationId changes, and acting on the old
  // params there would reopen the previous conversation.
  useEffect(() => {
    const current = searchParams.toString();
    if (current === lastSearchRef.current) return;
    lastSearchRef.current = current;
    const requested = searchParams.get('conversationId');
    if (!requested || requested === conversationIdRef.current || busy) return;
    void openConversation(requested);
  }, [busy, openConversation, searchParams]);

  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 200)}px`;
  }, [input]);

  useEffect(() => () => {
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
  }, []);

  /**
   * One turn's SSE request, wrapped so the page keeps what the adapter does not
   * expose: the run id (server cancel + live progress) and the skill the
   * operator chose from the slash menu.
   */
  const trackedStream = useCallback<typeof streamMessage>(
    (targetId, text, history, callbacks, signal, options) => {
      // A new turn is not a stopped one: the close-tail check must start clear.
      cancelledRef.current = false;
      const skillId = options?.skillId ?? pendingSkillIdRef.current ?? undefined;
      // Consumed by this turn only; a refused send must not leak the skill on.
      pendingSkillIdRef.current = null;
      return streamMessage(
        targetId,
        text,
        history,
        {
          ...callbacks,
          onThinking: (runId) => {
            if (runId) {
              activeRunIdRef.current = runId;
              setActiveRunId(runId);
            }
            callbacks.onThinking?.(runId);
          },
          onMessage: (content) => {
            setFinalReceived(true);
            callbacks.onMessage?.(content);
          },
          onDone: (data) => {
            if (data?.conversationId) adoptRef.current(data.conversationId);
            callbacks.onDone?.(data);
          },
        },
        signal,
        { ...options, skillId, isCancelled: () => cancelledRef.current },
      );
    },
    [],
  );

  const adapterId = conversationId ?? draftId;
  const adapter = useMemo(
    () => createAriaAdapter({
      conversationId: adapterId,
      getHistory: () => historyRef.current,
      stream: trackedStream,
    }),
    [adapterId, trackedStream],
  );

  useEffect(() => {
    adapterRef.current = adapter;
  }, [adapter]);

  const handleHistory = useCallback((rows: ChatHistoryRow[]) => {
    historyRef.current = rows;
  }, []);

  const handleThread = useCallback((running: boolean, count: number) => {
    setBusy(running);
    setMessageCount(count);
    if (running) {
      // Every message change re-reports the same run; the client timeout is one
      // budget for the whole turn, not one per frame.
      if (wasRunningRef.current) return;
      wasRunningRef.current = true;
      setFinalReceived(false);
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
      const turn = adapterRef.current;
      timeoutRef.current = setTimeout(() => {
        turn?.cancel();
        setNotice({
          title: 'No reply from Aria',
          message: "No reply after 10 minutes. The run may still be alive in the background — check the Runs page.",
        });
      }, CLIENT_TIMEOUT_MS);
      return;
    }
    wasRunningRef.current = false;
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }
    const runId = activeRunIdRef.current;
    activeRunIdRef.current = null;
    setActiveRunId(null);
    if (runId) void replay(runId);
    void refreshConversations();
  }, [refreshConversations, replay]);

  const send = useCallback((rawText: string, skillIdOverride?: string) => {
    const text = rawText.trim();
    if (!text || busy) return;
    const composer = auiRef.current?.thread.composer();
    if (!composer) return;

    pendingSkillIdRef.current = skillIdOverride ?? pendingSkillId;
    composer.setText(text);
    composer.send();

    setInput('');
    setNotice(null);
    setFinalReceived(false);
    slash.clearSkill();
    if (!conversationIdRef.current) adoptRef.current(draftId);
  }, [busy, draftId, pendingSkillId, slash]);

  const stop = useCallback(() => {
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }
    // Read by the stream's close tail: a Stop must not surface as a dropped
    // connection even when the server has already closed the response.
    cancelledRef.current = true;
    // The adapter owns the one SSE request this turn opened.
    adapterRef.current?.cancel();
    const runId = activeRunIdRef.current;
    if (runId) {
      void cancelRun(runId)
        .catch((err) => {
          setNotice({
            title: 'Stop not confirmed',
            message: `The server-side cancel could not be confirmed: ${err instanceof Error ? err.message : String(err)}`,
          });
        })
        .finally(() => {
          // The stopped turn already has a server-side conversation; refresh the
          // switcher instead of waiting for a page reload.
          void refreshConversations();
        });
    } else {
      void refreshConversations();
    }
  }, [refreshConversations]);

  const handleDelete = useCallback((id: string) => {
    if (busy) return;
    setPendingDelete(id);
  }, [busy]);

  const confirmDelete = useCallback(async () => {
    const id = pendingDelete;
    setPendingDelete(null);
    if (!id) return;
    try {
      await deleteConversation(id);
      if (id === conversationIdRef.current) startNewChat();
      await refreshConversations();
    } catch (err) {
      setListError(err instanceof Error ? err.message : String(err));
    }
  }, [pendingDelete, refreshConversations, startNewChat]);

  const handleKeyDown = useCallback((event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (slash.open) {
      if (event.key === 'ArrowDown') { event.preventDefault(); slash.move(1); return; }
      if (event.key === 'ArrowUp') { event.preventDefault(); slash.move(-1); return; }
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        slash.selectCurrent();
        return;
      }
      if (event.key === 'Escape') { event.preventDefault(); slash.dismiss(); return; }
    }
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      send(input);
    }
  }, [input, send, slash]);

  const activeTitle = useMemo(() => {
    const active = conversations.find((row) => row.conversationId === conversationId);
    return active?.title ?? (conversationId ? 'Conversation' : 'New chat');
  }, [conversationId, conversations]);

  const initialMessages = useMemo(() => toSeedMessages(entries), [entries]);
  const threadKey = `${adapterId}#${historyEpoch}`;

  const isLive = Boolean(busy && activeRunId && progress && progress.runId === activeRunId);
  const liveItems = isLive
    ? progress!.items.filter((item) => !(finalReceived && item.kind === 'answer'))
    : [];
  const indicator = isLive && liveItems.length > 0
    ? <ProgressTimeline items={liveItems} />
    : undefined;

  const showEmpty = !historyLoading && entries.length === 0 && messageCount === 0;

  return (
    <div
      className={`mc-shell grid h-[calc(100vh-var(--top-stack-h,67px))] min-h-0 max-[900px]:grid-cols-1 ${sidebarCollapsed ? 'sidebar-collapsed grid-cols-1' : 'grid-cols-1 min-[901px]:grid-cols-[260px_minmax(0,1fr)]'}`}
    >
      {!sidebarCollapsed && (
        <ConversationSidebar
          items={conversations}
          activeId={conversationId}
          loading={listLoading}
          error={listError}
          disabled={busy}
          onNew={startNewChat}
          onSelect={openConversation}
          onDelete={handleDelete}
        />
      )}

      {/* pb-24 keeps the composer clear of the shell's floating Aria button
          (56px + 24px gutter, fixed bottom-right), which otherwise covers the
          send control on narrow viewports. */}
      <section
        className="mc-main flex min-h-0 min-w-0 flex-col pb-24 [&_.mc-stack]:flex [&_.mc-stack]:min-h-0 [&_.mc-stack]:flex-1 [&_.mc-stack]:flex-col [&_.mc-stack]:gap-3.5 [&_.mc-stack]:overflow-y-auto [&_.mc-stack]:px-4 [&_.mc-stack]:pt-4 [&_.mc-stack]:pb-2 [&_.mc-stack_.ai-msg]:mx-auto [&_.mc-stack_.ai-msg]:w-full [&_.mc-stack_.ai-msg]:max-w-[860px]"
        aria-label="Aria chat"
      >
        <header className="mc-header flex items-center gap-2.5 border-b border-border px-4 py-2.5">
          <button
            type="button"
            className="mc-sidebar-toggle flex size-7 items-center justify-center rounded-lg border border-border bg-transparent text-muted-foreground transition-colors hover:text-foreground"
            onClick={() => setSidebarCollapsed((value) => !value)}
            aria-label={sidebarCollapsed ? 'Show conversations' : 'Hide conversations'}
          >
            ☰
          </button>
          <div className="mc-header-text min-w-0">
            <div className="mc-header-title truncate text-sm font-semibold text-foreground">{activeTitle}</div>
            {busy && (
              <div className="mc-header-sub mt-0.5 flex items-center gap-1.5 text-[11px] text-muted-foreground">
                <span className="ai-pulse-dot inline-block size-1.5 rounded-full bg-primary" aria-hidden="true" /> working
              </div>
            )}
          </div>
        </header>

        <AriaRuntimeProvider
          key={threadKey}
          adapter={adapter}
          initialMessages={initialMessages}
        >
          <RuntimeBridge auiRef={auiRef} onThread={handleThread} onHistory={handleHistory} />

          {historyError && <div className="mc-history-error px-4 py-2 text-xs text-destructive" role="alert">{historyError}</div>}
          {notice && <StateNotice kind="error" title={notice.title} message={notice.message} />}

          {historyLoading ? (
            <div className="mc-stack" aria-live="polite" aria-relevant="additions">
              <div className="mc-sidebar-empty px-1 py-1.5 text-xs text-muted-foreground">Loading conversation…</div>
            </div>
          ) : showEmpty ? (
            <div className="mc-stack" aria-live="polite" aria-relevant="additions">
              <div className="mc-empty mx-auto max-w-[520px] p-6">
                <div className="ai-empty-eyebrow font-mono text-[10px] tracking-[0.28em] text-primary uppercase">— operator copilot</div>
                <div className="ai-empty-title text-lg leading-tight font-medium text-foreground">Ask Aria anything.</div>
                <div className="ai-empty-sub text-sm leading-relaxed text-muted-foreground">
                  I can brief you on agents, runs, approvals and the knowledge base — and I can act on what you ask.
                </div>
              </div>
            </div>
          ) : (
            <AriaThread indicator={indicator} />
          )}

          <div className="mc-compose-wrap mx-auto w-full max-w-[860px]">
            <div className="ai-compose mc-compose relative flex flex-col gap-2 rounded-t-xl border border-border bg-card p-3">
              {slash.open && (
                <SlashCommandMenu
                  items={slash.items}
                  activeIndex={slash.activeIndex}
                  onHover={(index) => slash.move(index - slash.activeIndex)}
                  onSelect={(item) => {
                    slash.choose(item);
                    pendingSkillIdRef.current = item.id;
                    setInput('');
                  }}
                />
              )}
              {slash.pendingSkill && !slash.open && (
                <div className="ai-skill-chip inline-flex w-fit items-center gap-1.5 rounded-full border border-border bg-muted px-2 py-1 text-xs text-muted-foreground">
                  <span>/{slash.pendingSkill.name}</span>
                  <button type="button" onClick={slash.clearSkill} aria-label="Remove skill">✕</button>
                </div>
              )}
              <textarea
                ref={textareaRef}
                className="ai-compose-input w-full resize-none rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground"
                value={input}
                onChange={(event) => {
                  setInput(event.target.value);
                  slash.onInputChange(event.target.value, event.target.selectionStart ?? event.target.value.length);
                }}
                onKeyDown={handleKeyDown}
                placeholder="Ask Aria anything…  (type / for skills)"
                rows={2}
                disabled={busy}
                aria-controls={slash.open ? 'slash-command-listbox' : undefined}
              />
              {busy ? (
                <button
                  type="button"
                  className="ai-cancel-btn ml-auto inline-flex h-8 items-center gap-1.5 rounded-lg border border-destructive/50 bg-destructive/10 px-3 text-xs font-semibold tracking-wide text-destructive uppercase transition-colors hover:bg-destructive/20"
                  onClick={stop}
                  aria-label="Stop the turn"
                >
                  <span aria-hidden="true">■</span>
                  <span>Stop</span>
                </button>
              ) : (
                <button
                  type="button"
                  className="ai-action-btn ml-auto inline-flex h-8 items-center gap-1.5 rounded-lg bg-primary px-3 text-xs font-semibold text-primary-foreground transition-colors hover:bg-primary/80 disabled:opacity-50"
                  onClick={() => send(input)}
                  disabled={!input.trim()}
                  aria-label="Send message"
                >
                  <span className="ai-action-glyph" aria-hidden="true">↗</span>
                  <span className="ai-action-label">Send</span>
                </button>
              )}
            </div>
            <div className="ai-hint mc-hint py-1 text-center text-[11px] text-muted-foreground">
              Aria streams live · acts on the live system · review approvals before destructive moves.
            </div>
          </div>
        </AriaRuntimeProvider>
      </section>

      <ConfirmDialog
        open={pendingDelete !== null}
        danger
        title="Delete conversation"
        message="This cancels any live runs and destroys this conversation's history. Runs already recorded stay on the Runs page."
        onConfirm={confirmDelete}
        onCancel={() => setPendingDelete(null)}
      />
    </div>
  );
}
