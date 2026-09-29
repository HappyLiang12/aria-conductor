import client from './client';
import { applyOperatorHeaders } from './operatorSession';

/**
 * Managed Qoder runtime credential (spec §6.1, Task 5's routes).
 *
 * Every route below is operator-only and returns masked metadata only — the
 * stored secret is never returned, never echoed and never part of an error.
 * The UI therefore receives configuration state, key usability, the exact
 * injected environment variable and a fixed mask; the card renders its own
 * display mask rather than any server-provided string.
 *
 * `POST .../test` is the only route that may spend an inference credit: one
 * bounded call. A refusal (no probe wired, unreadable store, not configured)
 * answers 503/409 with an honest `reason` and makes no model call.
 */

export const QODER_CREDENTIAL_PATH = '/api/v1/adk/providers/qoder/credential';

/** Masked metadata as served by `GET /api/v1/adk/providers/qoder/credential`. */
export interface QoderCredentialMetadata {
  credentialRef: string;
  coreId: string;
  /** The exact variable the pinned CLI consumes. */
  environmentVariable: string;
  configured: boolean;
  /** False when the store's encryption key is missing: stored values are unreadable. */
  encryptionKeyConfigured: boolean;
  /**
   * Whether this backend can run the bounded credential test at all. The probe is
   * part of the wiring (the run-owned core bridge), so a deployment may not have
   * one; the card then explains the state instead of offering a call that can only
   * be refused.
   */
  testSupported: boolean;
  /** Backend-provided mask. Deliberately never rendered by the card (see above). */
  maskedSecret: string | null;
  updatedAt: string | null;
}

/**
 * Provider usage of the one bounded test call, as the backend serves it
 * (`UsageSnapshot`): `{ inputTokens, outputTokens, credits, observedModel }`.
 * Unknown usage stays unknown (nulls, never zeros).
 */
export interface CredentialTestUsage {
  inputTokens?: number | null;
  outputTokens?: number | null;
  /** Provider-reported credits of the bounded call; null when not reported. */
  credits?: number | null;
  observedModel?: string | null;
}

export interface CredentialTestOutcome {
  /** True only when a bounded call was actually made. */
  tested: boolean;
  authenticated?: boolean;
  model?: string | null;
  detail?: string | null;
  usage?: CredentialTestUsage | null;
  /** Present on a refusal (`tested: false`); the honest explanation, no call made. */
  reason?: string;
  costDisclosure?: string;
}

/** Masked metadata of the stored credential (read-only, no model call). */
export async function getQoderCredential(): Promise<QoderCredentialMetadata> {
  applyOperatorHeaders();
  const { data } = await client.get<QoderCredentialMetadata>(QODER_CREDENTIAL_PATH);
  return data;
}

/** Stores/replaces the credential, encrypted. The secret is never echoed back. */
export async function putQoderCredential(secret: string): Promise<QoderCredentialMetadata> {
  applyOperatorHeaders();
  const { data } = await client.put<QoderCredentialMetadata>(QODER_CREDENTIAL_PATH, { secret });
  return data;
}

/** Removes the credential so future launches can no longer resolve it (204). */
export async function deleteQoderCredential(): Promise<void> {
  applyOperatorHeaders();
  await client.delete(QODER_CREDENTIAL_PATH);
}

/** Runs the single explicit bounded credential test; never part of health polling. */
export async function testQoderCredential(): Promise<CredentialTestOutcome> {
  applyOperatorHeaders();
  const { data } = await client.post<CredentialTestOutcome>(`${QODER_CREDENTIAL_PATH}/test`);
  return data;
}
