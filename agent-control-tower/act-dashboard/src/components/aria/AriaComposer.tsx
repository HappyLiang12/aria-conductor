import type { ClipboardEvent, DragEvent, KeyboardEvent, RefObject } from 'react';
import type { SlashCommandState } from '../../hooks/useSlashCommands';
import type { SlashCommandItem } from '../../utils/slashCommands';
import type { PendingImage } from '../ariaAttachments';
import SlashCommandMenu from '../SlashCommandMenu';

/**
 * The panel composer: waiting-image chips, picker/paste/drop intake, the slash
 * command menu and its pending-skill chip, the textarea and the Send/Cancel
 * control.
 *
 * Wave 2 D1: split out of `AriaPanel` and kept presentational — the attachment
 * budgets, the stream and the cancel path all stay in the panel. Every `.ai-*`
 * hook, the `aria-attachment-input` test id, the placeholder and the control
 * labels are unchanged.
 */
export function AriaComposer({
  input,
  busy,
  gated,
  clearing,
  pendingImages,
  attachmentError,
  slash,
  fileInputRef,
  textareaRef,
  onInputChange,
  onKeyDown,
  onPaste,
  onSelectSkill,
  onRemoveImage,
  onAddFiles,
  onSend,
  onCancel,
}: {
  input: string;
  busy: boolean;
  gated: boolean;
  clearing: boolean;
  pendingImages: PendingImage[];
  attachmentError: string | null;
  slash: SlashCommandState;
  fileInputRef: RefObject<HTMLInputElement | null>;
  textareaRef: RefObject<HTMLTextAreaElement | null>;
  onInputChange: (value: string, caret: number) => void;
  onKeyDown: (event: KeyboardEvent<HTMLTextAreaElement>) => void;
  onPaste: (event: ClipboardEvent<HTMLTextAreaElement>) => void;
  onSelectSkill: (item: SlashCommandItem) => void;
  onRemoveImage: (id: string) => void;
  onAddFiles: (files: FileList | File[]) => void;
  onSend: () => void;
  onCancel: () => void;
}) {
  return (
    <div
      className="ai-compose relative flex flex-col gap-2 border-t border-border p-3"
      onDragOver={(event: DragEvent<HTMLDivElement>) => event.preventDefault()}
      onDrop={(event: DragEvent<HTMLDivElement>) => {
        event.preventDefault();
        if (event.dataTransfer.files.length > 0) void onAddFiles(event.dataTransfer.files);
      }}
    >
      {pendingImages.length > 0 && (
        <div className="ai-compose-attachments flex flex-wrap gap-1.5">
          {pendingImages.map((image) => (
            <span
              key={image.id}
              className="ai-attachment-chip inline-flex items-center gap-1.5 rounded-lg border border-border bg-muted px-2 py-1 text-xs text-muted-foreground"
            >
              <img src={image.previewUrl} alt={image.filename} className="ai-attachment-thumb size-8 rounded object-cover" />
              <span className="ai-attachment-name max-w-24 truncate">{image.filename}</span>
              <button
                type="button"
                className="text-muted-foreground hover:text-foreground"
                onClick={() => onRemoveImage(image.id)}
                aria-label={`Remove ${image.filename}`}
              >
                ✕
              </button>
            </span>
          ))}
        </div>
      )}
      {attachmentError && (
        <div className="ai-attachment-error text-xs text-destructive" role="alert">{attachmentError}</div>
      )}
      <input
        ref={fileInputRef}
        type="file"
        accept="image/jpeg,image/png,image/webp"
        multiple
        className="ai-attach-input hidden"
        data-testid="aria-attachment-input"
        onChange={(event) => {
          if (event.target.files) void onAddFiles(event.target.files);
          event.target.value = '';
        }}
      />
      {slash.open && (
        <SlashCommandMenu
          items={slash.items}
          activeIndex={slash.activeIndex}
          onHover={(index) => slash.move(index - slash.activeIndex)}
          onSelect={onSelectSkill}
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
        onChange={(event) => onInputChange(event.target.value, event.target.selectionStart ?? event.target.value.length)}
        onKeyDown={onKeyDown}
        onPaste={onPaste}
        placeholder="Ask Aria anything…  (type / for skills)"
        rows={2}
        disabled={busy || gated || clearing}
        aria-controls={slash.open ? 'slash-command-listbox' : undefined}
        aria-activedescendant={slash.open && slash.items[slash.activeIndex] ? `slash-opt-${slash.items[slash.activeIndex].id}` : undefined}
      />
      <div className="flex items-center gap-2">
        <button
          type="button"
          className="ai-attach-btn inline-flex size-8 items-center justify-center rounded-lg border border-border bg-muted text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:opacity-50"
          onClick={() => fileInputRef.current?.click()}
          disabled={busy || gated || clearing}
          aria-label="Attach images"
          title="Attach images (JPEG, PNG or WebP; up to 5, 4 MB each)"
        >
          <span aria-hidden="true">📎</span>
        </button>
        {busy ? (
          <button
            type="button"
            className="ai-cancel-btn ml-auto inline-flex h-8 items-center gap-1.5 rounded-lg border border-destructive/50 bg-destructive/10 px-3 text-xs font-semibold tracking-wide text-destructive uppercase transition-colors hover:bg-destructive/20"
            onClick={onCancel}
            aria-label="Cancel request"
          >
            <span aria-hidden="true">✕</span>
            <span>Cancel</span>
          </button>
        ) : (
          <button
            type="button"
            className="ai-action-btn ml-auto inline-flex h-8 items-center gap-1.5 rounded-lg bg-primary px-3 text-xs font-semibold text-primary-foreground transition-colors hover:bg-primary/80 disabled:opacity-50"
            onClick={onSend}
            disabled={!input.trim() || gated || clearing}
            aria-label="Send message"
          >
            <span className="ai-action-glyph" aria-hidden="true">↗</span>
            <span className="ai-action-label">Send</span>
          </button>
        )}
      </div>
      <div className="ai-hint text-center text-[11px] text-muted-foreground">
        Aria streams live · acts on the live system · review approvals before destructive moves.
      </div>
    </div>
  );
}

export default AriaComposer;
