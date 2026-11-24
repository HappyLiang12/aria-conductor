/**
 * F7 (SDD hardening): formatting for the nullable `arguments` payload an
 * approval may carry.
 *
 * The bridge/api lanes are adding `toolName` + `arguments` to approval rows.
 * The arguments of a tool call routinely contain credentials — a clone URL with
 * an embedded token, a provider key inside an MCP parameter, an Authorization
 * header — so the value is redacted before it can reach the DOM. Formatting is
 * best effort: strict JSON is pretty-printed, anything else is shown verbatim
 * after a regex pass that masks secret-looking assignments.
 */

/** Keys whose values must never be rendered. */
const SENSITIVE_KEY =
  /token|secret|password|passwd|passphrase|api[-_]?key|apikey|authorization|cookie|credential|private[-_]?key|access[-_]?key/i;

/** Placeholder rendered in place of a secret value. */
export const REDACTED_ARGUMENT_VALUE = '***';

/** Maximum rendered size of an arguments block (keeps an approval row readable). */
export const MAX_ARGUMENTS_LENGTH = 4000;

/** Recursion guard for hostile/deep payloads. */
const MAX_DEPTH = 8;

function isSensitiveKey(key: string): boolean {
  return SENSITIVE_KEY.test(key);
}

/**
 * Masks `key=value` / `key: value` / `"key":"value"` pairs inside text, so a
 * credential embedded in a URL or a shell command (`x-access-token:<secret>@`,
 * `--token=<secret>`) is masked even when its key is not an object key.
 */
const EMBEDDED_SECRET =
  /((?:x-access-token|token|secret|password|passwd|passphrase|api[-_]?key|apikey|authorization|cookie|credential|private[-_]?key|access[-_]?key)["']?\s*[:=]\s*["']?)([^\s"'@,;}]+)/gi;

/** `Authorization: Bearer <secret>` embedded in free text. */
const BEARER_SECRET = /\b(bearer)\s+[A-Za-z0-9._~+/=-]+/gi;

function scrubSecretsInText(text: string): string {
  return text
    .replace(EMBEDDED_SECRET, (_match, prefix: string) => `${prefix}${REDACTED_ARGUMENT_VALUE}`)
    .replace(BEARER_SECRET, (_match, prefix: string) => `${prefix} ${REDACTED_ARGUMENT_VALUE}`);
}

/**
 * Deep-copies a parsed argument value, replacing the value of every
 * secret-looking key (at any nesting level) with {@link REDACTED_ARGUMENT_VALUE}
 * and scrubbing credentials embedded in otherwise non-sensitive strings.
 */
export function redactApprovalValue(value: unknown, depth = 0): unknown {
  if (typeof value === 'string') return scrubSecretsInText(value);
  if (value === null || typeof value !== 'object') return value;
  if (depth >= MAX_DEPTH) return REDACTED_ARGUMENT_VALUE;

  if (Array.isArray(value)) {
    return value.map((entry) => redactApprovalValue(entry, depth + 1));
  }

  const out: Record<string, unknown> = {};
  for (const [key, entry] of Object.entries(value as Record<string, unknown>)) {
    out[key] = isSensitiveKey(key)
      ? REDACTED_ARGUMENT_VALUE
      : redactApprovalValue(entry, depth + 1);
  }
  return out;
}

/** Masks secret-looking pairs in text that is not strict JSON. */
function redactRawText(raw: string): string {
  return scrubSecretsInText(raw);
}

/**
 * F-B1 (T54): the `type` an ACP permission ask's stored `arguments` carry. The
 * backend keeps the WHOLE `permission.request` envelope there for a NATIVE_TOOL
 * ask (live evidence B-approvals-T1.json: `seq`, `at`, `requestId`,
 * `sessionId`, `toolCallId`, `input`, `inputBytes`, `argumentsDigest`,
 * `options`), not the tool's own argument object. Rendering the envelope asks
 * the operator to read transport plumbing; its `input` is what the tool will
 * do. One predicate, so every surface that shows approval arguments reduces the
 * payload the same way.
 */
export function isPermissionRequestEnvelope(value: unknown): value is Record<string, unknown> {
  return value !== null
    && typeof value === 'object'
    && !Array.isArray(value)
    && (value as Record<string, unknown>).type === 'permission.request';
}

