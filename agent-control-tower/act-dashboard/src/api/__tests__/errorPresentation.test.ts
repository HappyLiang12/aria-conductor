import { describe, it, expect, vi } from 'vitest';
import { apiErrorMessage } from '../operatorSession';
import {
  errorActionFor,
  errorActionForSurface,
  openConfigure,
  presentError,
  presentRunError,
  type ErrorAction,
} from '../errorPresentation';

function tabOf(action?: ErrorAction) {
  return action && 'tab' in action ? action.tab : undefined;
}

function pathOf(action?: ErrorAction) {
  return action && 'path' in action ? action.path : undefined;
}

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

describe('errorActionForSurface', () => {
  it('maps every served surface to its fixing action', () => {
    expect(errorActionForSurface('LLM_PROVIDERS')).toEqual({
      tab: 'llm',
      label: 'Open Configure → LLM Providers',
    });
    expect(errorActionForSurface('RUNTIME')).toEqual({
      tab: 'runtime',
      label: 'Open Configure → Runtime',
    });
    expect(errorActionForSurface('SYSTEM_CONFIG')).toEqual({
      tab: 'config',
      label: 'Open Configure → System Config',
    });
    expect(errorActionForSurface('CREW')).toEqual({ path: '/crew', label: 'Open Crew' });
  });

  it('does not guess for an absent or unknown surface', () => {
    expect(errorActionForSurface(null)).toBeUndefined();
    expect(errorActionForSurface('SOMEWHERE_ELSE')).toBeUndefined();
  });
});

describe('errorActionFor (legacy text fallback)', () => {
  it('maps a missing Qoder credential to Configure > LLM Providers', () => {
    expect(errorActionFor(CREDENTIAL_TEXT)).toEqual({
      tab: 'llm',
      label: 'Open Configure → LLM Providers',
    });
  });

  it('maps fleet runtime refusals to Configure > Runtime', () => {
    expect(tabOf(errorActionFor(FLEET_TEXT))).toBe('runtime');
    expect(tabOf(errorActionFor('Runtime mixing is not allowed: the fleet runs qoder/HOST.')))
      .toBe('runtime');
    expect(tabOf(errorActionFor('Unsupported execution mode: qoder/SANDBOX.'))).toBe('runtime');
  });

  it('maps circuit caps and the run deadline to Configure > System Config', () => {
    expect(tabOf(errorActionFor('Raise circuit.breaker.max.iterations in Configure > System Config.')))
      .toBe('config');
    expect(tabOf(errorActionFor('Token budget exceeded: used 10, limit 5.'))).toBe('config');
    expect(tabOf(errorActionFor('Run deadline reached at 2026-10-20T12:00:00Z.'))).toBe('config');
  });

  it('maps workspace and agent-lifecycle refusals to Crew', () => {
    expect(pathOf(errorActionFor('Edit the agent\'s workspace settings in Crew.'))).toBe('/crew');
    expect(pathOf(errorActionFor('Cannot create run for a retired agent (a-1).'))).toBe('/crew');
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

  it('prefers the persisted surface over the message text', () => {
    const presented = presentRunError(
      'Run deadline reached at 2026-10-20T12:00:00Z.',
      'SYSTEM_CONFIG',
      'RUN_DEADLINE_REACHED',
    );

    expect(presented?.action).toEqual({
      tab: 'config',
      label: 'Open Configure → System Config',
    });
    expect(presented?.code).toBe('RUN_DEADLINE_REACHED');
  });

  it('serves the Crew action for a workspace refusal', () => {
    expect(presentRunError('Worktree workspace requires a git repository: /tmp/x.', 'CREW')?.action)
      .toEqual({ path: '/crew', label: 'Open Crew' });
  });
});

describe('presentError', () => {
  it('uses the backend message and its served surface', () => {
    const error = {
      response: {
        data: { error: 'Conflict', message: FLEET_TEXT, surface: 'RUNTIME', code: 'FLEET_RUNTIME_MIXED' },
      },
    };

    expect(presentError(error, 'fallback')).toEqual({
      text: FLEET_TEXT,
      action: { tab: 'runtime', label: 'Open Configure → Runtime' },
      code: 'FLEET_RUNTIME_MIXED',
    });
  });

  it('falls back to text classification for a legacy body without a surface', () => {
    const error = { response: { data: { error: 'Conflict', message: FLEET_TEXT } } };

    expect(tabOf(presentError(error, 'fallback').action)).toBe('runtime');
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
