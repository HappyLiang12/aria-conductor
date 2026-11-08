import client from './client';
import type { AriaMessage } from '../types';

export interface AriaChatResponse {
  message: string;
  runId: string;
  conversationId: string;
  intent: string;
  actionsTaken: Array<{ type: string; description: string; result: string }>;
  timestamp: string;
  /**
   * F2: the underlying run's status, served by the api lane. Absent on an older
   * backend — treated as "not reported", never as completion.
   */
  status?: string | null;
  /** The run's failure detail when it ended non-successfully (backend field). */
  errorMessage?: string | null;
  /** Legacy alias kept for older payloads; `errorMessage` wins. */
  error?: string | null;
  /** Stable failure identity (e.g. RUNTIME_CREDENTIAL_NOT_CONFIGURED). */
  errorCode?: string | null;
}

/** Run statuses after which the turn is over. */
const TERMINAL_RUN_STATUS = new Set(['COMPLETED', 'FAILED', 'CANCELLED', 'ABORTED']);
/** Terminal statuses that did not succeed. */
const FAILED_RUN_STATUS = new Set(['FAILED', 'CANCELLED', 'ABORTED']);

function runRef(runId: string | null | undefined): string | null {
  if (typeof runId !== 'string' || !runId.trim()) return null;
  return `Run ${runId.trim().slice(0, 8)}`;
}

/**
 * F2 (SDD hardening): the sync chat's assistant copy, aligned with the run's
 * status instead of the backend's prose.
 *
 * The backend used to answer "I've completed your request…" while the run was
 * still RUNNING, and to say nothing useful when it failed. The api lane adds
 * `status`/`errorMessage`; this function never repeats a completion claim the
 * status does not support:
 * - FAILED/CANCELLED/ABORTED → the failure, its run, and where to look;
 * - a non-terminal status → "still <status>", with the run id;
 * - COMPLETED → the backend answer plus the run citation (the only case that
 *   may claim completion);
 * - an absent/unknown status → the answer plus an explicit "not reported"
 *   caveat (an older backend is not evidence of completion).
 */
export function buildSyncChatContent(response: AriaChatResponse): string {
  const run = runRef(response.runId);
  const status = typeof response.status === 'string' ? response.status.trim().toUpperCase() : '';
  const backendMessage = response.message?.trim() || '';

  if (status && FAILED_RUN_STATUS.has(status)) {
    const reason = response.errorMessage?.trim()
      || response.error?.trim()
      || response.errorCode?.trim()
      || null;
    return [
      `⚠ ${run ?? 'The run'} ${status}.`,
      reason ?? 'The run reported no error detail.',
      'Open the Runs page for the full trajectory.',
    ].join('\n\n');
  }

  if (status && !TERMINAL_RUN_STATUS.has(status)) {
    const waiting = status === 'WAITING_INPUT'
      ? ' The run is waiting for your input.'
      : '';
    return `${run ?? 'The run'} is still ${status} — not finished.${waiting} Track it on the Runs page.`;
  }

  if (status === 'COMPLETED') {
    return [
      backendMessage || 'The run completed.',
      `${run ?? 'The run'} · COMPLETED`,
    ].join('\n\n');
  }

  const caveat = run
    ? `${run}: status not reported by this backend — verify on the Runs page before relying on this result.`
    : 'Run status not reported by this backend — verify on the Runs page before relying on this result.';
  // The caveat leads: a legacy backend's prose may itself claim completion, so
  // it is quoted as an unverified reply rather than repeated as the answer.
  return [
    caveat,
    backendMessage ? `Backend reply (unverified): ${backendMessage}` : '',
  ].filter(Boolean).join('\n\n');
}

export async function sendMessage(
  conversationId: string,
  message: string,
  history: Array<{ role: string; content: string }> = []
): Promise<AriaMessage> {
  const { data } = await client.post<AriaChatResponse>('/api/v1/aria/chat', {
    message,
    history,
    conversationId,
  });
  return {
    id: crypto.randomUUID(),
    role: 'assistant',
    content: buildSyncChatContent(data),
    timestamp: data.timestamp || new Date().toISOString(),
  } as AriaMessage;
}

/**
 * Enriched terminal error payload of a failed turn (A4): the backend adds
 * `turnFailed`/`runId`/`reason` next to the legacy `message` key. Transport
 * errors (timeouts, dropped connections) stay plain strings.
 */
export interface StreamErrorDetail {
  message?: string;
  turnFailed?: boolean;
  runId?: string;
  reason?: string;
}

