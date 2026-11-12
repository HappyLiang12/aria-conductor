import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { ErrorNotice, RunErrorNotice } from '../ErrorNotice';

function LocationTracker({ paths }: { paths: string[] }) {
  const location = useLocation();
  paths.push(location.pathname);
  return null;
}

function renderNotice(presented: {
  text: string;
  action?: { tab: 'llm' | 'runtime' | 'config'; label: string } | { path: string; label: string };
}) {
  const paths: string[] = [];
  render(
    <MemoryRouter initialEntries={['/runs']}>
      <LocationTracker paths={paths} />
      <ErrorNotice presented={presented} />
    </MemoryRouter>,
  );
  return paths;
}

describe('ErrorNotice', () => {
  it('renders the backend text and opens Configure on the action tab', async () => {
    const listener = vi.fn();
    window.addEventListener('act:open-configure', listener);

    renderNotice({
      text: 'Qoder runtime credential is not configured (qoder:operator).',
      action: { tab: 'llm', label: 'Open Configure → LLM Providers' },
    });

    expect(screen.getByRole('alert')).toHaveTextContent('Qoder runtime credential is not configured');
    await userEvent.click(screen.getByRole('button', { name: /LLM Providers/ }));

    expect(listener).toHaveBeenCalledTimes(1);
    expect((listener.mock.calls[0][0] as CustomEvent).detail).toEqual({ tab: 'llm' });
    window.removeEventListener('act:open-configure', listener);
  });

  it('navigates to Crew for a workspace action', async () => {
    const paths = renderNotice({
      text: 'Edit the agent\'s workspace settings in Crew.',
      action: { path: '/crew', label: 'Open Crew' },
    });

    await userEvent.click(screen.getByRole('button', { name: 'Open Crew' }));

    expect(paths[paths.length - 1]).toBe('/crew');
  });

  it('renders no action button when the failure names no fixable surface', () => {
    renderNotice({ text: 'The bridge stream ended.' });

    expect(screen.getByRole('alert')).toHaveTextContent('The bridge stream ended.');
    expect(screen.queryByRole('button')).toBeNull();
  });
});

