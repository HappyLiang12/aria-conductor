import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getDoDStatus,
  listEvidence,
  submitReview,
  addEvidence,
} from '../api/dod';
import type {
  DoDStageStatus,
  DoDStageRollupStatus,
  EvidenceType,
} from '../types';

interface EvidenceDrawerProps {
  /** Task identifier — required to fetch the DoD record. */
  taskId: string;
  /** Optional reviewer identity used when submitting decisions. */
  reviewerId?: string;
  reviewerName?: string;
  /** Optional close handler — when present the drawer renders a close button. */
  onClose?: () => void;
}

const STAGE_LABELS: Record<string, string> = {
  dev: 'Dev',
  qa: 'QA',
  ba: 'BA',
  pm: 'PM',
};

const STAGE_GLYPH: Record<DoDStageRollupStatus | 'CURRENT', string> = {
  PASSED: '✓',
  FAILED: '✗',
  SKIPPED: '⤳',
  PENDING: '◌',
  CURRENT: '◉',
};

const EVIDENCE_TYPES: EvidenceType[] = ['LOG', 'ARTIFACT', 'TEST_RESULT', 'SCREENSHOT', 'COMMENT'];

export function EvidenceDrawer({
  taskId,
  reviewerId = 'dashboard-user',
  reviewerName = 'Dashboard User',
  onClose,
}: EvidenceDrawerProps) {
  const queryClient = useQueryClient();

  const statusQuery = useQuery({
    queryKey: ['dod', taskId, 'status'],
    queryFn: () => getDoDStatus(taskId),
    enabled: Boolean(taskId),
    retry: false,
  });

  const evidenceQuery = useQuery({
    queryKey: ['dod', taskId, 'evidence'],
    queryFn: () => listEvidence(taskId),
    enabled: Boolean(taskId),
  });

  const reviewMutation = useMutation({
    mutationFn: submitReview,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['dod', taskId] });
    },
  });

  const evidenceMutation = useMutation({
    mutationFn: ({ type, title, content }: { type: EvidenceType; title: string; content: string }) =>
      addEvidence(taskId, { type, title, content }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['dod', taskId, 'evidence'] });
      setEvidenceDraft({ type: 'LOG', title: '', content: '' });
    },
  });

  const [reviewComment, setReviewComment] = useState('');
  const [reviewEvidence, setReviewEvidence] = useState('');
  const [evidenceDraft, setEvidenceDraft] = useState<{ type: EvidenceType; title: string; content: string }>({
    type: 'LOG',
    title: '',
    content: '',
  });

  const status = statusQuery.data;
  const evidence = evidenceQuery.data ?? [];
  const reviewError = reviewMutation.error as { response?: { data?: { error?: string } } } | null;
  const reviewErrorMsg = reviewError?.response?.data?.error;

  const handleDecision = (passed: boolean) => {
    reviewMutation.mutate({
      taskId,
      reviewerId,
      reviewerName,
      passed,
      evidence: reviewEvidence || undefined,
      comment: reviewComment || undefined,
    });
    setReviewComment('');
    setReviewEvidence('');
  };

  const handleAddEvidence = () => {
    if (!evidenceDraft.title.trim() && !evidenceDraft.content.trim()) return;
    evidenceMutation.mutate(evidenceDraft);
  };

  if (!taskId) {
    return (
      <div className="evidence-drawer evidence-drawer-empty">
        <div className="evidence-empty-text">No task selected.</div>
      </div>
    );
  }

  return (
    <div className="evidence-drawer">
      <div className="evidence-drawer-header">
        <div className="evidence-drawer-eyebrow">DEFINITION OF DONE · DOSSIER</div>
        <div className="evidence-drawer-title-row">
          <h3 className="evidence-drawer-title">
            Task <span className="cell-mono">{taskId}</span>
          </h3>
          {onClose && (
            <button className="evidence-drawer-close" onClick={onClose} aria-label="Close drawer">
              ×
            </button>
          )}
        </div>
      </div>

      {statusQuery.isLoading && (
        <div className="loading-spinner"><div className="spinner" /><span>Loading DoD…</span></div>
      )}

      {statusQuery.isError && (
        <div className="evidence-error">
          No DoD record exists for this task yet. Initialize one to begin the stage gate.
        </div>
      )}

      {status && (
        <>
          {/* Stage progression rail */}
          <section className="evidence-section">
            <div className="evidence-section-label">Stage Gate</div>
            <div className="dod-rail" data-status={status.overallStatus}>
              {status.stages.map((stage, idx) => (
                <StageNode
                  key={stage.stage}
                  stage={stage}
                  isCurrent={stage.stage === status.currentStage}
                  isLast={idx === status.stages.length - 1}
                />
              ))}
            </div>
            <div className="dod-summary-row">
              <span className={`dod-summary-status dod-status-${status.overallStatus.toLowerCase()}`}>
                {status.overallStatus.replace('_', ' ')}
              </span>
              <span className="dod-summary-meta">
                Current stage: <strong>{STAGE_LABELS[status.currentStage] ?? status.currentStage}</strong>
              </span>
              <span className="dod-summary-meta">
                Evidence items: <strong>{status.evidenceCount}</strong>
              </span>
            </div>
          </section>

          {/* Review form */}
          {status.overallStatus !== 'PASSED' && (
            <section className="evidence-section">
              <div className="evidence-section-label">Submit review · {STAGE_LABELS[status.currentStage] ?? status.currentStage}</div>
              <div className="dod-review-form">
                <textarea
                  className="dod-textarea"
                  placeholder="Reviewer comment (optional)"
                  rows={2}
                  value={reviewComment}
                  onChange={(e) => setReviewComment(e.target.value)}
                />
                <textarea
                  className="dod-textarea"
                  placeholder="Evidence link or notes (optional)"
                  rows={2}
                  value={reviewEvidence}
                  onChange={(e) => setReviewEvidence(e.target.value)}
                />
                <div className="dod-review-actions">
                  <button
                    className="btn btn-success"
                    onClick={() => handleDecision(true)}
                    disabled={reviewMutation.isPending}
                  >
                    Pass
                  </button>
                  <button
                    className="btn btn-danger"
                    onClick={() => handleDecision(false)}
                    disabled={reviewMutation.isPending}
                  >
                    Fail
                  </button>
                </div>
                {reviewErrorMsg && <div className="evidence-error">{reviewErrorMsg}</div>}
              </div>
            </section>
          )}

          {/* Reviewer comments / timeline */}
          <section className="evidence-section">
            <div className="evidence-section-label">Reviewer log</div>
            {status.reviews.length === 0 ? (
              <div className="evidence-empty-text">No reviews recorded yet.</div>
            ) : (
              <ul className="dod-review-timeline">
                {status.reviews.map((r) => (
                  <li key={r.id} className={`dod-review-row dod-review-${r.passed ? 'pass' : 'fail'}`}>
                    <span className="dod-review-marker">{r.passed ? '✓' : '✗'}</span>
                    <div className="dod-review-body">
                      <div className="dod-review-meta">
                        <span className="dod-review-stage">{STAGE_LABELS[r.stage] ?? r.stage}</span>
                        <span className="dod-review-reviewer">{r.reviewerName ?? r.reviewerId}</span>
                        <span className="dod-review-time">{new Date(r.reviewedAt).toLocaleString()}</span>
                      </div>
                      {r.comment && <div className="dod-review-comment">{r.comment}</div>}
                      {r.evidence && <div className="dod-review-evidence">{r.evidence}</div>}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>

          {/* Evidence items */}
          <section className="evidence-section">
            <div className="evidence-section-label">
              Evidence collection <span className="evidence-section-count">{evidence.length}</span>
            </div>
            {evidence.length === 0 ? (
              <div className="evidence-empty-text">No evidence attached yet.</div>
            ) : (
              <ul className="evidence-list">
                {evidence.map((item) => (
                  <li key={item.id} className="evidence-item">
                    <span className={`evidence-tag evidence-tag-${item.type.toLowerCase()}`}>{item.type}</span>
                    <div className="evidence-item-body">
                      <div className="evidence-item-title">{item.title ?? '(untitled)'}</div>
                      {item.content && <div className="evidence-item-content">{item.content}</div>}
                      <div className="evidence-item-meta">
                        {item.artifactPath && <span>📎 {item.artifactPath}</span>}
                        {item.sourceRunId && <span className="cell-mono">run {item.sourceRunId.slice(0, 8)}</span>}
                        <span>{new Date(item.createdAt).toLocaleString()}</span>
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
            )}

            {/* Add evidence */}
            <div className="evidence-add-form">
              <div className="evidence-add-row">
                <select
                  className="dod-select"
                  value={evidenceDraft.type}
                  onChange={(e) =>
                    setEvidenceDraft((d) => ({ ...d, type: e.target.value as EvidenceType }))
                  }
                >
                  {EVIDENCE_TYPES.map((t) => (
                    <option key={t} value={t}>
                      {t}
                    </option>
                  ))}
                </select>
                <input
                  className="dod-input"
                  placeholder="Evidence title…"
                  value={evidenceDraft.title}
                  onChange={(e) => setEvidenceDraft((d) => ({ ...d, title: e.target.value }))}
                />
              </div>
              <textarea
                className="dod-textarea"
                placeholder="Content / notes / link"
                rows={2}
                value={evidenceDraft.content}
                onChange={(e) => setEvidenceDraft((d) => ({ ...d, content: e.target.value }))}
              />
              <div className="evidence-add-actions">
                <button
                  className="btn"
                  onClick={handleAddEvidence}
                  disabled={evidenceMutation.isPending}
                >
                  {evidenceMutation.isPending ? 'Saving…' : 'Attach evidence'}
                </button>
              </div>
            </div>
          </section>
        </>
      )}
    </div>
  );
}

interface StageNodeProps {
  stage: DoDStageStatus;
  isCurrent: boolean;
  isLast: boolean;
}

function StageNode({ stage, isCurrent, isLast }: StageNodeProps) {
  const glyph = isCurrent && stage.status === 'PENDING' ? STAGE_GLYPH.CURRENT : STAGE_GLYPH[stage.status];
  return (
    <>
      <div
        className={`dod-stage dod-stage-${stage.status.toLowerCase()} ${isCurrent ? 'is-current' : ''} ${
          stage.required ? 'is-required' : 'is-optional'
        }`}
      >
        <div className="dod-stage-glyph">{glyph}</div>
        <div className="dod-stage-name">{STAGE_LABELS[stage.stage] ?? stage.stage}</div>
        <div className="dod-stage-meta">
          {stage.required ? 'required' : 'optional'}
          {stage.reviewCount > 0 && ` · ${stage.reviewCount} review${stage.reviewCount === 1 ? '' : 's'}`}
        </div>
      </div>
      {!isLast && <div className={`dod-stage-link dod-stage-link-${stage.status.toLowerCase()}`} />}
    </>
  );
}
