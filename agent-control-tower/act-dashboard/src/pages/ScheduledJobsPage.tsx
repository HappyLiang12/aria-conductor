import { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  listJobs, createJob, updateJob, deleteJob, pauseJob, resumeJob,
} from '../api/ariaJobs';
import type { ScheduledJob, CreateScheduledJobRequest, JobCategory, JobStatus } from '../types';
import { apiErrorMessage } from '../api/operatorSession';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { Badge } from '../components/ui/badge';
import { Button } from '../components/ui/button';
import { Card } from '../components/ui/card';
import { Input } from '../components/ui/input';
import { Textarea } from '../components/ui/textarea';
import { formatTimestamp } from '../utils/formatTime';

/** Parse a single cron field into an array of allowed values. */
function parseCronField(field: string, min: number, max: number): number[] {
  const values = new Set<number>();
  const parts = field.split(',');
  for (const part of parts) {
    if (part === '*') {
      for (let i = min; i <= max; i++) values.add(i);
    } else if (part.includes('/')) {
      const [range, stepStr] = part.split('/');
      const step = parseInt(stepStr, 10);
      if (!step || step < 1) continue; // guard against */0 freeze
      const [start, end] = range === '*' ? [min, max] : range.split('-').map(Number);
      for (let i = isNaN(start) ? min : start; i <= (isNaN(end) ? max : end); i += step) {
        values.add(i);
      }
    } else if (part.includes('-')) {
      const [start, end] = part.split('-').map(Number);
      for (let i = start; i <= end; i++) values.add(i);
    } else {
      const v = parseInt(part, 10);
      if (!isNaN(v)) values.add(v);
    }
  }
  return [...values].sort((a, b) => a - b);
}

/** Compute the next N fire times from a 5-field cron expression. */
function getNextCronRuns(expr: string, count = 3): Date[] | null {
  const fields = expr.trim().split(/\s+/);
  if (fields.length !== 5) return null;
  try {
    const mins = parseCronField(fields[0], 0, 59);
    const hrs = parseCronField(fields[1], 0, 23);
    const days = parseCronField(fields[2], 1, 31);
    const months = parseCronField(fields[3], 1, 12);
    const dows = parseCronField(fields[4], 0, 6); // 0=Sunday

    const results: Date[] = [];
    const from = new Date();
    from.setSeconds(0, 0);
    from.setMinutes(from.getMinutes() + 1); // start from next minute

    // search forward up to 2 years
    const limit = new Date(from);
    limit.setFullYear(limit.getFullYear() + 2);

    let cursor = new Date(from);
    while (results.length < count && cursor <= limit) {
      const m = cursor.getMonth() + 1;
      const d = cursor.getDate();
      const h = cursor.getHours();
      const mn = cursor.getMinutes();
      const dw = cursor.getDay();

      if (months.includes(m) && days.includes(d) && hrs.includes(h) && mins.includes(mn) && dows.includes(dw)) {
        results.push(new Date(cursor));
      }
      cursor.setMinutes(cursor.getMinutes() + 1);
    }
    return results.length > 0 ? results : null;
  } catch {
    return null;
  }
}

type TabFilter = 'ALL' | JobCategory;

/** Status tone carried by a dot so it survives the Badge variant's own colours. */
function statusDot(status: JobStatus): string {
  if (status === 'ACTIVE') return 'bg-success';
  if (status === 'PAUSED') return 'bg-warning';
  return 'bg-muted-foreground';
}