// Task 30 / Item 1: an operator-only caller opts in, and a 401/403 then reads as
// the operator-session notice instead of the server's text. The opt-in matters:
// a caller that has not been audited keeps the plain error path, so this stays one
// component rather than a second notice invented beside it.
describe('ErrorNotice operator-session refusal', () => {
  function renderOperatorNotice(presented: {
    text: string;
    action?: { tab: 'llm' | 'runtime' | 'config'; label: string } | { path: string; label: string };
    operatorRejected?: true;
    crossOriginRejected?: true;
  }) {
    render(
      <MemoryRouter initialEntries={['/runs']}>
        <ErrorNotice presented={presented} operatorSession actionLabel="Applying the fleet runtime" />
      </MemoryRouter>,
    );
  }

  it.each([401, 403])('replaces the server text with the actionable notice on %i', () => {
    renderOperatorNotice({ text: 'OPERATOR_ROUTE_RAW', operatorRejected: true });

    const notice = screen.getByTestId('operator-session-notice');
    // Says who may do it and what to do about it — the product's voice.
    expect(notice).toHaveTextContent('Applying the fleet runtime is operator-only');
    expect(notice).toHaveTextContent('loopback requests are the operator automatically');
    expect(notice).toHaveTextContent('the operator who runs the backend must grant access');
    // Never the server's own words, which point at nothing.
    expect(notice).not.toHaveTextContent('OPERATOR_ROUTE_RAW');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('keeps the ordinary error path for a 500 even on an operator-only caller', () => {
    renderOperatorNotice({ text: 'fleet runtime store unavailable' });

    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('fleet runtime store unavailable');
  });

  it('leaves an unaudited caller on the plain error path for a refusal', () => {
    render(
      <MemoryRouter>
        <ErrorNotice presented={{ text: 'Operator session required', operatorRejected: true }} />
      </MemoryRouter>,
    );

    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Operator session required');
  });

  // Task 33 / Item 4: the notice used to tell the operator to "reload the page
  // and retry". A reload cannot change who the server thinks the operator is,
  // so the advice was a loop with no exit. The remedy has to be something this
  // deployment actually offers. Asserted as the remedy phrase, not the whole
  // sentence, so wording may change but the remedy cannot disappear.
  it('names a remedy the operator can actually carry out', () => {
    renderOperatorNotice({ text: 'OPERATOR_ROUTE_RAW', operatorRejected: true });

    const notice = screen.getByTestId('operator-session-notice');
    expect(notice).toHaveTextContent(/open the dashboard on the machine that runs the backend/i);
    // The advice that cannot work must be gone, not merely softened.
    expect(notice).not.toHaveTextContent(/reload the page and retry/i);
  });

  // Task 37 / Item 2: a 403 carrying the cross-origin marker is not an authority
  // refusal. Telling the operator to "grant access" for a forged cross-site page
  // is advice about the wrong problem, so that sentence must not appear.
  it('renders the cross-site remedy, not the operator-grant remedy, for a cross-origin 403', () => {
    renderOperatorNotice({
      text: 'Cross-origin operator mutation rejected',
      operatorRejected: true,
      crossOriginRejected: true,
    });

    const notice = screen.getByTestId('operator-session-notice');
    expect(notice).toHaveTextContent(/cross-site request/i);
    expect(notice).toHaveTextContent(/origin the backend trusts/i);
    expect(notice).not.toHaveTextContent(/must grant access/i);
    expect(notice).not.toHaveTextContent(/loopback requests are the operator automatically/i);
    // Still the one notice, still saying nothing was changed.
    expect(notice).toHaveTextContent(/nothing was changed by the refused call/i);
  });

  // Task 37 / Item 5b: the ordinary error path is `role="alert"`, so a refusal
  // that replaced it must be announced too — otherwise the one outcome the
  // operator has to act on is the silent one. The testid, the class and the text
  // are unchanged; only the live region is new, and it is load-bearing.
  it('announces the operator notice as a live region', () => {
    renderOperatorNotice({ text: 'OPERATOR_ROUTE_RAW', operatorRejected: true });

    const notice = screen.getByTestId('operator-session-notice');
    expect(notice).toHaveAttribute('role', 'status');
    expect(screen.getByRole('status')).toBe(notice);
    // The pin above would still pass if the notice had quietly become a
    // different element: keep its identity and its copy pinned here as well.
    expect(notice).toHaveClass('ask-ctx');
    expect(notice).toHaveTextContent('Applying the fleet runtime is operator-only');
  });
});

// T54 F3, against the T53 backend contract: a run that failed because the
// Qoder core rejected the saved credential carries `E_CREDENTIAL_REJECTED` at
// the front of its message. The run surfaces must read as a credential problem
// with a fix, not as an unactionable security/transport fault.
describe('RunErrorNotice credential rejection (T54 F3)', () => {
  const MESSAGE = 'E_CREDENTIAL_REJECTED: the core rejected the saved Qoder runtime credential at '
    + 'session/new (E_AUTH_REQUIRED) — a credential failure, not a transport failure; replace the '
    + 'saved credential at PUT /api/v1/cores/qoder/credential and re-dispatch the run. The runtime '
    + 'endpoint of run 961868f4 did not authenticate the run\'s control secret (no authenticated '
    + 'answer within PT30S); the runtime was terminated instead of being trusted on its executable '
    + 'name.';

  it('renders the remedy and the credential action beside the failure', async () => {
    const listener = vi.fn();
    window.addEventListener('act:open-configure', listener);

    render(<RunErrorNotice message={MESSAGE} />);

    const notice = screen.getByRole('alert');
    expect(notice).toHaveTextContent(/control secret/);
    expect(notice).toHaveTextContent(/Qoder runtime credential/);
    expect(notice).toHaveTextContent(/Qoder personal access token/i);
    await userEvent.click(screen.getByRole('button', { name: /LLM Providers/ }));
    expect((listener.mock.calls[0][0] as CustomEvent).detail).toEqual({ tab: 'llm' });
    window.removeEventListener('act:open-configure', listener);
  });

  // A transport failure recorded before the classification is a different
  // failure and must not be relabelled (evidence, not guesswork).
  it('does not add a credential remedy to an unclassified transport failure', () => {
    render(
      <RunErrorNotice
        message={'The runtime endpoint of run 961868f4 did not authenticate the run\'s control '
          + 'secret (last: ConnectException: null).'}
      />,
    );

    expect(screen.getByRole('alert')).not.toHaveTextContent(/Qoder personal access token/i);
    expect(screen.queryByTestId('error-remedy')).toBeNull();
  });

  it('leaves an unrelated run failure as the plain notice', () => {
    render(<RunErrorNotice message="relay dead" surface={null} />);

    expect(screen.getByRole('alert')).toHaveTextContent('relay dead');
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.queryByTestId('error-remedy')).toBeNull();
  });
});
