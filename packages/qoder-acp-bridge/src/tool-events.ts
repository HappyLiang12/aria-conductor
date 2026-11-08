// Derived tool-call events.
//
// The ACP `session/update` stream carries the tool trajectory (`tool_call` with
// `rawInput`, then `tool_call_update` with `status`/`rawOutput`), but the
// product API needs first-class `tool.call` / `tool.result` events (finding
// F17: `GET /runs/{id}/tool-calls` was empty while the CLI session log held
// every call). This module maps one update to zero or one canonical event.
//
// Large payloads are capped at TOOL_PAYLOAD_LIMIT_BYTES per field and marked
// (`inputTruncated` / `resultTruncated` plus the original byte size) rather
// than dropped: an audit trail that silently loses the call is worse than a
// visibly truncated one.
import { redactCredentialFields } from './events.js';

/** Documented per-field cap (UTF-8 bytes) for `input` and `result` payloads. */
export const TOOL_PAYLOAD_LIMIT_BYTES = 8192;
/** Appended to a capped value so a reader can tell it is incomplete. */
export const TRUNCATION_SUFFIX = ' [truncated]';

export interface CappedPayload {
  /** The original value when it fits, otherwise a suffix-marked string prefix. */
  value: unknown;
  /** Serialized size of the original value in UTF-8 bytes. */
  bytes: number;
  truncated: boolean;
}

export interface ToolEvent {
  type: 'tool.call' | 'tool.result';
  payload: Record<string, unknown>;
}

/** Per-session memory of the tool name offered by `tool_call` for a call id. */
export type ToolNameRegistry = Map<string, string | null>;

/**
 * Cap one payload field. Objects and arrays are serialized (JSON) before the
 * cap is applied, so a truncated value is a string prefix of that JSON with the
 * suffix marker; text is capped directly.
 */
export function capToolPayload(value: unknown, limitBytes: number = TOOL_PAYLOAD_LIMIT_BYTES): CappedPayload {
  const serialized = typeof value === 'string' ? value : value === undefined ? '' : JSON.stringify(value);
  const bytes = Buffer.byteLength(serialized, 'utf8');
  if (bytes <= limitBytes) return { value: typeof value === 'string' ? value : (value ?? null), bytes, truncated: false };
  const suffixBytes = Buffer.byteLength(TRUNCATION_SUFFIX, 'utf8');
  const budget = Math.max(0, limitBytes - suffixBytes);
  return { value: `${truncateUtf8(serialized, budget)}${TRUNCATION_SUFFIX}`, bytes, truncated: true };
}

function truncateUtf8(text: string, budgetBytes: number): string {
  let used = 0;
  let out = '';
  for (const character of text) {
    const size = Buffer.byteLength(character, 'utf8');
    if (used + size > budgetBytes) break;
    used += size;
    out += character;
  }
  return out;
}

/**
 * Map one `session/update` payload to at most one derived tool event.
 *
 * `tool_call` publishes `tool.call`; a terminal `tool_call_update`
 * (`completed`/`failed`) publishes `tool.result`. Intermediate statuses and
 * unrelated updates publish nothing. The tool name is remembered per call id,
 * so a result names the tool even when the update does not repeat `_meta`.
 */
export function toolEventsFromUpdate(update: unknown, names: ToolNameRegistry): ToolEvent[] {
  if (update === null || typeof update !== 'object' || Array.isArray(update)) return [];
  const record = update as Record<string, unknown>;
  const toolCallId = typeof record.toolCallId === 'string' ? record.toolCallId : null;
  if (toolCallId === null) return [];
  if (record.sessionUpdate === 'tool_call') {
    const toolName = extractToolName(record) ?? names.get(toolCallId) ?? null;
    names.set(toolCallId, toolName);
    const input = capToolPayload(redactCredentialFields(record.rawInput));
    return [
      {
        type: 'tool.call',
        payload: {
          toolCallId,
          toolName,
          kind: typeof record.kind === 'string' ? record.kind : null,
          title: typeof record.title === 'string' ? record.title : null,
          input: input.value,
          inputBytes: input.bytes,
          inputTruncated: input.truncated,
        },
      },
    ];
  }
  if (record.sessionUpdate === 'tool_call_update') {
    const status = typeof record.status === 'string' ? record.status : null;
    if (status !== 'completed' && status !== 'failed') return [];
    const result = capToolPayload(extractResultText(record));
    return [
      {
        type: 'tool.result',
        payload: {
          toolCallId,
          toolName: names.get(toolCallId) ?? null,
          status,
          isError: status === 'failed',
          result: result.value,
          resultBytes: result.bytes,
          resultTruncated: result.truncated,
        },
      },
    ];
  }
  return [];
}

function extractToolName(toolCall: Record<string, unknown>): string | null {
  const meta = toolCall._meta;
  if (meta !== null && typeof meta === 'object') {
    const qoder = (meta as Record<string, unknown>).qoder;
    if (qoder !== null && typeof qoder === 'object') {
      const name = (qoder as Record<string, unknown>).toolName;
      if (typeof name === 'string') return name;
    }
  }
  return null;
}

/**
 * The result text of a tool call: the recorded `rawOutput` when it is text,
 * else the concatenated `content` text parts, else the serialized raw output.
 */
function extractResultText(record: Record<string, unknown>): unknown {
  if (typeof record.rawOutput === 'string') return record.rawOutput;
  if (Array.isArray(record.content)) {
    const parts: string[] = [];
    for (const entry of record.content) {
      if (entry === null || typeof entry !== 'object') continue;
      const content = (entry as Record<string, unknown>).content;
      if (content !== null && typeof content === 'object') {
        const text = (content as Record<string, unknown>).text;
        if (typeof text === 'string') parts.push(text);
      }
    }
    if (parts.length > 0) return parts.join('\n');
  }
  return record.rawOutput ?? null;
}
