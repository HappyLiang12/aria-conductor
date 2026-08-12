import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createKanbanItem,
  deleteKanbanItem,
  listKanbanItems,
  transitionKanbanItem,
} from '../api/kanban';
import type {
  CreateKanbanItemRequest,
  KanbanItem,
  KanbanPriority,
  KanbanStatus,
} from '../types';
import { dispatchOpenTaskDrawer } from '../components/DrawerContext';

interface ColumnDef {
  status: KanbanStatus;
  label: string;
}

const COLUMNS: ColumnDef[] = [
  { status: 'TODO', label: 'Todo' },
  { status: 'IN_PROGRESS', label: 'In Progress' },
  { status: 'REVIEW', label: 'Review' },
  { status: 'BLOCKED', label: 'Blocked' },
  { status: 'DONE', label: 'Done' },
];

const PRIORITY_OPTIONS: KanbanPriority[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

// Service-side allowed transitions (mirror KanbanService)
const ALLOWED: Record<KanbanStatus, KanbanStatus[]> = {
  TODO: ['IN_PROGRESS', 'BLOCKED', 'CANCELLED'],
  IN_PROGRESS: ['DONE', 'BLOCKED', 'CANCELLED'],
  REVIEW: ['IN_PROGRESS', 'DONE', 'BLOCKED', 'CANCELLED'],
  BLOCKED: ['TODO', 'IN_PROGRESS', 'CANCELLED'],
  DONE: [],
  CANCELLED: [],
};

function priorityClass(priority: KanbanPriority): string {
  return `kanban-priority kanban-priority-${priority.toLowerCase()}`;
}

function parseLabels(labels: string | null): string[] {
  if (!labels) return [];
  return labels.split(',').map((s) => s.trim()).filter(Boolean);
}

interface NewItemFormState {
  title: string;
  priority: KanbanPriority;
  assignee: string;
  labels: string;
  description: string;
}

const EMPTY_FORM: NewItemFormState = {
  title: '',
  priority: 'MEDIUM',
  assignee: '',
  labels: '',
  description: '',
};

export function KanbanPage() {
  const queryClient = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState<NewItemFormState>(EMPTY_FORM);
  const [createError, setCreateError] = useState<string | null>(null);
  const [transitionError, setTransitionError] = useState<string | null>(null);
  const [draggingId, setDraggingId] = useState<string | null>(null);
  const [dragOverColumn, setDragOverColumn] = useState<KanbanStatus | null>(null);

  const { data: items, isLoading, error } = useQuery({
    queryKey: ['kanban-items'],
    queryFn: () => listKanbanItems(),
    refetchInterval: 10000,
  });

  const createMutation = useMutation({
    mutationFn: (request: CreateKanbanItemRequest) => createKanbanItem(request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
      setShowCreate(false);
      setForm(EMPTY_FORM);
      setCreateError(null);
    },
    onError: (err: unknown) => {
      const message = extractErrorMessage(err) ?? 'Failed to create item';
      setCreateError(message);
    },
  });

  const transitionMutation = useMutation({
    mutationFn: ({ id, status }: { id: string; status: KanbanStatus }) =>
      transitionKanbanItem(id, { status }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
      setTransitionError(null);
    },
    onError: (err: unknown) => {
      const message = extractErrorMessage(err) ?? 'Transition failed';
      setTransitionError(message);
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteKanbanItem(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['kanban-items'] });
    },
  });

  const grouped = useMemo(() => {
    const map: Record<KanbanStatus, KanbanItem[]> = {
      TODO: [],
      IN_PROGRESS: [],
      REVIEW: [],
      BLOCKED: [],
      DONE: [],
      CANCELLED: [],
    };
    (items ?? []).forEach((item) => {
      const bucket = map[item.status];
      if (bucket) bucket.push(item);
    });
    return map;
  }, [items]);

  const handleCreate = () => {
    const title = form.title.trim();
    if (!title) {
      setCreateError('Title is required');
      return;
    }
    createMutation.mutate({
      title,
      priority: form.priority,
      assignee: form.assignee.trim() || undefined,
      labels: form.labels.trim() || undefined,
      description: form.description.trim() || undefined,
    });
  };

  const handleDragStart = (item: KanbanItem) => {
    setDraggingId(item.id);
    setTransitionError(null);
  };

  const handleDragEnd = () => {
    setDraggingId(null);
    setDragOverColumn(null);
  };

  const handleDrop = (target: KanbanStatus) => {
    setDragOverColumn(null);
    if (!draggingId || !items) return;
    const item = items.find((it) => it.id === draggingId);
    setDraggingId(null);
    if (!item || item.status === target) return;
    const allowed = ALLOWED[item.status]?.includes(target);
    if (!allowed) {
      setTransitionError(`Cannot move from ${item.status} to ${target}`);
      return;
    }
    transitionMutation.mutate({ id: item.id, status: target });
  };

  return (
    <div className="page kanban-page">
      <div className="page-header">
        <h2>Kanban</h2>
        <div className="page-header-actions">
          <button className="btn btn-success" onClick={() => setShowCreate(true)}>
            + New Item
          </button>
        </div>
      </div>

      {transitionError && (
        <div className="kanban-banner kanban-banner-error">
          {transitionError}
          <button className="kanban-banner-close" onClick={() => setTransitionError(null)}>
            ×
          </button>
        </div>
      )}

      {isLoading && (
        <div className="loading-spinner">
          <div className="spinner" />
          <span>Loading items…</span>
        </div>
      )}
      {error && <div className="error-state">Failed to load kanban items.</div>}

      <div className="kanban-board">
        {COLUMNS.map((col) => {
          const columnItems = grouped[col.status];
          const isDragOver = dragOverColumn === col.status;
          return (
            <div
              key={col.status}
              className={`kanban-column ${isDragOver ? 'kanban-column-over' : ''}`}
              onDragOver={(e) => {
                e.preventDefault();
                setDragOverColumn(col.status);
              }}
              onDragLeave={() => setDragOverColumn((cur) => (cur === col.status ? null : cur))}
              onDrop={() => handleDrop(col.status)}
            >
              <div className="kanban-column-header">
                <span className="kanban-column-title">{col.label}</span>
                <span className="kanban-column-count">{columnItems.length}</span>
              </div>
              <div className="kanban-column-body">
                {columnItems.length === 0 && (
                  <div className="kanban-empty">No items</div>
                )}
                {columnItems.map((item) => (
                  <div
                    key={item.id}
                    className={`kanban-card ${draggingId === item.id ? 'kanban-card-dragging' : ''}`}
                    draggable
                    onDragStart={() => handleDragStart(item)}
                    onDragEnd={handleDragEnd}
                    onClick={() => dispatchOpenTaskDrawer(item.id)}
                  >
                    <div className="kanban-card-header">
                      <span className="kanban-card-title">{item.title}</span>
                      <span className={priorityClass(item.priority)}>{item.priority}</span>
                    </div>
                    {item.assignee && (
                      <div className="kanban-card-assignee">@{item.assignee}</div>
                    )}
                    {parseLabels(item.labels).length > 0 && (
                      <div className="kanban-card-labels">
                        {parseLabels(item.labels).map((label) => (
                          <span key={label} className="kanban-label">
                            {label}
                          </span>
                        ))}
                      </div>
                    )}
                    {item.linkedRunId && (
                      <div className="kanban-card-linked">
                        run · <span className="cell-mono">{item.linkedRunId.slice(0, 8)}</span>
                      </div>
                    )}
                    <div className="kanban-card-footer">
                      <button
                        className="kanban-card-delete"
                        onClick={() => {
                          if (confirm(`Delete "${item.title}"?`)) {
                            deleteMutation.mutate(item.id);
                          }
                        }}
                      >
                        Delete
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          );
        })}
      </div>

      {showCreate && (
        <div
          className="modal-overlay"
          onClick={() => {
            setShowCreate(false);
            setCreateError(null);
          }}
        >
          <div className="modal-dialog" onClick={(e) => e.stopPropagation()}>
            <h3>New Kanban Item</h3>
            <div className="kanban-form">
              <label className="kanban-form-row">
                <span>Title *</span>
                <input
                  value={form.title}
                  autoFocus
                  onChange={(e) => setForm({ ...form, title: e.target.value })}
                />
              </label>
              <label className="kanban-form-row">
                <span>Priority</span>
                <select
                  value={form.priority}
                  onChange={(e) =>
                    setForm({ ...form, priority: e.target.value as KanbanPriority })
                  }
                >
                  {PRIORITY_OPTIONS.map((p) => (
                    <option key={p} value={p}>
                      {p}
                    </option>
                  ))}
                </select>
              </label>
              <label className="kanban-form-row">
                <span>Assignee</span>
                <input
                  value={form.assignee}
                  onChange={(e) => setForm({ ...form, assignee: e.target.value })}
                />
              </label>
              <label className="kanban-form-row">
                <span>Labels (comma-separated)</span>
                <input
                  value={form.labels}
                  onChange={(e) => setForm({ ...form, labels: e.target.value })}
                />
              </label>
              <label className="kanban-form-row">
                <span>Description</span>
                <textarea
                  rows={3}
                  value={form.description}
                  onChange={(e) => setForm({ ...form, description: e.target.value })}
                />
              </label>
              {createError && <div className="kanban-form-error">{createError}</div>}
              <div className="modal-actions">
                <button
                  className="btn btn-success"
                  onClick={handleCreate}
                  disabled={createMutation.isPending}
                >
                  {createMutation.isPending ? 'Creating…' : 'Create'}
                </button>
                <button
                  className="btn"
                  onClick={() => {
                    setShowCreate(false);
                    setCreateError(null);
                  }}
                >
                  Cancel
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function extractErrorMessage(err: unknown): string | null {
  if (typeof err === 'object' && err !== null) {
    const anyErr = err as { response?: { data?: { message?: string; error?: string } }; message?: string };
    return (
      anyErr.response?.data?.message ??
      anyErr.response?.data?.error ??
      anyErr.message ??
      null
    );
  }
  return null;
}
