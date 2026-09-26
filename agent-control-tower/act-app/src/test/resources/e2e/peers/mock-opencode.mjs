#!/usr/bin/env node
// Deterministic mock OpenCode service (Task 7 fixture peer).
//
// It is a real Node process that serves the verified native HTTP subset recorded
// in e2e/agent-core/fixtures/opencode-host-cli-1.14.31-events.json: `serve
// --hostname 127.0.0.1 --port <n>` argv, `GET /global/health`,
// `POST /session` -> {id}, `POST /session/:id/message` -> {info, parts},
// `GET /session/:id/message` -> [{info, parts}] and `POST /session/:id/abort`.
// The recorded message envelope (assistant info with modelID/providerID/path/
// cost/tokens/time, plus an info.error object with name+statusCode+isRetryable
// for the scripted provider failure) is reproduced; message texts are
// fixture-defined.
//
// It never opens a database connection, never writes a run outcome and never
// contacts an external model. Fixture writes happen only through
// applyDecision()/grantWriterFixture() after a genuine allow-once decision, and
// only inside the admitted temporary workspace. A decision-gated scenario HOLDS
// its message response until the decision resolves and answers with the
// outcome-reflecting fixture text, so a governed write gate really blocks the
// core's turn instead of being offered while the run completes. Scenario
// selection happens at launch time behind the harness control token; the
// /__peer/* control surface requires that token and answers 401 without it.
import { randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { join } from 'node:path';
import {
  EXIT_CODES,
  applyDecision,
  assertInsideWorkspace,
  bootPeer,
  gitPushTargetFromPrompt,
  grantWriterFixture,
  loadScenarioManifest,
  pushGitBranch,
  record,
  recordedOptions,
  requirePortFlag,
  startBackgroundWriter,
} from './peer-actions.mjs';

const PEER_ID = 'opencode';
const boot = bootPeer({ peerId: PEER_ID, argv: process.argv.slice(2) });
const SCENARIO = boot.scenario;
const WORKSPACE = boot.workspace;
const TOKEN = boot.token;
const fixtures = loadScenarioManifest().fixtures;
const AVAILABLE_MODELS = fixtures.models.available;
const DEFAULT_MODEL = fixtures.models.default;
const WRITER_LOG = fixtures.writer.logName;
const WRITER_COMMAND = fixtures.writer.command;
const VERSION = '1.14.31';
const MAX_BODY_BYTES = 2 * 1024 * 1024;
const ALPHABET = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const port = requirePortFlag(boot.flags);
const hostname = boot.flags.hostname ?? '127.0.0.1';

let sessionId = null;
let pendingDecision = null;
let offered = 0;
let writerPid = null;
let writes = 0;
let decisions = 0;
let streaming = false;
const messages = [];

const newSessionId = () => `ses_${randomBytes(12).toString('hex')}`;
const newMessageId = () => `msg_${[...randomBytes(26)].map((byte) => ALPHABET[byte % 62]).join('')}`;

function json(response, status, body) {
  const payload = JSON.stringify(body);
  response.writeHead(status, { 'content-type': 'application/json; charset=utf-8' });
  response.end(payload);
}

function readBody(request) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    request.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(new Error('fixture request body exceeded the accepted maximum'));
        request.destroy();
        return;
      }
      chunks.push(chunk);
    });
    request.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      if (raw.length === 0) return resolve(null);
      try {
        resolve(JSON.parse(raw));
      } catch {
        reject(new Error(`fixture request body was not JSON: ${raw.slice(0, 80)}`));
      }
    });
  });
}

/**
 * The recorded assistant message envelope. `tokens` and `modelID` are omitted
 * when the scenario reports them as unknown (the `unknown-usage` fixture), so
 * the adapter has to keep a missing counter unknown instead of inventing zero.
 */
