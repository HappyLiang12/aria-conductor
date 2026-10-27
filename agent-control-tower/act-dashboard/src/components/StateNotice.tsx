import type { ReactElement } from 'react';
import '../styles/state-notice.css';

/**
 * One inline notice for loading, successful empty, error, stale and partial
 * data. Errors are alerts; every other phase is a polite status.
 */
export type StateNoticeKind = 'loading' | 'empty' | 'error' | 'stale' | 'partial';

export interface StateNoticeProps {
  kind: StateNoticeKind;
  title: string;
  message?: string;
  actionLabel?: string;
  onAction?: () => void;
}

export function StateNotice({
  kind,
  title,
  message,
  actionLabel,
  onAction,
}: StateNoticeProps): ReactElement {
  const role = kind === 'error' ? 'alert' : 'status';
  return (
    <div className={`state-notice state-notice--${kind}`} role={role} data-testid={`state-notice-${kind}`}>
      <strong>{title}</strong>
      {message && <p>{message}</p>}
      {actionLabel && onAction && (
        <button type="button" className="btn" onClick={onAction}>
          {actionLabel}
        </button>
      )}
    </div>
  );
}

export default StateNotice;
