import { describe, expect, it } from 'vitest';
import { mergeStableOrder } from '../stableOrder';

const a = { id: 'a' };
const b = { id: 'b' };
const c = { id: 'c' };

describe('mergeStableOrder', () => {
  it('keeps known rows in place and appends new rows while locked', () => {
    expect(mergeStableOrder([a, b], [b, c, a], true).map((x) => x.id)).toEqual(['a', 'b', 'c']);
  });

  it('follows the server order when unlocked', () => {
    expect(mergeStableOrder([a, b], [b, c, a], false).map((x) => x.id)).toEqual(['b', 'c', 'a']);
  });
});
