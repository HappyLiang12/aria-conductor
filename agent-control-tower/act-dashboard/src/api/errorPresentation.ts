import { apiErrorMessage } from './operatorSession';

export type ConfigureTab = 'llm' | 'runtime' | 'config';

/** Stable repair surfaces served by the backend error contract. */
export type ErrorSurface = 'LLM_PROVIDERS' | 'RUNTIME' | 'SYSTEM_CONFIG' | 'CREW';

/** The operator's next step shown beside an error. */
export type ErrorAction =
  | { tab: ConfigureTab; label: string }
  | { path: string; label: string };

export interface PresentedError {
  text: string;
  action?: ErrorAction;
  /** The backend's stable failure identity, when it served one. */
  code?: string;
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
  return {
    text,
    action: errorActionForSurface(surface) ?? errorActionFor(text),
    code: code ?? undefined,
  };
}

/** An API error (axios shape) mapped to its backend text, surface and next step. */
export function presentError(error: unknown, fallback: string): PresentedError {
  const text = apiErrorMessage(error, fallback);
  const data = (error as { response?: { data?: { surface?: unknown; code?: unknown } } } | undefined)
    ?.response?.data;
  const surface = typeof data?.surface === 'string' ? data.surface : undefined;
  const code = typeof data?.code === 'string' ? data.code : undefined;
  return {
    text,
    action: errorActionForSurface(surface) ?? errorActionFor(text),
    code,
  };
}
