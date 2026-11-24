/**
 * One image travelling with a chat turn. The base64 payload is turn input only:
 * it is never logged, never put on the stream, and never persisted — the
 * transcript keeps a names/sizes summary the backend writes into the seed.
 */
export interface AriaAttachmentPayload {
  mimeType: string;
  filename: string;
  dataBase64: string;
  width?: number;
  height?: number;
}

/**
 * An absent or empty attachment array adds no `attachments` key at all (the
 * backend then treats the turn as text-only).
 */
function attachmentBody(
  attachments: AriaAttachmentPayload[] | undefined,
): Record<string, unknown> {
  return attachments && attachments.length > 0 ? { attachments } : {};
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

/**
 * An ask the parked turn announces on its own ordered stream (spec A2). The
 * panel turns it into a decision card in the conversation, so the operator
 * answers the run in place instead of waiting out the client timeout.
 */
export interface ApprovalRequiredSignal {
  runId: string;
  approvalId: string;
  toolName: string;
  riskTier?: string;
  toolPolicy?: string;
  optionsJson?: string;
  expiresAt?: string;
}

export interface WaitingInputSignal {
  runId: string;
  approvalId: string;
  question: string;
}

/**
 * A governed memory write proposed by the run (Wave C consumes the callback;
 * the signal is dispatched here so the dispatcher stays stable). Field names
 * mirror the frame's keys except `id`, which is the producer's `proposalId`
 * (RunSignalSseListener): the panel and its decide call are addressed by it.
 */
export interface MemoryProposalSignal {
  runId: string;
  id: string;
  subjectType: string;
  entityId?: string;
  factKey?: string;
  valueText?: string;
  sensitivity?: string;
}

export interface StreamCallbacks {
  onThinking?: (runId?: string) => void;
  /**
   * A live `tool_call` frame (`runId`, `name`, `toolCallId`, `args`). `args` is
   * the redacted text the audit row stores — rendered as received; the client
   * does not redact again and must not log the raw value. Absent (an older
   * backend, or a call with no input) is not an error.
   */
  onToolCall?: (name: string, args?: string) => void;
  onToolResult?: (name: string, result: string, ok: boolean) => void;
  onMessage?: (content: string) => void;
  onDone?: (data: { runId: string; conversationId: string; intent: string }) => void;
  onError?: (error: StreamError) => void;
  /**
   * A refusal answered before the stream opens: a non-2xx response whose JSON
   * body names the reason (`{"error": "..."}`), e.g. an unsupported attachment
   * type (415) or an oversized image (413). Supplying it leaves `onError` for
   * post-stream failures only. Without it, the legacy
   * `Stream request failed: ...` string still reaches `onError`.
   */
  onRejected?: (status: number, message: string) => void;
  onApprovalRequired?: (ask: ApprovalRequiredSignal) => void;
  onWaitingInput?: (ask: WaitingInputSignal) => void;
  onMemoryProposal?: (proposal: MemoryProposalSignal) => void;
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
  options?: {
    isCancelled?: () => boolean;
    skillId?: string;
    attachments?: AriaAttachmentPayload[];
  },
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
      ...attachmentBody(options?.attachments),
    }),
    signal,
  });

  if (!response.ok) {
    // The refusal is JSON (`{"error": "..."}`) and carries no attachment payload,
    // so it is safe to surface verbatim; a non-JSON body keeps the status text.
    let reason = response.statusText || `HTTP ${response.status}`;
    try {
      const text = await response.text();
      const parsed = JSON.parse(text) as { error?: string; message?: string };
      reason = parsed.error || parsed.message || reason;
    } catch {
      // non-JSON body: keep the status text
    }
    if (callbacks.onRejected) callbacks.onRejected(response.status, reason);
    else callbacks.onError?.(`Stream request failed: ${reason}`);
    return;
  }
  if (!response.body) {
    callbacks.onError?.('Stream request failed: empty response body');
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
      // The producer redacts `args` before it leaves the server (T44), so this is
      // rendered verbatim: absent/null/blank means "no argument to show", never an error.
      cb.onToolCall?.(String(p.name ?? 'tool'), optionalText(p.args));
      break;
    case 'tool_result':
      // `ok` is `!isError` from the core payload and is always present on the
      // producer (CoreEventTelemetry#streamSignal in act-execution). An absent
      // field means an older backend answered, so it is read as success — never
      // as a measured failure; an explicit `false` is the only error signal.
      cb.onToolResult?.(String(p.name ?? 'tool'), String(p.result ?? ''), p.ok !== false);
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
    case 'approval_required': {
      const riskTier = optionalText(p.riskTier);
      const toolPolicy = optionalText(p.toolPolicy);
      const optionsJson = optionalJson(p.optionsJson);
      const expiresAt = optionalText(p.expiresAt);
      cb.onApprovalRequired?.({
        runId: String(p.runId ?? ''),
        approvalId: String(p.approvalId ?? ''),
        toolName: String(p.toolName ?? 'tool'),
        ...(riskTier !== undefined ? { riskTier } : {}),
        ...(toolPolicy !== undefined ? { toolPolicy } : {}),
        ...(optionsJson !== undefined ? { optionsJson } : {}),
        ...(expiresAt !== undefined ? { expiresAt } : {}),
      });
      break;
    }
    case 'waiting_input':
      cb.onWaitingInput?.({
        runId: String(p.runId ?? ''),
        approvalId: String(p.approvalId ?? ''),
        question: String(p.question ?? ''),
      });
      break;
    case 'memory_proposal': {
      // The producer keys the decidable id `proposalId` (RunSignalSseListener),
      // not `id`. Without it the card could only POST its decide call to
      // nothing, so such a frame is dropped rather than dispatched empty.
      const proposalId = optionalText(p.proposalId);
      if (proposalId === undefined) break;
      const entityId = optionalText(p.entityId);
      const factKey = optionalText(p.factKey);
      const valueText = optionalText(p.valueText);
      const sensitivity = optionalText(p.sensitivity);
      cb.onMemoryProposal?.({
        runId: String(p.runId ?? ''),
        id: proposalId,
        subjectType: String(p.subjectType ?? ''),
        ...(entityId !== undefined ? { entityId } : {}),
        ...(factKey !== undefined ? { factKey } : {}),
        ...(valueText !== undefined ? { valueText } : {}),
        ...(sensitivity !== undefined ? { sensitivity } : {}),
      });
      break;
    }
    default:
      break;
  }
}

/** A non-empty string from the payload, or undefined when the key is absent/blank. */
function optionalText(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() !== '' ? value : undefined;
}

/** `optionsJson` as a string: the backend may serve it already stringified or as options. */
function optionalJson(value: unknown): string | undefined {
  if (value === undefined || value === null) return undefined;
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value);
  } catch {
    return undefined;
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
