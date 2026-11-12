import { useState } from 'react';
import { decideApproval } from '../api/approvals';
import { OPERATOR_REMEDY_COPY, crossSiteSessionMessage } from '../api/errorPresentation';
import {
  apiErrorMessage,
  applyOperatorHeaders,
  isCrossOriginRejection,
  isOperatorRejection,
} from '../api/operatorSession';
import type { PanelItem } from './ariaPanelState';
import { isPermissionRequestEnvelope, permissionRequestInput } from '../utils/approvalArguments';

export type AriaApprovalItem = Extract<PanelItem, { kind: 'approval' }>;
/** The states a decision can produce; `pending` is the absence of one. */
export type ApprovalOutcome = Exclude<AriaApprovalItem['state'], 'pending'>;

/**
 * What an ACP envelope with no usable `input` says instead of a payload.
 *
 * The envelope class itself is shared with the Review surface
 * (`isPermissionRequestEnvelope`), so the two cannot disagree about what an ACP
 * ask's stored `arguments` are.
 */
const ENVELOPE_WITHOUT_INPUT =
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

/** The card's painted summary; see `argumentView`. */
export function argumentSummary(args: string): string {
  return argumentView(args).summary;
}

/**
 * The backend answers 409 when the ask is no longer PENDING (it expired, the
 * run was cancelled, or the decision was already taken elsewhere). That is not
 * a transient failure and not something a retry can fix.
 */
function isUnsettleableAsk(error: unknown): boolean {
  const status = (error as { response?: { status?: number } } | undefined)?.response?.status;
  return status === 409;
}

/**
 * A governed tool ask rendered in the conversation (spec A3, F1): the operator
 * decides in place and the parked turn resumes on its still-open stream, so
 * there is no timeout to wait out and no page switch to the Review Queue.
 *
 * `decideApproval` is operator-only, so the local session's CSRF token is
 * applied first — without it the backend fails closed (401/403). A refusal is
 * reported where the operator can act on it, never retried silently.
 */
/**
 * The meta line under the tool name. A settled card already HAS its decision,
 * so it must not ask for one (F-LC1): the live bar showed "ALLOWED ONCE" above
 * "this tool needs your decision before it runs". Each settled state says what
 * happened instead, and only a genuinely pending ask carries the instruction.
 */
function metaCopy(state: AriaApprovalItem['state']): string {
  switch (state) {
    case 'approved':
      return 'you allowed this ask once';
    case 'denied':
      return 'you denied this ask';
    case 'expired':
      return 'the window to decide has closed';
    case 'pending':
      return 'this tool needs your decision before it runs';
  }
}

export function AriaApprovalCard({
  item,
  onSettled,
}: {
  item: AriaApprovalItem;
  onSettled: (state: ApprovalOutcome, reason?: string) => void;
}) {
  const [submitting, setSubmitting] = useState<'allow' | 'deny' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const settled = item.state !== 'pending';
  const argument = item.args !== undefined ? argumentView(item.args) : { summary: '', detail: '' };
  const summary = argument.summary;
  // The backend serves `toolPolicy: null` for a tool the catalog does not
  // classify (ApprovalDetail), so the type's optionality is not enough: only a
  // real policy name may be rendered as a reviewed authority.
  const toolPolicy = typeof item.toolPolicy === 'string' && item.toolPolicy !== '' ? item.toolPolicy : null;

  async function decide(approved: boolean): Promise<void> {
    if (submitting !== null || settled) return;
    setSubmitting(approved ? 'allow' : 'deny');
    setError(null);
    applyOperatorHeaders();
    try {
      await decideApproval(item.approvalId, { approved });
      onSettled(approved ? 'approved' : 'denied');
    } catch (err) {
      if (isUnsettleableAsk(err)) {
        // The ask can no longer be decided. Say so with the backend's own
        // wording via the settled line instead of claiming it is still pending.
        onSettled('expired', apiErrorMessage(err, 'This ask is no longer pending.'));
        return;
      }
      setError(
        isCrossOriginRejection(err)
          // Task 37: a 403 carrying the cross-origin marker is not an authority
          // refusal — the request came from a page the backend does not trust, so
          // "the operator must grant access" would answer the wrong question.
          ? `${crossSiteSessionMessage('Approving this ask')} Nothing was decided by the refused call.`
          : isOperatorRejection(err)
          // Same posture as the Review panels: say the refusal, say what the
          // operator can do about it, and say that nothing was decided. The
          // remedy is the shared copy (Task 35) — a reload cannot restore an
          // operator session, so this card must not suggest one.
          ? `Your operator session was refused this decision: ${apiErrorMessage(err, 'operator-only route')}. `
            + `Approvals are operator-only. ${OPERATOR_REMEDY_COPY} Nothing was decided by the refused call.`
          : apiErrorMessage(err, 'The decision could not be recorded — the ask is still pending; retry.'),
      );
    } finally {
      setSubmitting(null);
    }
  }

  return (
    <div className={`ai-decision-card ask-card ai-decision-approval ai-decision-${item.state}`}>
      <div className="ai-decision-head">
        <span className="ask-q ai-decision-tool"><code>{item.toolName}</code></span>
        <span className="ai-decision-tags">
          {item.riskTier !== undefined && <span className="pill risk">{item.riskTier}</span>}
          {/* Spec :504-505: a tool the catalog does not classify still resolves to
              READ for backward compatibility, but the card must say it is
              unclassified rather than let a bare tier imply it was reviewed. */}
          {toolPolicy !== null
            ? <span className="pill dim">{toolPolicy}</span>
            : <span className="pill dim ai-decision-unclassified">unclassified</span>}
        </span>
      </div>
      <div className="ask-meta ai-decision-meta">
        Run <code>{item.runId.slice(0, 8)}</code> · {metaCopy(item.state)}
      </div>
      {summary !== '' ? (
        <pre
          className="ask-args ai-decision-args"
          title={argument.detail !== '' ? argument.detail : undefined}
        >
          {summary}
        </pre>
      ) : item.argsStatus === 'unavailable' ? (
        // Not "no arguments": the read failed. Say so, so the operator knows the
        // card is showing them less than the ask carries.
        <div className="ask-meta ai-decision-meta ai-decision-args-unavailable">
          The arguments could not be read, so this ask is shown by tool name only.
        </div>
      ) : item.argsStatus === 'reading' ? (
        <div className="ask-meta ai-decision-meta ai-decision-args-reading">Reading the arguments…</div>
      ) : null}
      {settled ? (
        <div className="ai-decision-settled">
          {item.state === 'approved' ? 'Allowed once' : item.state === 'denied' ? 'Denied' : 'Expired before a decision'}
          {item.reason !== undefined && item.reason !== '' ? ` — ${item.reason}` : ''}
        </div>
      ) : (
        <div className="ask-actions ai-decision-actions">
          <button
            type="button"
            className="btn btn-primary ai-decision-allow"
            onClick={() => { void decide(true); }}
            disabled={submitting !== null}
          >
            {submitting === 'allow' ? 'Allowing…' : 'Allow once'}
          </button>
          <button
            type="button"
            className="btn btn-ghost ai-decision-deny"
            onClick={() => { void decide(false); }}
            disabled={submitting !== null}
          >
            Deny
          </button>
        </div>
      )}
      {error !== null && <div role="alert" className="ai-decision-error error-text">{error}</div>}
    </div>
  );
}

export default AriaApprovalCard;
