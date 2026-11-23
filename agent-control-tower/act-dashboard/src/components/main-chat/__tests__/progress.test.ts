import { describe, it, expect } from 'vitest';
import { applyProgress, answerText, emptyProgress, type ProgressEntryLike } from '../progress';

function entry(overrides: Partial<ProgressEntryLike> = {}): ProgressEntryLike {
  return { runId: 'run-1', kind: 'ANSWER', content: 'x', seq: 1, partId: 'p1', ...overrides };
}

describe('applyProgress', () => {
  it('appends entries in arrival order and names their kinds', () => {
    let state = emptyProgress('run-1');
    state = applyProgress(state, entry({ kind: 'THINKING', content: 'weighing options', seq: 1, partId: 't1' }));
    state = applyProgress(state, entry({ kind: 'TOOL_CALL', toolName: 'bash', content: 'ls', seq: 2, partId: 'tool1' }));
    state = applyProgress(state, entry({ kind: 'ANSWER', content: 'Done.', seq: 3, partId: 'a1' }));

    expect(state.items.map((i) => i.kind)).toEqual(['thinking', 'tool', 'answer']);
    expect(state.items[1]).toMatchObject({ kind: 'tool', toolName: 'bash', toolDone: false });
    expect(state.lastSeq).toBe(3);
  });

  it('grows one answer part in place instead of appending a duplicate', () => {
    let state = emptyProgress('run-1');
    state = applyProgress(state, entry({ content: 'Raft needs', seq: 1, partId: 'a1' }));
    state = applyProgress(state, entry({ content: 'Raft needs a majority.', seq: 2, partId: 'a1' }));

    expect(state.items).toHaveLength(1);
    expect(state.items[0].text).toBe('Raft needs a majority.');
    expect(state.items[0].seq).toBe(2);
  });

  it('ignores a stale or duplicate observation of a part', () => {
    let state = emptyProgress('run-1');
    state = applyProgress(state, entry({ content: 'final text', seq: 5, partId: 'a1' }));
    const stale = applyProgress(state, entry({ content: 'older text', seq: 4, partId: 'a1' }));
    const duplicate = applyProgress(state, entry({ content: 'same seq', seq: 5, partId: 'a1' }));

    expect(stale).toBe(state);
    expect(duplicate).toBe(state);
  });

  it('ignores frames that belong to another run', () => {
    const state = emptyProgress('run-1');
    expect(applyProgress(state, entry({ runId: 'run-2' }))).toBe(state);
  });

  it('turns the same tool part from running to done when its result arrives', () => {
    let state = emptyProgress('run-1');
    state = applyProgress(state, entry({ kind: 'TOOL_CALL', toolName: 'read_file', content: 'runs.ts', seq: 1, partId: 'tool1' }));
    state = applyProgress(state, entry({ kind: 'TOOL_RESULT', toolName: 'read_file', content: 'export {}', seq: 2, partId: 'tool1' }));

    expect(state.items).toHaveLength(1);
    expect(state.items[0]).toMatchObject({ kind: 'tool', toolName: 'read_file', toolDone: true, text: 'export {}' });
  });

  it('keeps rows without a part id distinct by their seq', () => {
    let state = emptyProgress('run-1');
    state = applyProgress(state, entry({ kind: 'STATUS', content: 'step 1', seq: 1, partId: null }));
    state = applyProgress(state, entry({ kind: 'STATUS', content: 'step 2', seq: 2, partId: null }));

    expect(state.items).toHaveLength(2);
  });

  it('treats an ERROR fragment as an error row', () => {
    const state = applyProgress(emptyProgress('run-1'),
        entry({ kind: 'ERROR', content: 'sandbox exploded', seq: 1, partId: 'e1' }));
    expect(state.items[0].kind).toBe('error');
  });

  it('is idempotent when the REST replay repeats observations already seen live', () => {
    const frames = [
      entry({ content: 'Raft needs', seq: 1, partId: 'a1' }),
      entry({ content: 'Raft needs a majority.', seq: 2, partId: 'a1' }),
    ];
    let live = emptyProgress('run-1');
    frames.forEach((frame) => { live = applyProgress(live, frame); });
    let replayed = emptyProgress('run-1');
    frames.forEach((frame) => { replayed = applyProgress(replayed, frame); });
    frames.forEach((frame) => { live = applyProgress(live, frame); });

    expect(live.items).toHaveLength(1);
    expect(live.items[0].text).toBe('Raft needs a majority.');
    expect(live).toEqual(replayed);
  });
});

describe('answerText', () => {
  it('joins every answer part in order and ignores other kinds', () => {
    let state = emptyProgress('run-1');
    state = applyProgress(state, entry({ content: 'first', seq: 1, partId: 'a1' }));
    state = applyProgress(state, entry({ kind: 'TOOL_CALL', toolName: 'bash', content: 'ls', seq: 2, partId: 't1' }));
    state = applyProgress(state, entry({ content: 'second', seq: 3, partId: 'a2' }));

    expect(answerText(state)).toBe('first\n\nsecond');
  });

  it('returns an empty string when the turn has produced no answer yet', () => {
    expect(answerText(emptyProgress('run-1'))).toBe('');
  });
});