function assistantMessage({ text = 'pong', error = null, tokens = undefined, modelID = undefined } = {}) {
  const now = Date.now();
  return {
    info: {
      id: newMessageId(),
      role: 'assistant',
      mode: 'build',
      agent: 'build',
      path: { cwd: WORKSPACE, root: '/' },
      cost: 0,
      ...(tokens === null ? {} : { tokens: tokens ?? { input: 0, output: 0, reasoning: 0, cache: { read: 0, write: 0 } } }),
      ...(modelID === null ? {} : { modelID: modelID ?? DEFAULT_MODEL }),
      providerID: 'opencode',
      time: { created: now, completed: now },
      ...(error ? { error } : {}),
    },
    parts: error ? [] : [{ type: 'text', text }],
  };
}

/** Text parts of a message request, joined the way the native envelope concatenates them. */
function requestText(body) {
  return (Array.isArray(body?.parts) ? body.parts : [])
    .filter((part) => part?.type === 'text')
    .map((part) => part.text ?? '')
    .join('\n');
}

function pendingPayload() {
  if (pendingDecision === null) return { pending: [] };
  return {
    pending: [
      {
        id: pendingDecision.spec.requestId,
        kind: pendingDecision.spec.kind,
        toolName: pendingDecision.spec.toolName ?? null,
        path: pendingDecision.spec.target,
        command: pendingDecision.spec.command ?? null,
        contents: pendingDecision.spec.contents,
        options: pendingDecision.options,
      },
    ],
  };
}

/**
 * The governed git push: one execute-kind decision request for the recorded
 * `git_push` tool, with the branch and remote named by the run prompt. The
 * message response is held until the decision resolves, so a pending gate
 * really blocks the run; only an allow-once decision pushes (applyDecisionRequest).
 */
async function gitPushFlow(body) {
  const target = gitPushTargetFromPrompt(requestText(body));
  record('peer.git_push_requested', { branch: target.branch, remote: target.remote });
  const decision = await offerDecision({
    kind: 'execute',
    toolName: 'git_push',
    command: `git push ${target.remote} HEAD:refs/heads/${target.branch}`,
    gitPush: target,
    target: assertInsideWorkspace(WORKSPACE, join(WORKSPACE, 'git-push.probe')),
    contents: `${target.branch}\n`,
  });
  if (decision.status === 'written') {
    return assistantMessage({ text: `pushed ${decision.pushed}` });
  }
  return assistantMessage({ text: 'git_push was not granted' });
}

/**
 * The outcome-reflecting text of one decision-gated turn. The message response
 * is held until the decision resolves (the gitPushFlow contract), so the text
 * states what the decision did to the gated write; a completed run's finalOutput
 * is then evidence of the branch the peer took.
 */
function decisionText(decision) {
  switch (decision.status) {
    case 'written':
      return 'fixture write applied';
    case 'denied':
      return 'fixture write not applied: the decision denied it';
    case 'expired':
      return 'fixture write not applied: the permission window expired';
    case 'cancelled':
      return 'fixture write not applied: the run was cancelled';
    default:
      return 'fixture write not applied: the decision was refused';
  }
}

/** One `write-twice` verdict: only a genuine allow-once decision lands a write. */
const writeVerdict = (decision) => (decision.status === 'written' ? 'applied' : 'not applied');

/** Register a pending fixture decision and return its resolution promise. */
function offerDecision(spec) {
  const requestId = offered;
  offered += 1;
  const options = recordedOptions(spec.kind, { command: spec.command });
  return new Promise((resolve) => {
    const entry = { spec: { ...spec, requestId }, options, resolve };
    pendingDecision = entry;
    if (spec.expiryMs) {
      setTimeout(() => {
        if (pendingDecision !== entry) return;
        pendingDecision = null;
        record('peer.permission_expired', { requestId, windowMs: spec.expiryMs });
        resolve({ status: 'expired' });
      }, spec.expiryMs);
    }
  });
}

// ------------------------------------------------------------------ scenarios

