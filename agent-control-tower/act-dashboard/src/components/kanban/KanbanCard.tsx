import type { DragEvent, ReactElement } from 'react';
import type { KanbanItem, KanbanPriority } from '../../types';
import { reviewNeedsHuman } from '../../utils/reviewState';
import { RunOutcomeChip } from '../ReviewPanels';
import { Badge } from '../ui/badge';
import { Button } from '../ui/button';
import { Card } from '../ui/card';
import { Input } from '../ui/input';

export type CardFlash = 'ok' | 'err' | 'assign' | null;

export interface KanbanCardProps {
  item: KanbanItem;
  /** The card sits in the REVIEW gate column. */
  isGate: boolean;
  dragging: boolean;
  flash: CardFlash;
  agentName?: string;
  transitionPending: boolean;
  feedbackOpen: boolean;
  feedbackValue: string;
  onDragStart: (event: DragEvent<HTMLDivElement>, id: string) => void;
  onDragEnd: () => void;
  onOpen: (id: string) => void;
  onQuickApprove: (id: string) => void;
  onRequestChanges: (id: string) => void;
  onFeedbackChange: (value: string) => void;
  onCancelRequestChanges: () => void;
  onSendRequestChanges: (id: string) => void;
  onQuickDeny: (id: string) => void;
  onExpandReview: (id: string) => void;
  onRequestCancel: (id: string) => void;
}

type BadgeVariant = 'secondary' | 'outline' | 'destructive' | 'default';

function priorityBadge(priority: KanbanPriority): { variant: BadgeVariant; className: string } {
  switch (priority) {
    case 'CRITICAL':
      return { variant: 'destructive', className: 'pill risk' };
    case 'HIGH':
      return { variant: 'secondary', className: 'pill warn' };
    case 'MEDIUM':
      return { variant: 'secondary', className: 'pill' };
    case 'LOW':
    default:
      return { variant: 'outline', className: 'pill ok' };
  }
}

/**
 * One board card. Presentation only: drag/click/decision handlers stay in
 * `KanbanBoard` so the LEGAL_DROPS state machine and mutations are unchanged.
 * The legacy `card`/`card-open`/`pill` hooks stay for the E2E and unit suites.
 */