/** Terminal stream error: a plain message, or the enriched failed-turn payload. */
export type StreamError = string | StreamErrorDetail;

export interface StreamCallbacks {
  onThinking?: (runId?: string) => void;
  onToolCall?: (name: string) => void;
  onToolResult?: (name: string, result: string) => void;
  onMessage?: (content: string) => void;
  onDone?: (data: { runId: string; conversationId: string; intent: string }) => void;
  onError?: (error: StreamError) => void;
}

interface ParsedSseEvent {
  event: string;
  data: string;
}

function parseSseChunk(buffer: string): { events: ParsedSseEvent[]; remainder: string } {
  const events: ParsedSseEvent[] = [];
  const parts = buffer.split('\n\n');
  const remainder = parts.pop() ?? '';
  for (const block of parts) {
    if (!block.trim()) continue;
    let eventName = 'message';
    const dataLines: string[] = [];
    for (const rawLine of block.split('\n')) {
      const line = rawLine.replace(/\r$/, '');
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart());
      }
    }
    events.push({ event: eventName, data: dataLines.join('\n') });
  }
  return { events, remainder };
}

export async function streamMessage(
  conversationId: string,
  message: string,
  history: Array<{ role: string; content: string }>,
  callbacks: StreamCallbacks,
  signal?: AbortSignal,
  options?: { isCancelled?: () => boolean; skillId?: string },
): Promise<void> {
  const response = await fetch('/api/v1/aria/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify({
      conversationId,
      message,
      history,
      ...(options?.skillId ? { skillId: options.skillId } : {}),
    }),
    signal,
  });

  if (!response.ok || !response.body) {
    const detail = response.statusText || `HTTP ${response.status}`;
    callbacks.onError?.(`Stream request failed: ${detail}`);
    return;
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';

  let resolved = false;
  const wrappedCallbacks: StreamCallbacks = {
    ...callbacks,
    onDone: (data) => { resolved = true; callbacks.onDone?.(data); },
    onError: (errorDetail) => { resolved = true; callbacks.onError?.(errorDetail); },
  };

  try {
    // eslint-disable-next-line no-constant-condition
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const { events, remainder } = parseSseChunk(buffer);
      buffer = remainder;
      for (const evt of events) {
        dispatchEvent(evt, wrappedCallbacks);
      }
    }
    if (buffer.trim().length > 0) {
      const { events } = parseSseChunk(buffer + '\n\n');
      for (const evt of events) {
        dispatchEvent(evt, wrappedCallbacks);
      }
    }
  } catch (err) {
    if ((err as DOMException)?.name === 'AbortError') {
      return;
    }
    const msg = err instanceof Error ? err.message : String(err);
    wrappedCallbacks.onError?.(msg);
    return;
  }

  if (!resolved) {
    // M1: a user-initiated cancel aborts the stream; don't surface a spurious error in that case.
    if (!options?.isCancelled?.()) {
      callbacks.onError?.('Connection closed unexpectedly. Please try again.');
    }
  }
}

function dispatchEvent(evt: ParsedSseEvent, cb: StreamCallbacks): void {
  let payload: unknown = {};
  if (evt.data) {
    try {
      payload = JSON.parse(evt.data);
    } catch {
      payload = { raw: evt.data };
    }
  }
  const p = payload as Record<string, unknown>;
  switch (evt.event) {
    case 'thinking':
      cb.onThinking?.(String(p.runId ?? ''));
      break;
    case 'tool_call':
      cb.onToolCall?.(String(p.name ?? 'tool'));
      break;
    case 'tool_result':
      cb.onToolResult?.(String(p.name ?? 'tool'), String(p.result ?? ''));
      break;
    case 'message':
      cb.onMessage?.(String(p.content ?? ''));
      break;
    case 'done':
      cb.onDone?.({
        runId: String(p.runId ?? ''),
        conversationId: String(p.conversationId ?? ''),
        intent: String(p.intent ?? ''),
      });
      break;
    case 'error':
      cb.onError?.(streamErrorDetail(p));
      break;
    default:
      break;
  }
}

/**
 * Terminal `error` event detail: a failed turn carries the enriched payload
 * (`turnFailed`/`runId`/`reason`); anything else keeps the legacy plain message.
 */
function streamErrorDetail(p: Record<string, unknown>): StreamError {
  const message = String(p.message ?? 'unknown error');
  if (p.turnFailed !== true) return message;
  return {
    message,
    turnFailed: true,
    runId: String(p.runId ?? ''),
    reason: String(p.reason ?? message),
  };
}