/**
 * The two decisions of `write-twice`, awaited in order before the message is
 * answered: each decision is a gate the turn waits on (the gitPushFlow
 * contract), so a run that has not decided both is genuinely held.
 */
async function writeTwiceChain() {
  const first = await offerDecision({
    kind: 'edit',
    toolName: 'Write',
    target: assertInsideWorkspace(WORKSPACE, join(WORKSPACE, 'probe-allow-once.txt')),
    contents: 'alpha-allow-once',
  });
  const secondTarget = assertInsideWorkspace(WORKSPACE, join(WORKSPACE, 'probe-write-twice-2.txt'));
  const second = await offerDecision({
    kind: 'execute',
    toolName: 'Bash',
    target: secondTarget,
    contents: 'gamma-permission-twice',
    command: `printf 'gamma-permission-twice' > "${secondTarget}"`,
  });
  return assistantMessage({
    text: `fixture writes: probe-allow-once.txt ${writeVerdict(first)};`
      + ` probe-write-twice-2.txt ${writeVerdict(second)}`,
  });
}

async function writerChain() {
  const decision = await offerDecision({
    kind: 'execute',
    toolName: 'Bash',
    target: assertInsideWorkspace(WORKSPACE, join(WORKSPACE, WRITER_LOG)),
    contents: 'fixture-writer-start\n',
    command: WRITER_COMMAND,
    scaffold: true,
    afterWrite: async () => {
      const started = await startBackgroundWriter({ workspace: WORKSPACE, logName: WRITER_LOG });
      writerPid = started.writerPid;
      record('peer.child_started', {
        pid: started.writerPid,
        launcherPid: started.launcher.pid,
        command: WRITER_COMMAND,
        executable: process.execPath,
      });
      if (SCENARIO === 'pause-resume' || SCENARIO === 'non-cooperative') {
        streaming = true;
        void streamParts();
      }
    },
  });
  return decision;
}

async function streamParts() {
  while (streaming) {
    await sleep(1000);
    if (!streaming) return;
    messages.push({
      info: assistantMessage().info,
      parts: [{ type: 'text', text: 'fixture-streaming\n' }],
    });
  }
}

/**
 * Prepare the scenario's decision flow for one message request and return the
 * assistant message the turn completes with. A governed write decision is a
 * gate the core's turn WAITS on (the gitPushFlow contract), so every offer
 * scenario holds its message until the decision arrives and answers with the
 * outcome-reflecting fixture text (decisionText); the recorded expiry/cancel
 * semantics are honoured -- an expiring ask still expires and then releases the
 * message. The writer scenarios hold the same way and keep the recorded `DONE`
 * completion for a genuine grant.
 */
async function startDecisionFlow(body) {
  switch (SCENARIO) {
    case 'reported-usage':
      return assistantMessage({
        text: 'fixture-complete',
        tokens: { input: 12, output: 7, reasoning: 0, cache: { read: 0, write: 0 } },
        modelID: 'efficient',
      });
    case 'unknown-usage':
      return assistantMessage({ text: 'fixture-usage-unknown', tokens: null, modelID: null });
    case 'two-turn-nonce':
      // The exact request the core received is both recorded (harness evidence)
      // and echoed, so the adapter's context translation is directly observable.
      record('peer.message_request', {
        body: {
          model: body?.model ?? null,
          ...(body?.system === undefined ? {} : { system: body.system }),
          parts: Array.isArray(body?.parts) ? body.parts : [],
        },
      });
      return assistantMessage({ text: requestText(body) });
    case 'write-twice':
      return writeTwiceChain();
    case 'deny-write':
      return assistantMessage({
        text: decisionText(await offerDecision({
          kind: 'edit',
          toolName: 'Write',
          target: assertInsideWorkspace(WORKSPACE, join(WORKSPACE, 'probe-deny.txt')),
          contents: 'beta-should-not-exist',
        })),
      });
    case 'permission-expiry':
      return assistantMessage({
        text: decisionText(await offerDecision({
          kind: 'edit',
          toolName: 'Write',
          target: assertInsideWorkspace(WORKSPACE, join(WORKSPACE, 'probe-expiry.txt')),
          contents: 'epsilon-expired',
          expiryMs: 300,
        })),
      });
    case 'cancel-pending':
      return assistantMessage({
        text: decisionText(await offerDecision({
          kind: 'edit',
          toolName: 'Write',
          target: assertInsideWorkspace(WORKSPACE, join(WORKSPACE, 'probe-cancel-pending.txt')),
          contents: 'gamma-cancel-pending',
        })),
      });
    case 'background-writer':
    case 'pause-resume':
    case 'non-cooperative': {
      const decision = await writerChain();
      return assistantMessage({ text: decision.status === 'written' ? 'DONE' : decisionText(decision) });
    }
    default:
      return assistantMessage({ text: 'pong' });
  }
}

