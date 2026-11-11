/**
 * The eight-character run correlation every operator surface shows.
 *
 * The approvals list is a REST payload, not a guaranteed-complete object: a
 * sparse row (or one from an older backend) can arrive without `runId`. A
 * surface must name that absence instead of dereferencing it — the release
 * gate's minimal `{ id, status }` payload threw inside a render `.map` and
 * blanked the page. "unknown" is an honest absence; it is never a fabricated id
 * and never a reason to drop the row.
 */
export const UNKNOWN_RUN = 'unknown';

export function shortRunId(runId: string | null | undefined): string {
  if (typeof runId !== 'string') return UNKNOWN_RUN;
  const trimmed = runId.trim();
  return trimmed === '' ? UNKNOWN_RUN : trimmed.slice(0, 8);
}
