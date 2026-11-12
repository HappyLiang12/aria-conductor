import { apiErrorMessage, isCrossOriginRejection, isOperatorRejection } from './operatorSession';

export type ConfigureTab = 'llm' | 'runtime' | 'config';

/**
 * The repair surfaces the backend error contract can serve. This tuple mirrors
 * io.aria.conductor.common.exception.ErrorSurface; errorSurfaceContract.test.ts
 * reads the Java enum and fails when the two drift.
 */
export const ERROR_SURFACES = ['LLM_PROVIDERS', 'RUNTIME', 'SYSTEM_CONFIG', 'CREW'] as const;

/** Stable repair surfaces served by the backend error contract. */
export type ErrorSurface = (typeof ERROR_SURFACES)[number];

/** The operator's next step shown beside an error. */
export type ErrorAction =
  | { tab: ConfigureTab; label: string }
  | { path: string; label: string };

export interface PresentedError {
  text: string;
  action?: ErrorAction;
  /**
   * The plain-language cause and the fix, when the failure is one the server
   * text cannot name (T54 F3). Today only a credential the core rejected: the
   * platform reports it as a control-secret transport error, and the actionable
   * cause lives in a run-owned log. Render it beside `text`, never instead of
   * it — the operator may need to quote the original.
   */
  remedy?: string;
  /** The backend's stable failure identity, when it served one. */
  code?: string;
  /**
   * The route refused the call for want of the local operator (401/403). A
   * caller whose action is operator-only renders the operator-session notice
   * for this instead of `text`, which on a refusal points at nothing. Set only
   * when the server refused, so every other presentation shape is unchanged.
   */
  operatorRejected?: true;
  /**
   * The 403 carried the cross-origin marker: the request came from a page the
   * backend does not trust, which is not an authority problem. Callers that
   * render the operator-session notice render the cross-site remedy for this
   * instead. Absent (never false) on every other presentation.
   */
  crossOriginRejected?: true;
}

const LLM_ACTION: ErrorAction = { tab: 'llm', label: 'Open Configure → LLM Providers' };
const RUNTIME_ACTION: ErrorAction = { tab: 'runtime', label: 'Open Configure → Runtime' };
const CONFIG_ACTION: ErrorAction = { tab: 'config', label: 'Open Configure → System Config' };
const CREW_ACTION: ErrorAction = { path: '/crew', label: 'Open Crew' };

/** Opens the Configure modal on a tab; ConfigureModal listens for this event. */
export function openConfigure(tab: ConfigureTab): void {
  window.dispatchEvent(new CustomEvent('act:open-configure', { detail: { tab } }));
}

/**
 * The classification the backend puts at the FRONT of a failed run's message
 * when the core rejected the saved credential (T53 F3, `CoreCredentialService.
 * CREDENTIAL_REJECTED_MARKER`): `E_CREDENTIAL_REJECTED`. It is first on purpose,
 * because the Aria failed-turn reason is clipped to its first 300 characters
 * (`RunFailureReason.MAX_CHARS`).
 *
 * The same message keeps the phrase "Qoder runtime credential", so the shipped
 * `errorActionFor` already routes it to LLM_PROVIDERS — there is NO second
 * router here. This predicate only decides whether the failure also earns the
 * plain-language remedy below.
 *
 * Matched case-insensitively and anywhere in the text, not at index 0: the
 * panel prefixes its own label to the reason live ("⚠ 上一回合失敗：…") and the
 * timeline prefixes the stored entry ("回合執行失敗：…"), so the marker is at the
 * front of the REASON, not of the rendered string.
 */
const CREDENTIAL_REJECTED_MARKER = 'e_credential_rejected';

/**
 * True when the backend classified this failure as a rejected credential.
 * A transport failure recorded before that classification carries no marker, so
 * it is NOT diagnosed as a credential problem — a claim needs evidence.
 */
export function isCredentialRejection(text: string | null | undefined): boolean {
  if (!text) return false;
  return text.toLowerCase().includes(CREDENTIAL_REJECTED_MARKER);
}

/**
 * What a credential rejection means and what fixes it, or null for any other
 * failure. Names the place (Configure → LLM Providers → Qoder runtime
 * credential) and the action (save a token the core accepts, re-dispatch the
 * run) because the platform's transport wording names neither.
 */
export function credentialRejectionRemedy(text: string | null | undefined): string | null {
  if (!isCredentialRejection(text)) return null;
  return 'The saved Qoder credential was rejected: the Qoder core refused it at session/new, so '
    + 'the platform could not open the run\'s control connection. This is a credential problem, '
    + 'not a network fault. Fix it in Configure → LLM Providers → Qoder runtime credential: save '
    + 'a Qoder personal access token the qoder CLI accepts, then dispatch the run again. Saving '
    + 'cannot validate the token in this build, so a rejected token is only caught when a run '
    + 'starts.';
}

/**
 * The action for a backend-served surface. This is the primary mapping: it does
 * not depend on the wording of the message.
 */
export function errorActionForSurface(surface: string | null | undefined): ErrorAction | undefined {
  switch (surface) {
    case 'LLM_PROVIDERS':
      return LLM_ACTION;
    case 'RUNTIME':
      return RUNTIME_ACTION;
    case 'SYSTEM_CONFIG':
      return CONFIG_ACTION;
    case 'CREW':
      return CREW_ACTION;
    default:
      return undefined;
  }
}

