import { useState, useEffect, useRef } from 'react';
import { useWebSocketContext } from './Layout';
import { getUnreadCount, listNotifications, markRead, markAllRead } from '../api/ariaNotifications';
import type { Notification } from '../types';
import { useNavigate } from 'react-router-dom';
import { formatTimestamp } from '../utils/formatTime';

export function NotificationBell() {
  const [unreadCount, setUnreadCount] = useState(0);
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const { lastMessage } = useWebSocketContext();
  const wrapperRef = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();

  // Fetch initial unread count on mount
  useEffect(() => {
    getUnreadCount().then(c => setUnreadCount(c.unreadCount)).catch(() => {});
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
      setError(false);
      listNotifications(0, 20)
        .then(data => setNotifications(data.content))
        .catch(() => setError(true))
        .finally(() => setLoading(false));
    }
  };

  const handleMarkRead = async (id: string) => {
    try {
      await markRead(id);
      setUnreadCount(prev => Math.max(0, prev - 1));
      setNotifications(prev =>
        prev.map(n => n.id === id ? { ...n, isRead: true } : n)
      );
    } catch { /* silent */ }
  };

  const handleMarkAllRead = async () => {
    try {
      await markAllRead();
      setUnreadCount(0);
      setNotifications(prev => prev.map(n => ({ ...n, isRead: true })));
    } catch { /* silent */ }
  };

  const getNavRoute = (notification: Notification): string | null => {
    if (!notification.resourceType) return null;
    const rt = notification.resourceType;
    if (rt === 'run.completed' || rt === 'run.failed') return '/runs';
    if (rt === 'approval.requested') return '/approvals';
    if (rt === 'knowledge.submitted') return '/knowledge';
    if (rt === 'report.generated') return '/reports';
    return null;
  };

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

  const typeEmoji: Record<string, string> = {
    'run.completed': '✅',
    'run.failed': '❌',
    'approval.requested': '✋',
    'knowledge.submitted': '📝',
    'report.generated': '📊',
    'reminder': '⏰',
    'monitor': '👁',
    'brief': '📋',
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
            {loading ? (
              <div className="notif-empty">Loading…</div>
            ) : error ? (
              <div className="notif-empty" style={{ color: 'var(--red)' }}>Failed to load notifications</div>
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
