import { describe, expect, it } from 'vitest';
import { rememberFocus } from '../focusReturn';

describe('rememberFocus', () => {
  it('restores the captured element while it is connected', () => {
    const button = document.createElement('button');
    document.body.append(button);
    button.focus();
    const restore = rememberFocus();
    button.blur();
    expect(restore()).toBe(true);
    expect(document.activeElement).toBe(button);
    button.remove();
  });

  it('reports failure when the captured element is gone', () => {
    const button = document.createElement('button');
    document.body.append(button);
    button.focus();
    const restore = rememberFocus();
    button.remove();
    expect(restore()).toBe(false);
  });
});
