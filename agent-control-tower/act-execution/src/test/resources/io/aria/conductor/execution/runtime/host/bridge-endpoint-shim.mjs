#!/usr/bin/env node
// Bridge-shaped runtime stand-in for the Host integration suite (Task 9 fix
// round 2b). The production Host runtime is the run-bound Qoder ACP bridge
// (packages/qoder-acp-bridge): the trusted launcher mints a per-run control
// secret, the runtime serves the run's loopback endpoint and authenticates
// every route but /health with that secret. The mock core this suite drives is
// a stdio-only ACP peer with no HTTP surface, so this shim supplies exactly
// that endpoint contract around it:
//
//   * it reads the secret the backend delivered in QODER_BRIDGE_CONTROL_SECRET
//     (names must match HostExecutionBackend.CONTROL_SECRET_ENVIRONMENT) and
//     never echoes it -- a launch only succeeds if the backend's authenticated
//     call presents the same value this process received, which is what makes
//     the mint -> deliver -> prove chain observable at the integration tier;
//   * it serves /health unauthenticated ({"status":"ok"}) and /session only for
//     the matching x-bridge-control-secret header (HostExecutionBackend.
//     CONTROL_SECRET_HEADER), answering the run's binding view on success;
//   * it spawns the ACP peer (a Node program) with this shim's own
//     interpreter -- the host launch environment is cleared, so the fixture
//     never depends on PATH for its child -- and with inherited stdio, so the
//     suite keeps speaking ACP frames through this process and the peer stays
//     inside the launched tree the ownership controller enumerates and
//     controls.
//
// Usage: node bridge-endpoint-shim.mjs --port <port> --run-id <uuid> \
//          --workspace <dir> -- <peer script> [peer args...]
// Diagnostics are JSON lines on stderr: {"type":"shim.ready","secretDelivered":?}.
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';

const HEADER = 'x-bridge-control-secret';
const SECRET_ENV = 'QODER_BRIDGE_CONTROL_SECRET';
const MIN_SECRET_LENGTH = 16;

const argv = process.argv.slice(2);
let port = 0;
let runId = '';
let workspace = '';
let command = [];
for (let index = 0; index < argv.length; index += 1) {
  if (argv[index] === '--port') {
    port = Number(argv[index + 1]);
    index += 1;
  } else if (argv[index] === '--run-id') {
    runId = argv[index + 1] ?? '';
    index += 1;
  } else if (argv[index] === '--workspace') {
    workspace = argv[index + 1] ?? '';
    index += 1;
  } else if (argv[index] === '--') {
    command = argv.slice(index + 1);
    break;
  }
}
if (!(port > 0) || runId === '' || workspace === '' || command.length === 0) {
  process.stderr.write('{"type":"shim.failed","reason":"usage"}\n');
  process.exit(64);
}

const secret = process.env[SECRET_ENV] ?? '';
const secretDelivered = secret.length >= MIN_SECRET_LENGTH;

const json = (res, status, payload) => {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body, 'utf8'),
    'cache-control': 'no-store',
  });
  res.end(body);
};

const server = createServer((req, res) => {
  const pathname = new URL(req.url ?? '/', 'http://127.0.0.1').pathname;
  if (pathname === '/health') {
    json(res, 200, { status: 'ok' });
    return;
  }
  const provided = req.headers[HEADER];
  if (!secretDelivered || typeof provided !== 'string' || provided !== secret) {
    json(res, 401, { error: { code: 'E_UNAUTHORIZED' } });
    return;
  }
  if (pathname === '/session') {
    json(res, 200, { runId, workspace, model: 'fixture', sessionId: null, state: 'ready' });
    return;
  }
  json(res, 404, { error: { code: 'E_NOT_FOUND' } });
});

server.listen(port, '127.0.0.1', () => {
  // The ready line is emitted after the endpoint is listening but before the
  // peer starts: the backend's authenticated probe gates the launch, and the
  // suite asserts that the secret arrived in the delivered environment.
  process.stderr.write(`${JSON.stringify({ type: 'shim.ready', secretDelivered })}\n`);
  const peer = spawn(process.execPath, command, { stdio: 'inherit' });
  peer.on('error', (error) => {
    process.stderr.write(`${JSON.stringify({ type: 'shim.failed', reason: String(error) })}\n`);
    process.exit(70);
  });
  peer.on('exit', (code, signal) => {
    server.close();
    process.exit(code ?? (signal ? 1 : 0));
  });
});