/** The tool's own arguments inside a permission envelope, when it carries any. */
export function permissionRequestInput(envelope: Record<string, unknown>): unknown {
  return envelope.input;
}

/**
 * What an ACP envelope with no usable `input` says instead of a payload.
 *
 * The envelope class is shared by every approval surface
 * (`isPermissionRequestEnvelope`), so the two cannot disagree about what an ACP
 * ask's stored `arguments` are.
 */
export const ENVELOPE_WITHOUT_INPUT =
  'This request carries no argument summary — the core sent no tool input.';

export interface ArgumentView {
  /** The compact `key: value` lines the card paints. */
  summary: string;
  /** The same payload as one string, for the element's tooltip. */
  detail: string;
}

/** One `key: value` line per entry; JSON punctuation only is dropped. */
function summarizeEntries(entries: Record<string, unknown>): string {
  return Object.entries(entries)
    .map(([key, value]) => `${key}: ${typeof value === 'string' ? value : JSON.stringify(value) ?? 'null'}`)
    .join('\n');
}

/** The tool's own input, as the summary lines the operator decides on. */
function summarizeInput(input: unknown): string {
  if (typeof input === 'string') return input.trim();
  if (input !== null && typeof input === 'object' && !Array.isArray(input)) {
    return summarizeEntries(input as Record<string, unknown>);
  }
  if (input === undefined || input === null) return '';
  return JSON.stringify(input) ?? '';
}

/**
 * The arguments as a compact, truthful summary (spec A3, T45 Item 3): a JSON
 * object becomes one `key: value` line per entry, so a command and its target
 * are legible at a glance; anything else is shown verbatim. Nothing is invented
 * and nothing is dropped except JSON punctuation — the operator is approving
 * this payload, not a paraphrase of it.
 *
 * ONE exception, because it is not the tool's argument object at all: an ACP
 * `permission.request` envelope (F-B1). Its protocol fields — sequence number,
 * timestamp, session and call ids, byte count, digest, raw provider options —
 * are not things the operator can act on, and painting them into the transcript
 * asks them to read transport plumbing. The envelope's `input` IS the payload
 * the decision is about, so it is summarized in full and nothing else is shown.
 */
export function argumentView(args: string): ArgumentView {
  const text = args.trim();
  if (text === '') return { summary: '', detail: '' };
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return { summary: text, detail: text }; // not JSON: the raw text IS the argument
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { summary: text, detail: text };
  }
  const record = parsed as Record<string, unknown>;
  if (isPermissionRequestEnvelope(record)) {
    const input = permissionRequestInput(record);
    const summary = summarizeInput(input);
    if (summary === '') return { summary: ENVELOPE_WITHOUT_INPUT, detail: '' };
    const detail = typeof input === 'string' ? input : JSON.stringify(input) ?? summary;
    return { summary, detail };
  }
  const summary = summarizeEntries(record);
  return summary === '' ? { summary: text, detail: text } : { summary, detail: text };
}

function bound(text: string): string {
  if (text.length <= MAX_ARGUMENTS_LENGTH) return text;
  return `${text.slice(0, MAX_ARGUMENTS_LENGTH)}…`;
}

/**
 * The approval's arguments as display text: pretty-printed JSON (redacted and
 * size-bounded), the masked raw string when the payload is not JSON, or null
 * when there is nothing to render (absent/blank/non-string payloads).
 *
 * An ACP permission envelope is reduced to its `input` first — the protocol
 * fields are not the tool's arguments (F-B1) — so the Review surface shows the
 * same payload the Aria card does.
 */
export function formatApprovalArguments(raw: string | null | undefined): string | null {
  if (typeof raw !== 'string') return null;
  const trimmed = raw.trim();
  if (!trimmed) return null;

  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return bound(redactRawText(trimmed));
  }

  const payload = isPermissionRequestEnvelope(parsed) ? permissionRequestInput(parsed) : parsed;
  if (payload === undefined || payload === null) return null;

  try {
    return bound(JSON.stringify(redactApprovalValue(payload), null, 2));
  } catch {
    // Circular structures cannot arrive over JSON, but never let formatting
    // throw inside a render pass.
    return bound(redactRawText(trimmed));
  }
}
