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
import { apiErrorMessage, applyOperatorHeaders, isOperatorRejection } from '../api/operatorSession';
import { ConfirmDialog } from './ConfirmDialog';
import { formatTimestamp } from '../utils/formatTime';

/**
 * Managed Qoder runtime credential (spec §6.1) — masked metadata, an explicit
 * bounded test and (confirmed) removal.
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

/** Why the bounded test cannot run right now; null when it can. */
function testUnavailableReason(metadata: QoderCredentialMetadata): string | null {
  if (!metadata.configured) {
    return 'The bounded credential test needs a stored credential — save one first.';
  }
  if (!metadata.testSupported) {
    return 'No credential probe is wired in this build: the bounded test needs the run-owned core '
      + 'bridge, so the dashboard cannot make that call.';
  }
  return null;
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
      setNotice(`Credential stored — mask ${MASKED_CREDENTIAL_DISPLAY}, variable ${metadata.environmentVariable}.`);
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
        Stored encrypted and injected only into Qoder runs. Reads are masked — the stored
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
          {isOperatorRejection(metadataQuery.error) && (
            <div>Operator-only surface — establish the operator session in the Operator access panel first.</div>
          )}
        </div>
      )}

      {/* A refused read must not keep painting the previously authorized
          metadata next to the refusal: the mask and timestamp describe a read
          this surface no longer has the authority to make. */}
      {metadata && !metadataQuery.isError && (
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

          {!metadata.encryptionKeyConfigured && (
            <div className="error-state" style={{ marginBottom: 10 }}>
              {metadata.configured
                ? 'Stored credential is unreadable — the credential encryption key is not configured.'
                : 'The credential encryption key is not configured; storing a credential is refused.'}
            </div>
          )}

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
                  applyOperatorHeaders();
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

          {testUnavailable && (
            <div style={{ color: 'var(--text-mute)', fontSize: 11.5, marginTop: 8 }}>
              {testUnavailable}
            </div>
          )}

          {notice && <div style={{ marginTop: 8, fontSize: 11.5 }}>{notice}</div>}
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
          applyOperatorHeaders();
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
