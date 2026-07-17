import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { initDoD } from '../api/dod';
import { EvidenceDrawer } from '../components/EvidenceDrawer';
import type { DoDRecord } from '../types';

/**
 * Standalone DoD / evidence workspace. Lookup any task id to inspect its
 * stage gate, attach evidence, or submit a stage review. New tasks can be
 * initialized inline.
 */
export function DoDPage() {
  const [taskInput, setTaskInput] = useState('');
  const [activeTaskId, setActiveTaskId] = useState<string | null>(null);
  const [initTaskType, setInitTaskType] = useState('');
  const [initFeedback, setInitFeedback] = useState<string | null>(null);

  const initMutation = useMutation({
    mutationFn: (variables: { taskId: string; taskType?: string }) =>
      initDoD({ taskId: variables.taskId, taskType: variables.taskType || undefined }),
    onSuccess: (record: DoDRecord) => {
      setActiveTaskId(record.taskId);
      setInitFeedback(`DoD ready · current stage: ${record.currentStage}`);
    },
    onError: () => {
      setInitFeedback('Failed to initialize DoD. Check the task id and try again.');
    },
  });

  const handleLookup = () => {
    const trimmed = taskInput.trim();
    if (!trimmed) return;
    setActiveTaskId(trimmed);
    setInitFeedback(null);
  };

  const handleInit = () => {
    const trimmed = taskInput.trim();
    if (!trimmed) return;
    initMutation.mutate({ taskId: trimmed, taskType: initTaskType.trim() });
  };

  return (
    <div className="page dod-page">
      <div className="page-header">
        <h2>Definition of Done</h2>
        <span className="dod-page-subtitle">Stage-gate workflow · evidence dossier</span>
      </div>

      <section className="card dod-lookup-card">
        <div className="dod-lookup-row">
          <input
            className="dod-input dod-lookup-input"
            placeholder="Enter task id (e.g. STORY-1234)…"
            value={taskInput}
            onChange={(e) => setTaskInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') handleLookup();
            }}
          />
          <input
            className="dod-input dod-lookup-type"
            placeholder="Task type (optional)"
            value={initTaskType}
            onChange={(e) => setInitTaskType(e.target.value)}
          />
          <button className="btn" onClick={handleLookup} disabled={!taskInput.trim()}>
            Open
          </button>
          <button
            className="btn btn-success"
            onClick={handleInit}
            disabled={!taskInput.trim() || initMutation.isPending}
          >
            {initMutation.isPending ? 'Initializing…' : 'Initialize DoD'}
          </button>
        </div>
        {initFeedback && <div className="dod-lookup-feedback">{initFeedback}</div>}
      </section>

      {activeTaskId ? (
        <EvidenceDrawer taskId={activeTaskId} />
      ) : (
        <div className="card dod-placeholder">
          <div className="dod-placeholder-mark">DoD</div>
          <div className="dod-placeholder-text">
            Open a task to inspect its stage gate, reviewer log, and evidence collection.
          </div>
        </div>
      )}
    </div>
  );
}
