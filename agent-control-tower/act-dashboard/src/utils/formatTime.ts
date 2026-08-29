/**
 * F7: single source of truth for absolute timestamps across the dashboard.
 * Same calendar day → 24h "HH:mm"; any other day → "YYYY-MM-DD HH:mm".
 * Relative formats ("1m ago") remain only where they already existed
 * (NotificationBell / ReviewQueue / OpsPage) and fall back to this util.
 */
const pad = (n: number): string => String(n).padStart(2, '0');

export function formatTimestamp(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';

  const hh = pad(d.getHours());
  const mm = pad(d.getMinutes());

  const now = new Date();
  const sameDay = d.getFullYear() === now.getFullYear()
    && d.getMonth() === now.getMonth()
    && d.getDate() === now.getDate();
  if (sameDay) return `${hh}:${mm}`;

  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${hh}:${mm}`;
}
