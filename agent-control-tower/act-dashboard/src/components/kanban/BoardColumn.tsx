import type { DragEvent, ReactElement, ReactNode } from 'react';
import type { KanbanItem, KanbanStatus } from '../../types';
import { Badge } from '../ui/badge';
import { Button } from '../ui/button';
import { Card } from '../ui/card';

export interface BoardColumnDef {
  key: KanbanStatus;
  label: string;
  isGate?: boolean;
}

export interface BoardBatchActions {
  running: boolean;
  /** Cards that need an individual decision and are excluded from every preset. */
  heldByAsk: number;
  completedCount: number;
  failedCount: number;
  batchCount: number;
  onAcceptAll: () => void;
  onReworkAll: () => void;
  onCancelAll: () => void;
}

export interface BoardColumnProps {
  column: BoardColumnDef;
  items: KanbanItem[];
  draggingId: string | null;
  legalTargets: KanbanStatus[];
  onDragOver: (event: DragEvent<HTMLDivElement>, key: KanbanStatus) => void;
  onDrop: (key: KanbanStatus) => void;
  renderCard: (item: KanbanItem) => ReactNode;
  batch?: BoardBatchActions;
}

/**
 * One board lane on a token Card. The visible column is the drop target, so
 * the legacy `.col-k[data-col]` hook, the `lane-<key>` test id and the
 * `drop-legal`/`drop-illegal` state classes all live on the same element the
 * E2E and unit suites already select.
 */
export function BoardColumn({
  column,
  items,
  draggingId,
  legalTargets,
  onDragOver,
  onDrop,
  renderCard,
  batch,
}: BoardColumnProps): ReactElement {
  const legal = draggingId !== null && legalTargets.includes(column.key);
  const illegal = draggingId !== null && !legalTargets.includes(column.key);
  const gateClass = column.isGate ? ' gate' : '';
  const dropClass = `${legal ? ' drop-legal' : ''}${illegal ? ' drop-illegal' : ''}`;

  return (
    <div
      className={`col-k${gateClass}${dropClass} flex min-h-0! flex-col rounded-none! border-0! bg-transparent! p-0!`}
      data-col={column.key}
      data-testid={`lane-${column.key}`}
      onDragOver={(event) => onDragOver(event, column.key)}
      onDrop={() => onDrop(column.key)}
    >
      <Card className="flex h-full flex-1 flex-col gap-0 p-0!">
        <header className="flex items-center justify-between gap-2 border-b px-3 py-2">
          <span className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
            {column.label}
          </span>
          <Badge variant="secondary" className="tabular-nums">{items.length}</Badge>
        </header>
        {column.isGate && items.length > 0 && batch && (
        <div
          className="flex flex-col gap-1 border-b p-2"
          title={batch.heldByAsk > 0
            ? `${batch.heldByAsk} card${batch.heldByAsk === 1 ? '' : 's'} with pending asks need${batch.heldByAsk === 1 ? 's' : ''} an individual decision`
            : undefined}
        >
          <Button
            variant="ghost"
            size="xs"
            className="justify-start"
            disabled={batch.running || batch.completedCount === 0}
            onClick={batch.onAcceptAll}
          >
            Accept all completed ({batch.completedCount})
          </Button>
          <Button
            variant="ghost"
            size="xs"
            className="justify-start"
            disabled={batch.running || batch.failedCount === 0}
            onClick={batch.onReworkAll}
          >
            Rework all failed ({batch.failedCount})
          </Button>
          <Button
            variant="ghost"
            size="xs"
            className="justify-start"
            disabled={batch.running || batch.batchCount === 0}
            onClick={batch.onCancelAll}
          >
            Cancel all ({batch.batchCount})
          </Button>
        </div>
      )}
        <div className="flex min-h-[60px] flex-1 flex-col gap-2 p-2">
          {items.map((item) => renderCard(item))}
        </div>
      </Card>
    </div>
  );
}

export default BoardColumn;
