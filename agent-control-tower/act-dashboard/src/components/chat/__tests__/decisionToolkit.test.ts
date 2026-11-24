import { describe, expect, it } from 'vitest';
import { decisionToolkit } from '../decisionToolkit';

describe('decisionToolkit', () => {
  it('registers the three reserved decision tools as standalone backend renderers', () => {
    expect(Object.keys(decisionToolkit).sort()).toEqual(['__approval', '__memory', '__question']);
    for (const name of ['__approval', '__question', '__memory'] as const) {
      const entry = decisionToolkit[name];
      expect(entry.type).toBe('backend');
      // Standalone keeps a decision card out of the collapsed tool group.
      expect(entry.display).toBe('standalone');
      expect(typeof entry.render).toBe('function');
    }
  });
});
