import { useEffect, useRef, useState } from 'react';
import { useWebSocketContext } from './Layout';
import { eventLabel } from '../utils/eventLabels';

interface ToastItem {
  id: number;
  message: string;
  type: string;
  action?: { label: string; onClick: () => void };
}

let toastId = 0;

// Only user-relevant events surface as toasts; internal lifecycle noise
// (run.started, kanban.*, run.iteration, ...) updates data silently.
// Mirrors the event types actually broadcast by EventBroadcastListener.
const TOAST_WORTHY = new Set([
  'run.completed',
  'approval.requested',
  'approval.decided',
  'knowledge.submitted',
  'knowledge.approved',
  'knowledge.retired',
  'report.generated',
  'report.amended',
  'aria.notification',
]);

// Failures arrive as run.completed with payload.status — derive a truthful label.
function toastMessage(type: string, payload: Record<string, unknown> | undefined): string {
  const status = payload?.status as string | undefined;
  if (type === 'run.completed' && status && status !== 'COMPLETED') {
    return `Run ${status.charAt(0)}${status.slice(1).toLowerCase()}`;
  }
  return eventLabel(type);
}

export function Toast() {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const wsEvent = useWebSocketContext();
  const shownIds = useRef(new Set<string>());
  const timersRef = useRef(new Map<number, ReturnType<typeof setTimeout>>());

  const eventToUse = wsEvent.lastMessage;

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

  useEffect(() => {
    if (!eventToUse) return;
    if (!TOAST_WORTHY.has(eventToUse.type)) return;
    try {
      const id = ++toastId;

      // Handle aria.notification events
      if (eventToUse.type === 'aria.notification') {
        const notifId = eventToUse.payload?.id as string | undefined;
        if (notifId && shownIds.current.has(notifId)) return;
        if (notifId) shownIds.current.add(notifId);

        const title = (eventToUse.payload?.title as string) || 'Notification';
        const notifToast: ToastItem = {
          id,
          message: title,
          type: 'aria.notification',
          action: { label: 'View', onClick: () => console.log('View notification', notifId) },
        };
        setToasts((prev) => [...prev.slice(-4), notifToast]);
      } else {
        // Human-readable label only — never expose the raw event type.
        const message = toastMessage(eventToUse.type, eventToUse.payload);
        setToasts((prev) => [...prev.slice(-4), { id, message, type: eventToUse.type }]);
      }

      const timer = setTimeout(() => {
        setToasts((prev) => prev.filter((t) => t.id !== id));
        timersRef.current.delete(id);
      }, 5000);
      timersRef.current.set(id, timer);
    } catch {
      // Silently ignore malformed events
    }
  }, [eventToUse]);

  if (toasts.length === 0) return null;

  return (
    <div className="toast-container">
      {toasts.map((toast) => (
        <div key={toast.id} className="toast-item">
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
