/**
 * The panel header: identity, the conversation id with its copy control, Clear,
 * expand-into-main-chat and close. Presentational — every action is a callback
 * owned by the panel, so the conversation lifecycle never leaves `AriaPanel`.
 *
 * Wave 2 D1: keeps the `.ai-header*`, `.ai-session-info`, `.ai-copy-btn`,
 * `.ai-clear-btn` and `.ai-close` hooks and every accessible name; the surfaces
 * are the token Card/Border palette.
 */
export function AriaPanelHeader({
  conversationId,
  copied,
  clearing,
  loading,
  onCopyConversationId,
  onClear,
  onOpenMainChat,
  onClose,
}: {
  conversationId: string;
  copied: boolean;
  clearing: boolean;
  loading: boolean;
  onCopyConversationId: () => void;
  onClear: () => void;
  onOpenMainChat: () => void;
  onClose: () => void;
}) {
  return (
    <header className="ai-header relative flex items-center gap-3 border-b border-border px-4 pt-4 pb-3">
      <div className="ai-header-stripe absolute inset-y-0 left-0 w-[3px] bg-primary" aria-hidden="true" />
      <div
        className="ai-avatar flex size-9 shrink-0 items-center justify-center rounded-full bg-primary text-sm font-semibold text-primary-foreground"
        aria-hidden="true"
      >
        <span>A</span>
      </div>
      <div className="ai-header-text min-w-0 flex-1">
        <div className="ai-header-title truncate text-base font-semibold text-card-foreground">Aria</div>
        <div className="ai-header-sub mt-0.5 flex items-center gap-1.5 text-xs text-muted-foreground">
          <span className="ai-pulse-dot inline-block size-1.5 rounded-full bg-primary" aria-hidden="true" />
          {loading ? 'Connecting…' : 'Personal Assistant · always on'}
        </div>
      </div>
      <div className="ai-session-info ml-2 flex items-center gap-1.5">
        <code
          className="ai-conversation-id rounded border border-border bg-muted px-2 py-0.5 font-mono text-[11px] tracking-wide text-muted-foreground"
          title={conversationId}
        >
          {conversationId.slice(0, 8)}...
        </code>
        <button
          type="button"
          className="ai-copy-btn flex size-6 items-center justify-center rounded text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
          onClick={onCopyConversationId}
          aria-label="Copy conversation ID"
          title="Copy conversation ID to clipboard"
        >
          {copied ? '✓' : '📋'}
        </button>
        <button
          type="button"
          className="ai-clear-btn h-6 rounded border border-border bg-muted px-2.5 font-mono text-[11px] tracking-wide text-muted-foreground transition-colors hover:border-destructive/50 hover:bg-destructive/10 hover:text-destructive"
          onClick={onClear}
          disabled={clearing}
          aria-busy={clearing || undefined}
          aria-label="Clear and start new conversation"
          title={clearing
            ? 'Clearing this conversation — waiting for the run stop and transcript purge to answer'
            : "Clear and start new conversation — this cancels this conversation's runs and deletes its transcript"}
        >
          {clearing ? 'Clearing…' : 'Clear'}
        </button>
      </div>
      <button
        type="button"
        className="ai-close flex size-7 items-center justify-center rounded-full border border-border bg-muted text-base leading-none text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
        onClick={onOpenMainChat}
        aria-label="Open Aria in the main chat"
        title="Open in main chat"
      >
        ⤢
      </button>
      <button
        type="button"
        className="ai-close flex size-7 items-center justify-center rounded-full border border-border bg-muted text-base leading-none text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
        onClick={onClose}
        aria-label="Close Aria"
      >
        ×
      </button>
    </header>
  );
}

export default AriaPanelHeader;
