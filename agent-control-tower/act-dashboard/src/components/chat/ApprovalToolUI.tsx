import { useMemo, useRef, useState } from 'react';
import type { ToolCallMessagePartComponent } from '@assistant-ui/react';
import { decideApproval } from '@/api/approvals';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';

/**
 * Arguments of the reserved `__approval` tool call (spec Task 6 maps the SSE
 * `approval_required` frame into this shape). `toolName` is the tool the
 * *governed* call is about — the reserved tool this card renders is
 * `__approval` itself; the collision with the part's own `toolName` prop is
 * intentional.
 */
export interface ApprovalArgs {
  approvalId: string;
  toolName: string;
  riskTier?: string;
  toolPolicy?: string;
  expiresAt?: string;
  /**
   * The decision set the core offered, verbatim (`PermissionOption` rows:
   * `{ optionId, choice: "ALLOW_ONCE" | "DENY" }`, or ACP-shaped
   * `{ id, kind, label }`). Absent or unreadable → Allow/Deny fallback.
   */
  optionsJson?: string;
}

/** One decision the core offered, with the boolean this card can post for it. */
interface OfferedOption {
  label: string;
  /** `null` when the option cannot be expressed as `{ approved: boolean }`. */
  approved: boolean | null;
}

/** Words that mean "authorize this one call". */
const ALLOW_WORDS = new Set([
  'allow',
  'allowed',
  'approve',
  'approved',
  'accept',
  'accepted',
  'confirm',
  'once',
  'proceed',
  'yes',
  'ok',
]);

/** Words that mean "authorize nothing". */
const REJECT_WORDS = new Set([
  'reject',
  'rejected',
  'deny',
  'denied',
  'cancel',
  'cancelled',
  'canceled',
  'abort',
  'dismiss',
  'decline',
  'declined',
  'refuse',
  'no',
]);

function words(value: unknown): string[] {
  if (typeof value !== 'string') return [];
  return value.toLowerCase().split(/[^a-z0-9]+/).filter(Boolean);
}

function humanize(id: string): string {
  const text = id.replace(/[_-]+/g, ' ').trim();
  return text === '' ? id : text.charAt(0).toUpperCase() + text.slice(1);
}

/**
 * The boolean decision an offered option stands for.
 *
 * A rejection word wins over an allow word (`reject_once` carries both), and an
 * option that widens scope (`allow_always`) or negates (“not …”) resolves to
 * `null`: `decideApproval` only accepts `{ approved: boolean }`, so allowing
 * always cannot be honoured here, and claiming it could is exactly the kind of
 * dishonest affordance this card exists to avoid.
 */
function decisionOf(option: Record<string, unknown>): boolean | null {
  const tokens = [
    ...words(option.kind),
    ...words(option.choice),
    ...words(option.optionId),
    ...words(option.id),
  ];
  if (tokens.includes('always') || tokens.includes('not')) return null;
  if (tokens.some((token) => REJECT_WORDS.has(token))) return false;
  if (tokens.some((token) => ALLOW_WORDS.has(token))) return true;
  return null;
}

function labelOf(option: Record<string, unknown>, index: number): string {
  for (const key of ['label', 'name', 'optionId', 'id'] as const) {
    const value = option[key];
    if (typeof value === 'string' && value.trim() !== '') {
      return key === 'label' || key === 'name' ? value : humanize(value);
    }
  }
  return `Option ${index + 1}`;
}

/** The offered options, or an empty list when `optionsJson` carries none. */
function parseOptions(optionsJson: string | undefined): OfferedOption[] {
  if (!optionsJson) return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(optionsJson);
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) return [];
  return parsed.flatMap((entry, index) => {
    if (entry === null || typeof entry !== 'object' || Array.isArray(entry)) return [];
    const option = entry as Record<string, unknown>;
    return [{ label: labelOf(option, index), approved: decisionOf(option) }];
  });
}

export const ApprovalToolUI: ToolCallMessagePartComponent<ApprovalArgs> = ({ args }) => {
  const [state, setState] = useState<'pending' | 'submitting' | 'allowed' | 'denied' | 'error'>('pending');
  const [error, setError] = useState<string | null>(null);
  const offered = useMemo(() => parseOptions(args.optionsJson), [args.optionsJson]);
  /**
   * Synchronous in-flight guard: a double-click can dispatch two clicks before
   * React re-renders, and the backend rejects the second overlapping decision
   * with a 409 — which would replace the landed receipt with an error card.
   * The ref settles that race; `state === 'submitting'` keeps the controls
   * disabled for the user.
   */
  const inFlight = useRef(false);

  async function decide(approved: boolean) {
    if (inFlight.current) return;
    inFlight.current = true;
    setState('submitting');
    try {
      await decideApproval(args.approvalId, { approved });
      setError(null);
      setState(approved ? 'allowed' : 'denied');
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
      setState('error');
    } finally {
      inFlight.current = false;
    }
  }

  if (state === 'allowed' || state === 'denied') {
    return (
      <p className="text-sm text-muted-foreground">
        {state === 'allowed' ? 'Allowed' : 'Denied'} · {args.toolName}
      </p>
    );
  }

  return (
    <div className="my-2 rounded-lg border border-border bg-card p-4">
      <div className="flex flex-wrap items-center gap-2">
        <Badge variant={args.riskTier === 'DESTRUCTIVE' ? 'destructive' : 'secondary'}>
          {args.riskTier ?? 'REVIEW'}
        </Badge>
        <span className="font-medium">Approve {args.toolName}?</span>
        {args.toolPolicy && <Badge variant="outline">{args.toolPolicy}</Badge>}
      </div>
      {args.expiresAt && (
        <p className="mt-1 text-xs text-muted-foreground">
          Expires {new Date(args.expiresAt).toLocaleTimeString()}
        </p>
      )}
      {error && (
        <p role="alert" className="mt-2 text-sm text-destructive">
          {error}
        </p>
      )}
      <div className="mt-3 flex flex-wrap gap-2">
        {offered.length > 0 ? (
          offered.map((option, index) => (
            <Button
              key={`${index}-${option.label}`}
              size="sm"
              variant={option.approved === true ? 'default' : 'outline'}
              disabled={option.approved === null || state === 'submitting'}
              title={
                option.approved === null
                  ? 'This offered option cannot be expressed as a single approve/deny decision.'
                  : undefined
              }
              onClick={() => option.approved !== null && void decide(option.approved)}
            >
              {option.label}
            </Button>
          ))
        ) : (
          <>
            <Button size="sm" disabled={state === 'submitting'} onClick={() => void decide(true)}>
              Allow
            </Button>
            <Button
              size="sm"
              variant="outline"
              disabled={state === 'submitting'}
              onClick={() => void decide(false)}
            >
              Deny
            </Button>
          </>
        )}
      </div>
    </div>
  );
};
