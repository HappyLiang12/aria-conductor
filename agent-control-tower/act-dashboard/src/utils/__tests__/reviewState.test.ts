import { describe, expect, it } from 'vitest';
import { openAskCount, reviewNeedsHuman } from '../reviewState';
import type { KanbanItem } from '../../types';

/**
 * F24 (SDD hardening): completed workflow step cards stayed in REVIEW with
 * `pendingAskCount = 0`, so every REVIEW badge read as "a human must act" when
 * there was no ask to act on. REVIEW is a badge only while an ask is open; the
 * linked run's outcome carries the rest of the story.
 */
const item = (over: Partial<KanbanItem>): KanbanItem =>
  ({ id: 'k-1', status: 'REVIEW', pendingAskCount: null, ...over } as KanbanItem);

describe('review state (F24)', () => {
  it('counts only a positive, finite pendingAskCount', () => {
    expect(openAskCount(item({ pendingAskCount: 2 }))).toBe(2);
    expect(openAskCount(item({ pendingAskCount: 0 }))).toBe(0);
    expect(openAskCount(item({ pendingAskCount: null }))).toBe(0);
    expect(openAskCount(item({}))).toBe(0);
  });

  it('needs a human only for a REVIEW card with an open ask', () => {
    expect(reviewNeedsHuman(item({ status: 'REVIEW', pendingAskCount: 1 }))).toBe(true);
    expect(reviewNeedsHuman(item({ status: 'REVIEW', pendingAskCount: 0 }))).toBe(false);
    expect(reviewNeedsHuman(item({ status: 'REVIEW', pendingAskCount: null }))).toBe(false);
    // An ask on another column (mid-run gate) is not a REVIEW badge.
    expect(reviewNeedsHuman(item({ status: 'IN_PROGRESS', pendingAskCount: 2 }))).toBe(false);
  });
});
