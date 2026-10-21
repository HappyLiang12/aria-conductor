import { useState, useEffect, useRef } from 'react';
import { useWebSocketContext } from './Layout';
import { getUnreadCount, listNotifications, markRead, markAllRead } from '../api/ariaNotifications';
import { composeSynthesis } from '../api/ariaConversations';
import { apiErrorMessage } from '../api/operatorSession';
import type { Notification } from '../types';
import { useNavigate } from 'react-router-dom';
import { formatTimestamp } from '../utils/formatTime';
import { routeForNotificationType } from '../utils/notificationRoutes';

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
    <div className="notif-bell-wrapper" ref={wrapperRef}>
      <button
        type="button"
        className="notif-bell-btn"
        onClick={toggleDropdown}
        aria-label={`Notifications${unreadCount > 0 ? ` (${unreadCount} unread)` : ''}`}
      >
        🔔
        {unreadCount > 0 && <span className="notif-badge">{unreadCount > 99 ? '99+' : unreadCount}</span>}
        {countError && (
          // The failure notice otherwise lives inside the closed dropdown, so the
          // bell would keep looking healthy with no count at all.
          <span className="notif-error-dot" role="img" aria-label={countError} title={countError}>!</span>
        )}
      </button>

      {open && (
        <div className="notif-dropdown">
          <div className="notif-dropdown-header">
            <span className="notif-dropdown-title">Notifications</span>
            <button
              type="button"
              className="notif-mark-all-btn"
              disabled={unreadCount === 0}
              onClick={handleMarkAllRead}
            >
              Mark all read
            </button>
          </div>
          <div className="notif-dropdown-list" aria-live="polite">
            {countError && (
              <div className="notif-empty" style={{ color: 'var(--red)' }} role="alert">
                {countError}
              </div>
            )}
            {actionError && (
              <div className="notif-empty" style={{ color: 'var(--red)' }} role="alert">
                {actionError}
              </div>
            )}
            {loading ? (
              <div className="notif-empty">Loading…</div>
            ) : listError ? (
              <div className="notif-empty" style={{ color: 'var(--red)' }} role="alert">{listError}</div>
            ) : notifications.length === 0 ? (
              <div className="notif-empty">No notifications yet</div>
            ) : (
              notifications.map(n => (
                <div
                  key={n.id}
                  className={`notif-item${n.isRead ? '' : ' notif-item--unread'}`}
                  onClick={() => handleItemClick(n)}
                  role="button"
                  tabIndex={0}
                  onKeyDown={e => { if (e.key === 'Enter') handleItemClick(n); }}
                >
                  <span className="notif-item-emoji">{typeEmoji[n.type] || '🔔'}</span>
                  <div className="notif-item-content">
                    <div className="notif-item-title">{n.title}</div>
                    {n.type === 'run.batch.completed' && (
                      <button
                        type="button"
                        className="notif-mark-all-btn"
                        onClick={(e) => void handleComposeClick(e, n)}
                      >
                        彙整
                      </button>
                    )}
                    {n.body && <div style={{ fontSize: '0.78rem', color: 'var(--text-mute)', marginTop: 2 }}>{n.body.slice(0, 100)}</div>}
                    <div className="notif-item-time">{fmtTime(n.createdAt)}</div>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
}

export default NotificationBell;
