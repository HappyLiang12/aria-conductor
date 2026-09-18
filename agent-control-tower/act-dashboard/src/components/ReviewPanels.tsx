import { useMemo, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { transitionKanbanItem } from '../api/kanban';
import { answerAsk, approveApproval, rejectApproval } from '../api/approvals';
import { AcpDecisionOutcomes, type AcpOutcome } from './AcpDecisionOutcomes';
import {
  acpToolLabel,
  describeDecisionError,
  hasAllowOnce,
  isAcpAsk,
  isAskExpired,
  parseAcpDisplay,
} from '../utils/acpAsk';
import { formatTimestamp } from '../utils/formatTime';
import type { Approval, ApprovalDecisionReceipt, KanbanItem, KanbanStatus } from '../types';

interface PanelProps {
  item: KanbanItem;
  pendingAsks: Approval[];
}

/** Ask decision surface - used by the collapsed drawer and the ReviewWorkspace rail. */
export function DecisionPanel({ item, pendingAsks }: PanelProps) {
  const queryClient = useQueryClient();
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [requestFeedback, setRequestFeedback] = useState('');
  const [error, setError] = useState<string | null>(null);
  // ACP outcomes are panel-local, keyed by ask id (a decided ask leaves the
  // PENDING list on the next refetch, so the strip is the durable surface).
  const [outcomes, setOutcomes] = useState<Record<string, AcpOutcome>>({});
  const acpAsks = pendingAsks.filter(isAcpAsk);
  const legacyAsks = pendingAsks.filter((a) => !isAcpAsk(a));
  const outcomeList = useMemo(() => Object.values(outcomes), [outcomes]);
  const recordError = (ask: Approval, approved: boolean, err: unknown) => {
    setOutcomes((prev) => ({
      ...prev,
      [ask.id]: {
        askId: ask.id,
        ask,
        label: acpToolLabel(ask),
        approved,
        decision: null,
        deliveryState: null,
        // Typed 409 body {code, error}; anything else gets generic wording.
        error: describeDecisionError(err) ?? {
          code: 'ERROR',
          message: 'Action rejected — please retry.',
        },
      },
    }));
    // The card may be stale (expired/decided elsewhere): refresh the lists.
    queryClient.invalidateQueries({ queryKey: ['kanban'] });
    queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
    queryClient.invalidateQueries({ queryKey: ['approvals'] });
  };
  const resolveAsk = useMutation({
    mutationFn: ({
      ask,
      approved,
      answer,
    }: {
      ask: Approval;
      approved: boolean;
      answer?: string;
    }): Promise<Approval | ApprovalDecisionReceipt> => {
      // ACP asks always route through /decide regardless of askType — never
      // through answerAsk. Legacy routing is unchanged.
      if (isAcpAsk(ask) || ask.askType !== 'QUESTION') {
        return approved ? approveApproval(ask.id, answer) : rejectApproval(ask.id, answer);
      }
      return answerAsk(ask.id, { approved, answer });
    },
    onSuccess: (receipt, variables) => {
      if (isAcpAsk(variables.ask)) {
        // The ACP receipt carries the recorded decision + delivery state; a
        // delivery failure does NOT roll the decision back (it stays recorded).
        const acpReceipt = receipt as ApprovalDecisionReceipt;
        setOutcomes((prev) => ({
          ...prev,
          [variables.ask.id]: {
            askId: variables.ask.id,
            ask: variables.ask,
            label: acpToolLabel(variables.ask),
            approved: variables.approved,
            decision: acpReceipt.decision ?? null,
            deliveryState: acpReceipt.deliveryState ?? null,
            error: null,
          },
        }));
      }
      queryClient.invalidateQueries({ queryKey: ['kanban'] });
      // Badge + Waiting-on-you staleness: cards carry pendingAskCount.
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
      queryClient.invalidateQueries({ queryKey: ['approvals'] });
    },
    onError: (err, variables) => {
      if (isAcpAsk(variables.ask)) {
        recordError(variables.ask, variables.approved, err);
        return;
      }
      setError('Action rejected — please retry.');
    },
  });
  // Card-level fallback (spec 10.1): send the whole card back to the agent
  // with feedback even while asks are still pending on it.
  const requestChanges = useMutation({
    mutationFn: (feedback: string) =>
      transitionKanbanItem(item.id, { status: 'TODO', feedback: feedback.trim() || undefined }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
      queryClient.invalidateQueries({ queryKey: ['kanban'] });
    },
    onError: () => setError('Action rejected — please retry.'),
  });

  const resolve = (args: { ask: Approval; approved: boolean; answer?: string }) => {
    setError(null);
    resolveAsk.mutate(args);
  };

  return (
    <div className="decision-zone" role="region" aria-label="Decision panel">
      <div className="dz-title">⚑ NEEDS YOUR DECISION · {pendingAsks.length} asks</div>
      {acpAsks.map((ask) => {
        const display = parseAcpDisplay(ask);
        const toolLabel = acpToolLabel(ask);
        const expired = isAskExpired(ask);
        return (
          <div key={ask.id} className="ask-card acp-card">
            <div className="ask-q">
              <span className="pill acp">ACP permission</span>
              {/* Tool identity is backend-sanitized — rendered verbatim. */}
              {toolLabel && <span className="acp-tool">{toolLabel}</span>}
            </div>
            {display?.rawInput && (
              <details className="acp-details" open>
                <summary>Permission request preview</summary>
                {/* Redacted preview, verbatim from displayJson. */}
                <pre className="acp-preview">{display.rawInput}</pre>
                {display.rawInputTruncated && <div className="acp-truncated">truncated</div>}
              </details>
            )}
            {display && display.options.length > 0 && (
              <div className="acp-options">
                {display.options.map((option, idx) => (
                  <div key={`${option.optionId}-${idx}`} className="acp-option">
                    {option.optionId}
                    {option.kind ? ` · ${option.kind}` : ''}
                    {option.name ? ` · ${option.name}` : ''}
                  </div>
                ))}
              </div>
            )}
            <div className="acp-expiry">
              {expired && <span className="acp-expired">Expired</span>}
              {expired ? ' · ' : ''}expires {formatTimestamp(ask.expiresAt)}
            </div>
            <div className="ask-actions">
              {hasAllowOnce(ask) ? (
                <button
                  className="btn primary"
                  disabled={resolveAsk.isPending || expired}
                  onClick={() => resolve({ ask, approved: true })}
                >
                  Allow once
                </button>
              ) : (
                // The server would answer UNSUPPORTED_OPTIONS — no button, hint instead.
                <span className="acp-hint">No allow-once option on this ask</span>
              )}
              <button
                className="btn"
                disabled={resolveAsk.isPending || expired}
                onClick={() => resolve({ ask, approved: false })}
              >
                Deny
              </button>
            </div>
          </div>
        );
      })}
      {legacyAsks.map((ask) => (
        <div key={ask.id} className="ask-card">
          <div className="ask-q">
            {ask.askType === 'QUESTION' ? 'Question' : ask.askType === 'REVIEW_REQUEST' ? 'Review' : 'Approval'}
            : {ask.content?.slice(0, 160)}
          </div>
          {ask.contextMd && <div className="ask-ctx">{ask.contextMd}</div>}
          <textarea
            className="dod-textarea"
            rows={2}
            aria-label={`Answer for ask ${ask.id}`}
            placeholder="Answer / feedback (optional)"
            value={answers[ask.id] ?? ''}
            onChange={(e) => setAnswers((prev) => ({ ...prev, [ask.id]: e.target.value }))}
          />
          <div className="ask-actions">
            <button className="btn primary" disabled={resolveAsk.isPending} onClick={() => resolve({ ask, approved: true, answer: answers[ask.id] || undefined })}>Approve</button>
            <button className="btn" disabled={resolveAsk.isPending} onClick={() => resolve({ ask, approved: false, answer: answers[ask.id] || undefined })}>Deny</button>
          </div>
        </div>
      ))}
      <AcpDecisionOutcomes
        outcomes={outcomeList}
        retrying={resolveAsk.isPending}
        // A retry repeats the recorded decision (the server treats an operator
        // repeat of a FAILED-but-live ask as the delivery retry).
        onRetry={(outcome) => resolveAsk.mutate({ ask: outcome.ask, approved: outcome.approved })}
      />
      {legacyAsks.length > 0 && (
        <button
          className="btn primary"
          disabled={resolveAsk.isPending}
          onClick={() => {
            setError(null);
            legacyAsks.forEach((a) => resolveAsk.mutate({ ask: a, approved: true, answer: answers[a.id] || undefined }));
          }}
        >
          ✓ Approve all
        </button>
      )}
      <textarea
        className="dod-textarea"
        rows={2}
        aria-label="Request-changes feedback"
        placeholder="What should change? (sent back to the agent)"
        value={requestFeedback}
        onChange={(e) => setRequestFeedback(e.target.value)}
      />
      <button
        className="btn"
        disabled={requestChanges.isPending}
        onClick={() => {
          setError(null);
          requestChanges.mutate(requestFeedback);
          setRequestFeedback('');
        }}
      >
        ✎ Request changes
      </button>
      {error && <div className="kanban-form-error">{error}</div>}
    </div>
  );
}

/** Quick decision surface for ask-less Review cards (spec 10.1). */
export function ShortApprovalView({ item }: { item: KanbanItem }) {
  const queryClient = useQueryClient();
  const [feedback, setFeedback] = useState('');
  const [error, setError] = useState<string | null>(null);
  const move = useMutation({
    mutationFn: ({ status, feedback: fb }: { status: KanbanStatus; feedback?: string }) =>
      transitionKanbanItem(item.id, { status, feedback: fb }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
      // Drawer item query staleness (title/status/asks read on open).
      queryClient.invalidateQueries({ queryKey: ['kanban'] });
      setError(null);
    },
    onError: () => setError('Action rejected — the card is unchanged.'),
  });

  return (
    <div className="decision-zone short-view" role="region" aria-label="Decision panel">
      <div className="dz-title">Run completed — quick decision</div>
      <div className="ask-ctx">
        agent {item.assignee ?? 'n/a'} · run {item.linkedRunId ? item.linkedRunId.slice(0, 8) : '—'}
      </div>
      <textarea
        className="dod-textarea"
        rows={2}
        aria-label="Request-changes feedback"
        placeholder="What should change? (sent back to the agent)"
        value={feedback}
        onChange={(e) => setFeedback(e.target.value)}
      />
      {error && <div className="kanban-form-error">{error}</div>}
      <div className="ask-actions">
        <button className="btn primary" onClick={() => move.mutate({ status: 'DONE' })}>Approve</button>
        <button className="btn" onClick={() => { move.mutate({ status: 'TODO', feedback: feedback.trim() || undefined }); setFeedback(''); }}>Request changes</button>
        <button className="btn danger" onClick={() => move.mutate({ status: 'CANCELLED' })}>Deny</button>
      </div>
    </div>
  );
}
