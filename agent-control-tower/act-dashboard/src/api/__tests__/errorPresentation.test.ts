import { describe, it, expect, vi } from 'vitest';
import { apiErrorMessage } from '../operatorSession';
import {
  CROSS_SITE_REMEDY_COPY,
  OPERATOR_REMEDY_COPY,
  credentialRejectionRemedy,
  crossSiteSessionMessage,
  errorActionFor,
  errorActionForSurface,
  openConfigure,
  operatorSessionMessage,
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

/**
 * The exact shape the backend now produces for a rejected credential (T53 F3,
 * `HostExecutionBackend.authenticationFailureMessage`): the classification
 * marker FIRST (the Aria timeline clips the reason to 300 chars), then the
 * unchanged handshake sentence. It keeps the phrase "Qoder runtime credential"
 * so the shipped router finds it.
 */
const CREDENTIAL_REJECTED_TEXT = 'E_CREDENTIAL_REJECTED: the core rejected the saved Qoder '
  + 'runtime credential at session/new (E_AUTH_REQUIRED) — a credential failure, not a transport '
  + 'failure; replace the saved credential at PUT /api/v1/cores/qoder/credential and re-dispatch '
  + 'the run. The runtime endpoint of run 961868f4-1a5a-44d3-8923-6129991c16e4 did not '
  + "authenticate the run's control secret (no authenticated answer within PT30S); the runtime "
  + 'was terminated instead of being trusted on its executable name.';

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

// T54 F3, against the T53 backend contract: the failed run's message starts
// with the literal `E_CREDENTIAL_REJECTED` when the core rejected the saved
// credential, and keeps the phrase "Qoder runtime credential". The frontend half
// (1) lets the SHIPPED router find the phrase — no second router — and
// (2) adds the plain-language remedy the transport wording cannot carry.
describe('credential rejection presentation (T54 F3)', () => {
  it('routes through the shipped "Qoder runtime credential" phrase, not a second router', () => {
    // The message carries the phrase, so `errorActionFor` finds the surface
    // that stores the credential with no credential-specific branch.
    expect(errorActionFor(CREDENTIAL_REJECTED_TEXT)).toEqual({
      tab: 'llm',
      label: 'Open Configure → LLM Providers',
    });
    expect(presentRunError(CREDENTIAL_REJECTED_TEXT)?.action).toEqual({
      tab: 'llm',
      label: 'Open Configure → LLM Providers',
    });
  });

  it('names the credential surface and the action that fixes it', () => {
    const remedy = credentialRejectionRemedy(CREDENTIAL_REJECTED_TEXT);

    expect(remedy).not.toBeNull();
    // The place.
    expect(remedy).toMatch(/Configure → LLM Providers/);
    expect(remedy).toMatch(/Qoder runtime credential/);
    // The action.
    expect(remedy).toMatch(/save/i);
    expect(remedy).toMatch(/Qoder personal access token/i);
    expect(remedy).toMatch(/dispatch the run again|run again/i);
    // It says what the operator was looking at, so the two readings connect.
    expect(remedy).toMatch(/rejected/i);
    // It must not blame a transport fault or the run's secret in general.
    expect(remedy).not.toMatch(/executable name/i);
  });

  it('matches the marker case-insensitively, wherever the reason label sits', () => {
    // The Aria panel and the stored timeline both prefix the reason, so the
    // marker is not at index 0 of the rendered string.
    expect(credentialRejectionRemedy(`⚠ 上一回合失敗：${CREDENTIAL_REJECTED_TEXT}`)).not.toBeNull();
    expect(credentialRejectionRemedy(CREDENTIAL_REJECTED_TEXT.replace(
      'E_CREDENTIAL_REJECTED',
      'e_credential_rejected',
    ))).not.toBeNull();
    expect(presentRunError(CREDENTIAL_REJECTED_TEXT)?.remedy).toBe(
      credentialRejectionRemedy(CREDENTIAL_REJECTED_TEXT),
    );
  });

  // A transport failure recorded BEFORE the classification carries no marker,
  // so the frontend cannot know its cause and must not name a credential fix:
  // a diagnosis needs evidence. (Live evidence: run 961868f4's own text.)
  it('does not diagnose an unclassified transport failure as a credential rejection', () => {
    const TRANSPORT_TEXT = 'The runtime endpoint of run 961868f4 did not authenticate the run\'s '
      + "control secret (last: ConnectException: null); the runtime was terminated instead of "
      + 'being trusted on its executable name.';

    expect(credentialRejectionRemedy(TRANSPORT_TEXT)).toBeNull();
    expect(presentRunError(TRANSPORT_TEXT)?.remedy).toBeUndefined();
  });

  // Positive control: an ordinary failure gets no credential remedy, and the
  // presentation shape it always had is unchanged.
  it('leaves an unrelated failure without a remedy', () => {
    expect(credentialRejectionRemedy('relay dead')).toBeNull();
    expect(presentRunError('relay dead')).toEqual({ text: 'relay dead', action: undefined });
    expect(presentRunError(CREDENTIAL_TEXT)?.remedy).toBeUndefined();
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

describe('the operator remedy is one copy (Task 35)', () => {
  // A 401/403 is an authority problem, not a transient one: a reload cannot
  // change who the server thinks the operator is, and on a remote machine the
  // operator is refused by design. The one remedy that can work lives here and
  // every refusal surface composes it, so this pin is the source of truth.
  it('names a remedy the operator can carry out, and never either reload phrase', () => {
    expect(OPERATOR_REMEDY_COPY).toMatch(/open the dashboard on the machine that runs the backend/i);
    expect(OPERATOR_REMEDY_COPY).toMatch(/loopback requests are the operator automatically/i);
    expect(OPERATOR_REMEDY_COPY).toMatch(/the operator who runs the backend must grant access/i);
    expect(OPERATOR_REMEDY_COPY).not.toMatch(/reload the page and retry/i);
    expect(OPERATOR_REMEDY_COPY).not.toMatch(/reload and try again/i);
  });

  // `operatorSessionMessage` used to be a second, stale copy with no caller.
  // It is now the producer the shared notice renders, so it has to build on the
  // constant rather than repeat it.
  it('builds the labelled refusal on that constant', () => {
    const message = operatorSessionMessage('Applying the fleet runtime');

    expect(message).toContain('Applying the fleet runtime is operator-only, so the server refused it.');
    expect(message).toContain(OPERATOR_REMEDY_COPY);
    expect(message).not.toMatch(/reload the page and retry/i);
    expect(message).not.toMatch(/reload and try again/i);
  });
});

// Task 37 / Item 2: a cross-origin 403 is not "you are not the operator". The
// marker the backend serves says the request came from a page it does not trust,
// so the remedy is the trusted origin — the operator-session remedy ("the
// operator who runs the backend must grant access") is false for it.
describe('the cross-site remedy is its own copy (Task 37)', () => {
  it('names the origin remedy and not the operator-grant remedy', () => {
    expect(CROSS_SITE_REMEDY_COPY).toMatch(/origin the backend trusts/i);
    expect(CROSS_SITE_REMEDY_COPY).toMatch(/not a missing operator session/i);
    expect(CROSS_SITE_REMEDY_COPY).not.toMatch(/must grant access/i);
    expect(CROSS_SITE_REMEDY_COPY).not.toMatch(/reload the page and retry/i);
  });

  it('builds the labelled refusal on that constant, naming the refusal kind', () => {
    const message = crossSiteSessionMessage('Applying the fleet runtime');

    expect(message).toContain('Applying the fleet runtime was refused as a cross-site request');
    expect(message).toContain(CROSS_SITE_REMEDY_COPY);
    expect(message).not.toMatch(/must grant access/i);
  });
});

describe('presentError classifies a cross-origin refusal apart from an authority one', () => {
  /** The memory advice's spec-closed envelope (Task 41 measured shape). */
  function envelope(status: number, message: string) {
    return { response: { status, data: { ok: false, errorType: 'FORBIDDEN', message } } };
  }

  /** The `{ error }` body the approvals/execution controllers serve. */
  function refusal(status: number, data: Record<string, unknown>) {
    return { response: { status, data } };
  }

  it('marks the {error} body /decide serves for a cross-site mutation as refused and cross-origin', () => {
    // ApprovalController.decideApproval answers 403 `{error: <marker>}`.
    const presented = presentError(
      refusal(403, { error: 'Cross-origin operator mutation rejected' }),
      'x',
    );

    expect(presented).toMatchObject({ operatorRejected: true, crossOriginRejected: true });
  });

  it('marks the memory envelope that carries the marker under message', () => {
    const presented = presentError(envelope(403, 'Cross-origin operator mutation rejected'), 'x');

    expect(presented).toMatchObject({ operatorRejected: true, crossOriginRejected: true });
  });

  it.each([401, 403])('marks a plain %i on the same {error} route as an authority refusal only', (status) => {
    // The same controller serves 401 and the CSRF 403 as `{error}` without the marker.
    const presented = presentError(refusal(status, { error: 'Operator authority required' }), 'x');

    expect(presented.operatorRejected).toBe(true);
    // Absent rather than false, so no consumer ever reads a new key by accident.
    expect('crossOriginRejected' in presented).toBe(false);
  });

  it('leaves a 500 with neither marker', () => {
    const presented = presentError(envelope(500, 'store unavailable'), 'x');

    expect('operatorRejected' in presented).toBe(false);
    expect('crossOriginRejected' in presented).toBe(false);
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
