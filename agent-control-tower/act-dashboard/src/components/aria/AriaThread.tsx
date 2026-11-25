import type { RefObject } from 'react';
import { Card } from '../ui/card';
import type { PanelItem } from '../ariaPanelState';
import type { ProposalSettledState } from '../AriaProposalCard';
import AriaActivityStrip from '../AriaActivityStrip';
import AriaApprovalCard from '../AriaApprovalCard';
import AriaClarificationCard from '../AriaClarificationCard';
import AriaProposalCard from '../AriaProposalCard';
import { OperatorSessionNotice } from '../AriaProposalCard';
import { AriaMessage } from './AriaMessage';

type ApprovalItem = Extract<PanelItem, { kind: 'approval' }>;
type ClarificationItem = Extract<PanelItem, { kind: 'clarification' }>;

/** What the panel reports for one reconcile read that was refused or failed. */
export interface AriaReconcileFailure {
  text: string;
  operatorRejected: boolean;
  action: string;
}

/**
 * The scrollable transcript: empty state, read failures, every conversation
 * item, the live thinking indicator and the starter prompts.
 *
 * Wave 2 D1: split out of `AriaPanel` as the thread surface. It decides nothing
 * and streams nothing — every settlement is a callback into the panel, which
 * owns the reducer, the stream and the reconcile.
 */
export function AriaThread({
  items,
  assistantHtml,
  tailId,
  lastIsRetryable,
  busy,
  isEmpty,
  historyError,
  reconcileFailure,
  reconcileFaultText,
  refusedReadsLabel,
  suggestions,
  sendDisabled,
  stackRef,
  onRetry,
  onSuggestion,
  onOpenConfigure,
  onApprovalSettled,
  onClarificationSettled,
  onProposalSettled,
}: {
  items: PanelItem[];
  assistantHtml: Map<string, string>;
  tailId: string | undefined;
  lastIsRetryable: boolean;
  busy: boolean;
  isEmpty: boolean;
  historyError: string | null;
  reconcileFailure: AriaReconcileFailure[];
  reconcileFaultText: string | null;
  refusedReadsLabel: string | null;
  suggestions: string[];
  sendDisabled: boolean;
  stackRef: RefObject<HTMLDivElement | null>;
  onRetry: () => void;
  onSuggestion: (text: string) => void;
  onOpenConfigure: () => void;
  onApprovalSettled: (item: ApprovalItem, state: 'approved' | 'denied' | 'expired', reason?: string) => void;
  onClarificationSettled: (item: ClarificationItem, state: 'answered' | 'expired', reason?: string) => void;
  onProposalSettled: (proposalId: string, state: ProposalSettledState) => void;
}) {
  return (
    <div
      className="ai-stack relative z-[1] flex flex-1 flex-col gap-3 overflow-y-auto px-4 py-4"
      ref={stackRef}
      aria-live="polite"
      aria-relevant="additions"
    >
      {isEmpty && (
        <Card size="sm" className="ai-card ai-empty gap-1.5 rounded-xl bg-muted/40 p-4 [--card-spacing:0px]">
          <div className="ai-empty-eyebrow font-mono text-[10px] tracking-[0.28em] text-primary uppercase">
            — operator copilot
          </div>
          <div className="ai-empty-title text-lg leading-tight font-medium text-card-foreground">
            Good to see you. <em className="not-italic font-semibold text-primary">How can I help?</em>
          </div>
          <div className="ai-empty-sub text-sm leading-relaxed text-muted-foreground">
            I can brief you on agents, runs, approvals, and the knowledge
            base — and I can act on what you ask.
          </div>
        </Card>
      )}

      {historyError && (
        <div role="alert" className="mx-3 my-2 text-xs text-destructive">
          {historyError}
        </div>
      )}

      {/* A reconcile read that failed. A refusal is an authority problem with a
          remedy, so it reads as the shared notice (naming the read that was
          refused); a fault keeps the server's own text. */}
      {reconcileFailure.some((failure) => !failure.operatorRejected) && (
        <div role="alert" className="mx-3 my-2 text-xs text-destructive">
          {reconcileFaultText}
        </div>
      )}
      {reconcileFailure.some((failure) => failure.operatorRejected) && refusedReadsLabel !== null && (
        <div className="mx-3 my-2">
          <OperatorSessionNotice actionLabel={refusedReadsLabel} />
        </div>
      )}

      {items.map((item) => {
        if (item.kind === 'activity') {
          return <AriaActivityStrip key={item.id} item={item} />;
        }
        if (item.kind === 'approval') {
          return (
            <AriaApprovalCard
              key={item.id}
              item={item}
              onSettled={(settled, reason) => onApprovalSettled(item, settled, reason)}
            />
          );
        }
        if (item.kind === 'clarification') {
          return (
            <AriaClarificationCard
              key={item.id}
              item={item}
              onSettled={(settled, reason) => onClarificationSettled(item, settled, reason)}
            />
          );
        }
        if (item.kind === 'queued') {
          return (
            <div key={item.id} className="ai-queued-pill flex">
              <span className="pill dim inline-flex items-center rounded-full border border-border bg-muted px-2 py-0.5 text-xs text-muted-foreground">
                Queued · {item.runIds.map((runId) => runId.slice(0, 8)).join(', ')}
              </span>
            </div>
          );
        }
        if (item.kind === 'proposal') {
          // The card owns the decide call: the panel only mounts it and settles
          // the item, so one decision renders one settlement. A proposal does
          // not gate the composer; an undecided one is a card to act on.
          return (
            <AriaProposalCard
              key={item.id}
              item={item}
              onSettled={(proposalId, settled) => onProposalSettled(proposalId, settled)}
            />
          );
        }
        return (
          <AriaMessage
            key={item.id}
            item={item}
            html={item.kind === 'assistant' ? assistantHtml.get(item.id) ?? '' : ''}
            isTail={item.id === tailId}
            canRetry={lastIsRetryable}
            busy={busy}
            onRetry={onRetry}
            onOpenConfigure={onOpenConfigure}
          />
        );
      })}

      {busy && (
        <div className="ai-msg ai-msg-assistant flex items-start gap-2">
          <div
            className="ai-msg-avatar mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full border border-border bg-muted text-[10px] text-muted-foreground"
            aria-hidden="true"
          >
            A
          </div>
          <div className="ai-msg-body flex min-w-0 flex-1 flex-col gap-1">
            <div className="ai-msg-bubble ai-msg-bubble-thinking inline-flex max-w-[88%] items-center rounded-2xl border border-dashed border-border bg-muted/40 px-3 py-2">
              <div className="typing-indicator flex items-center gap-1" aria-label="Aria is thinking">
                <span className="typing-dot size-1.5 rounded-full bg-primary" />
                <span className="typing-dot size-1.5 rounded-full bg-primary" />
                <span className="typing-dot size-1.5 rounded-full bg-primary" />
              </div>
            </div>
          </div>
        </div>
      )}

      {isEmpty && (
        <div className="ai-suggestions flex flex-wrap gap-2" role="group" aria-label="Suggested prompts">
          {suggestions.map((suggestion) => (
            <button
              key={suggestion}
              type="button"
              className="ai-sg inline-flex items-center rounded-full border border-border bg-muted px-3 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:opacity-50"
              onClick={() => onSuggestion(suggestion)}
              disabled={sendDisabled}
            >
              {suggestion}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

export default AriaThread;
