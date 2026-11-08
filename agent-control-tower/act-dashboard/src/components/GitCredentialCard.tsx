import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import {
  GIT_PACK_CREDENTIAL_KEY,
  GIT_PACK_ID,
  storePackCredential,
  type PackCredentialStoreResult,
} from '../api/packCredentials';
import { apiErrorMessage } from '../api/operatorSession';

/**
 * F8 (SDD hardening): editor for the git pack credential (`pack-git-0001`).
 *
 * The workflow git handoff resolves its token from this pack, but the Configure
 * surface had no editor — the operator had to curl the store endpoint. The card
 * mirrors RuntimeCredentialsCard: a masked input, a single write, the typed
 * value cleared whatever the outcome, and the stored secret never rendered.
 *
 * Two honest limits are stated instead of papered over:
 * - the endpoint has no read route (GET answers 405), so the card cannot show
 *   whether a credential is stored — saving replaces it;
 * - the backend resolves the token per git operation, so a store takes effect
 *   on the next operation and answers `restartRequired: false`. The restart
 *   wording is kept only for an older backend that omits the field.
 */

export interface GitCredentialStoreOutcome {
  result: PackCredentialStoreResult;
  /** True while the running backend still resolves the token only at startup. */
  restartRequired: boolean;
}

export const GIT_CREDENTIAL_STORED_RESTART =
  'Credential stored. The git token is resolved once at backend startup, so restart the backend '
  + 'before the next workflow run uses it.';

export const GIT_CREDENTIAL_STORED_IMMEDIATE =
  'Credential stored — new workflow runs resolve it without a restart.';

export function restartRequiredOf(result: PackCredentialStoreResult): boolean {
  // Only an explicit false lifts the restart requirement: an older backend
  // omits the field and still resolves the token in an eager bean.
  return result.restartRequired !== false;
}

export function GitCredentialCard() {
  const [secret, setSecret] = useState('');
  const [outcome, setOutcome] = useState<GitCredentialStoreOutcome | null>(null);
  const [error, setError] = useState<string | null>(null);

  const save = useMutation({
    mutationFn: (value: string) =>
      storePackCredential(GIT_PACK_ID, { key: GIT_PACK_CREDENTIAL_KEY, value }),
    onSuccess: (result) => {
      setError(null);
      setOutcome({ result, restartRequired: restartRequiredOf(result) });
    },
    onError: (err: unknown) => {
      setOutcome(null);
      setError(apiErrorMessage(err, 'The git credential could not be stored.'));
    },
    onSettled: () => {
      // The typed secret never outlives the request, whatever the outcome.
      setSecret('');
    },
  });

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const value = secret.trim();
    if (!value) {
      setError('A git credential value is required');
      return;
    }
    setError(null);
    setOutcome(null);
    save.mutate(value);
  };

  return (
    <div className="card" style={{ marginTop: 24 }} data-testid="git-credential-card">
      <h3 className="form-title">Git pack credential</h3>
      <p style={{ color: 'var(--text-dim)', fontSize: 12 }}>
        The workflow git handoff resolves its token from <code>{GIT_PACK_ID}</code> as{' '}
        <code>{GIT_PACK_CREDENTIAL_KEY}</code>. The value is stored by the backend and never
        returned to the dashboard.
      </p>
      <p style={{ color: 'var(--text-mute)', fontSize: 11.5 }}>
        The pack credential endpoint has no read route, so this editor cannot show whether one is
        stored — saving replaces the stored value.
      </p>

      <form onSubmit={submit}>
        <label
          htmlFor="git-credential-value"
          style={{ display: 'block', fontSize: 10.5, color: 'var(--text-dim)', margin: '8px 0 4px', letterSpacing: '.8px', textTransform: 'uppercase' }}
        >
          Git credential (GITHUB_TOKEN)
        </label>
        <input
          id="git-credential-value"
          type="password"
          autoComplete="off"
          value={secret}
          placeholder="Paste a GitHub token with repo access"
          onChange={(e) => setSecret(e.target.value)}
          style={{ width: '100%', padding: '8px 10px', borderRadius: 8, background: 'rgba(0,0,0,.25)', color: 'var(--text)', border: '1px solid var(--line-2)', font: 'inherit', fontSize: 13, boxSizing: 'border-box' }}
        />
        <div className="actions" style={{ display: 'flex', gap: 8, marginTop: 10, flexWrap: 'wrap' }}>
          <button type="submit" className="btn primary" disabled={save.isPending}>
            {save.isPending ? 'Storing…' : 'Save git credential'}
          </button>
        </div>
      </form>

      {outcome && (
        <div
          role="status"
          style={{ marginTop: 8, fontSize: 11.5 }}
          data-testid="git-credential-outcome"
        >
          <div>{outcome.restartRequired ? GIT_CREDENTIAL_STORED_RESTART : GIT_CREDENTIAL_STORED_IMMEDIATE}</div>
          {outcome.result.key && !outcome.restartRequired && (
            <div style={{ color: 'var(--text-mute)' }}>Stored key: {outcome.result.key}</div>
          )}
        </div>
      )}
      {error && (
        <div role="alert" style={{ marginTop: 8, color: 'var(--red)', fontSize: 11.5 }}>
          {error}
        </div>
      )}
    </div>
  );
}

export default GitCredentialCard;
