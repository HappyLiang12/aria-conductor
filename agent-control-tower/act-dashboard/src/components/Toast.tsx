import { useCallback, useEffect, useRef, useState } from 'react';
import type { FocusEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useWebSocketContext } from './Layout';
import { eventLabel } from '../utils/eventLabels';
import { routeForNotificationType } from '../utils/notificationRoutes';
import '../styles/toast-flow.css';

interface ToastItem {
  id: number;
  message: string;
  type: string;
  action?: { label: string; onClick: () => void };
}

let toastId = 0;

/** How long a toast stays before it dismisses itself. */
const TOAST_TTL_MS = 5000;
/** Visible stack size; a sixth toast evicts the oldest one that lacks focus. */
const TOAST_CAP = 5;

// Only user-relevant events surface as toasts; internal lifecycle noise
// (run.started, kanban.*, run.iteration, ...) updates data silently.
// Mirrors the event types actually broadcast by EventBroadcastListener.
const TOAST_WORTHY = new Set([
  'run.completed',
  'approval.requested',
  'approval.decided',
  'approval.expired',
  'knowledge.submitted',
  'knowledge.approved',
  'knowledge.retired',
  'report.generated',
  'report.amended',
  'aria.notification',
  'audit.HOUSEKEEPING_EXECUTED',
]);

// Failures arrive as run.completed with payload.status — derive a truthful label.
function toastMessage(type: string, payload: Record<string, unknown> | undefined): string {
  const status = payload?.status as string | undefined;
  if (type === 'run.completed' && status && status !== 'COMPLETED') {
    return `Run ${status.charAt(0)}${status.slice(1).toLowerCase()}`;
  }
  return eventLabel(type);
}

/** The toast id owning a focused/blurred descendant, or null. */
function toastIdFromTarget(target: EventTarget | null): number | null {
  const el = target instanceof Element ? target.closest('[data-toast-id]') : null;
  const raw = el?.getAttribute('data-toast-id');
  if (raw == null) return null;
  const id = Number(raw);
  return Number.isNaN(id) ? null : id;
}

/**
 * Transient notice stack (Task 8).
 *
 * Lifecycle contract:
 * - one armed `setTimeout` per visible toast; a new event never clears another
 *   toast's timer;
 * - hovering the stack or focusing a control inside it pauses that toast's
 *   remaining time (per-toast remainder, not a global clock);
 * - at the cap the oldest toast that does not contain focus is evicted, so a
 *   keyboard user's focused View action is never yanked out from under them;
 * - `role="status" aria-live="polite"`: announcements are polite and focus is
 *   never moved by the component;
 * - `aria.notification` toasts only offer View when the fine-grained type
 *   resolves to a route — never an action whose click can be a no-op.
 */