export function KanbanCard({
  item,
  isGate,
  dragging,
  flash,
  agentName,
  transitionPending,
  feedbackOpen,
  feedbackValue,
  onDragStart,
  onDragEnd,
  onOpen,
  onQuickApprove,
  onRequestChanges,
  onFeedbackChange,
  onCancelRequestChanges,
  onSendRequestChanges,
  onQuickDeny,
  onExpandReview,
  onRequestCancel,
}: KanbanCardProps): ReactElement {
  const priority = priorityBadge(item.priority);
  const cardClass = [
    'card kanban-card',
    isGate ? 'gate' : '',
    item.status === 'DONE' ? 'done' : '',
    flash === 'err' ? 'moving-err' : flash ? 'moving' : '',
  ].filter(Boolean).join(' ');

  return (
    <Card
      size="sm"
      className={`${cardClass} gap-0 bg-card! p-0!`}
      data-card={item.id}
      draggable
      data-dragging={dragging || undefined}
      onDragStart={(event) => onDragStart(event, item.id)}
      onDragEnd={onDragEnd}
    >
      <button
        type="button"
        className="card-open p-3!"
        draggable
        aria-label={`Open task ${item.title}`}
        onClick={(event) => {
          event.stopPropagation();
          onOpen(item.id);
        }}
      >
        <div className="flex min-w-0 items-center gap-2">
          <span className="font-mono text-[10.5px] text-muted-foreground">{item.id.slice(0, 8)}</span>
        </div>
        <div className="t mt-1 mb-1.5 text-sm font-semibold break-words text-card-foreground">
          {item.title}
        </div>
        {/* F24: REVIEW is a badge only while an ask is open; a finished card in
            the Review column says so instead of reading as "needs you". */}
        {item.status === 'REVIEW' && reviewNeedsHuman(item) && (
          <Badge variant="secondary" className="pill warn">Review</Badge>
        )}
        {item.status === 'REVIEW' && !reviewNeedsHuman(item) && (
          <span className="owner text-xs text-muted-foreground">awaiting sign-off · no open asks</span>
        )}
        {!!item.pendingAskCount && (
          <Badge variant="secondary" className="pill warn">{item.pendingAskCount} asks</Badge>
        )}
        {item.status === 'REVIEW' && item.runOutcome && item.runOutcome !== 'UNKNOWN' && (
          // R-RFUX4: the board's compact REVIEW card carries the linked run's
          // outcome (the exported chip's own tones); UNKNOWN/null claim nothing.
          <RunOutcomeChip outcome={item.runOutcome} />
        )}
        {flash === 'assign' && (
          <span className="owner text-xs text-primary">◐ Aria assigning…</span>
        )}
        {item.lastError && (
          <div className="owner text-xs text-destructive">{item.lastError}</div>
        )}
        <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
          <Badge variant={priority.variant} className={priority.className}>
            {item.priority}
          </Badge>
          {agentName && <span className="owner text-xs text-muted-foreground">↪ {agentName}</span>}
          {item.assignee && (
            <span className="owner text-xs text-muted-foreground">@{item.assignee}</span>
          )}
        </div>
      </button>
      {item.status === 'REVIEW' && (
        // Full quick decision lives on the card face: approve, request changes
        // (inline composer) and deny without opening the drawer/workspace.
        <div className="card-approve flex flex-wrap items-center gap-1" onClick={(event) => event.stopPropagation()}>
          {feedbackOpen ? (
            <div className="flex w-full flex-col gap-1.5">
              <Input
                autoFocus
                placeholder="What should change?"
                aria-label="Request-changes feedback"
                value={feedbackValue}
                onChange={(event) => onFeedbackChange(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Escape') onCancelRequestChanges();
                }}
              />
              <div className="flex gap-1">
                <Button
                  size="xs"
                  aria-label="Send request changes"
                  disabled={transitionPending}
                  onClick={() => onSendRequestChanges(item.id)}
                >
                  Send
                </Button>
                <Button
                  variant="ghost"
                  size="xs"
                  aria-label="Cancel request changes"
                  onClick={onCancelRequestChanges}
                >
                  ✕
                </Button>
              </div>
            </div>
          ) : (
            <>
              <Button
                variant="outline"
                size="xs"
                className="text-emerald-700 dark:text-emerald-400"
                title={item.pendingAskCount
                  ? `Decide the ${item.pendingAskCount} pending ask${item.pendingAskCount === 1 ? '' : 's'} first`
                  : 'Approve (complete task)'}
                aria-label="Quick approve"
                disabled={transitionPending || !!item.pendingAskCount}
                onClick={() => onQuickApprove(item.id)}
              >
                ✓ Approve
              </Button>
              <Button
                variant="outline"
                size="xs"
                title="Request changes (sent back to the agent)"
                aria-label="Request changes"
                onClick={() => onRequestChanges(item.id)}
              >
                ✎ Changes
              </Button>
              <Button
                variant="destructive"
                size="xs"
                title="Deny (cancel task)"
                aria-label="Quick deny"
                disabled={transitionPending}
                onClick={() => onQuickDeny(item.id)}
              >
                ✕ Deny
              </Button>
              <Button
                variant="ghost"
                size="icon-xs"
                title="Expand review workspace"
                aria-label="Expand review workspace"
                onClick={() => onExpandReview(item.id)}
              >
                ⤢
              </Button>
            </>
          )}
        </div>
      )}
      {item.status !== 'DONE' && item.status !== 'CANCELLED' && (
        <Button
          variant="ghost"
          size="icon-xs"
          className="card-cancel absolute top-1 right-1"
          title="Cancel task"
          aria-label="Cancel task"
          disabled={transitionPending}
          onClick={(event) => {
            event.stopPropagation();
            onRequestCancel(item.id);
          }}
        >
          ✕
        </Button>
      )}
    </Card>
  );
}

export default KanbanCard;
