import { useNavigate } from 'react-router-dom';
import {
  openConfigure,
  presentRunError,
  type PresentedError,
} from '../api/errorPresentation';
import { OperatorSessionNotice } from './AriaProposalCard';
import { Alert, AlertAction, AlertDescription, AlertTitle } from './ui/alert';
import { Button } from './ui/button';

/**
 * A path action is the only one that needs router state. It lives in its own
 * component so a notice with a Configure action (a window event) can render
 * outside a Router, e.g. inside a modal test.
 */
function PathActionButton({ action }: { action: { path: string; label: string } }) {
  const navigate = useNavigate();
  return (
    <Button type="button" variant="outline" size="sm" onClick={() => navigate(action.path)}>
      {action.label}
    </Button>
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
 *
 * Task 35: the notice itself (and the remedy in it) lives in
 * `OperatorSessionNotice`, so this component no longer keeps a copy of the
 * wording. The action label still names the refused action in its first
 * sentence, which the existing pins assert.
 *
 * Wave 2 F1: the failure renders on the shadcn `Alert` token surface. The
 * `error-notice`/`error-text`/`error-remedy` hooks and the `error-remedy` test
 * id stay as the compatibility shim.
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
      <OperatorSessionNotice
        actionLabel={actionLabel ?? 'This action'}
        crossOrigin={presented.crossOriginRejected}
      />
    );
  }
  return (
    <Alert
      variant="destructive"
      className={className ? `error-notice ${className}` : 'error-notice'}
    >
      <AlertTitle className="error-text text-sm font-medium break-words">
        {presented.text}
      </AlertTitle>
      {presented.remedy !== undefined && (
        // The cause and the fix, when the server text names neither (T54 F3).
        <AlertDescription
          className="error-remedy text-xs text-muted-foreground"
          data-testid="error-remedy"
        >
          {presented.remedy}
        </AlertDescription>
      )}
      {action && (
        <AlertAction>
          {('tab' in action
            ? (
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => openConfigure(action.tab)}
              >
                {action.label}
              </Button>
            )
            : <PathActionButton action={action} />)}
        </AlertAction>
      )}
    </Alert>
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