export function Toast() {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  // Synchronous mirror: timers and eviction read the current stack without
  // waiting for a render (bursts of frames arrive in one tick).
  const toastsRef = useRef<ToastItem[]>([]);
  const { subscribe } = useWebSocketContext();
  const shownIds = useRef(new Set<string>());
  const timersRef = useRef(new Map<number, ReturnType<typeof setTimeout>>());
  const deadlinesRef = useRef(new Map<number, number>());
  const remainingRef = useRef(new Map<number, number>());
  const hoverPausedRef = useRef(false);
  const focusedToastRef = useRef<number | null>(null);
  const navigate = useNavigate();

  const clearToastTimer = useCallback((id: number) => {
    const timer = timersRef.current.get(id);
    if (timer !== undefined) clearTimeout(timer);
    timersRef.current.delete(id);
    deadlinesRef.current.delete(id);
  }, []);

  const dismiss = useCallback(
    (id: number) => {
      clearToastTimer(id);
      remainingRef.current.delete(id);
      const next = toastsRef.current.filter((t) => t.id !== id);
      toastsRef.current = next;
      setToasts(next);
    },
    [clearToastTimer],
  );

  const arm = useCallback(
    (id: number) => {
      // Already armed (or paused then re-armed in this tick): leave it alone.
      if (timersRef.current.has(id)) return;
      const ms = remainingRef.current.get(id) ?? TOAST_TTL_MS;
      if (ms <= 0) {
        dismiss(id);
        return;
      }
      deadlinesRef.current.set(id, Date.now() + ms);
      timersRef.current.set(id, setTimeout(() => dismiss(id), ms));
    },
    [dismiss],
  );

  const pauseOne = useCallback((id: number) => {
    const timer = timersRef.current.get(id);
    if (timer === undefined) return; // not armed: nothing to pause
    clearTimeout(timer);
    timersRef.current.delete(id);
    const deadline = deadlinesRef.current.get(id);
    deadlinesRef.current.delete(id);
    if (deadline !== undefined) {
      remainingRef.current.set(id, Math.max(0, deadline - Date.now()));
    }
  }, []);

  const pauseAll = useCallback(() => {
    hoverPausedRef.current = true;
    toastsRef.current.forEach((t) => pauseOne(t.id));
  }, [pauseOne]);

  const resumeAll = useCallback(() => {
    hoverPausedRef.current = false;
    toastsRef.current.forEach((t) => {
      if (t.id !== focusedToastRef.current) arm(t.id);
    });
  }, [arm]);

  const onFocusCapture = (e: FocusEvent<HTMLDivElement>) => {
    const id = toastIdFromTarget(e.target);
    if (id === null) return;
    focusedToastRef.current = id;
    pauseOne(id);
  };

  const onBlurCapture = (e: FocusEvent<HTMLDivElement>) => {
    const id = toastIdFromTarget(e.target);
    if (id === null || focusedToastRef.current !== id) return;
    focusedToastRef.current = null;
    // Moving focus to another control inside the same toast keeps it paused.
    const toastEl = e.target instanceof Element ? e.target.closest('[data-toast-id]') : null;
    const next = e.relatedTarget;
    if (next instanceof Node && toastEl?.contains(next)) return;
    if (!hoverPausedRef.current) arm(id);
  };

  // Clear every pending dismiss timer on unmount only. Timers must NOT be
  // cleared when a new event arrives — cancelling them froze the toast stack
  // on screen (regression test: "dismisses each toast on its own schedule").
  useEffect(() => {
    const timers = timersRef.current;
    return () => {
      timers.forEach((t) => clearTimeout(t));
      timers.clear();
    };
  }, []);

  // Consume every frame via subscribe: run lifecycle frames arrive in bursts
  // (run.started / kanban.* / run.completed within milliseconds) and reading
  // only `lastMessage` loses the toast-worthy frame whenever React coalesces
  // the burst into one render (regression test: "run lifecycle arrives as one
  // burst").
  useEffect(() => {
    const sub = subscribe((event) => {
      if (!TOAST_WORTHY.has(event.type)) return;
      try {
        const id = ++toastId;
        let item: ToastItem;

        if (event.type === 'aria.notification') {
          const notifId = event.payload?.id as string | undefined;
          if (notifId && shownIds.current.has(notifId)) return;
          if (notifId) shownIds.current.add(notifId);

          const title = (event.payload?.title as string) || 'Notification';
          // The fine-grained notification type (run.completed, report.generated,
          // ...) lives in the payload; payload.resourceType is only a coarse
          // category (RUN/APPROVAL/KNOWLEDGE/REPORT) and has no route mapping.
          const notifType = event.payload?.type as string | undefined;
          const route = routeForNotificationType(notifType);
          item = {
            id,
            message: title,
            type: 'aria.notification',
            // No View at all when there is nowhere to go: a visible control
            // whose click does nothing is worse than no control.
            action: route ? { label: 'View', onClick: () => navigate(route) } : undefined,
          };
        } else {
          // Human-readable label only — never expose the raw event type.
          const route = routeForNotificationType(event.type);
          item = {
            id,
            message: toastMessage(event.type, event.payload),
            type: event.type,
            action: route ? { label: 'View', onClick: () => navigate(route) } : undefined,
          };
        }

        const next = [...toastsRef.current, item];
        const evicted: number[] = [];
        while (next.length > TOAST_CAP) {
          const idx = next.findIndex((t) => {
            const el = document.querySelector(`[data-toast-id="${t.id}"]`);
            // A toast that is not rendered yet cannot hold focus.
            return !el || !el.contains(document.activeElement);
          });
          if (idx === -1) break;
          const [removed] = next.splice(idx, 1);
          evicted.push(removed.id);
        }
        toastsRef.current = next;
        setToasts(next);
        evicted.forEach((evictedId) => {
          clearToastTimer(evictedId);
          remainingRef.current.delete(evictedId);
        });

        remainingRef.current.set(id, TOAST_TTL_MS);
        if (!hoverPausedRef.current) arm(id);
      } catch {
        // Silently ignore malformed events
      }
    });
    return () => sub.unsubscribe();
  }, [subscribe, navigate, arm, clearToastTimer]);

  if (toasts.length === 0) return null;

  return (
    <div
      className="toast-container"
      role="status"
      aria-live="polite"
      onMouseEnter={pauseAll}
      onMouseLeave={resumeAll}
      onFocusCapture={onFocusCapture}
      onBlurCapture={onBlurCapture}
    >
      {toasts.map((toast) => (
        <div key={toast.id} className="toast-item" data-toast-id={toast.id}>
          <span className="toast-message">{toast.message}</span>
          {toast.action && (
            <button type="button" className="btn" onClick={toast.action.onClick}>
              {toast.action.label}
            </button>
          )}
        </div>
      ))}
    </div>
  );
}