/**
 * Legacy text matching for failures that predate the surface contract (stored
 * runs, third-party responses). New failures carry `surface`; this only keeps
 * old records actionable and must never override a served surface.
 */
export function errorActionFor(text: string): ErrorAction | undefined {
  const normalized = text.toLowerCase();
  // A rejected credential lands on the surface that stores it, through the
  // phrase the classification keeps ("Qoder runtime credential", T53 F3). This
  // is the ONE router: `credentialRejectionRemedy` only renders copy.
  if (normalized.includes('qoder runtime credential')
      || normalized.includes('credential is not configured')) {
    return LLM_ACTION;
  }
  if (normalized.includes('fleet runtime is mixed')
      || normalized.includes('runtime mixing is not allowed')
      || normalized.includes('unsupported agent core')
      || normalized.includes('unsupported execution mode')) {
    return RUNTIME_ACTION;
  }
  if (normalized.includes('circuit.breaker.')
      || normalized.includes('token budget exceeded')
      || normalized.includes('max iterations reached')
      || normalized.includes('error rate')
      || normalized.includes('run deadline')) {
    return CONFIG_ACTION;
  }
  if (normalized.includes('workspace settings in crew')
      || normalized.includes('retired agent')
      || normalized.includes('unhealthy agent')) {
    return CREW_ACTION;
  }
  return undefined;
}

/** A run's persisted failure (message + served surface) plus its next step, or null. */
export function presentRunError(
  errorMessage: string | null | undefined,
  surface?: string | null,
  code?: string | null,
): PresentedError | null {
  if (!errorMessage || !errorMessage.trim()) {
    return null;
  }
  const text = errorMessage.trim();
  const remedy = credentialRejectionRemedy(text);
  return {
    text,
    action: errorActionForSurface(surface) ?? errorActionFor(text),
    code: code ?? undefined,
    ...(remedy !== null ? { remedy } : {}),
  };
}

/** An API error (axios shape) mapped to its backend text, surface and next step. */
export function presentError(error: unknown, fallback: string): PresentedError {
  const text = apiErrorMessage(error, fallback);
  const data = (error as { response?: { data?: { surface?: unknown; code?: unknown } } } | undefined)
    ?.response?.data;
  const surface = typeof data?.surface === 'string' ? data.surface : undefined;
  const code = typeof data?.code === 'string' ? data.code : undefined;
  const remedy = credentialRejectionRemedy(text);
  return {
    text,
    action: errorActionForSurface(surface) ?? errorActionFor(text),
    code,
    ...(remedy !== null ? { remedy } : {}),
    // Absent rather than false: a presented error keeps its exact previous shape
    // unless the server actually refused, so no consumer reads a new key.
    ...(isOperatorRejection(error) ? { operatorRejected: true as const } : {}),
    ...(isCrossOriginRejection(error) ? { crossOriginRejected: true as const } : {}),
  };
}

/**
 * The ONE remedy for an operator-only route's 401/403 refusal.
 *
 * The resolver's documented deployment is single-operator localhost, so a
 * loopback request is the operator automatically; a remote operator is refused
 * by design and there is no token entry in the dashboard. A reload cannot
 * change who the server thinks the operator is, so "reload the page and retry"
 * was advice that could not work — the operator reloaded, was refused again,
 * and the notice was indistinguishable from a decoration.
 *
 * Every surface that reports a refusal composes this constant instead of
 * restating the remedy: the shared notice, the approval and clarification
 * cards, the Review panel and the error notice. `errorPresentation.test.ts`
 * pins it as the working remedy and pins both bad phrases out.
 */
export const OPERATOR_REMEDY_COPY = (
  'Open the dashboard on the machine that runs the backend — loopback requests are the operator '
  + 'automatically. From another machine, the operator who runs the backend must grant access with '
  + 'the server\'s operator credential; the dashboard has no token entry. Reloading the page cannot '
  + 'change who the server thinks you are.'
);

/**
 * What an operator reads when an operator-only route refused a named action
 * (401/403). The producer of the shared notice; it builds on
 * `OPERATOR_REMEDY_COPY` so the remedy has exactly one source.
 */
export function operatorSessionMessage(actionLabel: string): string {
  return `${actionLabel} is operator-only, so the server refused it. ${OPERATOR_REMEDY_COPY}`;
}

/**
 * The OTHER refusal the operator routes serve with a 403: the request's Origin
 * named a page the backend does not trust (`CROSS_ORIGIN_REJECTION_MARKER`). It
 * is not an authority problem, so the operator remedy — "the operator who runs
 * the backend must grant access" — answers the wrong question: no grant makes a
 * forged cross-site request trusted. The fix is to use an origin the backend
 * allows, which is what this says. One copy, composed by every refusal surface
 * that distinguishes the two cases.
 */
export const CROSS_SITE_REMEDY_COPY = (
  'The request came from a page whose origin the backend does not trust, so it refused the change '
  + '— this is not a missing operator session. Open the dashboard on the origin the backend trusts '
  + '(the front end it was started with) and make the change there.'
);

/** The cross-site refusal for a named action; builds on `CROSS_SITE_REMEDY_COPY`. */
export function crossSiteSessionMessage(actionLabel: string): string {
  return `${actionLabel} was refused as a cross-site request, not for want of the operator. `
    + CROSS_SITE_REMEDY_COPY;
}
