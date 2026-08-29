import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { streamMessage } from '../api/aria';
import { cancelRun } from '../api/runs';
import type { AriaMessage } from '../types';
import { getLatestConversation, getConversationTimeline, deleteConversation } from '../api/ariaConversations';
import { formatTimestamp } from '../utils/formatTime';

// ponytail: localStorage removed — conversation ownership now backend-backed.
// PANEL_OPEN_KEY retained for UI toggle only (not data).
const PANEL_OPEN_KEY = 'aria-panel-open';

const CLIENT_TIMEOUT_MS = 600_000; // 10 min — matches backend SSE timeout (Issue #12)

const SUGGESTIONS = [
  'My schedule today',
  'Brief me on overnight runs',
  'What needs my approval?',
  'Knowledge status summary',
];

interface PanelMessage extends AriaMessage {
  id: string;
  /** Optional UI-only error tag so we can render a retry affordance per-bubble. */
  error?: boolean;
}

function loadOpenState(): boolean {
  try {
    return localStorage.getItem(PANEL_OPEN_KEY) === '1';
  } catch {
    return false;
  }
}

/** Compact, allocation-light markdown renderer for assistant bubbles (m2: + tables & horizontal rules). */
function renderMarkdown(text: string): string {
  if (!text) return '<p></p>';
  // m2: guard very large payloads — render verbatim to avoid costly regex passes.
  if (text.length > 10000) {
    return `<pre>${text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')}</pre>`;
  }
  try {
    // Extract fenced code blocks first so their pipes/dashes aren't parsed as tables/rules.
    const codeBlocks: string[] = [];
    let src = text.replace(/```(\w*)\n([\s\S]*?)```/g, (_m, _lang, code) => {
      codeBlocks.push(`<pre class="md-code-block"><code>${String(code).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')}</code></pre>`);
      return `\u0000CB${codeBlocks.length - 1}\u0000`;
    });
    src = src
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
    const inline = (s: string): string =>
      s
        .replace(/`([^`]+)`/g, '<code class="md-inline-code">$1</code>')
        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
        .replace(/\*([^*]+)\*/g, '<em>$1</em>');
    const lines = src.split('\n');
    const out: string[] = [];
    let inList = false;
    let inTable = false;
    let tableHeaderDone = false;
    const closeList = () => { if (inList) { out.push('</ul>'); inList = false; } };
    const closeTable = () => { if (inTable) { out.push('</tbody></table>'); inTable = false; tableHeaderDone = false; } };
    for (const raw of lines) {
      const line = raw.trimEnd();
      const trimmed = line.trim();
      if (trimmed.startsWith('|') && trimmed.endsWith('|') && trimmed.length > 1) {
        const cells = trimmed.slice(1, -1).split('|').map((c) => c.trim());
        const isSeparator = cells.every((c) => /^:?-{2,}:?$/.test(c));
        if (isSeparator) { continue; }
        if (!inTable) {
          closeList();
          out.push('<table class="md-table"><thead><tr>');
          for (const c of cells) out.push(`<th>${inline(c)}</th>`);
          out.push('</tr></thead><tbody>');
          inTable = true;
          tableHeaderDone = true;
        } else {
          out.push('<tr>');
          for (const c of cells) out.push(`<td>${inline(c)}</td>`);
          out.push('</tr>');
        }
        continue;
      }
      closeTable();
      if (/^(-{3,}|\*{3,}|_{3,})$/.test(trimmed)) {
        closeList();
        out.push('<hr/>');
        continue;
      }
      let h = line;
      h = h.replace(/^### (.+)$/, '<h4>$1</h4>');
      h = h.replace(/^## (.+)$/, '<h3>$1</h3>');
      h = h.replace(/^# (.+)$/, '<h2>$1</h2>');
      h = inline(h);
      if (/^[•\-\*] (.+)$/.test(h)) {
        if (!inList) { out.push('<ul>'); inList = true; }
        out.push(`<li>${h.replace(/^[•\-\*] /, '')}</li>`);
        continue;
      }
      if (/^\d+\. (.+)$/.test(h)) {
        if (!inList) { out.push('<ul>'); inList = true; }
        out.push(`<li>${h.replace(/^\d+\. /, '')}</li>`);
        continue;
      }
      closeList();
      if (h.trim() === '') { out.push(''); } else { out.push(h); }
    }
    closeList();
    closeTable();
    let html = out.join('\n');
    html = html.replace(/\u0000CB(\d+)\u0000/g, (_m, i) => codeBlocks[Number(i)] ?? '');
    html = html.replace(/\n\n/g, '</p><p>');
    html = html.replace(/\n/g, '<br/>');
    if (!html.startsWith('<')) html = `<p>${html}</p>`;
    return html;
  } catch {
    return `<pre>${text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')}</pre>`;
  }
}

