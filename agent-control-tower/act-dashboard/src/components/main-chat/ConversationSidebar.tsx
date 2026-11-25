import type { ConversationListItem } from '../../api/ariaConversations';
import { Card } from '../ui/card';

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

/**
 * The conversation rail: New chat, loading/empty/error states and one row per
 * conversation with open/delete controls.
 *
 * Wave 2 D6: the rail is a token Card inside the preserved `.mc-sidebar` hook.
 * `.mc-conversation-list`, `.mc-conversation-open` and `.mc-conversation-delete`
 * stay because the aria-main-chat E2E drives them; the styling is token classes.
 */
export function ConversationSidebar({
  items, activeId, loading, error, disabled, onNew, onSelect, onDelete,
}: Props) {
  return (
    <aside className="mc-sidebar flex min-h-0 flex-col border-r border-border max-[900px]:fixed max-[900px]:top-[var(--top-stack-h,67px)] max-[900px]:bottom-0 max-[900px]:left-[84px] max-[900px]:z-[60] max-[900px]:w-[260px] max-[900px]:bg-background max-[900px]:shadow-xl">
      <Card className="flex min-h-0 flex-1 flex-col gap-2 rounded-none border-0 bg-transparent p-2.5 shadow-none ring-0 [--card-spacing:0px]">
        <button
          type="button"
          className="mc-new-chat flex items-center gap-1.5 rounded-lg border border-border bg-muted px-2.5 py-2 text-xs font-semibold text-foreground transition-colors hover:border-ring disabled:opacity-50"
          onClick={onNew}
          disabled={disabled}
        >
          <span aria-hidden="true">＋</span> New chat
        </button>
        {error && <div className="mc-sidebar-error px-1 py-1.5 text-xs text-destructive" role="alert">{error}</div>}
        {loading ? (
          <div className="mc-sidebar-empty px-1 py-1.5 text-xs text-muted-foreground">Loading…</div>
        ) : items.length === 0 ? (
          <div className="mc-sidebar-empty px-1 py-1.5 text-xs text-muted-foreground">No conversations yet.</div>
        ) : (
          <ul className="mc-conversation-list flex list-none flex-col gap-0.5 p-0">
            {items.map((item) => {
              const active = item.conversationId === activeId;
              return (
                <li
                  key={item.conversationId}
                  className={`mc-conversation flex items-center rounded-lg transition-colors hover:bg-muted ${active ? 'active bg-accent' : ''}`}
                >
                  <button
                    type="button"
                    className="mc-conversation-open min-w-0 flex-1 rounded-lg px-2 py-2 text-left text-foreground disabled:opacity-60"
                    onClick={() => onSelect(item.conversationId)}
                    disabled={disabled}
                    aria-current={active ? 'true' : undefined}
                    title={`${item.title} (${item.runCount} run${item.runCount === 1 ? '' : 's'})`}
                  >
                    <span className="mc-conversation-title block overflow-hidden text-xs text-ellipsis whitespace-nowrap">
                      {item.title}
                    </span>
                  </button>
                  <button
                    type="button"
                    className="mc-conversation-delete mr-1 flex size-6 shrink-0 items-center justify-center rounded-md text-sm leading-none text-muted-foreground opacity-0 transition-opacity hover:bg-destructive/10 hover:text-destructive focus-visible:opacity-100 [.mc-conversation:hover_&]:opacity-100"
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
      </Card>
    </aside>
  );
}
