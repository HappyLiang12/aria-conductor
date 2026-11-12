import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  deleteQoderCredential,
  getQoderCredential,
  putQoderCredential,
  testQoderCredential,
  type CredentialTestOutcome,
  type QoderCredentialMetadata,
} from '../api/runtimeCredentials';
import { apiErrorMessage } from '../api/operatorSession';
import { ConfirmDialog } from './ConfirmDialog';
import { formatTimestamp } from '../utils/formatTime';

/**
 * Managed Qoder runtime credential (plain core credential surface) — masked
 * metadata, an explicit bounded test and (confirmed) removal.
 *
 * Reads are masked by the backend; the card additionally renders its own fixed
 * display mask and never paints `maskedSecret` (or anything else server-sent)
 * into that slot, so a masking regression or a hostile response cannot put
 * credential bytes into the DOM. The replace input is cleared on every outcome
 * and the typed value is only ever sent to `PUT`.
 *
 * The credential test is the single endpoint that may spend an inference
 * credit; a refusal is reported with the backend's own reason, and unknown
 * provider usage is shown as unknown rather than as zero.
 */

/** Fixed display mask owned by the UI; no server-provided string is rendered here. */
export const MASKED_CREDENTIAL_DISPLAY = '••••••••';

export const CREDENTIAL_QUERY_KEY = ['qoder-runtime-credential'];

/**
 * Why the bounded test cannot run right now; null when it can. The backend
 * serves this as `testUnavailableReason` (T53 F3), and the served text is what
 * is rendered — this local copy is the fallback for a backend older than that
 * contract, and it names the action that still verifies the token: a stored
 * credential that cannot be probed is only proven by a run.
 */
function fallbackTestUnavailableReason(metadata: QoderCredentialMetadata): string | null {
  if (!metadata.configured) {
    return 'The bounded credential test needs a stored credential — save one first.';
  }
  // `=== false`, not falsy: a backend older than this frontend omits the field,
  // and a missing capability must not read as "no probe wired".
  if (metadata.testSupported === false) {
    return 'No credential probe is wired in this build: the bounded test needs the run-owned core '
      + 'bridge, so the dashboard cannot make that call. To verify the token, dispatch a Qoder '
      + 'run — a rejected credential fails it with E_CREDENTIAL_REJECTED at session/new.';
  }
  return null;
}

/** The backend's stated reason when served; the local fallback otherwise. */
function testUnavailableReason(metadata: QoderCredentialMetadata): string | null {
  // T56 F2: an unconfigured credential is decided FIRST. The shipped backend
  // serves `testUnavailableReason` whenever no probe is wired — including when
  // nothing is stored — so leading with the served text displaced the only
  // actionable step ("save one first") with an explanation of a test that has
  // nothing to test.
  if (!metadata.configured) {
    return 'The bounded credential test needs a stored credential — save one first.';
  }
  return metadata.testUnavailableReason ?? fallbackTestUnavailableReason(metadata);
}

/**
 * What a save actually proved: the platform stored the value, and nothing else.
 * When no probe is wired the token has NOT been validated, and the operator's
 * only verification is a run — a credential the core rejects fails that run
 * under E_CREDENTIAL_REJECTED (T54 F3, contract from T53). Saying "stored" alone
 * read as "working".
 */
function saveNotice(metadata: QoderCredentialMetadata): string {
  const base = `Credential stored — mask ${MASKED_CREDENTIAL_DISPLAY}, variable ${metadata.environmentVariable}. `
    + 'It was not validated';
  const reason = testUnavailableReason(metadata);
  if (reason !== null) {
    return `${base}: ${reason} To verify it, keep the value in Configure → LLM Providers → `
      + 'Qoder runtime credential — save a Qoder personal access token the qoder CLI accepts — and '
      + 'dispatch a Qoder run; a rejected credential fails that run with E_CREDENTIAL_REJECTED.';
  }
  return `${base} yet — use Test credential to verify it before relying on it.`;
}