/**
 * Floating Aria assistant panel — FAB + slide-out chat surface.
 *
 * Wired into the global app shell so the operator can summon Aria from any
 * page without losing route context. Streams responses via Server-Sent Events
 * (`/api/v1/aria/chat/stream`) and falls back to a per-bubble retry button if
 * the backend emits an `error` event mid-stream.
 */
export function AriaPanel() {
  const [open, setOpen] = useState<boolean>(() => loadOpenState());
  const [messages, setMessages] = useState<PanelMessage[]>([]);
  const [input, setInput] = useState('');
  const [conversationId, setConversationId] = useState('');
  const [busy, setBusy] = useState(false);
  const [activeTool, setActiveTool] = useState<string | null>(null);
  const [lastSentMessage, setLastSentMessage] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [loading, setLoading] = useState(true);

  const stackRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const cancelledRef = useRef(false); // M1: suppress the false "connection closed" error after a user cancel
  const activeRunIdRef = useRef<string | null>(null); // M1: run id for server-side cancellation
  const [toolElapsed, setToolElapsed] = useState(0); // m3: seconds the current tool has been running

  const isEmpty = messages.length === 0;

  // Load conversation from backend on mount
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const summary = await getLatestConversation();
        if (cancelled) return;
        if (summary) {
          setConversationId(summary.conversationId);
          const timeline = await getConversationTimeline(summary.conversationId);
          if (cancelled) return;
          setMessages(timeline.map((t) => ({
            id: crypto.randomUUID(),
            role: (t.role === 'user' || t.role === 'assistant') ? t.role : 'assistant' as const,
            content: t.content,
            timestamp: t.timestamp,
          })));
        } else {
          const fresh = crypto.randomUUID();
          setConversationId(fresh);
          console.log(`[Aria] New conversation: ${fresh}`);
        }
      } catch {
        // Backend unavailable — start fresh locally
        if (!cancelled) {
          setConversationId(crypto.randomUUID());
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  // Persist open state only (UI toggle, not data)
  useEffect(() => {
    try {
      localStorage.setItem(PANEL_OPEN_KEY, open ? '1' : '0');
    } catch {
      /* ignore */
    }
  }, [open]);

  // Auto-scroll on message stack growth
  useEffect(() => {
    if (!open) return;
    const el = stackRef.current;
    if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
  }, [messages, busy, activeTool, open]);

  // Focus textarea when panel opens
  useEffect(() => {
    if (open) {
      const t = setTimeout(() => textareaRef.current?.focus(), 60);
      return () => clearTimeout(t);
    }
    return undefined;
  }, [open]);

  // Cancel in-flight stream when component unmounts
  useEffect(() => () => {
    abortRef.current?.abort();
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
  }, []);

  const togglePanel = useCallback(() => setOpen((v) => !v), []);
  const closePanel = useCallback(() => setOpen(false), []);

  const sendStreamed = useCallback(
    (rawText: string) => {
      const text = rawText.trim();
      if (!text || busy) return;

      // Abort any prior in-flight stream defensively.
      abortRef.current?.abort();
      cancelledRef.current = false; // M1: reset per-request cancel state
      activeRunIdRef.current = null;
      const ctrl = new AbortController();
      abortRef.current = ctrl;

      const userMsg: PanelMessage = {
        id: crypto.randomUUID(),
        role: 'user',
        content: text,
        timestamp: new Date().toISOString(),
      };

      // Snapshot prior history BEFORE the new message goes on screen.
      const history = messages.map((m) => ({ role: m.role, content: m.content }));
      setMessages((prev) => [...prev, userMsg]);
      setInput('');
      setBusy(true);
      setActiveTool(null);
      setLastSentMessage(text);

      void streamMessage(
        conversationId,
        text,
        history,
        {
          onThinking: (runId) => {
            if (runId) activeRunIdRef.current = runId; // M1: capture run id for cancel
            setActiveTool(null);
          },
          onToolCall: (name) => setActiveTool(name),
          onToolResult: () => setActiveTool(null),
          onMessage: (content) => {
            setMessages((prev) => [
              ...prev,
              {
                id: crypto.randomUUID(),
                role: 'assistant',
                content: content || 'No response received.',
                timestamp: new Date().toISOString(),
              },
            ]);
          },
          onDone: (data) => {
            if (timeoutRef.current) clearTimeout(timeoutRef.current);
            // Adopt the server-issued conversationId so multi-turn context persists (#36).
            if (data?.conversationId) {
              setConversationId((prev) => prev || data.conversationId);
            }
            setBusy(false);
            setActiveTool(null);
          },
          onError: (msg) => {
            if (cancelledRef.current) return; // M1: user cancelled — don't show an error bubble
            if (timeoutRef.current) clearTimeout(timeoutRef.current);
            setBusy(false);
            setActiveTool(null);
            setMessages((prev) => [
              ...prev,
              {
                id: crypto.randomUUID(),
                role: 'assistant',
                content: `⚠ ${msg || 'Streaming failed. Please try again.'}\n\nConversation ID: \`${conversationId}\` (include this when reporting issues)`,
                timestamp: new Date().toISOString(),
                error: true,
              },
            ]);
          },
        },
        ctrl.signal,
        { isCancelled: () => cancelledRef.current },
      );

      // Client-side timeout: if no response in CLIENT_TIMEOUT_MS, abort and show error.
      timeoutRef.current = setTimeout(() => {
        ctrl.abort();
        setBusy(false);
        setActiveTool(null);
        setMessages((prev) => [
          ...prev,
          {
            id: crypto.randomUUID(),
            role: 'assistant',
            content: '⚠ Aria is taking longer than expected. The request may have timed out. Please try again.\n\nConversation ID: `' + conversationId + '` (include this when reporting issues)',
            timestamp: new Date().toISOString(),
            error: true,
          },
        ]);
      }, CLIENT_TIMEOUT_MS);
    },
    [busy, messages, conversationId],
  );

  const handleSend = useCallback(() => {
    sendStreamed(input);
  }, [input, sendStreamed]);

  const handleRetry = useCallback(() => {
    if (busy || !lastSentMessage) return;
    // Drop the trailing error bubble so the retry doesn't pile up duplicates.
    setMessages((prev) => {
      if (prev.length === 0 || !prev[prev.length - 1].error) return prev;
      return prev.slice(0, -1);
    });
    sendStreamed(lastSentMessage);
  }, [busy, lastSentMessage, sendStreamed]);

  const handleCancel = useCallback(() => {
    cancelledRef.current = true; // M1: suppress the false close-error bubble
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
    abortRef.current?.abort();
    // M1: cancel the server-side run so it stops consuming tokens (best-effort; runId
    // is known once the first `thinking` event arrives).
    if (activeRunIdRef.current) {
      cancelRun(activeRunIdRef.current).catch(() => {});
    }
    setBusy(false);
    setActiveTool(null);
    setMessages((prev) => [
      ...prev,
      {
        id: crypto.randomUUID(),
        role: 'assistant',
        content: 'Request cancelled.',
        timestamp: new Date().toISOString(),
      },
    ]);
  }, []);

  const handleCopyConversationId = useCallback(() => {
    navigator.clipboard.writeText(conversationId).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }).catch(() => {
      /* ignore clipboard errors */
    });
  }, [conversationId]);

  const handleNewConversation = useCallback(() => {
    if (conversationId) {
      deleteConversation(conversationId).catch(() => {
        /* ignore backend errors — clear locally regardless */
      });
    }
    const newConversationId = crypto.randomUUID();
    setMessages([]);
    setConversationId(newConversationId);
    console.log(`[Aria] New conversation: ${newConversationId}`);
    setInput('');
    setBusy(false);
    setActiveTool(null);
    setLastSentMessage(null);
  }, [conversationId]);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    } else if (e.key === 'Escape') {
      closePanel();
    }
  };

  const lastIsError = useMemo(
    () => messages.length > 0 && !!messages[messages.length - 1].error,
    [messages],
  );

  // m3: show elapsed seconds while a tool is running so long calls don't look dead.
  useEffect(() => {
    if (!activeTool) {
      setToolElapsed(0);
      return undefined;
    }
    const start = Date.now();
    setToolElapsed(0);
    const id = window.setInterval(() => setToolElapsed(Math.floor((Date.now() - start) / 1000)), 1000);
    return () => window.clearInterval(id);
  }, [activeTool]);

  // m2: memoize rendered markdown so it only recomputes when messages change.
  const messagesHtml = useMemo(() => messages.map((m) => renderMarkdown(m.content)), [messages]);

  return (
    <>
      <button
        type="button"
        className={`ai-fab ${open ? 'ai-fab-active' : ''}`}
        onClick={togglePanel}
        aria-label={open ? 'Close Aria panel' : 'Open Aria panel'}
        aria-expanded={open}
      >
        <span className="ai-fab-halo" aria-hidden="true" />
        <span className="ai-fab-glyph" aria-hidden="true">✦</span>
        <span className="ai-fab-dot" aria-hidden="true" />
      </button>

      {open && (
        <section className="ai-panel" role="dialog" aria-label="Aria assistant">
          <header className="ai-header">
            <div className="ai-header-stripe" aria-hidden="true" />
            <div className="ai-avatar" aria-hidden="true">
              <span>A</span>
            </div>
            <div className="ai-header-text">
              <div className="ai-header-title">Aria</div>
              <div className="ai-header-sub">
                <span className="ai-pulse-dot" aria-hidden="true" />
                {loading ? 'Connecting…' : 'Personal Assistant · always on'}
              </div>
            </div>
            <div className="ai-session-info">
              <code className="ai-conversation-id" title={conversationId}>
                {conversationId.slice(0, 8)}...
              </code>
              <button
                type="button"
                className="ai-copy-btn"
                onClick={handleCopyConversationId}
                aria-label="Copy conversation ID"
                title="Copy conversation ID to clipboard"
              >
                {copied ? '✓' : '📋'}
              </button>
              <button
                type="button"
                className="ai-clear-btn"
                onClick={handleNewConversation}
                aria-label="Clear and start new conversation"
                title="Clear and start new conversation"
              >
                Clear
              </button>
            </div>
            <button
              type="button"
              className="ai-close"
              onClick={closePanel}
              aria-label="Close Aria"
            >
              ×
            </button>
          </header>

          <div className="ai-stack" ref={stackRef} aria-live="polite" aria-relevant="additions">
            {isEmpty && (
              <div className="ai-card ai-empty">
                <div className="ai-empty-eyebrow">— operator copilot</div>
                <div className="ai-empty-title">
                  Good to see you. <em>How can I help?</em>
                </div>
                <div className="ai-empty-sub">
                  I can brief you on agents, runs, approvals, and the knowledge
                  base — and I can act on what you ask.
                </div>
              </div>
            )}

            {messages.map((msg, idx) => (
              <div key={msg.id} className={`ai-msg ai-msg-${msg.role}`}>
                <div className="ai-msg-avatar" aria-hidden="true">
                  {msg.role === 'user' ? 'You' : 'A'}
                </div>
                <div className="ai-msg-body">
                  <div className={`ai-msg-bubble ${msg.error ? 'ai-msg-bubble-error' : ''}`}>
                    {msg.role === 'assistant' ? (
                      <div
                        className="ai-msg-md markdown-content"
                        dangerouslySetInnerHTML={{ __html: messagesHtml[idx] ?? '' }}
                      />
                    ) : (
                      <div className="ai-msg-text">{msg.content}</div>
                    )}
                  </div>
                  <div className="ai-msg-meta">
                    {msg.role === 'user' ? 'You' : 'Aria'} ·{' '}
                    {formatTimestamp(msg.timestamp)}
                    {msg.error && lastIsError && (
                      <button
                        type="button"
                        className="ai-retry"
                        onClick={handleRetry}
                        disabled={busy}
                      >
                        Retry
                      </button>
                    )}
                  </div>
                </div>
              </div>
            ))}

            {busy && (
              <div className="ai-msg ai-msg-assistant">
                <div className="ai-msg-avatar" aria-hidden="true">
                  A
                </div>
                <div className="ai-msg-body">
                  <div className="ai-msg-bubble ai-msg-bubble-thinking">
                    {activeTool ? (
                      <div className="ai-tool-tag">
                        <span className="ai-tool-spark" aria-hidden="true">⟢</span>
                        Using <code>{activeTool}</code>
                        <span className="ai-tool-elapsed"> ({toolElapsed}s{toolElapsed >= 30 ? ' · still working…' : ''})</span>
                      </div>
                    ) : (
                      <div className="typing-indicator" aria-label="Aria is thinking">
                        <span className="typing-dot" />
                        <span className="typing-dot" />
                        <span className="typing-dot" />
                      </div>
                    )}
                  </div>
                </div>
              </div>
            )}

            {isEmpty && (
              <div className="ai-suggestions" role="group" aria-label="Suggested prompts">
                {SUGGESTIONS.map((s) => (
                  <button
                    key={s}
                    type="button"
                    className="ai-sg"
                    onClick={() => sendStreamed(s)}
                    disabled={busy}
                  >
                    {s}
                  </button>
                ))}
              </div>
            )}
          </div>

          <div className="ai-compose">
            <textarea
              ref={textareaRef}
              className="ai-compose-input"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="Ask Aria anything…  (Enter to send, Shift+Enter for newline)"
              rows={2}
              disabled={busy}
            />
            {busy ? (
              <button
                type="button"
                className="ai-cancel-btn"
                onClick={handleCancel}
                aria-label="Cancel request"
              >
                <span aria-hidden="true">✕</span>
                <span>Cancel</span>
              </button>
            ) : (
              <button
                type="button"
                className="ai-action-btn"
                onClick={handleSend}
                disabled={!input.trim()}
                aria-label="Send message"
              >
                <span className="ai-action-glyph" aria-hidden="true">↗</span>
                <span className="ai-action-label">Send</span>
              </button>
            )}
          </div>
          <div className="ai-hint">
            Aria streams live · acts on the live system · review approvals before destructive moves.
          </div>
        </section>
      )}
    </>
  );
}

export default AriaPanel;
