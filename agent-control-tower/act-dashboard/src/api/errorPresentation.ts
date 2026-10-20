import { apiErrorMessage } from './operatorSession';

export type ConfigureTab = 'llm' | 'runtime';

/** The operator's next step shown beside an error. */
export interface ErrorAction {
  tab: ConfigureTab;
  label: string;
}

export interface PresentedError {
  text: string;
  action?: ErrorAction;
}

const LLM_ACTION: ErrorAction = { tab: 'llm', label: 'Open Configure → LLM Providers' };
const RUNTIME_ACTION: ErrorAction = { tab: 'runtime', label: 'Open Configure → Runtime' };

/** Opens the Configure modal on a tab; ConfigureModal listens for this event. */
export function openConfigure(tab: ConfigureTab): void {
  window.dispatchEvent(new CustomEvent('act:open-configure', { detail: { tab } }));
}

/**
 * The next step for a known failure. The match targets the operator-facing
 * messages the backend deliberately writes to name the surface to fix; the
 * backend tests pin those strings. Unknown failures get no action rather than a
 * guessed one.
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
  return undefined;
}

/** A run's persisted error message (raw text) plus its next step, or null when absent. */
export function presentRunError(errorMessage: string | null | undefined): PresentedError | null {
  if (!errorMessage || !errorMessage.trim()) {
    return null;
  }
  const text = errorMessage.trim();
  return { text, action: errorActionFor(text) };
}

/** An API error (axios shape) mapped to its backend text plus its next step. */
export function presentError(error: unknown, fallback: string): PresentedError {
  const text = apiErrorMessage(error, fallback);
  return { text, action: errorActionFor(text) };
}
