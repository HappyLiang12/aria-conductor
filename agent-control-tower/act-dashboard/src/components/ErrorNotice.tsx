import { useNavigate } from 'react-router-dom';
import {
  openConfigure,
  presentRunError,
  type PresentedError,
} from '../api/errorPresentation';

/**
 * A path action is the only one that needs router state. It lives in its own
 * component so a notice with a Configure action (a window event) can render
 * outside a Router, e.g. inside a modal test.
 */
function PathActionButton({ action }: { action: { path: string; label: string } }) {
  const navigate = useNavigate();
  return (
    <button type="button" className="btn" onClick={() => navigate(action.path)}>
      {action.label}
    </button>
  );
}

/**
 * One error surface for the dashboard: the backend's own text plus, when the
 * failure names a configuration surface, the button that opens it.
 */
export function ErrorNotice({ presented, className }: {
  presented: PresentedError;
  className?: string;
}) {
  const action = presented.action;
  return (
    <div
      className={className ? `error-notice ${className}` : 'error-notice'}
      role="alert"
      style={{ display: 'flex', gap: 8, alignItems: 'flex-start', flexWrap: 'wrap' }}
    >
      <span className="error-text" style={{ flex: '1 1 240px', wordBreak: 'break-word' }}>
        {presented.text}
      </span>
      {action && ('tab' in action
        ? (
          <button type="button" className="btn" onClick={() => openConfigure(action.tab)}>
            {action.label}
          </button>
        )
        : <PathActionButton action={action} />)}
    </div>
  );
}

export default ErrorNotice;

/** A run's persisted failure rendered with its next step; null-safe. */
export function RunErrorNotice({ message, surface, code, className }: {
  message: string | null | undefined;
  surface?: string | null;
  code?: string | null;
  className?: string;
}) {
  const presented = presentRunError(message, surface, code);
  if (!presented) {
    return null;
  }
  return <ErrorNotice presented={presented} className={className} />;
}
