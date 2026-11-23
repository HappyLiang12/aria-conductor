#!/usr/bin/env node
// The committed live smoke check (spec §Deployment): one operator upgrade, one `hello`,
// one `ready`. It is the 1 s DoD of X1 measured from outside the backend, and it is the
// command the phone-side operator runs from the LAN.
//
//   ARIA_OPERATOR_BEARER_TOKEN=… node scripts/smoke.mjs [--host 127.0.0.1] [--port 8080]
//
// It prints exactly one line — `ready v=… maxSessionSec=…` — and exits 0 only when the
// ready advertises the contract's v:1 and 7200 s within one second of `hello`. Every
// failure prints a metadata-only sentence and exits non-zero; the bearer is read from the
// environment and never printed, and no frame payload is ever echoed.

import WebSocket from 'ws';

const DEFAULT_HOST = '127.0.0.1';
const DEFAULT_PORT = '8080';
const PATH = '/api/v1/aria/live';
const PROTOCOL = 'aria-live.v1';
const READY_DEADLINE_MS = 1000;
const CONTRACT_V = 1;
const CONTRACT_MAX_SESSION_SEC = 7200;

/** `--host x`, `--host=x`, `--port n`, `--port=n`; anything else is refused, never echoed. */
function parseArgs(argv) {
  const args = { host: DEFAULT_HOST, port: DEFAULT_PORT };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--host' || arg === '--port') {
      const value = argv[index + 1];
      if (value === undefined || value.startsWith('--')) {
        throw new Error(`${arg} needs a value`);
      }
      args[arg.slice(2)] = value;
      index += 1;
    } else if (arg.startsWith('--host=')) {
      args.host = arg.slice('--host='.length);
    } else if (arg.startsWith('--port=')) {
      args.port = arg.slice('--port='.length);
    } else {
      throw new Error('unknown argument');
    }
  }
  return args;
}

function fail(message) {
  process.stderr.write(`smoke: ${message}\n`);
  process.exit(1);
}

const token = (process.env.ARIA_OPERATOR_BEARER_TOKEN ?? '').trim();
if (token === '') {
  fail('ARIA_OPERATOR_BEARER_TOKEN is not set');
}

let args;
try {
  args = parseArgs(process.argv.slice(2));
} catch (error) {
  // The parser's messages name the flag only; no value is ever echoed back.
  fail(`bad arguments: ${error.message}`);
}

const url = `ws://${args.host}:${args.port}${PATH}`;
const conversationId = `live-smoke-${Date.now()}`;
const hello = {
  type: 'hello',
  v: CONTRACT_V,
  conversationId,
  audio: { inRate: 16000, outRate: 24000, format: 'pcm16le', channels: 1 },
  mic: false,
  ar: false,
};

let deadline = null;
let finished = false;

const finish = (code, line) => {
  if (finished) {
    return;
  }
  finished = true;
  if (deadline !== null) {
    clearTimeout(deadline);
  }
  if (line !== null) {
    process.stdout.write(`${line}\n`);
  }
  socket.close();
  process.exit(code);
};

const socket = new WebSocket(url, [PROTOCOL], {
  headers: { Authorization: `Bearer ${token}` },
});

socket.on('open', () => {
  // The 1 s clock is the server's: it starts when the hello is sent, not when the TCP
  // connection opened (a LAN handshake is not what the contract measures).
  deadline = setTimeout(() => {
    process.stderr.write(`smoke: no ready within ${READY_DEADLINE_MS} ms\n`);
    process.exit(1);
  }, READY_DEADLINE_MS);
  socket.send(JSON.stringify(hello));
});

socket.on('message', (data, isBinary) => {
  if (isBinary) {
    return;
  }
  let event;
  try {
    event = JSON.parse(data.toString());
  } catch {
    return;
  }
  if (event.type !== 'ready') {
    return;
  }
  const v = Number(event.v);
  const maxSessionSec = Number(event.maxSessionSec);
  const line = `ready v=${v} maxSessionSec=${maxSessionSec}`;
  const ok = v === CONTRACT_V && maxSessionSec === CONTRACT_MAX_SESSION_SEC;
  finish(ok ? 0 : 1, line);
});

socket.on('unexpected-response', (_request, response) => {
  fail(`upgrade refused with status ${response.statusCode}`);
});

socket.on('error', (error) => {
  // `ws` error texts can quote request options; the code is the honest, safe part.
  fail(`connection failed (${error?.code ?? 'no code'})`);
});

socket.on('close', () => {
  if (!finished) {
    fail(`socket closed before ready (within ${READY_DEADLINE_MS} ms)`);
  }
});
