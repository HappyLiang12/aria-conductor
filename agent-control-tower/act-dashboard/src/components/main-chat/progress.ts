/**
 * One run's live progress, merged from WS `run.progress` frames and the REST
 * replay. A fragment is an observation of a part (RunProgressEvent): the part
 * id is the identity, `seq` is the order of observations, and a later
 * observation of the same part replaces the earlier one instead of appending.
 * Frames of another run, unknown kinds and observations no newer than the one
 * already held are ignored.
 */
export type ProgressItemKind = 'thinking' | 'answer' | 'tool' | 'status' | 'error';

/** The subset of RunProgressEntry the merge needs (WS payload or REST row). */
export interface ProgressEntryLike {
  runId: string;
  kind: string;
  content?: string | null;
  toolName?: string | null;
  seq: number;
  partId?: string | null;
}

export interface ProgressItem {
  key: string;
  kind: ProgressItemKind;
  seq: number;
  text: string;
  toolName?: string | null;
  toolDone?: boolean;
}

export interface ProgressState {
  runId: string;
  items: ProgressItem[];
  lastSeq: number;
  seen: Record<string, number>;
}

export function emptyProgress(runId: string): ProgressState {
  return { runId, items: [], lastSeq: 0, seen: {} };
}

function partKey(entry: ProgressEntryLike): string {
  return entry.partId ? `p:${entry.partId}` : `s:${entry.seq}`;
}

function kindOf(entryKind: string): ProgressItemKind | null {
  switch (entryKind) {
    case 'THINKING':
      return 'thinking';
    case 'ANSWER':
      return 'answer';
    case 'TOOL_CALL':
    case 'TOOL_RESULT':
      return 'tool';
    case 'STATUS':
      return 'status';
    case 'ERROR':
      return 'error';
    default:
      return null;
  }
}

export function applyProgress(state: ProgressState, entry: ProgressEntryLike): ProgressState {
  if (!entry || entry.runId !== state.runId) {
    return state;
  }
  const seq = entry.seq;
  if (typeof seq !== 'number' || !Number.isFinite(seq) || seq <= 0) {
    return state;
  }
  const key = partKey(entry);
  const seenSeq = state.seen[key];
  if (seenSeq !== undefined && seq <= seenSeq) {
    return state;
  }
  const kind = kindOf(entry.kind);
  if (!kind) {
    return state;
  }

  const existingIndex = state.items.findIndex((item) => item.key === key);
  const existing = existingIndex >= 0 ? state.items[existingIndex] : null;
  const text = entry.content ?? '';
  const item: ProgressItem = kind === 'tool'
    ? {
        key,
        kind,
        seq,
        text,
        toolName: entry.toolName ?? existing?.toolName ?? 'tool',
        toolDone: entry.kind === 'TOOL_RESULT',
      }
    : { key, kind, seq, text };

  const items = existingIndex >= 0
    ? state.items.map((current, index) => (index === existingIndex ? item : current))
    : [...state.items, item];

  return {
    runId: state.runId,
    items,
    lastSeq: Math.max(state.lastSeq, seq),
    seen: { ...state.seen, [key]: seq },
  };
}

/** The answer parts joined in order; what the live bubble shows while the turn runs. */
export function answerText(state: ProgressState): string {
  return state.items
    .filter((item) => item.kind === 'answer' && item.text.trim().length > 0)
    .map((item) => item.text)
    .join('\n\n');
}