function usageLine(outcome: CredentialTestOutcome): string {
  const usage = outcome.usage;
  const parts: string[] = [];
  if (usage && (usage.inputTokens != null || usage.outputTokens != null)) {
    parts.push(`${usage.inputTokens ?? 0} input / ${usage.outputTokens ?? 0} output tokens`);
  }
  if (usage?.credits != null) {
    parts.push(`${usage.credits} credits`);
  }
  if (parts.length === 0) {
    return 'Provider usage: not reported by the provider (unknown, not zero)';
  }
  return `Provider usage: ${parts.join(' · ')}`;
}

export function RuntimeCredentialsCard() {
  const queryClient = useQueryClient();
  const [secret, setSecret] = useState('');
  const [confirmingRemove, setConfirmingRemove] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const metadataQuery = useQuery({
    queryKey: CREDENTIAL_QUERY_KEY,
    queryFn: getQoderCredential,
  });

  const save = useMutation({
    mutationFn: (value: string) => putQoderCredential(value),
    onSuccess: (metadata: QoderCredentialMetadata) => {
      setError(null);
      setNotice(saveNotice(metadata));
      queryClient.invalidateQueries({ queryKey: CREDENTIAL_QUERY_KEY });
    },
    onError: (err: unknown) => {
      setNotice(null);
      setError(apiErrorMessage(err, 'The credential could not be stored.'));
    },
    onSettled: () => {
      // The typed secret never outlives the request, whatever the outcome.
      setSecret('');
    },
  });

  const test = useMutation({
    mutationFn: testQoderCredential,
    onSuccess: () => setError(null),
    onError: () => setError(null),
  });

  const remove = useMutation({
    mutationFn: deleteQoderCredential,
    onSuccess: () => {
      setError(null);
      setNotice('Credential removed — future launches can no longer resolve it.');
      queryClient.invalidateQueries({ queryKey: CREDENTIAL_QUERY_KEY });
    },
    onError: (err: unknown) => {
      setNotice(null);
      setError(apiErrorMessage(err, 'The credential could not be removed.'));
    },
  });

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const value = secret.trim();
    if (!value) {
      setError('A runtime credential value is required');
      return;
    }
    setNotice(null);
    setError(null);
    save.mutate(value);
  };

  const metadata = metadataQuery.data;
  const testUnavailable = metadata ? testUnavailableReason(metadata) : null;

  return (
    <div className="card" style={{ marginTop: 24 }} data-testid="runtime-credentials-card">
      <h3 className="form-title">Qoder runtime credential</h3>
      <p style={{ color: 'var(--text-dim)', fontSize: 12 }}>
        Stored by the platform and injected only into Qoder runs. Reads are masked — the stored
        secret is never returned to the dashboard.
      </p>

      {metadataQuery.isLoading && (
        <div className="loading-spinner">
          <div className="spinner" />
          <span>Loading runtime credential…</span>
        </div>
      )}

      {metadataQuery.isError && (
        <div className="error-state">
          <div>{apiErrorMessage(metadataQuery.error, 'Failed to load the Qoder runtime credential.')}</div>
        </div>
      )}

      {/* A failed read keeps the last known metadata and the controls: the
          error block above shows what happened while the mask, timestamp and
          actions stay usable instead of emptying the card. */}
      {metadata && (
        <>
          <div className="rf-meta" style={{ marginBottom: 10 }}>
            {metadata.configured ? (
              <>
                <span className="pill ok">Configured</span>
                <span className="owner cell-mono" style={{ fontSize: 13, letterSpacing: 2 }}>
                  {MASKED_CREDENTIAL_DISPLAY}
                </span>
                <span className="owner">
                  injected as <code>{metadata.environmentVariable}</code>
                </span>
                <span className="owner">updated {formatTimestamp(metadata.updatedAt)}</span>
              </>
            ) : (
              <>
                <span className="pill warn">Not configured</span>
                <span className="owner">
                  Qoder runs fail admission until an operator stores the runtime credential.
                </span>
              </>
            )}
          </div>

          <form onSubmit={submit}>
            <label
              htmlFor="runtime-credential-value"
              style={{ display: 'block', fontSize: 10.5, color: 'var(--text-dim)', margin: '8px 0 4px', letterSpacing: '.8px', textTransform: 'uppercase' }}
            >
              Runtime credential
            </label>
            <input
              id="runtime-credential-value"
              type="password"
              autoComplete="off"
              value={secret}
              placeholder="Paste the Qoder runtime credential"
              onChange={(e) => setSecret(e.target.value)}
              style={{ width: '100%', padding: '8px 10px', borderRadius: 8, background: 'rgba(0,0,0,.25)', color: 'var(--text)', border: '1px solid var(--line-2)', font: 'inherit', fontSize: 13, boxSizing: 'border-box' }}
            />
            {/* T54 F3: stated BEFORE the controls, because it is what the
                operator needs to know before pressing Save: this build cannot
                pre-validate the token. The backend serves the wording. */}
            {testUnavailable && (
              <div
                data-testid="credential-test-unavailable"
                style={{ color: 'var(--text-mute)', fontSize: 11.5, marginTop: 8 }}
              >
                {testUnavailable}
              </div>
            )}
            <div className="actions" style={{ display: 'flex', gap: 8, marginTop: 10, flexWrap: 'wrap' }}>
              <button type="submit" className="btn primary" disabled={save.isPending}>
                {save.isPending ? 'Storing…' : 'Save credential'}
              </button>
              <button
                type="button"
                className="btn"
                disabled={test.isPending || testUnavailable !== null}
                onClick={() => {
                  setNotice(null);
                  setError(null);
                  // A fresh test start clears the previous outcome so a stale
                  // result is never read as this call's result.
                  test.reset();
                  test.mutate();
                }}
              >
                {test.isPending ? 'Testing…' : 'Test credential'}
              </button>
              <button
                type="button"
                className="btn danger"
                disabled={remove.isPending || !metadata.configured}
                onClick={() => setConfirmingRemove(true)}
              >
                Remove credential
              </button>
            </div>
          </form>

          {notice && (
            <div data-testid="credential-save-notice" style={{ marginTop: 8, fontSize: 11.5 }}>
              {notice}
            </div>
          )}
          {error && <div style={{ marginTop: 8, color: 'var(--red)', fontSize: 11.5 }}>{error}</div>}

          {test.data && <TestOutcomeBlock outcome={test.data} />}
          {test.isError && (
            <div className="error-state" style={{ marginTop: 10 }}>
              {apiErrorMessage(test.error, 'The credential test could not be run.')}
            </div>
          )}
        </>
      )}

      <ConfirmDialog
        open={confirmingRemove}
        title="Remove the Qoder runtime credential?"
        message={
          <>
            Future Qoder launches can no longer resolve the credential. Runs that already
            received it are ended through the normal cancellation path — removal alone does not
            revoke them.
          </>
        }
        danger
        onConfirm={() => {
          setConfirmingRemove(false);
          remove.mutate();
        }}
        onCancel={() => setConfirmingRemove(false)}
      />
    </div>
  );
}

function TestOutcomeBlock({ outcome }: { outcome: CredentialTestOutcome }) {
  if (!outcome.tested) {
    return (
      <div className="error-state" style={{ marginTop: 10 }}>
        {outcome.reason ?? 'The credential test was refused; no model call was made.'}
      </div>
    );
  }
  return (
    <div style={{ marginTop: 10, fontSize: 12 }}>
      <div className={outcome.authenticated ? 'pill ok' : 'pill risk'} style={{ marginBottom: 6 }}>
        {outcome.authenticated
          ? `Credential test passed — authenticated with model ${outcome.model ?? 'unknown'}`
          : 'Credential test failed — the provider rejected the credential'}
      </div>
      {outcome.detail && <div>{outcome.detail}</div>}
      <div>{usageLine(outcome)}</div>
      {outcome.costDisclosure && (
        <div style={{ color: 'var(--text-mute)', marginTop: 6 }}>{outcome.costDisclosure}</div>
      )}
    </div>
  );
}

export default RuntimeCredentialsCard;
