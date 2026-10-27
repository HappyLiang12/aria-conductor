import { useEffect, useId, useLayoutEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import '../styles/feedback-flow.css';

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
 * `.modal-dialog` + `.modal-actions`, role/aria-modal/aria-labelledby).
 * Task 7 makes the gate destroy-safe: a danger dialog focuses Cancel, and
 * Tab/Shift+Tab cycle inside the dialog instead of reaching the page behind.
 * The opener is captured before the programmatic focus and restored on close,
 * including the unmount path where the dialog mounted already open.
 */
export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message: ReactNode;
  /** Styles the confirm control as destructive (cancel / retire / reject). */
  danger?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export function ConfirmDialog({
  open,
  title,
  message,
  danger = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  // useId, not a literal: KnowledgePage renders more than one dialog and
  // hardcoded ids would collide (first match wins for aria-labelledby).
  const titleId = useId();
  const dialogRef = useRef<HTMLDivElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);

  // Capture the opener BEFORE the programmatic focus moves inside the dialog.
  // The containment check keeps a re-run of this effect (StrictMode) from
  // mistaking the dialog's own focused control for the opener.
  useLayoutEffect(() => {
    if (!open) return;
    const active = document.activeElement;
    if (
      active instanceof HTMLElement
      && active !== document.body
      && !dialogRef.current?.contains(active)
    ) {
      returnFocusRef.current = active;
    }
    (danger ? cancelRef : confirmRef).current?.focus();
    return () => {
      const opener = returnFocusRef.current;
      returnFocusRef.current = null;
      if (opener?.isConnected) opener.focus();
    };
  }, [open, danger]);

  // Escape dismisses; Tab is trapped. The trap lives on the window because a
  // click on the dialog title or message moves focus to <body>: the next Tab
  // then targets body and a dialog-scoped handler would never see it, letting
  // focus reach the page behind an aria-modal dialog.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.key === 'Escape') {
        onCancel();
        return;
      }
      if (e.key !== 'Tab') return;
      const first = danger ? cancelRef.current : confirmRef.current;
      const second = danger ? confirmRef.current : cancelRef.current;
      e.preventDefault();
      if (document.activeElement === first) {
        second?.focus();
      } else if (document.activeElement === second) {
        first?.focus();
      } else {
        first?.focus();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onCancel, danger]);

  if (!open) return null;

  return (
    <div className="modal-overlay" onClick={onCancel}>
      <div
        ref={dialogRef}
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
          {danger ? (
            <>
              {/* Danger: the safe exit is first in DOM and focus order, so a
                  stray Enter dismisses instead of confirming. */}
              <button ref={cancelRef} type="button" className="btn" onClick={onCancel}>
                Cancel
              </button>
              <button
                ref={confirmRef}
                type="button"
                className="btn danger"
                onClick={onConfirm}
              >
                Confirm
              </button>
            </>
          ) : (
            <>
              <button
                ref={confirmRef}
                type="button"
                className="btn primary"
                onClick={onConfirm}
              >
                Confirm
              </button>
              <button ref={cancelRef} type="button" className="btn" onClick={onCancel}>
                Cancel
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
