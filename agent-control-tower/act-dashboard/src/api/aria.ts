import client from './client';
import type { AriaMessage } from '../types';

export interface AriaChatResponse {
  message: string;
  runId: string;
  conversationId: string;
  intent: string;
  actionsTaken: Array<{ type: string; description: string; result: string }>;
  timestamp: string;
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
    content: data.message || 'No response received.',
    timestamp: data.timestamp || new Date().toISOString(),
  } as AriaMessage;
}

export interface StreamCallbacks {
  onThinking?: () => void;
  onToolCall?: (name: string) => void;
  onToolResult?: (name: string, result: string) => void;
  onMessage?: (content: string) => void;
  onDone?: (data: { runId: string; conversationId: string; intent: string }) => void;
  onError?: (msg: string) => void;
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
): Promise<void> {
  const response = await fetch('/api/v1/aria/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify({ conversationId, message, history }),
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
    onError: (msg) => { resolved = true; callbacks.onError?.(msg); },
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
    callbacks.onError?.('Connection closed unexpectedly. Please try again.');
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
      cb.onThinking?.();
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
      cb.onError?.(String(p.message ?? 'unknown error'));
      break;
    default:
      break;
  }
}
