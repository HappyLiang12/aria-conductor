import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  listJobs, createJob, updateJob, deleteJob, pauseJob, resumeJob,
} from '../api/ariaJobs';
import type { ScheduledJob, CreateScheduledJobRequest, JobCategory, JobStatus } from '../types';

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

export function ScheduledJobsPage() {
  const queryClient = useQueryClient();
  const [categoryFilter, setCategoryFilter] = useState<TabFilter>('ALL');
  const [statusFilter, setStatusFilter] = useState<JobStatus | ''>('');
  const [modalOpen, setModalOpen] = useState(false);
  const [editingJob, setEditingJob] = useState<ScheduledJob | null>(null);

  const categoryParam = categoryFilter === 'ALL' ? undefined : categoryFilter;
  const statusParam = statusFilter || undefined;

  const { data: jobs = [], isLoading, isError } = useQuery({
    queryKey: ['scheduled-jobs', categoryParam, statusParam],
    queryFn: () => listJobs({ category: categoryParam, status: statusParam }),
  });

  const deleteMut = useMutation({ mutationFn: deleteJob, onSuccess: () => queryClient.invalidateQueries({ queryKey: ['scheduled-jobs'] }), onError: (err: unknown) => { alert(`Operation failed: ${(err as Error)?.message || 'Unknown error'}`); } });
  const pauseMut = useMutation({ mutationFn: pauseJob, onSuccess: () => queryClient.invalidateQueries({ queryKey: ['scheduled-jobs'] }), onError: (err: unknown) => { alert(`Operation failed: ${(err as Error)?.message || 'Unknown error'}`); } });
  const resumeMut = useMutation({ mutationFn: resumeJob, onSuccess: () => queryClient.invalidateQueries({ queryKey: ['scheduled-jobs'] }), onError: (err: unknown) => { alert(`Operation failed: ${(err as Error)?.message || 'Unknown error'}`); } });

  const categoryEmoji: Record<string, string> = { REMINDER: '🔔', MONITOR: '📊', BRIEF: '📋' };

  const fmtLocal = (iso: string | null): string => {
    if (!iso) return '—';
    return new Date(iso).toLocaleString();
  };

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
    onError: (err: unknown) => { alert(`Create failed: ${(err as Error)?.message || 'Unknown error'}`); },
  });
  const updateMut = useMutation({
    mutationFn: (req: { id: string; data: CreateScheduledJobRequest }) => updateJob(req.id, req.data),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['scheduled-jobs'] }); setModalOpen(false); },
    onError: (err: unknown) => { alert(`Update failed: ${(err as Error)?.message || 'Unknown error'}`); },
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
      <div className="page-header">
        <h2>📅 Scheduled Jobs</h2>
        <button className="btn primary" onClick={openCreateModal}>+ New Job</button>
      </div>

      <div className="scheduled-jobs-toolbar">
        <div className="filter-tabs">
          {(['ALL', 'REMINDER', 'MONITOR', 'BRIEF'] as const).map(tab => (
            <button
              key={tab}
              className={`filter-tab${categoryFilter === tab ? ' active' : ''}`}
              onClick={() => setCategoryFilter(tab)}
            >
              {tab === 'ALL' ? 'All' : `${categoryEmoji[tab] || ''} ${tab}`}
            </button>
          ))}
        </div>
        <select className="select-filter" value={statusFilter} onChange={e => setStatusFilter(e.target.value as JobStatus | '')}>
          <option value="">All Status</option>
          <option value="ACTIVE">Active</option>
          <option value="PAUSED">Paused</option>
          <option value="COMPLETED">Completed</option>
        </select>
      </div>

      {isLoading && <div className="loading-spinner"><div className="spinner" /><span>Loading jobs…</span></div>}
      {isError && <div className="evidence-error">Failed to load jobs.</div>}

      {!isLoading && !isError && jobs.length === 0 && (
        <div className="card" style={{ textAlign: 'center', padding: '2.5rem 1.5rem' }}>
          <p style={{ color: 'var(--text-dim)' }}>No scheduled jobs found.</p>
        </div>
      )}

      <div className="job-grid">
        {jobs.map(job => (
          <div key={job.id} className="job-card card">
            <div className="job-card-header">
              <span className="job-card-emoji">{categoryEmoji[job.category] || '📌'}</span>
              <div className="job-card-title">{job.title}</div>
              <span className={`pill ${job.status === 'ACTIVE' ? 'ok' : job.status === 'PAUSED' ? 'warn' : ''}`}>{job.status}</span>
            </div>
            <div className="job-card-cron">{job.scheduleExpression}</div>
            <div className="job-card-meta">
              <span>Next: {fmtLocal(job.nextFireAt)}</span>
              <span>Last: {fmtLocal(job.lastFiredAt)}</span>
            </div>
            <div className="job-card-actions">
              {job.status === 'ACTIVE' && (
                <button className="btn sm" onClick={() => pauseMut.mutate(job.id)} disabled={pauseMut.isPending}>
                  {pauseMut.isPending ? 'Pausing…' : '⏸ Pause'}
                </button>
              )}
              {job.status === 'PAUSED' && (
                <button className="btn sm primary" onClick={() => resumeMut.mutate(job.id)} disabled={resumeMut.isPending}>
                  {resumeMut.isPending ? 'Resuming…' : '▶ Resume'}
                </button>
              )}
              <button className="btn sm" onClick={() => openEditModal(job)}>✏️ Edit</button>
              <button className="btn sm danger" onClick={() => { if (confirm('Delete this job?')) deleteMut.mutate(job.id); }} disabled={deleteMut.isPending}>
                {deleteMut.isPending ? 'Deleting…' : '🗑 Delete'}
              </button>
            </div>
          </div>
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
            <div className="modal-body" style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <label>
                Title
                <input required value={form.title} onChange={e => updateForm('title', e.target.value)} placeholder="Daily summary brief" />
              </label>
              <label>
                Category
                <select value={form.category} onChange={e => updateForm('category', e.target.value as JobCategory)}>
                  <option value="REMINDER">🔔 Reminder</option>
                  <option value="MONITOR">📊 Monitor</option>
                  <option value="BRIEF">📋 Brief</option>
                </select>
              </label>
              <fieldset style={{ border: 'none', padding: 0, display: 'flex', gap: 16 }}>
                <legend style={{ fontSize: '0.85rem', fontWeight: 500, marginBottom: 4 }}>Schedule Type</legend>
                <label style={{ display: 'flex', alignItems: 'center', gap: 4, cursor: 'pointer' }}>
                  <input type="radio" name="scheduleType" value="ONE_SHOT"
                    checked={form.scheduleType === 'ONE_SHOT'}
                    onChange={() => updateForm('scheduleType', 'ONE_SHOT')} />
                  One-Shot
                </label>
                <label style={{ display: 'flex', alignItems: 'center', gap: 4, cursor: 'pointer' }}>
                  <input type="radio" name="scheduleType" value="RECURRING"
                    checked={form.scheduleType === 'RECURRING'}
                    onChange={() => updateForm('scheduleType', 'RECURRING')} />
                  Recurring
                </label>
              </fieldset>
              <label>
                Schedule Expression {form.scheduleType === 'RECURRING' && <span style={{ color: 'var(--text-dim)', fontSize: '0.75rem' }}>(cron: min hour day month weekday)</span>}
                <input required value={form.scheduleExpression}
                  onChange={e => updateForm('scheduleExpression', e.target.value)}
                  placeholder={form.scheduleType === 'ONE_SHOT' ? '2026-07-15T09:00:00' : '0 9 * * *'} />
              </label>
              {/* Cron Preview */}
              {form.scheduleType === 'RECURRING' && form.scheduleExpression && (
                <div className="cron-preview">
                  <div className="cron-preview-label">Next 3 runs:</div>
                  {cronPreview === null ? (
                    <div className="cron-preview-item" style={{ color: 'var(--red)' }}>Invalid expression</div>
                  ) : (
                    cronPreview.map((d, i) => (
                      <div key={i} className="cron-preview-item">{d.toLocaleString()}</div>
                    ))
                  )}
                </div>
              )}
              <label>
                Notification Title
                <input required value={form.notificationTitle} onChange={e => updateForm('notificationTitle', e.target.value)} placeholder="Daily brief ready" />
              </label>
              <label>
                Notification Body <span style={{ color: 'var(--text-dim)', fontSize: '0.75rem' }}>(optional)</span>
                <textarea rows={3} value={form.notificationBody} onChange={e => updateForm('notificationBody', e.target.value)} placeholder="Your daily summary is ready for review." />
              </label>
            </div>
            <div className="modal-footer">
              <button className="btn" type="button" onClick={() => setModalOpen(false)}>Cancel</button>
              <button className="btn primary" type="submit"
                disabled={createMut.isPending || updateMut.isPending}>
                {createMut.isPending || updateMut.isPending ? 'Saving…' : editingJob ? 'Update' : 'Create'}
              </button>
            </div>
          </form>
          </div>
        </div>
      )}
    </div>
  );
}

export default ScheduledJobsPage;
