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

export function StatusBadge({ status, size = 'md' }: StatusBadgeProps) {
  const color = statusColors[status] || '#90a4ae';
  return (
    <span
      className={`status-badge status-badge-${size}`}
      style={{ backgroundColor: color + '22', color, borderColor: color }}
    >
      {status}
    </span>
  );
}