/** Apply one harness decision; resolves after any granted post-write effect. */
async function applyDecisionRequest(optionId) {
  if (pendingDecision === null) {
    return { status: 409, body: { error: 'no pending fixture decision' } };
  }
  const entry = pendingDecision;
  const kind = entry.options.find((option) => option.optionId === optionId)?.kind ?? null;
  if (kind !== 'allow_once' && kind !== 'reject_once') {
    // An unoffered decision must not consume the pending request.
    record('peer.invalid_decision', { optionId, kind, writes: 0 });
    return { status: 409, body: { error: 'unsupported fixture decision' } };
  }
  pendingDecision = null;
  decisions += 1;
  let decision;
  try {
    if (entry.spec.gitPush) {
      decision = pushGitBranch({
        optionId,
        options: entry.options,
        workspace: WORKSPACE,
        branch: entry.spec.gitPush.branch,
        remote: entry.spec.gitPush.remote,
      });
    } else if (entry.spec.scaffold) {
      decision = await grantWriterFixture({
        optionId,
        options: entry.options,
        workspace: WORKSPACE,
        logName: WRITER_LOG,
        initialContents: entry.spec.contents,
      });
    } else {
      decision = await applyDecision(optionId, entry.options, entry.spec.target, entry.spec.contents, WORKSPACE);
    }
  } catch (error) {
    record('peer.invalid_decision', { optionId, kind, writes: 0 });
    entry.resolve({ status: 'refused' });
    return { status: 409, body: { error: 'unsupported fixture decision' } };
  }
  if (decision.status === 'written') {
    writes += decision.writes;
    if (entry.spec.afterWrite) await entry.spec.afterWrite();
  }
  entry.resolve(decision);
  return { status: 200, body: { status: decision.status, writes: decision.writes, optionId } };
}

// ------------------------------------------------------------------ HTTP surface

