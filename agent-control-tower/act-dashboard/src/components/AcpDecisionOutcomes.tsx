/**
 * C5: durable outcome strip for ACP permission decides — shared verbatim by the
 * Review decision panel and the Ops approval queue (one render path, one
 * delivery-label mapping; do not duplicate the markup per surface).
 *
 * A decided ACP ask leaves the PENDING list on the next refetch, so the outcome
 * must survive outside the ask card. Entries are keyed by ask id upstream and
 * replaced on every new decision/retry for the same ask.
 */
import type { ReactElement } from 'react';
import type { Approval } from '../types';
import { deliveryLabel } from '../utils/acpAsk';

export interface AcpOutcome {
  askId: string;
  ask: Approval;
  label: string | null;
  approved: boolean;
  decision: string | null;
  deliveryState: string | null;
  error: { code: string; message: string } | null;
}

interface Props {
  outcomes: AcpOutcome[];
  retrying: boolean;
  onRetry: (outcome: AcpOutcome) => void;
}

/**
 * `DELIVERED`/`CANCELLED` = ok, `FAILED`/`MISSING` = warn (retryable at most
 * once — a MISSING ask has no companion row to retry), anything else neutral.
 */
function toneOf(outcome: AcpOutcome): string {
  if (outcome.error) return 'err';
  if (outcome.deliveryState === 'DELIVERED' || outcome.deliveryState === 'CANCELLED') return 'ok';
  if (outcome.deliveryState === 'FAILED' || outcome.deliveryState === 'MISSING') return 'warn';
  return '';
}

function textOf(outcome: AcpOutcome): string {
  if (outcome.error) return `${outcome.error.code}: ${outcome.error.message}`;
  const decision = outcome.decision === 'DENIED' ? 'denied' : 'approved';
  return `${decision} · ${deliveryLabel(outcome.deliveryState)}`;
}

export function AcpDecisionOutcomes({ outcomes, retrying, onRetry }: Props): ReactElement | null {
  if (outcomes.length === 0) return null;
  return (
    <div className="acp-outcomes" aria-label="ACP decision outcomes">
      {outcomes.map((outcome) => {
        const tone = toneOf(outcome);
        return (
          <div
            key={outcome.askId}
            className={`acp-outcome${tone ? ` ${tone}` : ''}`}
            data-ask-id={outcome.askId}
          >
            <span className="acp-outcome-label">{outcome.label ?? 'ACP permission'}</span>
            <span className="acp-outcome-text">{textOf(outcome)}</span>
            {!outcome.error && outcome.deliveryState === 'FAILED' && (
              <button
                className="btn"
                disabled={retrying}
                onClick={() => onRetry(outcome)}
                title="Retry delivery of the recorded decision"
              >
                Retry
              </button>
            )}
          </div>
        );
      })}
    </div>
  );
}

export default AcpDecisionOutcomes;
