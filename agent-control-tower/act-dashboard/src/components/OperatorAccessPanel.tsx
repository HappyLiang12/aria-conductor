import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import {
  apiErrorMessage,
  applyOperatorHeaders,
  clearOperatorSession,
  establishOperatorSession,
  operatorSessionState,
  revokeOperatorSession,
  type OperatorSessionState,
} from '../api/operatorSession';
import { formatTimestamp } from '../utils/formatTime';

/**
 * Local operator access (spec §6.2): core selection, Host workspace admission,
 * managed credentials and approval decisions are operator-only, so the
 * dashboard must obtain operator authority through this local setup instead of
 * a token shipped in frontend assets.
 *
 * The operator types the separately provisioned operator credential
 * (`aria.operator.bearer-token` / `ARIA_OPERATOR_BEARER_TOKEN`) here. It is
 * exchanged once for an HttpOnly/SameSite session cookie plus a CSRF token and
 * is cleared from the DOM immediately — the credential itself is never stored.
 * The status line reflects this tab's stored session only (there is no
 * server-side session probe); the loader reports an already-expired record as
 * such (it drops the record in the same read), so an expired session is never
 * treated as authority and never confused with a first run.
 */
export function OperatorAccessPanel() {
  const queryClient = useQueryClient();
  const [credential, setCredential] = useState('');
  const [session, setSession] = useState<OperatorSessionState>({
    established: false,
    expiresAt: null,
    expired: false,
  });
  const [expiredNotice, setExpiredNotice] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    // Read this tab's stored session first: the expired fact must come from the
    // record as it was found, because the loader drops an expired record in the
    // same read (and applyOperatorHeaders() below runs that loader).
    const state = operatorSessionState();
    // Then re-apply the stored CSRF token so approvals/credential calls made
    // from other surfaces use the session established in this tab.
    applyOperatorHeaders();
    setSession(state);
    setExpiredNotice(state.expired);
  }, []);

  const establish = async (e: React.FormEvent) => {
    e.preventDefault();
    const value = credential.trim();
    if (!value) {
      setError('An operator credential is required');
      return;
    }
    setError(null);
    setBusy(true);
    try {
      const info = await establishOperatorSession(value);
      setSession({ established: true, expiresAt: info.expiresAt, expired: false });
      setExpiredNotice(false);
      // The authority just changed, so every cached read may have been refused
      // under the previous one (the Qoder credential card caches its 401). Refetch
      // them under the new session instead of leaving the stale refusal on screen
      // until a manual reload.
      queryClient.invalidateQueries();
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not establish the operator session.'));
      setSession({ established: false, expiresAt: null, expired: false });
    } finally {
      // The credential leaves the DOM in every outcome; it is never retained.
      setCredential('');
      setBusy(false);
    }
  };

  const signOut = async () => {
    setError(null);
    setBusy(true);
    try {
      await revokeOperatorSession();
    } catch (err) {
      // Revocation refused (e.g. expired session): drop the local record anyway
      // so a dead session cannot linger, and report the backend wording.
      clearOperatorSession();
      setError(apiErrorMessage(err, 'Could not revoke the operator session.'));
    } finally {
      setSession({ established: false, expiresAt: null, expired: false });
      setBusy(false);
      // The local record is gone, so operator-only reads are re-evaluated against
      // the server as it now stands: a revoked session refetches into the refused
      // state, while a revoke that failed against a still-live cookie may still be
      // authorized — that is the server's state, not a cached claim.
      queryClient.invalidateQueries();
    }
  };

  return (
    <div className="card" style={{ marginTop: 24 }} data-testid="operator-access-panel">
      <h3 className="form-title">Operator access</h3>
      <p style={{ color: 'var(--text-dim)', fontSize: 12 }}>
        Operator authority is required for approval decisions and managed runtime credentials.
        It is a separately provisioned single-operator credential, never a worker or core secret.
      </p>

      {session.established ? (
        <>
          <div className="rf-meta" style={{ marginBottom: 10 }}>
            <span className="pill ok">Operator session established</span>
            <span className="owner">expires {formatTimestamp(session.expiresAt)}</span>
          </div>
          <button className="btn" disabled={busy} onClick={signOut}>
            {busy ? 'Signing out…' : 'Sign out'}
          </button>
        </>
      ) : (
        <form onSubmit={establish}>
          {expiredNotice && (
            <div style={{ fontSize: 11.5, color: 'var(--amber)', marginBottom: 8 }}>
              The previous operator session expired — establish a new one.
            </div>
          )}
          <label htmlFor="operator-credential" style={{ display: 'block', fontSize: 10.5, color: 'var(--text-dim)', margin: '8px 0 4px', letterSpacing: '.8px', textTransform: 'uppercase' }}>
            Operator credential
          </label>
          <input
            id="operator-credential"
            type="password"
            autoComplete="off"
            value={credential}
            placeholder="Operator bearer credential"
            onChange={(e) => setCredential(e.target.value)}
            style={{ width: '100%', padding: '8px 10px', borderRadius: 8, background: 'rgba(0,0,0,.25)', color: 'var(--text)', border: '1px solid var(--line-2)', font: 'inherit', fontSize: 13, boxSizing: 'border-box' }}
          />
          <div style={{ color: 'var(--text-mute)', fontSize: 11, marginTop: 6 }}>
            Configured on the backend as <code>aria.operator.bearer-token</code> (or
            {' '}<code>ARIA_OPERATOR_BEARER_TOKEN</code>). It is exchanged for an HttpOnly session
            cookie here and is not stored by the dashboard.
          </div>
          {error && (
            <div style={{ marginTop: 8, color: 'var(--red)', fontSize: 11.5 }}>{error}</div>
          )}
          <div className="actions" style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 12 }}>
            <button type="submit" className="btn primary" disabled={busy}>
              {busy ? 'Establishing…' : 'Establish operator session'}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}
