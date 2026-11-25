import type { ReactElement } from 'react';
import { Alert, AlertAction, AlertDescription, AlertTitle } from './ui/alert';
import { Button } from './ui/button';

/**
 * One inline notice for loading, successful empty, error, stale and partial
 * data. Errors are alerts; every other phase is a polite status.
 *
 * Wave 2 F1: the notice is the shadcn `Alert` token surface. The legacy
 * `state-notice*` class hooks stay on the element because page/E2E selectors
 * and the `state-notice-<kind>` test ids are part of the compatibility shim.
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
    <Alert
      variant={kind === 'error' ? 'destructive' : 'default'}
      role={role}
      className={`state-notice state-notice--${kind}`}
      data-testid={`state-notice-${kind}`}
    >
      <AlertTitle className="text-sm font-semibold">{title}</AlertTitle>
      {message && (
        <AlertDescription className="min-w-0 text-sm break-words">
          {message}
        </AlertDescription>
      )}
      {actionLabel && onAction && (
        <AlertAction>
          <Button type="button" variant="outline" size="sm" onClick={onAction}>
            {actionLabel}
          </Button>
        </AlertAction>
      )}
    </Alert>
  );
}

export default StateNotice;
