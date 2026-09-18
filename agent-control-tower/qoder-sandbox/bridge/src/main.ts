/**
 * Bridge entry point.
 *
 * C0.2: the sandbox bridge listens on `BRIDGE_TOKEN` / `PORT`, fails closed when the
 * token is missing, and treats neither value as optional. The HTTP surface itself lives
 * in `server.ts`; this file only reads configuration, starts the listener and shuts it
 * down on SIGTERM/SIGINT.
 *
 * G7/G8: the value the provider forwards in `APPROVAL_TIMEOUT_MS` — the host's approval window
 * (`approvals.timeout-ms`) PADDED by the provider's documented margin — rides into the server, so
 * the bridge clamps every per-ask deadline to the host's window plus that margin. The host anchors
 * an ask's expiry when it persists the ask, after the bridge handled the frame, so the padding is
 * what keeps the bridge's local deadline from preceding the host's expiry: the host always expires
 * an ask it holds first, and the bridge's local reject only fires for an ask the host no longer
 * answers.
 */
import { pathToFileURL } from 'node:url';

import { DEFAULT_PERMISSION_DEADLINE_MS, PINNED_CLI_VERSION, createBridgeServer } from './server.js';

/** C0.2: the sandbox bridge port inside the container. */
export const DEFAULT_BRIDGE_PORT = 4097;

/**
 * G7/G8: environment variable carrying the host's approval window plus the provider's documented
 * margin, in milliseconds. The provider starts the bridge (and the image CMD starts the same
 * process with the same container environment), so the value that bounds the host's ask, padded,
 * is the value that bounds the bridge's per-ask deadline — one source of truth, no second number
 * to drift.
 */
export const APPROVAL_WINDOW_ENV = 'APPROVAL_TIMEOUT_MS';

export interface BridgeConfig {
  token: string;
  port: number;
  /**
   * The host's approval window (`min(now + approvals.timeout-ms, runDeadline)`) PLUS the
   * provider's documented margin, as forwarded in `APPROVAL_TIMEOUT_MS`. The bridge clamps each
   * per-ask deadline to it, and to the session run deadline, whichever is nearer. The host anchors
   * its expiry at persist time (after the frame reached the bridge), so the padding is what keeps
   * the bridge's local deadline from preceding the host's expiry: the host expires an ask it holds
   * first, and the bridge's local reject only fires for an ask the host no longer answers.
   */
  permissionDeadlineMs: number;
}

/**
 * Fail closed: without a non-empty `BRIDGE_TOKEN` there is no configuration at all, so
 * the caller (and therefore the process) must refuse to start. `PORT` is validated as a
 * usable TCP port; absent/blank means the pinned default.
 */
export function readBridgeConfig(env: NodeJS.ProcessEnv = process.env): BridgeConfig {
  const token = (env.BRIDGE_TOKEN ?? '').trim();
  if (token === '') {
    throw new Error('BRIDGE_TOKEN is required: refusing to start without a bridge token');
  }

  const rawPort = (env.PORT ?? '').trim();
  if (rawPort === '') {
    return { token, port: DEFAULT_BRIDGE_PORT, permissionDeadlineMs: readApprovalWindowMs(env) };
  }
  if (!/^\d+$/.test(rawPort)) {
    throw new Error(`Invalid PORT: ${rawPort} is not a whole number`);
  }
  const port = Number(rawPort);
  if (port < 1 || port > 65535) {
    throw new Error(`Invalid PORT: ${rawPort} is outside 1..65535`);
  }
  return { token, port, permissionDeadlineMs: readApprovalWindowMs(env) };
}

/**
 * G7/G8: the approval window the provider forwards (`APPROVAL_TIMEOUT_MS`) — the host's
 * `approvals.timeout-ms` plus the provider's documented margin. A host that starts the bridge
 * always sets it, so a malformed value refuses to start rather than silently enforcing a deadline
 * the host does not share. An ABSENT value is the documented fallback
 * {@link DEFAULT_PERMISSION_DEADLINE_MS} for bridges started without a host (sandbox smoke tests,
 * the image boot check) — those paths have no host ask to disagree with.
 */
function readApprovalWindowMs(env: NodeJS.ProcessEnv): number {
  const raw = (env[APPROVAL_WINDOW_ENV] ?? '').trim();
  if (raw === '') {
    return DEFAULT_PERMISSION_DEADLINE_MS;
  }
  if (!/^\d+$/.test(raw)) {
    throw new Error(`Invalid ${APPROVAL_WINDOW_ENV}: ${raw} is not a whole number of milliseconds`);
  }
  const windowMs = Number(raw);
  if (!Number.isSafeInteger(windowMs) || windowMs < 1) {
    throw new Error(`Invalid ${APPROVAL_WINDOW_ENV}: ${raw} is outside the usable millisecond range`);
  }
  return windowMs;
}

function logLine(line: string): void {
  process.stdout.write(`${line}\n`);
}

/**
 * Start the bridge and block until shutdown. Request logging is the server's own
 * (route, status and session id at most); this function only announces lifecycle.
 */
export async function main(): Promise<void> {
  const config = readBridgeConfig();
  const server = createBridgeServer({
    token: config.token,
    log: logLine,
    permissionDeadlineMs: config.permissionDeadlineMs,
  });
  const port = await server.listen(config.port);
  logLine(`bridge listening on port ${port} (cli ${PINNED_CLI_VERSION},`
    + ` approval window ${config.permissionDeadlineMs}ms)`);

  let shuttingDown = false;
  const shutdown = (signal: NodeJS.Signals): void => {
    if (shuttingDown) {
      return;
    }
    shuttingDown = true;
    logLine(`bridge shutting down (${signal})`);
    // No process.exit: the ACP client's kill timers must be allowed to escalate while
    // the event loop drains, otherwise a terminated CLI could be orphaned.
    void server.close().then(() => {
      logLine('bridge stopped');
    });
  };
  process.once('SIGTERM', shutdown);
  process.once('SIGINT', shutdown);
}

const entryPoint = process.argv[1];
if (entryPoint !== undefined && import.meta.url === pathToFileURL(entryPoint).href) {
  main().catch((error: unknown) => {
    const message = error instanceof Error ? error.message : String(error);
    process.stderr.write(`bridge failed to start: ${message}\n`);
    process.exitCode = 1;
  });
}
