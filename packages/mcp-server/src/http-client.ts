/**
 * Lightweight HTTP client for ACT backend REST APIs.
 * Configurable via ACT_BASE_URL env var (default: http://localhost:8080).
 * ACT_ACTOR_TOKEN carries the run-scoped worker token this process acts with;
 * it is sent as a Bearer credential and never appended to a URL.
 */

const BASE_URL = process.env.ACT_BASE_URL?.replace(/\/$/, '') || 'http://localhost:8080';

/**
 * Run-scoped worker credential for the run this MCP server serves. Only the
 * worker token belongs here — never the operator credential or a core secret.
 */
const ACTOR_TOKEN = process.env.ACT_ACTOR_TOKEN?.trim() || '';

export interface ApiError {
  status: number;
  message: string;
  details?: unknown;
}

export class ActHttpError extends Error {
  constructor(public readonly status: number, message: string, public readonly details?: unknown) {
    super(`ACT API Error [${status}]: ${message}`);
    this.name = 'ActHttpError';
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const url = `${BASE_URL}${path}`;
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (ACTOR_TOKEN) {
    headers['Authorization'] = `Bearer ${ACTOR_TOKEN}`;
  }

  const res = await fetch(url, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  if (!res.ok) {
    let details: unknown;
    try { details = await res.json(); } catch { /* ignore */ }
    throw new ActHttpError(res.status, res.statusText, details);
  }

  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export const http = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body),
  patch: <T>(path: string, body?: unknown) => request<T>('PATCH', path, body),
  delete: <T>(path: string) => request<T>('DELETE', path),
};

/** Helper: build query string from optional params, skipping undefined values. */
export function qs(params: Record<string, string | number | boolean | undefined>): string {
  const entries = Object.entries(params).filter(([, v]) => v !== undefined);
  if (entries.length === 0) return '';
  return '?' + new URLSearchParams(entries.map(([k, v]) => [k, String(v)] as [string, string])).toString();
}

/** Format any value as MCP tool result text. */
export function toJsonResult(data: unknown): { content: Array<{ type: 'text'; text: string }> } {
  const text = data === undefined || data === null ? '' : typeof data === 'string' ? data : JSON.stringify(data, null, 2);
  return { content: [{ type: 'text', text }] };
}
