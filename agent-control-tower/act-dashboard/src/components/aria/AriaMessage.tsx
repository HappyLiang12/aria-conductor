import { credentialRejectionRemedy } from '../../api/errorPresentation';
import { formatTimestamp } from '../../utils/formatTime';
import type { PanelItem } from '../ariaPanelState';

type UserItem = Extract<PanelItem, { kind: 'user' }>;
type AssistantItem = Extract<PanelItem, { kind: 'assistant' }>;

/** The two items the transcript draws as a chat bubble (the rest are cards). */
export type AriaChatItem = UserItem | AssistantItem;

/**
 * One chat bubble: the operator's turn (with the thumbnails its send carried) or
 * Aria's answer (rendered markdown, or the failure bubble with its remedy).
 *
 * Wave 2 D1: split out of `AriaPanel`; it stays presentational. The `.ai-msg*`
 * hooks, the `credential-remedy` test id and the Retry control's accessible name
 * are unchanged — the aria E2E specs select `.ai-msg-user`, `.ai-msg-assistant`
 * and `.ai-msg-bubble-error`.
 */
export function AriaMessage({
  item,
  html,
  isTail,
  canRetry,
  busy,
  onRetry,
  onOpenConfigure,
}: {
  item: AriaChatItem;
  html: string;
  isTail: boolean;
  canRetry: boolean;
  busy: boolean;
  onRetry: () => void;
  onOpenConfigure: () => void;
}) {
  const remedy = item.kind === 'assistant' && item.error === true
    ? credentialRejectionRemedy(item.content)
    : null;

  return (
    <div className={`ai-msg ai-msg-${item.kind} flex items-start gap-2`}>
      <div
        className="ai-msg-avatar mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full border border-border bg-muted text-[10px] text-muted-foreground"
        aria-hidden="true"
      >
        {item.kind === 'user' ? 'You' : 'A'}
      </div>
      <div className="ai-msg-body flex min-w-0 flex-1 flex-col gap-1">
        <div
          className={`ai-msg-bubble max-w-[88%] rounded-2xl border border-border bg-muted px-3 py-2 text-sm leading-relaxed text-card-foreground ${item.kind === 'assistant' && item.error ? 'ai-msg-bubble-error border-destructive/45 bg-destructive/10' : ''}`}
        >
          {item.kind === 'user' && item.attachments && item.attachments.length > 0 && (
            // The turn's images, drawn from the client's own object URLs: nothing
            // about the bytes is ever persisted or restored, so a re-read
            // transcript shows text only.
            <div className="ai-msg-attachments mb-2 flex flex-wrap gap-1.5">
              {item.attachments.map((attachment) => (
                <img
                  key={attachment.previewUrl}
                  src={attachment.previewUrl}
                  alt={attachment.filename}
                  className="ai-msg-attachment-thumb size-16 rounded-md border border-border object-cover"
                />
              ))}
            </div>
          )}
          {item.kind === 'assistant' && item.error === true && remedy !== null && (
            // T54 F3: a failure whose server text names no fix leads with the
            // cause and the action, then keeps the original wording below it as
            // the record the operator can quote.
            <div className="ai-msg-remedy mb-2 rounded-md border border-border bg-background/60 p-2" data-testid="credential-remedy">
              <p className="ai-msg-remedy-text m-0 text-sm text-card-foreground">{remedy}</p>
              <button
                type="button"
                className="btn mt-1.5 inline-flex h-7 items-center rounded-md border border-border bg-background px-2.5 text-xs font-medium text-foreground transition-colors hover:bg-muted"
                onClick={onOpenConfigure}
              >
                Open Configure → LLM Providers
              </button>
            </div>
          )}
          {item.kind === 'assistant' ? (
            <div
              className="ai-msg-md markdown-content"
              dangerouslySetInnerHTML={{ __html: html }}
            />
          ) : (
            <div className="ai-msg-text whitespace-pre-wrap">{item.content}</div>
          )}
        </div>
        <div className="ai-msg-meta flex items-center gap-2 text-[10px] tracking-wider text-muted-foreground uppercase">
          {item.kind === 'user' ? 'You' : 'Aria'} ·{' '}
          {formatTimestamp(item.timestamp)}
          {item.kind === 'assistant' && item.error === true && canRetry && isTail && (
            <button
              type="button"
              className="ai-retry ml-1 inline-flex h-6 items-center rounded-full border border-border px-2 text-[10px] tracking-wider text-primary uppercase transition-colors hover:bg-muted disabled:opacity-40"
              onClick={onRetry}
              disabled={busy}
            >
              Retry
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

export default AriaMessage;
