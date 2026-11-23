#!/usr/bin/env node
// The committed live smoke check (spec §Deployment): one operator upgrade, one `hello`,
// one `ready`. It is the 1 s DoD of X1 measured from outside the backend, and it is the
// command the phone-side operator runs from the LAN.
//
//   ARIA_OPERATOR_BEARER_TOKEN=… node scripts/smoke.mjs [--host 127.0.0.1] [--port 8080]
//   ARIA_OPERATOR_BEARER_TOKEN=… node scripts/smoke.mjs --turn "而家幾點？"
//
// Without `--turn` it prints exactly one line — `ready v=… maxSessionSec=…` — and exits 0
// only when the ready advertises the contract's v:1 and 7200 s within one second of `hello`.
//
// With `--turn <text>` it continues past `ready`, sends one `{"type":"text","v":1,…}`, and
// prints one metadata line per event for up to 30 s. That run is the only check that proves
// the provider accepts the session config the bridge declares (`turn_detection`:
// `semantic_vad_ec`): a rejected config arrives as `error{code:"capability_mismatch"}`.
// The phone event that ends a turn is the final `output_transcript`; the bridge's internal
// `turn_done` line is consumed by Java and never crosses to the socket, so a tool-using turn
// additionally shows `tool_status` and a `run_event`. Exit codes in turn mode: 0 when the
// final `output_transcript` arrived, 2 on `error` (code/retryable/message class only),
// 3 when no final transcript arrived within 30 s.
//
// Every failure prints a metadata-only sentence and exits non-zero; the bearer is read from
// the environment and never printed, and no frame payload — text, audio, ids — is ever
// echoed or logged.

import WebSocket from 'ws';

const DEFAULT_HOST = '127.0.0.1';
const DEFAULT_PORT = '8080';
const PATH = '/api/v1/aria/live';
const PROTOCOL = 'aria-live.v1';
const READY_DEADLINE_MS = 1000;
const TURN_DEADLINE_MS = 30_000;
const CONTRACT_V = 1;
const CONTRACT_MAX_SESSION_SEC = 7200;

/** `--host x`, `--host=x`, `--port n`, `--port=n`, `--turn "text"`; else refused, never echoed. */
function parseArgs(argv) {
  const args = { host: DEFAULT_HOST, port: DEFAULT_PORT, turn: null };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--host' || arg === '--port' || arg === '--turn') {
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
    } else if (arg.startsWith('--turn=')) {
      args.turn = arg.slice('--turn='.length);
    } else {
      throw new Error('unknown argument');
    }
  }
  if (args.turn !== null && args.turn.trim() === '') {
    // The turn text is operator input; the error names the flag only.
    throw new Error('--turn needs a non-empty value');
  }
  return args;
}

/** The `message` of an error classified to a fixed word, so provider text never prints. */
function messageClass(message) {
  const text = String(message ?? '').toLowerCase();
  if (text.includes('capabilit') || text.includes('turn_detection') || text.includes('semantic')) {
    return 'capability';
  }
  if (text.includes('rate')) {
    return 'rate_limited';
  }
  if (text.includes('auth') || text.includes('401') || text.includes('403')) {
    return 'auth';
  }
  if (text.includes('expire')) {
    return 'expired';
  }
  if (text.includes('unavailable')) {
    return 'unavailable';
  }
  if (text.includes('bridge')) {
    return 'bridge';
  }
  return 'other';
}

/** One metadata line: type, plus code/retryable/tMs/seq/final when the event carries them. */
function eventLine(event, withMessageClass) {
  let line = `event type=${String(event.type)}`;
  if (event.code !== undefined) {
    line += ` code=${String(event.code)}`;
  }
  if (event.retryable !== undefined) {
    line += ` retryable=${event.retryable === true}`;
  }
  if (event.tMs !== undefined) {
    line += ` tMs=${Number(event.tMs)}`;
  }
  if (event.seq !== undefined) {
    line += ` seq=${Number(event.seq)}`;
  }
  if (event.final !== undefined) {
    line += ` final=${event.final === true}`;
  }
  if (withMessageClass) {
    line += ` messageClass=${messageClass(event.message)}`;
  }
  return line;
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
let phase = 'ready';

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
  // Outbound audio is binary and is never read: the smoke proves the control path only.
  if (isBinary) {
    return;
  }
  let event;
  try {
    event = JSON.parse(data.toString());
  } catch {
    return;
  }
  if (phase === 'ready') {
    if (event.type !== 'ready') {
      return;
    }
    const v = Number(event.v);
    const maxSessionSec = Number(event.maxSessionSec);
    const line = `ready v=${v} maxSessionSec=${maxSessionSec}`;
    if (v !== CONTRACT_V || maxSessionSec !== CONTRACT_MAX_SESSION_SEC) {
      finish(1, line);
      return;
    }
    if (args.turn === null) {
      finish(0, line);
      return;
    }
    // The ready is the turn's starting gun: send the one text frame, then listen.
    phase = 'turn';
    process.stdout.write(`${line}\n`);
    socket.send(JSON.stringify({ type: 'text', v: CONTRACT_V, text: args.turn }));
    if (deadline !== null) {
      clearTimeout(deadline);
    }
    deadline = setTimeout(() => {
      process.stderr.write(`smoke: no final output_transcript within ${TURN_DEADLINE_MS} ms\n`);
      process.exit(3);
    }, TURN_DEADLINE_MS);
    return;
  }
  if (phase !== 'turn') {
    return;
  }
  const isError = event.type === 'error';
  process.stdout.write(`${eventLine(event, isError)}\n`);
  if (isError) {
    // The provider (or the bridge) refused this turn: the code says which failure it is.
    finish(2, null);
    return;
  }
  if (event.type === 'output_transcript' && event.final === true) {
    finish(0, null);
  }
});

socket.on('unexpected-response', (_request, response) => {
  fail(`upgrade refused with status ${response.statusCode}`);
});

socket.on('error', (error) => {
  // `ws` error texts can quote request options; the code is the honest, safe part.
  fail(`connection failed (${error?.code ?? 'no code'})`);
});

socket.on('close', () => {
  if (finished) {
    return;
  }
  if (phase === 'turn') {
    // A close before the final transcript is the same outcome as the timeout: no turn.
    process.stderr.write('smoke: socket closed before the final output_transcript\n');
    process.exit(3);
  }
  fail(`socket closed before ready (within ${READY_DEADLINE_MS} ms)`);
});
