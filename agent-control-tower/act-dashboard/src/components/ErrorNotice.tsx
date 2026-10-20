import {
  openConfigure,
  presentRunError,
  type PresentedError,
} from '../api/errorPresentation';

/**
 * One error surface for the dashboard: the backend's own text plus, when the
 * failure names a configuration surface, the button that opens it.
 */
export function ErrorNotice({ presented, className }: {
  presented: PresentedError;
  className?: string;
}) {
  return (
    <div
      className={className ? `error-notice ${className}` : 'error-notice'}
      role="alert"
      style={{ display: 'flex', gap: 8, alignItems: 'flex-start', flexWrap: 'wrap' }}
    >
      <span className="error-text" style={{ flex: '1 1 240px', wordBreak: 'break-word' }}>
        {presented.text}
      </span>
      {presented.action && (
        <button
          type="button"
          className="btn"
          onClick={() => openConfigure(presented.action!.tab)}
        >
          {presented.action.label}
        </button>
      )}
    </div>
  );
}

export default ErrorNotice;

/** A run's persisted error message rendered with its next step; null-safe. */
export function RunErrorNotice({ message, className }: {
  message: string | null | undefined;
  className?: string;
}) {
  const presented = presentRunError(message);
  if (!presented) {
    return null;
  }
  return <ErrorNotice presented={presented} className={className} />;
}
