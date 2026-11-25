import { Badge } from './ui/badge';

interface StatusBadgeProps {
  status: string;
  size?: 'sm' | 'md';
}

const statusColors: Record<string, string> = {
  // Agent health
  HEALTHY: '#4caf50',
  DEGRADED: '#ff9800',
  UNHEALTHY: '#f44336',
  RETIRED: '#9e9e9e',
  // Run status
  PENDING: '#90caf9',
  INITIALIZING: '#ce93d8',
  RUNNING: '#4caf50',
  EXECUTING: '#ff9800',
  PAUSED: '#ff9800',
  WAITING_INPUT: '#ffb74d',
  COMPLETED: '#66bb6a',
  FAILED: '#f44336',
  CANCELLED: '#9e9e9e',
  ABORTED: '#f44336',
  // Approval
  APPROVED: '#4caf50',
  DENIED: '#f44336',
  EXPIRED: '#9e9e9e',
  // Knowledge
  DRAFT: '#90caf9',
  REJECTED: '#f44336',
};

/**
 * Run/agent/approval status pill.
 *
 * Wave 2 B3: the shadcn `Badge` token surface. The inline status colour and the
 * `status-badge status-badge-<size>` hooks stay because page/E2E contracts and
 * the badge suite assert them; the token badge supplies the shape after the
 * legacy CSS is removed.
 */
export function StatusBadge({ status, size = 'md' }: StatusBadgeProps) {
  const color = statusColors[status] || '#90a4ae';
  return (
    <Badge
      variant="outline"
      className={`status-badge status-badge-${size} rounded-full border px-2 py-0.5 text-[10.5px] font-semibold`}
      style={{ backgroundColor: color + '22', color, borderColor: color }}
    >
      {status}
    </Badge>
  );
}
