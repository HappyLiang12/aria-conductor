import { useEffect, useRef, useState } from 'react';
import type { WsEvent } from '../types';
import { useWebSocket } from '../hooks/useWebSocket';
import { eventLabel } from '../utils/eventLabels';

interface ToastItem {
  id: number;
  message: string;
  type: string;
  action?: { label: string; onClick: () => void };
}

let toastId = 0;

export function Toast({ lastEvent }: { lastEvent: WsEvent | null }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const wsEvent = useWebSocket();
  const shownIds = useRef(new Set<string>());

  const eventToUse = lastEvent || wsEvent.lastMessage;

  useEffect(() => {
    if (!eventToUse) return;
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

        const timer = setTimeout(() => {
          setToasts((prev) => prev.filter((t) => t.id !== id));
        }, 5000);

        return () => clearTimeout(timer);
      }

      // Default handling for other event types — use human-readable label, not raw JSON dump
      const label = eventLabel(eventToUse.type || 'event');
      const message = `[${label}]`;
      setToasts((prev) => [...prev.slice(-4), { id, message, type: eventToUse.type || 'event' }]);

      const timer = setTimeout(() => {
        setToasts((prev) => prev.filter((t) => t.id !== id));
      }, 5000);

      return () => clearTimeout(timer);
    } catch {
      // Silently ignore malformed events
    }
  }, [eventToUse]);

  if (toasts.length === 0) return null;

  return (
    <div className="toast-container">
      {toasts.map((toast) => (
        <div key={toast.id} className="toast-item">
          <span className="toast-badge">{toast.type}</span>
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