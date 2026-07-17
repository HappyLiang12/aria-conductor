import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createKanbanItem, listKanbanItems } from '../api/kanban';
import type { CreateKanbanItemRequest, KanbanItem, KanbanPriority } from '../types';
import { useWebSocketContext } from './Layout';

interface ColumnDef {
  key: string;
  label: string;
  isGate?: boolean;
  filter: (item: KanbanItem) => boolean;
}

const hasLabel = (item: KanbanItem, needle: string): boolean => {
  if (!item.labels) return false;
  return item.labels
    .toLowerCase()
    .split(',')
    .map((s) => s.trim())
    .includes(needle.toLowerCase());
};

const COLUMNS: ColumnDef[] = [
  {
    key: 'backlog',
    label: 'Backlog',
    filter: (it) => it.status === 'TODO' && hasLabel(it, 'backlog'),
  },
  {
    key: 'todo',
    label: 'Todo',
    filter: (it) => it.status === 'TODO' && !hasLabel(it, 'backlog') && !hasLabel(it, 'review'),
  },
  {
    key: 'in_progress',
    label: 'In Progress',
    filter: (it) => it.status === 'IN_PROGRESS' && !hasLabel(it, 'qa-gate'),
  },
  {
    key: 'review',
    label: 'Review',
    filter: (it) => it.status === 'BLOCKED' || hasLabel(it, 'review'),
  },
  {
    key: 'qa_gate',
    label: 'QA Gate',
    isGate: true,
    filter: (it) => hasLabel(it, 'qa-gate') || hasLabel(it, 'gate'),
  },
  {
    key: 'done',
    label: 'Done',
    filter: (it) => it.status === 'DONE',
  },
  {
    key: 'archived',
    label: 'Archived',
    filter: (it) => it.status === 'CANCELLED',
  },
];

function priorityPillClass(priority: KanbanPriority): string {
  switch (priority) {
    case 'CRITICAL':
      return 'pill risk';
    case 'HIGH':
      return 'pill warn';
    case 'MEDIUM':
      return 'pill';
    case 'LOW':
    default:
      return 'pill ok';
  }
}

function dispatchOpenTaskDrawer(itemId: string) {
  window.dispatchEvent(
    new CustomEvent('act:open-task-drawer', { detail: { id: itemId } })
  );
}

interface NewItemDraft {
  title: string;
  priority: KanbanPriority;
  assignee: string;
}

const EMPTY_DRAFT: NewItemDraft = {
  title: '',
  priority: 'MEDIUM',
  assignee: '',
};

export default function KanbanBoard() {
  const queryClient = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [draft, setDraft] = useState<NewItemDraft>(EMPTY_DRAFT);
  const [error, setError] = useState<string | null>(null);
  const { lastMessage } = useWebSocketContext();

  const { data: items, isLoading } = useQuery({
    queryKey: ['kanban-items'],
    queryFn: () => listKanbanItems(),
    refetchInterval: 12000,
  });

  // React instantly to kanban or run events from WebSocket.
  useEffect(() => {
    if (!lastMessage) return;
    const t = lastMessage.type;
    if (t.startsWith('kanban.') || t.startsWith('run.')) {
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
    }
  }, [lastMessage, queryClient]);

  const createMutation = useMutation({
    mutationFn: (request: CreateKanbanItemRequest) => createKanbanItem(request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
      setShowCreate(false);
      setDraft(EMPTY_DRAFT);
      setError(null);
    },
    onError: (err: unknown) => {
      const anyErr = err as { message?: string };
      setError(anyErr?.message ?? 'Failed to create item');
    },
  });

  const grouped = useMemo(() => {
    const map: Record<string, KanbanItem[]> = {};
    COLUMNS.forEach((c) => (map[c.key] = []));
    (items ?? []).forEach((item) => {
      const matched = COLUMNS.find((c) => c.filter(item));
      if (matched) map[matched.key].push(item);
    });
    return map;
  }, [items]);

  const handleCreate = () => {
    const title = draft.title.trim();
    if (!title) {
      setError('Title is required');
      return;
    }
    createMutation.mutate({
      title,
      priority: draft.priority,
      assignee: draft.assignee.trim() || undefined,
    });
  };

  return (
    <section className="panel" style={{ position: 'relative' }}>
      <h2>
        <span>Kanban Board</span>
        <span className="accent">· Governed Flow</span>
        <button
          className="btn primary"
          style={{ marginLeft: 'auto' }}
          onClick={() => {
            setShowCreate(true);
            setError(null);
          }}
        >
          + New Item
        </button>
      </h2>

      <div className="kanban">
        <div className="kanban-grid">
          {COLUMNS.map((col) => {
            const columnItems = grouped[col.key] ?? [];
            return (
              <div
                key={col.key}
                className={`col-k${col.isGate ? ' gate' : ''}`}
                data-col={col.key}
              >
                <header>
                  <span>{col.label}</span>
                  <span className="count">{columnItems.length}</span>
                </header>
                <div className="lane">
                  {isLoading && columnItems.length === 0 ? null : null}
                  {columnItems.map((item) => (
                    <div
                      key={item.id}
                      className={`card${col.isGate ? ' gate' : ''}${
                        item.status === 'BLOCKED' ? ' blocked' : ''
                      }${item.status === 'DONE' ? ' done' : ''}`}
                      data-card={item.id}
                      onClick={() => dispatchOpenTaskDrawer(item.id)}
                    >
                      <div className="gateline" />
                      <div className="id">{item.id.slice(0, 8)}</div>
                      <div className="t">{item.title}</div>
                      <div className="meta">
                        <span className={priorityPillClass(item.priority)}>
                          {item.priority}
                        </span>
                        {item.assignee && (
                          <span className="owner">@{item.assignee}</span>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {showCreate && (
        <div
          className="modal-overlay"
          onClick={() => {
            setShowCreate(false);
            setError(null);
          }}
        >
          <div className="modal-dialog" onClick={(e) => e.stopPropagation()}>
            <h3>New Kanban Item</h3>
            <div className="kanban-form">
              <label className="kanban-form-row">
                <span>Title *</span>
                <input
                  autoFocus
                  value={draft.title}
                  onChange={(e) => setDraft({ ...draft, title: e.target.value })}
                />
              </label>
              <label className="kanban-form-row">
                <span>Priority</span>
                <select
                  value={draft.priority}
                  onChange={(e) =>
                    setDraft({ ...draft, priority: e.target.value as KanbanPriority })
                  }
                >
                  <option value="LOW">LOW</option>
                  <option value="MEDIUM">MEDIUM</option>
                  <option value="HIGH">HIGH</option>
                  <option value="CRITICAL">CRITICAL</option>
                </select>
              </label>
              <label className="kanban-form-row">
                <span>Assignee</span>
                <input
                  value={draft.assignee}
                  onChange={(e) => setDraft({ ...draft, assignee: e.target.value })}
                />
              </label>
              {error && <div className="kanban-form-error">{error}</div>}
              <div className="modal-actions">
                <button
                  className="btn primary"
                  onClick={handleCreate}
                  disabled={createMutation.isPending}
                >
                  {createMutation.isPending ? 'Creating…' : 'Create'}
                </button>
                <button
                  className="btn"
                  onClick={() => {
                    setShowCreate(false);
                    setError(null);
                  }}
                >
                  Cancel
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
