import type { ConversationListItem } from '../../api/ariaConversations';

interface Props {
  items: ConversationListItem[];
  activeId: string | null;
  loading: boolean;
  error: string | null;
  disabled: boolean;
  onNew: () => void;
  onSelect: (conversationId: string) => void;
  onDelete: (conversationId: string) => void;
}

export function ConversationSidebar({
  items, activeId, loading, error, disabled, onNew, onSelect, onDelete,
}: Props) {
  return (
    <aside className="mc-sidebar" aria-label="Conversations">
      <button type="button" className="mc-new-chat" onClick={onNew} disabled={disabled}>
        <span aria-hidden="true">＋</span> New chat
      </button>
      {error && <div className="mc-sidebar-error" role="alert">{error}</div>}
      {loading ? (
        <div className="mc-sidebar-empty">Loading…</div>
      ) : items.length === 0 ? (
        <div className="mc-sidebar-empty">No conversations yet.</div>
      ) : (
        <ul className="mc-conversation-list">
          {items.map((item) => {
            const active = item.conversationId === activeId;
            return (
              <li key={item.conversationId} className={`mc-conversation${active ? ' active' : ''}`}>
                <button
                  type="button"
                  className="mc-conversation-open"
                  onClick={() => onSelect(item.conversationId)}
                  disabled={disabled}
                  aria-current={active ? 'true' : undefined}
                  title={`${item.title} (${item.runCount} run${item.runCount === 1 ? '' : 's'})`}
                >
                  <span className="mc-conversation-title">{item.title}</span>
                </button>
                <button
                  type="button"
                  className="mc-conversation-delete"
                  aria-label={`Delete conversation ${item.title}`}
                  onClick={() => onDelete(item.conversationId)}
                  disabled={disabled}
                >
                  ×
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </aside>
  );
}
