import { useEffect, useId } from 'react';
import type { ReactNode } from 'react';

/**
 * The single confirmation surface for destructive actions (Task 10).
 *
 * Before this component existed four call sites gated (or failed to gate) their
 * destructive action with four different dialogs — two markup conventions and
 * one missing gate entirely. One implementation, one policy: the destructive
 * control only opens this dialog, the mutation fires from `onConfirm`, and
 * `onCancel` / backdrop / Escape clear the pending state without mutating.
 *
 * Accessibility mirrors the app's modal convention (`.modal-overlay` +
 * `.modal-dialog` + `.modal-actions`, role/aria-modal/aria-labelledby) and
 * autofocuses the confirm control, matching ReviewWorkspace's precedent.
 */
export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message: ReactNode;
  /** Accessible name of the confirming control. Defaults to 'Confirm'. */
  confirmLabel?: string;
  /** Accessible name of the dismissing control. Defaults to 'Cancel'. */
  cancelLabel?: string;
  /** Styles the confirm control as destructive (cancel / retire / reject). */
  danger?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  danger = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  // useId, not a literal: KnowledgePage renders more than one dialog and
  // hardcoded ids would collide (first match wins for aria-labelledby).
  const titleId = useId();

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onCancel]);

  if (!open) return null;

  return (
    <div className="modal-overlay" onClick={onCancel}>
      <div
        className="modal-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        // A click inside the dialog body must not reach the backdrop handler.
        onClick={(e) => e.stopPropagation()}
      >
        <h3 id={titleId}>{title}</h3>
        <p>{message}</p>
        <div className="modal-actions">
          <button
            className={danger ? 'btn danger' : 'btn primary'}
            autoFocus
            onClick={onConfirm}
          >
            {confirmLabel}
          </button>
          <button className="btn" onClick={onCancel}>
            {cancelLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

export default ConfirmDialog;
