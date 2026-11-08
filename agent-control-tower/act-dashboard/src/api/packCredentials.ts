import client from './client';

/**
 * F8 (SDD hardening): the git pack credential editor's write path.
 *
 * `POST /api/v1/packs/{id}/credentials` is the only route the git handoff
 * credential has: there is no read route (GET answers 405), so the dashboard
 * can store a value and report the outcome, never "the stored value is".
 * The stored secret is never returned, echoed or logged.
 *
 * The controller reads a `{key, value, agentId?}` body and answers
 * `{status: "stored", key, restartRequired: false}`; `agentId` omitted means the
 * global credential. The token is resolved per git operation, so no restart is
 * needed; an older backend that omits `restartRequired` keeps the restart wording.
 */

export const GIT_PACK_ID = 'pack-git-0001';

/** The key the git handoff resolves (GitBranchConfig.GITHUB_TOKEN_KEY). */
export const GIT_PACK_CREDENTIAL_KEY = 'GITHUB_TOKEN';

export interface StorePackCredentialRequest {
  key: string;
  value: string;
  /** Per-agent override; omitted stores the global credential. */
  agentId?: string;
}

export interface PackCredentialStoreResult {
  status?: string;
  key?: string;
  /**
   * False only when the backend states the value is used without a restart.
   * Absent keeps the restart-required state the running backend implements.
   */
  restartRequired?: boolean;
  [key: string]: unknown;
}

export async function storePackCredential(
  packId: string,
  request: StorePackCredentialRequest,
): Promise<PackCredentialStoreResult> {
  const { data } = await client.post<PackCredentialStoreResult>(
    `/api/v1/packs/${encodeURIComponent(packId)}/credentials`,
    request,
  );
  return data;
}