const server = createServer(async (request, response) => {
  const url = new URL(request.url, `http://${hostname}:${port}`);
  const path = url.pathname;
  try {
    if (path.startsWith('/__peer/')) {
      if (request.headers['x-peer-control-token'] !== TOKEN) {
        json(response, 401, { error: 'unauthorized' });
        return;
      }
      if (path === '/__peer/pending' && request.method === 'GET') {
        json(response, 200, pendingPayload());
        return;
      }
      if (path === '/__peer/decision' && request.method === 'POST') {
        const body = await readBody(request);
        const outcome = await applyDecisionRequest(body?.optionId);
        json(response, outcome.status, outcome.body);
        return;
      }
      if (path === '/__peer/state' && request.method === 'GET') {
        json(response, 200, { scenario: SCENARIO, sessionId, writes, decisions, writerPid, messages: messages.length });
        return;
      }
      json(response, 404, { error: 'not found' });
      return;
    }
    if (path === '/global/health' && request.method === 'GET') {
      if (SCENARIO === 'handshake-failure') {
        json(response, 503, { healthy: false, version: VERSION });
        return;
      }
      json(response, 200, { healthy: true, version: VERSION });
      return;
    }
    if (path === '/session' && request.method === 'POST') {
      if (SCENARIO === 'invalid-auth') {
        json(response, 401, { error: 'unauthorized', message: 'invalid authentication' });
        return;
      }
      if (SCENARIO === 'handshake-failure') {
        json(response, 503, { error: 'service unavailable', message: 'fixture handshake failure' });
        return;
      }
      sessionId = newSessionId();
      messages.length = 0;
      json(response, 200, {
        id: sessionId,
        projectID: 'fixture-project',
        directory: WORKSPACE,
        time: { created: Date.now(), updated: Date.now() },
      });
      return;
    }
    if (path.startsWith('/session/') && path.endsWith('/message')) {
      const target = path.slice('/session/'.length, -'/message'.length);
      if (target !== sessionId) {
        json(response, 404, { error: 'unknown session' });
        return;
      }
      if (request.method === 'GET') {
        json(response, 200, messages);
        return;
      }
      if (request.method !== 'POST') {
        json(response, 404, { error: 'not found' });
        return;
      }
      const body = await readBody(request);
      const requestedModel = body?.model ?? DEFAULT_MODEL;
      if (!AVAILABLE_MODELS.includes(requestedModel)) {
        json(response, 400, {
          error: {
            name: 'APIError',
            data: {
              message: `Unsupported model id: ${requestedModel}`,
              statusCode: 400,
              isRetryable: false,
            },
          },
        });
        return;
      }
      if (SCENARIO === 'malformed-frame') {
        response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
        response.end('{"info":{"id":"msg_');
        return;
      }
      if (SCENARIO === 'timeout') {
        record('peer.request_pending', { method: 'POST /session/:id/message', id: null });
        return;
      }
      if (SCENARIO === 'disconnect') {
        record('peer.disconnect', { scenario: SCENARIO });
        response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
        response.write('{"info":{"id":"msg_');
        if (typeof response.flushHeaders === 'function') response.flushHeaders();
        setTimeout(() => response.destroy(), 20);
        return;
      }
      if (SCENARIO === 'git-push') {
        // The governed push holds the message response until its decision
        // resolves, so the run really blocks on the PUSH gate.
        const message = await gitPushFlow(body);
        messages.push(message);
        json(response, 200, message);
        return;
      }
      // A decision-gated scenario holds the message response until its decision
      // resolves (startDecisionFlow), so the run really blocks on the gate.
      const message = await startDecisionFlow(body);
      messages.push(message);
      json(response, 200, message);
      return;
    }
    if (path.startsWith('/session/') && path.endsWith('/abort') && request.method === 'POST') {
      const target = path.slice('/session/'.length, -'/abort'.length);
      if (target !== sessionId) {
        json(response, 404, { error: 'unknown session' });
        return;
      }
      if (SCENARIO === 'non-cooperative') {
        record('peer.abort_ignored', { sessionId });
        json(response, 200, true);
        return;
      }
      streaming = false;
      if (pendingDecision !== null) {
        const entry = pendingDecision;
        pendingDecision = null;
        record('peer.cancel', {
          sessionId,
          pending: 'decision',
          pendingRequestIds: [entry.spec.requestId],
        });
        entry.resolve({ status: 'cancelled' });
      }
      json(response, 200, true);
      return;
    }
    json(response, 404, { error: 'not found' });
  } catch (error) {
    record('peer.handler_error', { message: error.message });
    json(response, 500, { error: 'fixture handler error', message: error.message });
  }
});

server.listen(port, hostname, () => {
  record('peer.listening', { peer: PEER_ID, scenario: SCENARIO, host: hostname, port: server.address().port });
});

process.on('SIGTERM', () => {
  if (SCENARIO === 'non-cooperative') {
    record('peer.sigterm_ignored', { writerPid });
    return;
  }
  process.exit(EXIT_CODES.ok);
});

process.on('unhandledRejection', (error) => {
  record('peer.unhandled_rejection', { message: String(error) });
  process.exit(EXIT_CODES.scripted);
});