export function ScheduledJobsPage() {
  const queryClient = useQueryClient();
  const [categoryFilter, setCategoryFilter] = useState<TabFilter>('ALL');
  const [statusFilter, setStatusFilter] = useState<JobStatus | ''>('');
  const [modalOpen, setModalOpen] = useState(false);
  const [editingJob, setEditingJob] = useState<ScheduledJob | null>(null);
  // Job awaiting delete confirmation. Deletion is irreversible, so the control
  // only opens the shared dialog (Task 10).
  const [deletingJob, setDeletingJob] = useState<ScheduledJob | null>(null);
  // Operation feedback — the page has no global toast, so keep a local notice
  // (replaces the previous blocking alert() calls).
  const [toastMsg, setToastMsg] = useState<string | null>(null);

  useEffect(() => {
    if (!toastMsg) return;
    const t = setTimeout(() => setToastMsg(null), 2800);
    return () => clearTimeout(t);
  }, [toastMsg]);

  const categoryParam = categoryFilter === 'ALL' ? undefined : categoryFilter;
  const statusParam = statusFilter || undefined;

  const { data: jobs = [], isLoading, isError, error: jobsError } = useQuery({
    queryKey: ['scheduled-jobs', categoryParam, statusParam],
    queryFn: () => listJobs({ category: categoryParam, status: statusParam }),
  });

  const deleteMut = useMutation({ mutationFn: deleteJob, onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['scheduled-jobs'] }); setDeletingJob(null); }, onError: (err: unknown) => { setToastMsg(apiErrorMessage(err, (err as Error)?.message ?? 'Operation failed.')); setDeletingJob(null); } });
  const pauseMut = useMutation({ mutationFn: pauseJob, onSuccess: () => queryClient.invalidateQueries({ queryKey: ['scheduled-jobs'] }), onError: (err: unknown) => { setToastMsg(apiErrorMessage(err, (err as Error)?.message ?? 'Operation failed.')); } });
  const resumeMut = useMutation({ mutationFn: resumeJob, onSuccess: () => queryClient.invalidateQueries({ queryKey: ['scheduled-jobs'] }), onError: (err: unknown) => { setToastMsg(apiErrorMessage(err, (err as Error)?.message ?? 'Operation failed.')); } });

  const categoryEmoji: Record<string, string> = { REMINDER: '🔔', MONITOR: '📊', BRIEF: '📋' };

  const [form, setForm] = useState<CreateScheduledJobRequest>({
    scheduleType: 'RECURRING', category: 'REMINDER', title: '',
    scheduleExpression: '', notificationTitle: '', notificationBody: '',
  });
  const [cronPreview, setCronPreview] = useState<Date[] | null>(null);

  const openCreateModal = () => {
    setEditingJob(null);
    setForm({ scheduleType: 'RECURRING', category: 'REMINDER', title: '', scheduleExpression: '', notificationTitle: '', notificationBody: '' });
    setCronPreview(null);
    setModalOpen(true);
  };

  const openEditModal = (job: ScheduledJob) => {
    setEditingJob(job);
    setForm({
      scheduleType: job.scheduleType,
      category: job.category,
      title: job.title,
      scheduleExpression: job.scheduleExpression,
      notificationTitle: job.notificationTitle,
      notificationBody: job.notificationBody ?? '',
    });
    if (job.scheduleType === 'RECURRING') {
      setCronPreview(getNextCronRuns(job.scheduleExpression));
    } else {
      setCronPreview(null);
    }
    setModalOpen(true);
  };

  const updateForm = <K extends keyof CreateScheduledJobRequest>(key: K, value: CreateScheduledJobRequest[K]) => {
    setForm(prev => {
      const next = { ...prev, [key]: value };
      if (key === 'scheduleExpression' && next.scheduleType === 'RECURRING') {
        setCronPreview(getNextCronRuns(value as string));
      } else if (key === 'scheduleType') {
        if (value === 'RECURRING' && next.scheduleExpression) {
          setCronPreview(getNextCronRuns(next.scheduleExpression));
        } else {
          setCronPreview(null);
        }
      }
      return next;
    });
  };

  const createMut = useMutation({
    mutationFn: createJob,
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['scheduled-jobs'] }); setModalOpen(false); },
    onError: (err: unknown) => { setToastMsg(`Create failed: ${(err as Error)?.message || 'Unknown error'}`); },
  });
  const updateMut = useMutation({
    mutationFn: (req: { id: string; data: CreateScheduledJobRequest }) => updateJob(req.id, req.data),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['scheduled-jobs'] }); setModalOpen(false); },
    onError: (err: unknown) => { setToastMsg(`Update failed: ${(err as Error)?.message || 'Unknown error'}`); },
  });

  const handleSubmit = () => {
    if (editingJob) {
      updateMut.mutate({ id: editingJob.id, data: form });
    } else {
      createMut.mutate(form);
    }
  };

  return (
    <div className="page scheduled-jobs-page">
      {/* The header keeps `.page-header` + `h2` (E2E pins `.page-header h2`). */}
      <div className="page-header">
        <h2 className="flex items-center gap-2">📅 Scheduled Jobs</h2>
        <Button size="sm" onClick={openCreateModal}>+ New Job</Button>
      </div>

      <div className="scheduled-jobs-toolbar">
        <div className="filter-tabs">
          {(['ALL', 'REMINDER', 'MONITOR', 'BRIEF'] as const).map(tab => (
            <button
              key={tab}
              type="button"
              aria-pressed={categoryFilter === tab}
              className={`filter-tab${categoryFilter === tab ? ' active' : ''}`}
              onClick={() => setCategoryFilter(tab)}
            >
              {tab === 'ALL' ? 'All' : `${categoryEmoji[tab] || ''} ${tab}`}
            </button>
          ))}
        </div>
        <select
          className="select-filter h-8 rounded-lg border border-input bg-transparent px-2.5 text-sm text-foreground"
          value={statusFilter}
          onChange={e => setStatusFilter(e.target.value as JobStatus | '')}
        >
          <option value="">All Status</option>
          <option value="ACTIVE">Active</option>
          <option value="PAUSED">Paused</option>
          <option value="COMPLETED">Completed</option>
        </select>
      </div>

      {isLoading && <div className="loading-spinner"><div className="spinner" /><span>Loading jobs…</span></div>}
      {isError && <div className="evidence-error">{apiErrorMessage(jobsError, 'Failed to load jobs.')}</div>}

      {!isLoading && !isError && jobs.length === 0 && (
        <div className="rounded-xl border border-dashed border-border px-6 py-10 text-center">
          <p className="text-sm text-muted-foreground">No scheduled jobs found.</p>
        </div>
      )}

      <div className="job-grid">
        {jobs.map(job => (
          <Card key={job.id} className="job-card">
            <div className="flex flex-col gap-2 px-(--card-spacing)">
              <div className="job-card-header flex items-center gap-2">
                <span className="job-card-emoji text-xl" aria-hidden="true">{categoryEmoji[job.category] || '📌'}</span>
                <div className="job-card-title min-w-0 flex-1 truncate text-sm font-semibold text-card-foreground">{job.title}</div>
                <Badge
                  variant="outline"
                  className={`pill gap-1.5 ${job.status === 'ACTIVE' ? 'ok' : job.status === 'PAUSED' ? 'warn' : ''}`}
                >
                  <span className={`size-1.5 rounded-full ${statusDot(job.status)}`} aria-hidden="true" />
                  {job.status}
                </Badge>
              </div>
              <div className="job-card-cron rounded-md bg-muted px-2.5 py-1 font-mono text-xs text-foreground">
                {job.scheduleExpression}
              </div>
              <div className="job-card-meta flex justify-between text-[11px] text-muted-foreground">
                <span>Next: {formatTimestamp(job.nextFireAt)}</span>
                <span>Last: {formatTimestamp(job.lastFiredAt)}</span>
              </div>
              <div className="job-card-actions flex flex-wrap gap-1.5 border-t border-border pt-1.5">
                {job.status === 'ACTIVE' && (
                  <Button variant="outline" size="sm" onClick={() => pauseMut.mutate(job.id)} disabled={pauseMut.isPending}>
                    {pauseMut.isPending ? 'Pausing…' : '⏸ Pause'}
                  </Button>
                )}
                {job.status === 'PAUSED' && (
                  <Button size="sm" onClick={() => resumeMut.mutate(job.id)} disabled={resumeMut.isPending}>
                    {resumeMut.isPending ? 'Resuming…' : '▶ Resume'}
                  </Button>
                )}
                <Button variant="outline" size="sm" onClick={() => openEditModal(job)}>✏️ Edit</Button>
                <Button variant="destructive" size="sm" onClick={() => setDeletingJob(job)} disabled={deleteMut.isPending}>
                  {deleteMut.isPending ? 'Deleting…' : '🗑 Delete'}
                </Button>
              </div>
            </div>
          </Card>
        ))}
      </div>

      {/* Create/Edit Modal */}
      {modalOpen && (
        <div className="modal-overlay" onClick={() => setModalOpen(false)}>
          <div className="modal-dialog" onClick={e => e.stopPropagation()} style={{ maxWidth: 520 }}>
            <div className="modal-header">
              <h3>{editingJob ? 'Edit Job' : 'New Job'}</h3>
              <button className="close" onClick={() => setModalOpen(false)}>✕</button>
            </div>
          <form onSubmit={(e) => { e.preventDefault(); handleSubmit(); }}>
            <div className="modal-body flex flex-col gap-3">
              <label className="flex flex-col gap-1 text-sm text-muted-foreground">
                Title
                <Input required value={form.title} onChange={e => updateForm('title', e.target.value)} placeholder="Daily summary brief" />
              </label>
              <label className="flex flex-col gap-1 text-sm text-muted-foreground">
                Category
                <select
                  className="h-8 rounded-lg border border-input bg-transparent px-2.5 text-sm text-foreground"
                  value={form.category}
                  onChange={e => updateForm('category', e.target.value as JobCategory)}
                >
                  <option value="REMINDER">🔔 Reminder</option>
                  <option value="MONITOR">📊 Monitor</option>
                  <option value="BRIEF">📋 Brief</option>
                </select>
              </label>
              <fieldset className="flex gap-4 border-0 p-0">
                <legend className="mb-1 text-sm font-medium text-foreground">Schedule Type</legend>
                <label className="flex cursor-pointer items-center gap-1 text-sm text-muted-foreground">
                  <input type="radio" name="scheduleType" value="ONE_SHOT"
                    checked={form.scheduleType === 'ONE_SHOT'}
                    onChange={() => updateForm('scheduleType', 'ONE_SHOT')} />
                  One-Shot
                </label>
                <label className="flex cursor-pointer items-center gap-1 text-sm text-muted-foreground">
                  <input type="radio" name="scheduleType" value="RECURRING"
                    checked={form.scheduleType === 'RECURRING'}
                    onChange={() => updateForm('scheduleType', 'RECURRING')} />
                  Recurring
                </label>
              </fieldset>
              <label className="flex flex-col gap-1 text-sm text-muted-foreground">
                Schedule Expression {form.scheduleType === 'RECURRING' && <span className="text-xs text-muted-foreground">(cron: min hour day month weekday)</span>}
                <Input required value={form.scheduleExpression}
                  onChange={e => updateForm('scheduleExpression', e.target.value)}
                  placeholder={form.scheduleType === 'ONE_SHOT' ? '2026-07-15T09:00:00' : '0 9 * * *'} />
              </label>
              {/* Cron Preview */}
              {form.scheduleType === 'RECURRING' && form.scheduleExpression && (
                <div className="cron-preview rounded-md border border-border bg-muted/40 px-3 py-2 text-xs">
                  <div className="cron-preview-label mb-1 font-medium text-muted-foreground">Next 3 runs:</div>
                  {cronPreview === null ? (
                    <div className="cron-preview-item text-destructive">Invalid expression</div>
                  ) : (
                    cronPreview.map((d, i) => (
                      <div key={i} className="cron-preview-item py-px font-mono text-foreground">{formatTimestamp(d.toISOString())}</div>
                    ))
                  )}
                </div>
              )}
              <label className="flex flex-col gap-1 text-sm text-muted-foreground">
                Notification Title
                <Input required value={form.notificationTitle} onChange={e => updateForm('notificationTitle', e.target.value)} placeholder="Daily brief ready" />
              </label>
              <label className="flex flex-col gap-1 text-sm text-muted-foreground">
                Notification Body <span className="text-xs text-muted-foreground">(optional)</span>
                <Textarea rows={3} value={form.notificationBody} onChange={e => updateForm('notificationBody', e.target.value)} placeholder="Your daily summary is ready for review." />
              </label>
            </div>
            <div className="modal-footer">
              <Button variant="outline" size="sm" type="button" onClick={() => setModalOpen(false)}>Cancel</Button>
              <Button size="sm" type="submit"
                disabled={createMut.isPending || updateMut.isPending}>
                {createMut.isPending || updateMut.isPending ? 'Saving…' : editingJob ? 'Update' : 'Create'}
              </Button>
            </div>
          </form>
          </div>
        </div>
      )}

      {/* Shared destructive-action confirmation (Task 10): the Delete control
          only opens this dialog, and the deletion fires from onConfirm. */}
      <ConfirmDialog
        open={deletingJob !== null}
        title="Delete this job?"
        message={
          <>
            Delete <strong>{deletingJob?.title}</strong>? Its schedule stops
            permanently and it cannot be restored from here.
          </>
        }
        danger
        onConfirm={() => {
          if (!deletingJob) return;
          const id = deletingJob.id;
          // Closed immediately: onSuccess/onError re-clear (idempotent) so a
          // rejection can never strand the dialog open.
          setDeletingJob(null);
          deleteMut.mutate(id);
        }}
        onCancel={() => setDeletingJob(null)}
      />

      {/* ---------- Toast ---------- */}
      {toastMsg && (
        <div
          role="status"
          className="fixed right-6 bottom-6 z-50 rounded-lg border border-border bg-card px-3.5 py-2.5 text-xs text-card-foreground shadow-lg backdrop-blur"
        >
          {toastMsg}
        </div>
      )}
    </div>
  );
}

export default ScheduledJobsPage;
