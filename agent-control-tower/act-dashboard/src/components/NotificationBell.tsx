import { useState, useEffect, useRef } from 'react';
import { useWebSocketContext } from './Layout';
import { getUnreadCount, listNotifications, markRead, markAllRead } from '../api/ariaNotifications';
import { composeSynthesis } from '../api/ariaConversations';
import { apiErrorMessage } from '../api/operatorSession';
import type { Notification } from '../types';
import { useNavigate } from 'react-router-dom';
import { formatTimestamp } from '../utils/formatTime';
import { routeForNotificationType } from '../utils/notificationRoutes';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { Card } from './ui/card';

/**
 * Browser-only notification centre: unread count on the bell, a token Card
 * dropdown listing the feed, and manual mark-read. No OS notification, no
 * auto-close, no countdown — the operator closes it.
 *
 * Wave 2 F2: the bell, count and dropdown are token surfaces (`Badge`, `Card`,
 * `Button`). The `notif-*` hooks, the accessible names and every handler stay
 * unchanged for the E2E specs.
 */
export function NotificationBell() {
  const [unreadCount, setUnreadCount] = useState(0);
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [listError, setListError] = useState<string | null>(null);
  const [countError, setCountError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const { lastMessage } = useWebSocketContext();
  const wrapperRef = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();

  // Fetch initial unread count on mount
  useEffect(() => {
    getUnreadCount()
      .then(c => {
        setUnreadCount(c.unreadCount);
        setCountError(null);
      })
      .catch((err) => setCountError(apiErrorMessage(err, 'The unread notification count could not be loaded.')));
  }, []);

  // Listen for aria.notification WebSocket events
  useEffect(() => {
    if (!lastMessage || lastMessage.type !== 'aria.notification') return;
    setUnreadCount(prev => prev + 1);
  }, [lastMessage]);

  const toggleDropdown = () => {
    setOpen(prev => !prev);
    if (!open) {
      setLoading(true);
      setListError(null);
      listNotifications(0, 20)
        .then(data => setNotifications(data.content))
        .catch((err) => setListError(apiErrorMessage(err, 'Notifications could not be loaded.')))
        .finally(() => setLoading(false));
    }
  };

  const handleMarkRead = async (id: string) => {
    try {
      await markRead(id);
      setActionError(null);
      setUnreadCount(prev => Math.max(0, prev - 1));
      setNotifications(prev =>
        prev.map(n => n.id === id ? { ...n, isRead: true } : n)
      );
    } catch (err) {
      setActionError(apiErrorMessage(err, 'The notification could not be marked read.'));
    }
  };

  const handleMarkAllRead = async () => {
    try {
      await markAllRead();
      setActionError(null);
      setUnreadCount(0);
      setNotifications(prev => prev.map(n => ({ ...n, isRead: true })));
    } catch (err) {
      setActionError(apiErrorMessage(err, 'Notifications could not be marked read.'));
    }
  };

  const getNavRoute = (notification: Notification): string | null =>
    routeForNotificationType(notification.type);

  const handleItemClick = async (notification: Notification) => {
    if (!notification.isRead) {
      await handleMarkRead(notification.id);
    }
    const route = getNavRoute(notification);
    if (route) {
      setOpen(false);
      navigate(route);
    }
  };

  /**
   * One-click synthesis (Feature B2): a `run.batch.completed` notification carries
   * the dispatching conversation in `resourceId`. The backend composes the prompt,
   * the row is marked read, and the prompt is handed to AriaPanel through the
   * `aria:compose` window event (the panel owns the SSE path). Never navigates —
   * the stopPropagation keeps the row's own click handler out of this press.
   */
  const handleComposeClick = async (
    e: React.MouseEvent<HTMLButtonElement>,
    notification: Notification,
  ) => {
    e.stopPropagation();
    if (!notification.resourceId) return;
    try {
      const { prompt } = await composeSynthesis(notification.resourceId);
      if (!notification.isRead) {
        // handleMarkRead owns its failure notice; clearing it here would hide a
        // mark-read rejection every time the compose call itself succeeds.
        await handleMarkRead(notification.id);
      } else {
        setActionError(null);
      }
      window.dispatchEvent(new CustomEvent('aria:compose', { detail: { prompt } }));
    } catch (err) {
      setActionError(apiErrorMessage(err, 'The synthesis prompt could not be composed.'));
    }
  };

  const typeEmoji: Record<string, string> = {
    'run.completed': '✅',
    'run.failed': '❌',
    'approval.requested': '✋',
    'approval.expired': '⌛',
    'knowledge.submitted': '📝',
    'report.generated': '📊',
    'reminder': '⏰',
    'monitor': '👁',
    'brief': '📋',
    'run.batch.completed': '📦',
  };

  const fmtTime = (iso: string): string => {
    const d = new Date(iso);
    if (isNaN(d.getTime())) return iso; // c2: guard malformed timestamps
    const now = new Date();
    const diffMs = Math.max(0, now.getTime() - d.getTime()); // c2: clamp future/negative diffs
    const diffMin = Math.floor(diffMs / 60000);
    if (diffMin < 1) return 'just now';
    if (diffMin < 60) return `${diffMin}m ago`;
    const diffHr = Math.floor(diffMin / 60);
    if (diffHr < 24) return `${diffHr}h ago`;
    return formatTimestamp(iso);
  };

  // Click-outside close
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    if (open) document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  return (
    <div className="notif-bell-wrapper relative inline-flex" ref={wrapperRef}>
      <Button
        type="button"
        variant="ghost"
        size="icon"
        className="notif-bell-btn relative text-foreground"
        onClick={toggleDropdown}
        aria-label={`Notifications${unreadCount > 0 ? ` (${unreadCount} unread)` : ''}`}
      >
        🔔
        {unreadCount > 0 && (
          <Badge variant="destructive" className="notif-badge">
            {unreadCount > 99 ? '99+' : unreadCount}
          </Badge>
        )}
        {countError && (
          // The failure notice otherwise lives inside the closed dropdown, so the
          // bell would keep looking healthy with no count at all.
          <span className="notif-error-dot" role="img" aria-label={countError} title={countError}>!</span>
        )}
      </Button>

      {open && (
        <Card className="notif-dropdown absolute top-full right-0 z-[1000] mt-2 w-[360px] max-h-[480px] gap-0 py-0 ring-foreground/15">
          <div className="notif-dropdown-header flex items-center justify-between border-b border-border px-4 py-3">
            <span className="notif-dropdown-title text-sm font-semibold text-foreground">
              Notifications
            </span>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="notif-mark-all-btn"
              disabled={unreadCount === 0}
              onClick={handleMarkAllRead}
            >
              Mark all read
            </Button>
          </div>
          <div className="notif-dropdown-list max-h-[400px] overflow-y-auto" aria-live="polite">
            {countError && (
              <div className="notif-empty p-8 text-center text-sm text-destructive" role="alert">
                {countError}
              </div>
            )}
            {actionError && (
              <div className="notif-empty p-8 text-center text-sm text-destructive" role="alert">
                {actionError}
              </div>
            )}
            {loading ? (
              <div className="notif-empty p-8 text-center text-sm text-muted-foreground">Loading…</div>
            ) : listError ? (
              <div className="notif-empty p-8 text-center text-sm text-destructive" role="alert">{listError}</div>
            ) : notifications.length === 0 ? (
              <div className="notif-empty p-8 text-center text-sm text-muted-foreground">No notifications yet</div>
            ) : (
              notifications.map(n => (
                <div
                  key={n.id}
                  className={`notif-item flex cursor-pointer items-start gap-2.5 border-b border-border px-4 py-2.5 transition-colors hover:bg-muted/50${n.isRead ? '' : ' notif-item--unread bg-muted/30'}`}
                  onClick={() => handleItemClick(n)}
                  role="button"
                  tabIndex={0}
                  onKeyDown={e => { if (e.key === 'Enter') handleItemClick(n); }}
                >
                  <span className="notif-item-emoji mt-0.5 shrink-0 text-lg">
                    {typeEmoji[n.type] || '🔔'}
                  </span>
                  <div className="notif-item-content min-w-0 flex-1">
                    <div className="notif-item-title text-sm leading-snug text-foreground">{n.title}</div>
                    {n.type === 'run.batch.completed' && (
                      <Button
                        type="button"
                        variant="outline"
                        size="xs"
                        className="notif-mark-all-btn mt-1"
                        onClick={(e) => void handleComposeClick(e, n)}
                      >
                        彙整
                      </Button>
                    )}
                    {n.body && (
                      <div className="mt-0.5 text-xs text-muted-foreground">{n.body.slice(0, 100)}</div>
                    )}
                    <div className="notif-item-time mt-0.5 text-[0.72rem] text-muted-foreground">
                      {fmtTime(n.createdAt)}
                    </div>
                  </div>
                </div>
              ))
            )}
          </div>
        </Card>
      )}
    </div>
  );
}

export default NotificationBell;
