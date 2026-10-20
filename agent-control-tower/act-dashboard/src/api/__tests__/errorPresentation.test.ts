import { describe, it, expect, vi } from 'vitest';
import { apiErrorMessage } from '../operatorSession';
import {
  errorActionFor,
  openConfigure,
  presentError,
  presentRunError,
} from '../errorPresentation';

const CREDENTIAL_TEXT = 'Qoder runtime credential is not configured (qoder:operator).'
  + ' Save a Qoder personal access token in Configure > LLM Providers > Qoder runtime credential.';
const FLEET_TEXT = 'The fleet runtime is mixed; converge it first in Configure > Runtime.';

describe('apiErrorMessage carries the actionable backend detail', () => {
  it('prefers message over the reason phrase served by the shared exception handler', () => {
    const error = {
      response: { data: { error: 'Bad Request', message: CREDENTIAL_TEXT } },
    };

    expect(apiErrorMessage(error, 'fallback')).toBe(CREDENTIAL_TEXT);
  });

  it('still reads the { error } shape used by controller rejections', () => {
    const error = { response: { data: { error: 'Unsupported core credential: other' } } };

    expect(apiErrorMessage(error, 'fallback')).toBe('Unsupported core credential: other');
  });
});

describe('errorActionFor', () => {
  it('maps a missing Qoder credential to Configure > LLM Providers', () => {
    expect(errorActionFor(CREDENTIAL_TEXT)).toEqual({
      tab: 'llm',
      label: 'Open Configure → LLM Providers',
    });
  });

  it('maps fleet runtime refusals to Configure > Runtime', () => {
    expect(errorActionFor(FLEET_TEXT)?.tab).toBe('runtime');
    expect(errorActionFor('Runtime mixing is not allowed: the fleet runs qoder/HOST.')?.tab)
      .toBe('runtime');
    expect(errorActionFor('Unsupported execution mode: qoder/SANDBOX.')?.tab).toBe('runtime');
  });

  it('guesses nothing for an unknown failure', () => {
    expect(errorActionFor('The Qoder bridge event stream ended before the prompt settled'))
      .toBeUndefined();
  });
});

describe('presentRunError', () => {
  it('returns null for an absent or blank message', () => {
    expect(presentRunError(null)).toBeNull();
    expect(presentRunError('   ')).toBeNull();
  });

  it('carries the text and the next step for a known failure', () => {
    expect(presentRunError(CREDENTIAL_TEXT)).toEqual({
      text: CREDENTIAL_TEXT,
      action: { tab: 'llm', label: 'Open Configure → LLM Providers' },
    });
  });
});

describe('presentError', () => {
  it('uses the backend message and classifies it', () => {
    const error = { response: { data: { error: 'Conflict', message: FLEET_TEXT } } };

    expect(presentError(error, 'fallback')).toEqual({
      text: FLEET_TEXT,
      action: { tab: 'runtime', label: 'Open Configure → Runtime' },
    });
  });

  it('falls back without an action when the backend gave no usable text', () => {
    expect(presentError(new Error('network down'), 'Something failed'))
      .toEqual({ text: 'Something failed' });
  });
});

describe('openConfigure', () => {
  it('dispatches the tab-aware configure event the modal listens for', () => {
    const listener = vi.fn();
    window.addEventListener('act:open-configure', listener);

    openConfigure('runtime');

    expect(listener).toHaveBeenCalledTimes(1);
    expect((listener.mock.calls[0][0] as CustomEvent).detail).toEqual({ tab: 'runtime' });
    window.removeEventListener('act:open-configure', listener);
  });
});
