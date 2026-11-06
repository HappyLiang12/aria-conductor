import { formatTimestamp } from '../utils/formatTime';
import type { Approval } from '../types';

/**
 * The normalized correlation of a native permission ask, rendered by every
 * pending surface of the dashboard (Review panels, ReviewQueue, Ops).
 *
 * Native permission asks reach the UI as normal approvals whose reason carries
 * the correlation the backend registered (`PermissionCoordinator.registrationReason`):
 * request id, session, tool and the target kind (`NativePermission.target`).
 * The surfaces render the kind and the ask's expiry from here; a shared string
 * is recognized rather than guessed, and anything unmatched simply stays a
 * gate approval (never an invented kind).
 *
 * The exact rendered shape is pinned on the backend by
 * `PermissionCoordinatorTest.registrationReasonRendersTheExactShapeTheReviewSurfaceParses`
 * (act-execution), so a wording change breaks loudly instead of silently
 * dropping the kind/expiry row and the batch exclusion.
 */

/** The permission kind a normalized native ask belongs to (spec §6.3). */
export type NativePermissionKind = 'NATIVE_TOOL' | 'PLATFORM_MCP';

export interface NativePermissionAsk {
  requestId: string;
  toolName: string;
  kind: NativePermissionKind;
}

export const NATIVE_PERMISSION_REASON =
  /^Native permission request (\S+) from session \S+ for tool (\S+) \((NATIVE_TOOL|PLATFORM_MCP)\)$/;

export function nativePermissionOf(ask: Approval): NativePermissionAsk | null {
  const match = NATIVE_PERMISSION_REASON.exec(ask.reason ?? '');
  if (!match) return null;
  return { requestId: match[1], toolName: match[2], kind: match[3] as NativePermissionKind };
}

/** The kind pill every pending surface renders for a normalized native ask. */
export function NativePermissionKindPill({ permission }: { permission: NativePermissionAsk }) {
  return <span className="pill risk">Native permission · {permission.kind}</span>;
}

/**
 * The correlation facts of a normalized native ask — tool, request, risk and
 * expiry — rendered identically by every pending surface; the caller owns the
 * surrounding container so each surface keeps its own layout.
 */
export function NativePermissionFacts({
  ask,
  permission,
}: {
  ask: Approval;
  permission: NativePermissionAsk;
}) {
  return (
    <>
      <span className="owner cell-mono">tool {permission.toolName}</span>
      <span className="owner cell-mono">request {permission.requestId}</span>
      {ask.riskTier && <span className="pill warn">risk {ask.riskTier}</span>}
      {ask.expiresAt && <span className="owner">expires {formatTimestamp(ask.expiresAt)}</span>}
    </>
  );
}
