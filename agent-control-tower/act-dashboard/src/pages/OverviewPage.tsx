import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import ExecutiveSummary from '../components/ExecutiveSummary';
import KanbanBoard from '../components/KanbanBoard';
import AgentTeam from '../components/AgentTeam';
import ActivityTimeline from '../components/ActivityTimeline';
import MorningBriefing from '../components/MorningBriefing';
import { DecisionQueue } from '../components/DecisionQueue';
import { AttentionQueue } from '../components/AttentionQueue';
import { ReviewWorkspace } from '../components/ReviewWorkspace';
import { useDrawerContext } from '../components/DrawerContext';
import { approveApproval, answerAsk, rejectApproval } from '../api/approvals';
import { transitionKanbanItem } from '../api/kanban';
import { applyOperatorHeaders } from '../api/operatorSession';
import { openConfigure, presentError, type PresentedError } from '../api/errorPresentation';
import { useDecisionQueue, type DecisionRow } from '../hooks/useDecisionQueue';
import { useAttentionQueue, type AttentionRow } from '../hooks/useAttentionQueue';
import { allAuthoritative } from '../utils/queryState';
import '../styles/overview-flow.css';

interface RowError {
  id: string;
  presented: PresentedError;
}

/**
 * Decision-first Overview: what waits on the operator first, then the board.
 * A section collapses only after every contributing source succeeded and both
 * authoritative counts are zero; partial/stale/error never become "all clear".
 */
export default function OverviewPage() {
  const queryClient = useQueryClient();
  const { state, openTaskDrawer, openReviewMode } = useDrawerContext();
  const reviewId = state.reviewTargetId;
  const [queueInteraction, setQueueInteraction] = useState(false);
  const [decisionBusyId, setDecisionBusyId] = useState<string | null>(null);
  const [decisionError, setDecisionError] = useState<RowError | null>(null);
  const [attentionBusyId, setAttentionBusyId] = useState<string | null>(null);
  const [attentionError, setAttentionError] = useState<RowError | null>(null);

  const locked = Boolean(reviewId) || state.taskDrawer.open || queueInteraction;
  const decision = useDecisionQueue({ locked });
  const attention = useAttentionQueue({ locked });

  const invalidateDecision = () => {
    void queryClient.invalidateQueries({ queryKey: ['approvals'] });
    void queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
    void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
  };

  const decideMutation = useMutation({
    mutationFn: async ({ row, approved }: { row: DecisionRow; approved: boolean }) => {
      applyOperatorHeaders();
      return approved ? approveApproval(row.approvalId) : rejectApproval(row.approvalId);
    },
    onMutate: ({ row }) => {
      setDecisionBusyId(row.id);
      setDecisionError(null);
    },
    onSuccess: invalidateDecision,
    onError: (error, { row }) => {
      setDecisionError({ id: row.id, presented: presentError(error, 'Decision failed.') });
    },
    onSettled: () => setDecisionBusyId(null),
  });

  const answerMutation = useMutation({
    mutationFn: async ({ row, answer }: { row: DecisionRow; answer: string }) => {
      applyOperatorHeaders();
      return answerAsk(row.approvalId, { answer, approved: true });
    },
    onMutate: ({ row }) => {
      setDecisionBusyId(row.id);
      setDecisionError(null);
    },
    onSuccess: invalidateDecision,
    onError: (error, { row }) => {
      setDecisionError({ id: row.id, presented: presentError(error, 'Answer failed.') });
    },
    onSettled: () => setDecisionBusyId(null),
  });

  const retryPickupMutation = useMutation({
    mutationFn: async (row: AttentionRow) => {
      if (!row.taskId) throw new Error('Task details unavailable');
      return transitionKanbanItem(row.taskId, { status: 'IN_PROGRESS' });
    },
    onMutate: (row) => {
      setAttentionBusyId(row.id);
      setAttentionError(null);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
      void queryClient.invalidateQueries({ queryKey: ['kanban'] });
      void queryClient.invalidateQueries({ queryKey: ['runs'] });
      void queryClient.invalidateQueries({ queryKey: ['runs-all'] });
      void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
    },
    onError: (error, row) => {
      setAttentionError({ id: row.id, presented: presentError(error, 'Pickup retry was refused.') });
    },
    onSettled: () => setAttentionBusyId(null),
  });

  const allClear =
    !decision.partial
    && !attention.partial
    && allAuthoritative([decision.phase, attention.phase])
    && decision.count === 0
    && attention.count === 0;

  const handleActivate = (row: DecisionRow) => {
    if (!row.taskId) return;
    if (row.state === 'REVIEW') openReviewMode(row.taskId);
    else openTaskDrawer(row.taskId);
  };

  return (
    <div className="view-zone" data-view="overview">
      <div className={reviewId ? 'layout overview-grid overview-grid--review' : 'layout overview-grid'}>
        <div className="col overview-main">
          {reviewId ? (
            <ReviewWorkspace itemId={reviewId} />
          ) : (
            <>
              <div
                className="overview-queues"
                onMouseEnter={() => setQueueInteraction(true)}
                onMouseLeave={() => setQueueInteraction(false)}
                onFocusCapture={() => setQueueInteraction(true)}
                onBlurCapture={() => setQueueInteraction(false)}
              >
                <DecisionQueue
                  rows={decision.rows}
                  phase={decision.phase}
                  partial={decision.partial}
                  count={decision.count}
                  retry={decision.retry}
                  onActivate={handleActivate}
                  onDecide={(row, approved) => decideMutation.mutate({ row, approved })}
                  onAnswer={(row, answer) => answerMutation.mutate({ row, answer })}
                  busyId={decisionBusyId}
                  actionError={decisionError}
                  collapsed={allClear}
                />
                <AttentionQueue
                  rows={attention.rows}
                  phase={attention.phase}
                  partial={attention.partial}
                  count={attention.count}
                  retry={attention.retry}
                  onOpenTask={(row) => { if (row.taskId) openTaskDrawer(row.taskId); }}
                  onRetryPickup={(row) => retryPickupMutation.mutate(row)}
                  onOpenConfigure={() => openConfigure('runtime')}
                  busyId={attentionBusyId}
                  actionError={attentionError}
                  collapsed={allClear}
                />
              </div>
              <KanbanBoard />
              <ExecutiveSummary />
              <MorningBriefing />
            </>
          )}
        </div>
        {!reviewId && (
          <div className="col overview-support">
            <AgentTeam />
            <ActivityTimeline />
          </div>
        )}
      </div>
      {reviewId && (
        <div className="expanded-strip overview-strip layout">
          <AgentTeam />
          <ActivityTimeline />
          <MorningBriefing />
        </div>
      )}
    </div>
  );
}
