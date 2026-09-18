/**
 * C5: ACP permission asks on the dashboard.
 *
 * Wire facts (verified against AcpPermissionCoordinator / ApprovalController):
 * - an ACP ask is exactly `source === 'ACP_PERMISSION'` (anything else,
 *   including a missing source, is a legacy gate ask);
 * - `displayJson` is a JSON string
 *   `{rawInputTruncated, grantable, toolName, rawInput, options[]}` whose
 *   `toolName`/`rawInput` are already sanitized + redacted by the backend. The
 *   UI renders them verbatim — never re-sanitize, re-truncate or re-parse
 *   beyond the defensive JSON.parse below;
 * - delivery states are exactly
 *   `PENDING | DELIVERING | DELIVERED | CANCELLED | FAILED | MISSING`;
 * - a rejected decide answers 409 with body `{code, error}` (codes
 *   `EXPIRED | ALREADY_DECIDED | UNSUPPORTED_OPTIONS | INCONSISTENT_ASK | UNDECIDABLE_ASK
 *   | GRANT_ALREADY_CONSUMED`).
 */
import { isAxiosError } from 'axios';
import type { Approval } from '../types';

export interface AcpOption {
  optionId: string;
  kind?: string;
  name?: string;
}

export interface AcpDisplay {
  rawInputTruncated: boolean;
  grantable: boolean;
  toolName: string | null;
  rawInput: string | null;
  options: AcpOption[];
}

/** ACP detection mirrors the backend dispatch rule: exact source match. */
export function isAcpAsk(ask: Approval): boolean {
  return ask.source === 'ACP_PERMISSION';
}

/**
 * Defensive parse of `displayJson`. Returns null on invalid JSON, on a
 * non-object payload, or when the required `options` array is missing; scalar
 * fields fall back to conservative defaults.
 */
export function parseAcpDisplay(ask: Approval): AcpDisplay | null {
  if (!ask.displayJson) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(ask.displayJson);
  } catch {
    return null;
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null;
  const raw = parsed as Record<string, unknown>;
  if (!Array.isArray(raw.options)) return null;

  const options: AcpOption[] = [];
  for (const entry of raw.options) {
    if (typeof entry !== 'object' || entry === null) continue;
    const option = entry as Record<string, unknown>;
    if (typeof option.optionId !== 'string') continue;
    const normalized: AcpOption = { optionId: option.optionId };
    if (typeof option.kind === 'string') normalized.kind = option.kind;
    if (typeof option.name === 'string') normalized.name = option.name;
    options.push(normalized);
  }

  return {
    rawInputTruncated: raw.rawInputTruncated === true,
    grantable: raw.grantable === true,
    toolName: typeof raw.toolName === 'string' ? raw.toolName : null,
    rawInput: typeof raw.rawInput === 'string' ? raw.rawInput : null,
    options,
  };
}

/** Resolved tool identity: display payload first, ask column as the fallback. */
export function acpToolLabel(ask: Approval): string | null {
  return parseAcpDisplay(ask)?.toolName ?? ask.toolName ?? null;
}

/** `Allow once` is offered only when the ask carries an allow_once option. */
export function hasAllowOnce(ask: Approval): boolean {
  return parseAcpDisplay(ask)?.options.some((o) => o.kind === 'allow_once') ?? false;
}

/**
 * Whether an ACP ask is undecidable for approval (F3/R4). Mirrors the backend
 * predicate (`AcpPermissionCoordinator.isTruncated`, consulted only on the ACP
 * path — callers gate with `isAcpAsk`): it fails closed, so a blank or
 * unparseable display record counts as truncated, and so does an absent (or
 * non-boolean) `rawInputTruncated` flag. Only an explicit `false` — the one
 * value the backend reads as decidable — may offer Allow once.
 *
 * `parseAcpDisplay` collapses an absent flag to `false`, so the raw record is
 * consulted here for the tri-state; this reads the control flag only and never
 * re-renders the redacted payload.
 */
export function isUndecidableAcpAsk(ask: Approval): boolean {
  let parsed: unknown;
  try {
    parsed = JSON.parse(ask.displayJson ?? '');
  } catch {
    return true;
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return true;
  return (parsed as Record<string, unknown>).rawInputTruncated !== false;
}

/** Unparseable/missing expiry is treated as not expired (never block a decide). */
export function isAskExpired(ask: Approval): boolean {
  const parsed = Date.parse(ask.expiresAt);
  return !Number.isNaN(parsed) && parsed < Date.now();
}

/** Operator-facing delivery text. Unknown values render raw; null is honest. */
export function deliveryLabel(state?: string | null): string {
  switch (state) {
    case 'DELIVERED':
      return 'delivered';
    case 'CANCELLED':
      return 'cancelled at the provider';
    case 'DELIVERING':
      return 'delivering';
    case 'PENDING':
      return 'delivery pending';
    case 'FAILED':
      return 'delivery failed';
    case 'MISSING':
    case null:
    case undefined:
      return 'no delivery recorded';
    default:
      return state;
  }
}

/**
 * Typed 409 body `{code, error}` from a rejected decide. Only axios errors with
 * a string `code` qualify; everything else is null (the caller picks its own
 * wording).
 */
export function describeDecisionError(err: unknown): { code: string; message: string } | null {
  if (!isAxiosError(err)) return null;
  const data: unknown = err.response?.data;
  if (typeof data !== 'object' || data === null) return null;
  const body = data as Record<string, unknown>;
  if (typeof body.code !== 'string') return null;
  return {
    code: body.code,
    message: typeof body.error === 'string' ? body.error : '',
  };
}
