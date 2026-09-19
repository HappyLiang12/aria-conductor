import client from './client';
import type { QoderCredentialStatus, QoderCredentialTestResult } from '../types';

/**
 * Operator-facing qoder runtime-credential surface (B8 wire contract).
 *
 * Security shape: the PAT travels one way only. `saveQoderCredential` places
 * the value exclusively in the PUT body of the pinned route; responses carry
 * the masked status and never echo the secret.
 */
const BASE = '/api/v1/adk/providers/qoder/credential';

/** Masked credential status. Absent credential = 200 with nulls (not an error). */
export async function getQoderCredential(): Promise<QoderCredentialStatus> {
  const { data } = await client.get<QoderCredentialStatus>(BASE);
  return data;
}

/** Store or replace the PAT; resolves with the same masked status shape as GET. */
export async function saveQoderCredential(pat: string): Promise<QoderCredentialStatus> {
  const { data } = await client.put<QoderCredentialStatus>(BASE, { pat });
  return data;
}

/** Remove the stored credential (204, idempotent — an absent row is a no-op). */
export async function deleteQoderCredential(): Promise<void> {
  await client.delete(BASE);
}

/** Bounded non-billable structural probe; requires a configured credential. */
export async function testQoderCredential(): Promise<QoderCredentialTestResult> {
  const { data } = await client.post<QoderCredentialTestResult>(`${BASE}/test`);
  return data;
}
