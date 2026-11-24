import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { streamMessage, type StreamError } from '../api/aria';
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
import { ConversationSidebar } from '../components/main-chat/ConversationSidebar';
import { ProgressTimeline, StepsDisclosure } from '../components/main-chat/ProgressTimeline';
import { useLiveProgress } from '../components/main-chat/useLiveProgress';
import { renderMarkdown } from '../utils/markdown';
import { formatTimestamp } from '../utils/formatTime';
import '../styles/main-chat.css';

const CLIENT_TIMEOUT_MS = 600_000; // Matches the backend SSE timeout (aria.sse.timeout.ms).

interface ChatEntry {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: string;
  runId?: string;
  error?: boolean;
  retryPrompt?: string;
}

function entryId(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `entry-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function errorCopy(error: StreamError): string {
  if (typeof error === 'string') return error;
  const run = error.runId ? ` (Run ${error.runId.slice(0, 8)})` : '';
  if (error.turnFailed) {
    return `This turn failed${run}: ${error.reason || error.message || 'the backend reported no reason'}`;
  }
  return error.message || 'The stream ended with an error.';
}

function fromTimeline(timeline: TimelineEntry[]): ChatEntry[] {
  return timeline.map((entry, index) => ({
    id: `${entry.runId || 'timeline'}-${index}`,
    role: entry.role === 'user' ? 'user' : 'assistant',
    content: entry.content ?? '',
    timestamp: entry.timestamp,
    runId: entry.runId,
    error: entry.error,
    retryPrompt: entry.retryPrompt,
  }));
}

export default function MainChatPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [conversations, setConversations] = useState<ConversationListItem[]>([]);
  const [listLoading, setListLoading] = useState(true);
  const [listError, setListError] = useState<string | null>(null);
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [entries, setEntries] = useState<ChatEntry[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [activeRunId, setActiveRunId] = useState<string | null>(null);
  const [finalReceived, setFinalReceived] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(
    () => typeof window !== 'undefined' && window.innerWidth <= 900,
  );
  const [pendingDelete, setPendingDelete] = useState<string | null>(null);

  const { progress, replay } = useLiveProgress(activeRunId);

  const abortRef = useRef<AbortController | null>(null);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const cancelledRef = useRef(false);
  const activeRunIdRef = useRef<string | null>(null);
  const loadTokenRef = useRef(0);
  const lastSearchRef = useRef<string | null>(null);
  const stackRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const slash = useSlashCommands(true);
  const pendingSkillId = slash.pendingSkill?.id;

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

  const openConversation = useCallback(async (id: string) => {
    const token = ++loadTokenRef.current;
    setConversationId(id);
    setSearchParams({ conversationId: id }, { replace: true });
    setHistoryLoading(true);
    setHistoryError(null);
    try {
      const timeline = await getConversationTimeline(id);
      if (token !== loadTokenRef.current) return;
      setEntries(fromTimeline(timeline));
    } catch (err) {
      if (token !== loadTokenRef.current) return;
      setHistoryError(err instanceof Error ? err.message : String(err));
      setEntries([]);
    } finally {
      if (token === loadTokenRef.current) setHistoryLoading(false);
    }
  }, [setSearchParams]);

  const startNewChat = useCallback(() => {
    if (busy) return;
    setConversationId(null);
    setSearchParams({}, { replace: true });
    setEntries([]);
    setHistoryError(null);
    setInput('');
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
    if (!requested || requested === conversationId || busy) return;
    void openConversation(requested);
  }, [busy, conversationId, openConversation, searchParams]);

  useEffect(() => {
    const stack = stackRef.current;
    if (!stack) return;
    stack.scrollTop = stack.scrollHeight;
  }, [entries, progress, busy]);

  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 200)}px`;
  }, [input]);

  useEffect(() => () => {
    abortRef.current?.abort();
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
  }, []);

  const send = useCallback((rawText: string, skillIdOverride?: string) => {
    const text = rawText.trim();
    if (!text || busy) return;

    const skillId = skillIdOverride ?? pendingSkillId;
    const history = entries
      .filter((entry) => !entry.error)
      .map((entry) => ({ role: entry.role, content: entry.content }));
    const targetConversationId = conversationId || entryId();
    if (!conversationId) {
      setConversationId(targetConversationId);
      setSearchParams({ conversationId: targetConversationId }, { replace: true });
    }

    abortRef.current?.abort();
    cancelledRef.current = false;
    activeRunIdRef.current = null;
    const controller = new AbortController();
    abortRef.current = controller;

    setEntries((prev) => [
      ...prev,
      { id: entryId(), role: 'user', content: text, timestamp: new Date().toISOString() },
    ]);
    setInput('');
    setBusy(true);
    setActiveRunId(null);
    setFinalReceived(false);
    slash.clearSkill();

    void streamMessage(
      targetConversationId,
      text,
      history,
      {
        onThinking: (runId) => {
          if (!runId) return;
          activeRunIdRef.current = runId;
          setActiveRunId(runId);
        },
        onMessage: (content) => {
          setFinalReceived(true);
          const runId = activeRunIdRef.current ?? undefined;
          setEntries((prev) => [
            ...prev,
            {
              id: entryId(),
              role: 'assistant',
              content: content || 'No response received.',
              timestamp: new Date().toISOString(),
              runId,
            },
          ]);
        },
        onDone: (data) => {
          if (timeoutRef.current) clearTimeout(timeoutRef.current);
          if (data?.conversationId) {
            setConversationId((prev) => prev || data.conversationId);
          }
          if (data?.runId) {
            void replay(data.runId);
          }
          setBusy(false);
          setActiveRunId(null);
          void refreshConversations();
        },
        onError: (error) => {
          if (cancelledRef.current) return;
          if (timeoutRef.current) clearTimeout(timeoutRef.current);
          setEntries((prev) => [
            ...prev,
            {
              id: entryId(),
              role: 'assistant',
              content: errorCopy(error),
              timestamp: new Date().toISOString(),
              runId: typeof error === 'string' ? undefined : error.runId,
              error: true,
              retryPrompt: text,
            },
          ]);
          setBusy(false);
          setActiveRunId(null);
        },
      },
      controller.signal,
      { isCancelled: () => cancelledRef.current, skillId },
    );

    timeoutRef.current = setTimeout(() => {
      controller.abort();
      setBusy(false);
      setActiveRunId(null);
      setEntries((prev) => [
        ...prev,
        {
          id: entryId(),
          role: 'assistant',
          content: 'No reply after 10 minutes. The run may still be alive in the background — check the Runs page.',
          timestamp: new Date().toISOString(),
          error: true,
          retryPrompt: text,
        },
      ]);
    }, CLIENT_TIMEOUT_MS);
  }, [busy, conversationId, entries, pendingSkillId, refreshConversations, replay, setSearchParams, slash]);

  const stop = useCallback(() => {
    cancelledRef.current = true;
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
    abortRef.current?.abort();
    const runId = activeRunIdRef.current;
    setBusy(false);
    setActiveRunId(null);
    if (runId) {
      void cancelRun(runId)
        .catch((err) => {
          setEntries((prev) => [
            ...prev,
            {
              id: entryId(),
              role: 'assistant',
              content: `The server-side cancel could not be confirmed: ${err instanceof Error ? err.message : String(err)}`,
              timestamp: new Date().toISOString(),
              error: true,
            },
          ]);
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
      if (id === conversationId) startNewChat();
      await refreshConversations();
    } catch (err) {
      setListError(err instanceof Error ? err.message : String(err));
    }
  }, [conversationId, pendingDelete, refreshConversations, startNewChat]);

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

  const isLive = Boolean(busy && activeRunId && progress && progress.runId === activeRunId);
  const liveItems = isLive
    ? progress!.items.filter((item) => !(finalReceived && item.kind === 'answer'))
    : [];

  return (
    <div className={`mc-shell${sidebarCollapsed ? ' sidebar-collapsed' : ''}`}>
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

      <section className="mc-main" aria-label="Aria chat">
        <header className="mc-header">
          <button
            type="button"
            className="mc-sidebar-toggle"
            onClick={() => setSidebarCollapsed((value) => !value)}
            aria-label={sidebarCollapsed ? 'Show conversations' : 'Hide conversations'}
          >
            ☰
          </button>
          <div className="mc-header-text">
            <div className="mc-header-title">{activeTitle}</div>
            {busy && <div className="mc-header-sub"><span className="ai-pulse-dot" aria-hidden="true" /> working</div>}
          </div>
        </header>

        <div className="mc-stack" ref={stackRef} aria-live="polite" aria-relevant="additions">
          {historyError && <div className="mc-history-error" role="alert">{historyError}</div>}
          {historyLoading ? (
            <div className="mc-sidebar-empty">Loading conversation…</div>
          ) : entries.length === 0 && !busy ? (
            <div className="mc-empty">
              <div className="ai-empty-eyebrow">— operator copilot</div>
              <div className="ai-empty-title">Ask Aria anything.</div>
              <div className="ai-empty-sub">
                I can brief you on agents, runs, approvals and the knowledge base — and I can act on what you ask.
              </div>
            </div>
          ) : (
            entries.map((entry) => (
              <div key={entry.id} className={`ai-msg ai-msg-${entry.role}`}>
                <div className="ai-msg-avatar" aria-hidden="true">
                  {entry.role === 'user' ? 'You' : 'A'}
                </div>
                <div className="ai-msg-body">
                  <div className={`ai-msg-bubble${entry.error ? ' ai-msg-bubble-error' : ''}`}>
                    {entry.role === 'assistant' ? (
                      <div
                        className="ai-msg-md markdown-content"
                        dangerouslySetInnerHTML={{ __html: renderMarkdown(entry.content) }}
                      />
                    ) : (
                      <div className="ai-msg-text">{entry.content}</div>
                    )}
                  </div>
                  <div className="ai-msg-meta">
                    {entry.role === 'user' ? 'You' : 'Aria'} · {formatTimestamp(entry.timestamp)}
                    {entry.error && entry.retryPrompt && !busy && (
                      <button
                        type="button"
                        className="ai-retry"
                        onClick={() => send(entry.retryPrompt ?? '')}
                      >
                        Retry
                      </button>
                    )}
                    {entry.role === 'assistant' && entry.runId && !entry.error && entry.runId !== activeRunId && (
                      <StepsDisclosure
                        runId={entry.runId}
                        liveSteps={progress && progress.runId === entry.runId
                          ? progress.items.filter((item) => item.kind === 'tool')
                          : undefined}
                      />
                    )}
                  </div>
                </div>
              </div>
            ))
          )}

          {busy && (
            <div className="ai-msg ai-msg-assistant">
              <div className="ai-msg-avatar" aria-hidden="true">A</div>
              <div className="ai-msg-body">
                {isLive ? (
                  <ProgressTimeline items={liveItems} />
                ) : (
                  <div className="ai-msg-bubble ai-msg-bubble-thinking">
                    <div className="typing-indicator" aria-label="Aria is thinking">
                      <span className="typing-dot" />
                      <span className="typing-dot" />
                      <span className="typing-dot" />
                    </div>
                  </div>
                )}
              </div>
            </div>
          )}
        </div>

        <div className="mc-compose-wrap">
          <div className="ai-compose mc-compose">
            {slash.open && (
              <SlashCommandMenu
                items={slash.items}
                activeIndex={slash.activeIndex}
                onHover={(index) => slash.move(index - slash.activeIndex)}
                onSelect={(item) => { slash.choose(item); setInput(''); }}
              />
            )}
            {slash.pendingSkill && !slash.open && (
              <div className="ai-skill-chip">
                <span>/{slash.pendingSkill.name}</span>
                <button type="button" onClick={slash.clearSkill} aria-label="Remove skill">✕</button>
              </div>
            )}
            <textarea
              ref={textareaRef}
              className="ai-compose-input"
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
              <button type="button" className="ai-cancel-btn" onClick={stop} aria-label="Stop the turn">
                <span aria-hidden="true">■</span>
                <span>Stop</span>
              </button>
            ) : (
              <button
                type="button"
                className="ai-action-btn"
                onClick={() => send(input)}
                disabled={!input.trim()}
                aria-label="Send message"
              >
                <span className="ai-action-glyph" aria-hidden="true">↗</span>
                <span className="ai-action-label">Send</span>
              </button>
            )}
          </div>
          <div className="ai-hint mc-hint">
            Aria streams live · acts on the live system · review approvals before destructive moves.
          </div>
        </div>
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
