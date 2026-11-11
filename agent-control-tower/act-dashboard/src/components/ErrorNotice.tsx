import { useNavigate } from 'react-router-dom';
import {
  openConfigure,
  presentRunError,
  type PresentedError,
} from '../api/errorPresentation';

/**
 * What an operator reads when an operator-only route refused them (401/403).
 *
 * The resolver's documented deployment is a single operator on localhost: a
 * loopback request is the operator automatically, and a remote request has to
 * be granted access by the operator who runs the backend. There is no token
 * entry in this dashboard (`establishOperatorSession` has no caller), so
 * "sign in here" would be a UI that does not exist.
 *
 * The previous wording said "reload the page and retry". A reload does not
 * change who the server thinks the operator is, so that advice could not work:
 * the operator reloaded, was refused again, and the notice was indistinguishable
 * from a decoration. This names the one thing that does confer authority.
 *
 * It lives here, beside its only caller, because `src/api/` belongs to another
 * lane this wave. The phrases the existing pins assert ("<action> is
 * operator-only", "loopback requests are the operator automatically", "the
 * operator who runs the backend must grant access") are unchanged, so those pins
 * stay green unloosened.
 */
function operatorRefusalMessage(actionLabel: string): string {
  return (
    `${actionLabel} is operator-only, so the server refused it. `
    + 'Open the dashboard on the machine that runs the backend — loopback requests are the operator '
    + 'automatically. From another machine, the operator who runs the backend must grant access with '
    + 'the server\'s operator credential; the dashboard has no token entry. Reloading the page cannot '
    + 'change who the server thinks you are, and nothing was changed by the refused call.'
  );
}

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
 *
 * `operatorSession` is for callers whose action is operator-only. A 401/403 then
 * renders the operator-session notice — the same `operator-session-notice` idiom
 * MemoryPage and AriaProposalCard use — instead of the server's text, because on
 * a refusal the server text points at no fix. Any other failure keeps the
 * ordinary path: a 500 is a fault, not an authority problem.
 */
export function ErrorNotice({ presented, className, operatorSession, actionLabel }: {
  presented: PresentedError;
  className?: string;
  operatorSession?: boolean;
  /** The refused action, named in the notice's first sentence. */
  actionLabel?: string;
}) {
  const action = presented.action;
  if (operatorSession && presented.operatorRejected) {
    return (
      <div className="ask-ctx" data-testid="operator-session-notice">
        {operatorRefusalMessage(actionLabel ?? 'This action')}
      </div>
    );
  }
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
