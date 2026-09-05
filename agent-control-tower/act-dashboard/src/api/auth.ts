// Operator API-token storage and prompting for ARIA API authentication.
// The token is deliberately kept in sessionStorage (never localStorage) so it is
// scoped to the browser tab and cleared when the tab closes.

const TOKEN_STORAGE_KEY = 'aria-api-token';

export function getApiToken(): string {
  try {
    return sessionStorage.getItem(TOKEN_STORAGE_KEY) ?? '';
  } catch {
    return '';
  }
}

export function setApiToken(token: string): void {
  try {
    if (token) {
      sessionStorage.setItem(TOKEN_STORAGE_KEY, token);
    } else {
      sessionStorage.removeItem(TOKEN_STORAGE_KEY);
    }
  } catch {
    // storage unavailable (e.g. tests / privacy mode) — token simply won't persist
  }
}

export function clearApiToken(): void {
  try {
    sessionStorage.removeItem(TOKEN_STORAGE_KEY);
  } catch {
    // no-op
  }
}

/** Adds an `Authorization: Bearer <token>` header when an API token is stored. */
export function withAuthHeaders(headers: Record<string, string> = {}): Record<string, string> {
  const token = getApiToken();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return headers;
}

// Default prompt: a lightweight browser prompt. Overridable for tests/embedding.
let tokenPrompt: () => Promise<string | null> | string | null = () => {
  if (typeof window !== 'undefined' && typeof window.prompt === 'function') {
    return window.prompt('Aria Conductor requires an API token', '');
  }
  return null;
};

export function setTokenPrompt(prompt: () => Promise<string | null> | string | null): void {
  tokenPrompt = prompt;
}

/** Asks the operator for an API token; returns the trimmed value or null if cancelled. */
export async function requestApiTokenFromOperator(): Promise<string | null> {
  const value = await tokenPrompt();
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed || null;
}
