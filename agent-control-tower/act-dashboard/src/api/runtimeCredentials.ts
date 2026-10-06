import client from './client';

/**
 * Managed Qoder runtime credential (plain core credential surface, Task 5's
 * routes).
 *
 * Every route below answers with masked metadata only — the stored secret is
 * never returned, never echoed and never part of an error. The UI receives
 * configuration state, the exact injected environment variable and the
 * backend's mask; the card still renders its own display mask rather than any
 * server-provided string.
 *
 * Authority is resolved server-side per request: a loopback caller (local
 * deployment) is the operator, so no session or bearer headers are attached
 * here.
 *
 * `POST .../test` is the only route that may spend an inference credit: one
 * bounded call. A refusal (no probe wired, not configured) answers 503/409
 * with an honest `reason` and makes no model call.
 */

export const QODER_CREDENTIAL_PATH = '/api/v1/cores/qoder/credential';

/** Masked metadata as served by `GET /api/v1/cores/qoder/credential`. */
export interface QoderCredentialMetadata {
  credentialRef: string;
  coreId: string;
  /** The exact variable the pinned CLI consumes. */
  environmentVariable: string;
  configured: boolean;
  /** Backend-provided mask ("****" + last 4, or "****"); null when unconfigured. */
  maskedSecret: string | null;
  updatedAt: string | null;
  /**
   * Whether this backend can run the bounded credential test at all. The probe is
   * part of the wiring (the run-owned core bridge), so a deployment may not have
   * one; the card then explains the state instead of offering a call that can only
   * be refused.
   */
  testSupported: boolean;
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
  const { data } = await client.get<QoderCredentialMetadata>(QODER_CREDENTIAL_PATH);
  return data;
}

/**
 * Stores/replaces the credential: the raw secret travels as the request body,
 * exactly as the controller reads it. The secret is never echoed back.
 *
 * The body must be sent with a non-JSON content type. The shared client
 * defaults to `Content-Type: application/json`, and axios's `transformRequest`
 * runs string bodies with a JSON content type through `stringifySafely`, which
 * JSON-quotes every secret that is not itself parseable JSON (and silently
 * trims the ones that are) — a data-dependent corruption of the stored secret.
 * `text/plain` falls through axios's transform untouched, so the controller's
 * `@RequestBody String rawBody` reads the secret verbatim (Spring's string body
 * reader does not require application/json).
 */
export async function putQoderCredential(secret: string): Promise<QoderCredentialMetadata> {
  const { data } = await client.put<QoderCredentialMetadata>(QODER_CREDENTIAL_PATH, secret, {
    headers: { 'Content-Type': 'text/plain' },
  });
  return data;
}

/** Removes the credential so future launches can no longer resolve it (204). */
export async function deleteQoderCredential(): Promise<void> {
  await client.delete(QODER_CREDENTIAL_PATH);
}

/** Runs the single explicit bounded credential test; never part of health polling. */
export async function testQoderCredential(): Promise<CredentialTestOutcome> {
  const { data } = await client.post<CredentialTestOutcome>(`${QODER_CREDENTIAL_PATH}/test`);
  return data;
}
