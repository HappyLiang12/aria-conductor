/**
 * Bridge entry point.
 *
 * C0.2: the sandbox bridge listens on `BRIDGE_TOKEN` / `PORT`, fails closed when the
 * token is missing, and treats neither value as optional. The HTTP surface itself lives
 * in `server.ts`; this file only reads configuration, starts the listener and shuts it
 * down on SIGTERM/SIGINT.
 */
import { pathToFileURL } from 'node:url';

import { PINNED_CLI_VERSION, createBridgeServer } from './server.js';

/** C0.2: the sandbox bridge port inside the container. */
export const DEFAULT_BRIDGE_PORT = 4097;

export interface BridgeConfig {
  token: string;
  port: number;
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
    return { token, port: DEFAULT_BRIDGE_PORT };
  }
  if (!/^\d+$/.test(rawPort)) {
    throw new Error(`Invalid PORT: ${rawPort} is not a whole number`);
  }
  const port = Number(rawPort);
  if (port < 1 || port > 65535) {
    throw new Error(`Invalid PORT: ${rawPort} is outside 1..65535`);
  }
  return { token, port };
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
  const server = createBridgeServer({ token: config.token, log: logLine });
  const port = await server.listen(config.port);
  logLine(`bridge listening on port ${port} (cli ${PINNED_CLI_VERSION})`);

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
