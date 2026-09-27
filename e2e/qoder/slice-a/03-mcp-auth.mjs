#!/usr/bin/env node
// =============================================================================
// Task A3 (Slice A gate): authenticated MCP round-trip with real headers.
//
// Runs INSIDE a real OpenSandbox sandbox (image aria-conductor/qoder-sandbox:0.1,
// Node 22) as a Node ESM script; QoderMcpAuthE2ETest uploads it to
// /workspace/03-mcp-auth.mjs and executes it through
// OpenCodeSandboxManager#runCommand. It extends the Section 8 recipe of
// docs/reviews/2026-09-17-qoder-cli-acp-spike.md (ACP handshake, permission reply
// shape, loopback HTTP MCP stub) with a header-validating stub and negative cases.
//
// What it proves, in three cases against a fresh in-sandbox loopback stub:
//   1. POSITIVE: `session/new.mcpServers[0].headers` carries
//      `Authorization: Bearer test-worker-token` (SYNTHETIC placeholder); the stub
//      accepts the JSON-RPC requests, the CLI's aria_ping call succeeds, the
//      permission request is answered with the offered option whose kind is
//      `allow_once` (positively identified call only), and the agent replies with
//      the stub's exact text.
//   2. NEGATIVE (header omitted): the stub answers HTTP 401 and records the refusal;
//      the CLI's call fails — no tool execution on the stub, no stub text in the
//      answer. This is what makes the header path load-bearing.
//   3. NEGATIVE (wrong header): same, and the recorded refusal is a value mismatch
//      (the stub compares the value, not merely the presence of the header).
//   4. NEGATIVE (call itself 401s): metadata stays public but `tools/call` is
//      refused — the CLI's own call is attempted and fails at the call, not at
//      session establishment.
//
// Credentials: the Qoder PAT must be present in the sandbox environment as
// QODER_PERSONAL_ACCESS_TOKEN (the Java test injects it through
// createSandbox(agentId, image, env) — environment only, never argv). This script
// never prints it or any other token material; `test-worker-token` is a synthetic
// placeholder that may appear freely.
//
// The ACP session is pinned to the zero-credit model: qodercli is spawned with
// `-m $QODER_E2E_MODEL` (default `efficient`) and the session is additionally
// pinned with `session/set_model {sessionId, modelId}`; both results are recorded.
//
// Exit status: 0 only when every case passes; otherwise the number of failed cases.
// The last markers are
//   A3-SUMMARY-JSON: {"pingCalls":...,"permissionGrants":...,"authFailures":...}
//   A3-MCP-AUTH-RESULT: PASS   (or ...: FAIL (n case(s)))
// =============================================================================
import http from 'node:http';
import { spawn } from 'node:child_process';

// ---- configuration ---------------------------------------------------------

const MODEL = (process.env.QODER_E2E_MODEL || 'efficient').trim();
const PLUGIN_DIR = (process.env.QODER_E2E_PLUGIN_DIR || '').trim();
const TOKEN_ENV = 'QODER_PERSONAL_ACCESS_TOKEN';
const CWD = '/workspace';

const SERVER_NAME = 'aria-stub';
const TOOL_NAME = 'aria_ping';
const FULL_TOOL_NAME = `mcp__${SERVER_NAME}__${TOOL_NAME}`;

// Synthetic MCP bearer token: a placeholder, never a real credential.
const SYNTHETIC_BEARER = 'test-worker-token';
const EXPECTED_AUTHORIZATION = `Bearer ${SYNTHETIC_BEARER}`;
const WRONG_AUTHORIZATION = 'Bearer wrong-synthetic-token';
const EXPECTED_TEXT = 'pong-from-aria-stub';

const PROMPT = `Call the tool named ${TOOL_NAME} from the ${SERVER_NAME} MCP server, `
  + 'then reply with its exact output text and nothing else. Do not use filesystem or shell tools.';

const CASE_TIMEOUT_MS = 150000;
const MAX_TRANSCRIPT_CHECKS = 2000;

// ---- credential-safe output -------------------------------------------------

// Never print credential material: every console.log line passes through a
// redactor that replaces the Qoder token value — if the environment provided one
// — with [redacted]. CLI stderr tails and agent answers are therefore safe even
// if a future CLI build echoes credentials into its own diagnostics.
const TOKEN_VALUE = process.env[TOKEN_ENV] || '';
const consoleLog = console.log.bind(console);
console.log = (...args) => consoleLog(...args.map(arg => (typeof arg === 'string' && TOKEN_VALUE.length >= 8
  ? arg.split(TOKEN_VALUE).join('[redacted]')
  : arg)));

// ---- tiny helpers ----------------------------------------------------------

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

function excerpt(value, length = 200) {
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  return text === undefined ? null : text.slice(0, length);
}

// Record any usage/credit/cost fragments a message carries (raw, never asserted).
function collectUsage(rawLine, record) {
  if (record.usageFragments.length >= 20) {
    return;
  }
  const matches = rawLine.match(/"(usage|total_credits|credits|total_cost_usd|cost|input_tokens|output_tokens)"\s*:\s*[^,}\]]+/g);
  if (!matches) {
    return;
  }
  for (const match of matches.slice(0, 5)) {
    if (!record.usageFragments.includes(match)) {
      record.usageFragments.push(match);
    }
  }
}

// ---- in-sandbox MCP stub ---------------------------------------------------

// Loopback HTTP MCP stub offering exactly one tool (aria_ping). `authScope` selects
// where the `Authorization: Bearer test-worker-token` header is enforced:
//   'all'        every JSON-RPC POST must carry it (a normally authenticated server)
//   'call-only'  metadata stays public, `tools/call` must carry it — this isolates the
//                failure AT the call, so the CLI's own call is the thing that fails
// A missing or wrong header gets HTTP 401 and is recorded (method name +
// missing/mismatch only — header values are never recorded or printed).
function startStub(authScope = 'all') {
  const stats = { posts: 0, authorized: 0, publicRequests: 0, unauthorized: 0, unauthorizedDetail: [], toolCalls: 0 };
  const server = http.createServer((req, res) => {
    if (req.method !== 'POST') {
      res.writeHead(405, { Allow: 'POST' }).end();
      return;
    }
    let body = '';
    req.on('data', chunk => {
      body += chunk;
    });
    req.on('end', () => {
      stats.posts += 1;
      const message = parseJson(body);
      const method = message && typeof message.method === 'string' ? message.method : '<unparsed>';
      const requiresAuth = authScope === 'all' || method === 'tools/call';
      const authorization = req.headers['authorization'];
      if (requiresAuth && authorization !== EXPECTED_AUTHORIZATION) {
        stats.unauthorized += 1;
        if (stats.unauthorizedDetail.length < 10) {
          stats.unauthorizedDetail.push(`${method}:${authorization === undefined ? 'missing' : 'mismatch'}`);
        }
        res.writeHead(401, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          jsonrpc: '2.0',
          id: message && message.id !== undefined ? message.id : null,
          error: { code: -32001, message: 'Unauthorized: missing or invalid Authorization header' },
        }));
        return;
      }
      if (requiresAuth) {
        stats.authorized += 1;
      } else {
        stats.publicRequests += 1;
      }
      if (message === undefined) {
        res.writeHead(400, { 'Content-Type': 'application/json' }).end();
        return;
      }
      if (message.id === undefined) {
        // JSON-RPC notification (e.g. notifications/initialized)
        res.writeHead(202).end();
        return;
      }
      let result = null;
      if (message.method === 'initialize') {
        result = {
          protocolVersion: '2024-11-05',
          capabilities: { tools: {} },
          serverInfo: { name: 'aria-stub-a3', version: '1' },
        };
      } else if (message.method === 'tools/list') {
        result = {
          tools: [{
            name: TOOL_NAME,
            description: 'Return a fixed diagnostic string',
            inputSchema: { type: 'object', properties: {} },
          }],
        };
      } else if (message.method === 'tools/call' && message.params && message.params.name === TOOL_NAME) {
        stats.toolCalls += 1;
        result = { content: [{ type: 'text', text: EXPECTED_TEXT }], isError: false };
      }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        jsonrpc: '2.0',
        id: message.id,
        ...(result ? { result } : { error: { code: -32601, message: `Unsupported method: ${method}` } }),
      }));
    });
  });
  return new Promise((resolve, reject) => {
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      resolve({
        stats,
        url: `http://127.0.0.1:${server.address().port}/mcp`,
        close: () => {
          try {
            server.closeAllConnections();
            server.close();
          } catch {
            /* ignore */
          }
        },
      });
    });
  });
}

// ---- one ACP case ----------------------------------------------------------

// Run one full ACP case (initialize -> session/new with `headers` -> set_model ->
// prompt) against a fresh stub. Resolves with a per-case record; never rejects.
async function runCase(caseName, headers, options = {}) {
  const stub = await startStub(options.authScope || 'all');
  const record = {
    case: caseName,
    sessionCreated: false,
    modelSet: null,
    stopReason: null,
    answer: '',
    permissionRequests: 0,
    permissionGrants: 0,
    permissionCancels: 0,
    identifiedToolName: null,
    attemptedCall: false,
    cliToolFailureObserved: false,
    notificationTypes: [],
    toolEvents: [],
    permissionSamples: [],
    pluginTranscriptSamples: [],
    commandUpdateSample: null,
    authMethods: [],
    availableModels: [],
    currentModeId: null,
    pluginMentions: 0,
    usageFragments: [],
    stub: stub.stats,
    note: null,
  };
  const pingCallIds = new Set();

  // Credential handling: inherit the environment (the PAT arrives only this way),
  // drop the SDK entrypoint variables that make `--acp` refuse to start.
  const env = { ...process.env };
  for (const key of ['QODER_AGENT_SDK_ENTRYPOINT', 'QODER_WORKER_RUNTIME_ASSET_ROOT', 'QODER_AGENT_SDK_VERSION',
    'QODERCLI_RUNTIME_PACKAGING', 'QODER_SESSION_TYPE', 'QODER_WORKER_CWD', 'QODER_SDK_AUTH_PAYLOAD_FILE']) {
    delete env[key];
  }

  const args = ['-m', MODEL];
  if (PLUGIN_DIR) {
    args.push('--plugin-dir', PLUGIN_DIR);
  }
  args.push('--acp');

  const child = spawn('qodercli', args, { cwd: CWD, env, stdio: ['pipe', 'pipe', 'pipe'] });

  let sessionId = null;
  let stderrTail = '';
  let transcriptChecks = 0;
  let finished = false;

  const finish = note => {
    if (finished) {
      return;
    }
    finished = true;
    if (note) {
      record.note = note;
    }
    record.stderrTail = stderrTail.trim().slice(-400);
    clearTimeout(caseTimer);
    try {
      child.kill();
    } catch {
      /* ignore */
    }
    stub.close();
    resolveRecord(record);
  };
  let resolveRecord = null;
  const done = new Promise(resolve => {
    resolveRecord = resolve;
  });
  const caseTimer = setTimeout(() => finish(`timeout after ${CASE_TIMEOUT_MS} ms`), CASE_TIMEOUT_MS);

  const send = message => {
    try {
      child.stdin.write(JSON.stringify({ jsonrpc: '2.0', ...message }) + '\n');
    } catch {
      /* pipe closed */
    }
  };

  child.on('error', error => finish(`spawn error: ${error.message}`));
  child.on('exit', code => {
    if (!finished) {
      finish(`qodercli exited early (code ${code})`);
    }
  });
  child.stderr.setEncoding('utf8');
  child.stderr.on('data', text => {
    stderrTail = (stderrTail + text).slice(-800);
  });

  function handle(message, rawLine) {
    if (transcriptChecks < MAX_TRANSCRIPT_CHECKS) {
      transcriptChecks += 1;
      collectUsage(rawLine, record);
      if (rawLine.includes('aria-pinned')) {
        record.pluginMentions += 1;
        if (record.pluginTranscriptSamples.length < 3) {
          const at = rawLine.indexOf('aria-pinned');
          record.pluginTranscriptSamples.push(rawLine.slice(Math.max(0, at - 250), Math.min(rawLine.length, at + 250)));
        }
      }
    }

    // Responses to our own requests.
    if (message.id === 1) {
      if (message.error) {
        finish(`initialize failed: ${excerpt(message.error, 200)}`);
        return;
      }
      if (Array.isArray(message.result?.authMethods)) {
        record.authMethods = message.result.authMethods.map(method => method && method.id).filter(Boolean).slice(0, 10);
      }
      send({
        id: 2,
        method: 'session/new',
        params: {
          cwd: CWD,
          mcpServers: [{ type: 'http', name: SERVER_NAME, url: stub.url, headers }],
        },
      });
      return;
    }
    if (message.id === 2) {
      if (message.error) {
        finish(`session/new failed: ${excerpt(message.error, 300)}`);
        return;
      }
      record.sessionCreated = true;
      sessionId = message.result?.sessionId ?? null;
      const models = message.result?.models?.availableModels;
      if (Array.isArray(models)) {
        record.availableModels = models.map(model => model && model.modelId).filter(Boolean).slice(0, 30);
      }
      record.currentModeId = message.result?.modes?.currentModeId ?? null;
      send({ id: 3, method: 'session/set_model', params: { sessionId, modelId: MODEL } });
      return;
    }
    if (message.id === 3) {
      record.modelSet = message.error
        ? `error ${message.error.code}: ${excerpt(message.error.message, 120)}`
        : 'accepted';
      send({ id: 4, method: 'session/prompt', params: { sessionId, prompt: [{ type: 'text', text: PROMPT }] } });
      return;
    }
    if (message.id === 4) {
      record.stopReason = message.result?.stopReason ?? null;
      finish(message.error ? `prompt failed: ${excerpt(message.error, 200)}` : 'prompt response received');
      return;
    }

    // Permission requests (agent -> client). Never allow_always: only a positively
    // identified aria_ping mcp_call gets the option whose kind is `allow_once`;
    // everything else is cancelled.
    if (message.method === 'session/request_permission') {
      record.permissionRequests += 1;
      const toolCall = message.params?.toolCall;
      if (record.permissionSamples.length < 4) {
        record.permissionSamples.push(excerpt(JSON.stringify({
          title: message.params?.title ?? null,
          toolCall,
          optionKinds: (message.params?.options || []).map(option => option.kind),
        }), 1500));
      }
      // Positive identification of OUR diagnostic call. Two observed shapes exist:
      // this Linux build announces the MCP tool directly
      // (`_meta.qoder.toolName = mcp__aria-stub__aria_ping`), while the Windows
      // spike of 2026-09-17 saw the wrapper (`mcp_call` + rawInput.toolName).
      // Everything else — including the `authenticate` tool the CLI synthesizes
      // when the stub answers 401 — is cancelled.
      const qoderToolName = toolCall?._meta?.qoder?.toolName ?? null;
      const ownPingCall = qoderToolName === FULL_TOOL_NAME
        || (qoderToolName === 'mcp_call' && toolCall?.rawInput?.toolName === FULL_TOOL_NAME);
      const allowOnce = ownPingCall
        ? (message.params?.options || []).find(option => option.kind === 'allow_once')
        : undefined;
      if (ownPingCall) {
        record.identifiedToolName = qoderToolName;
      }
      if (allowOnce) {
        record.permissionGrants += 1;
      } else {
        record.permissionCancels += 1;
      }
      send({
        id: message.id,
        result: { outcome: allowOnce ? { outcome: 'selected', optionId: allowOnce.optionId } : { outcome: 'cancelled' } },
      });
      return;
    }

    // Any other client-facing request: refuse (never auto-approve unknown methods).
    if (message.method && message.id !== undefined) {
      send({ id: message.id, error: { code: -32601, message: 'Unsupported client method' } });
      return;
    }

    // Notifications.
    if (message.method === 'session/update') {
      const update = message.params?.update || {};
      const type = update.sessionUpdate;
      if (type && record.notificationTypes.length < 30 && !record.notificationTypes.includes(type)) {
        record.notificationTypes.push(type);
      }
      if (type === 'agent_message_chunk') {
        record.answer += update.content?.text || '';
        return;
      }
      if (type === 'available_commands_update' && record.commandUpdateSample === null) {
        // Shape sample for the session-time command/skill announcement (bounded).
        record.commandUpdateSample = excerpt(JSON.stringify(update), 500);
      }
      if (type === 'tool_call' || type === 'tool_call_update') {
        const qoderTool = update._meta?.qoder?.toolName || null;
        const targetTool = update.rawInput?.toolName || null;
        // Same identification rule as the permission handler, plus the tool title
        // (`aria_ping (aria-stub MCP Server)`) as a display-level fallback.
        const isPingTarget = qoderTool === FULL_TOOL_NAME
          || (qoderTool === 'mcp_call' && targetTool === FULL_TOOL_NAME)
          || (typeof update.title === 'string' && new RegExp(`^${TOOL_NAME}\\b`).test(update.title));
        if (type === 'tool_call' && isPingTarget) {
          pingCallIds.add(update.toolCallId);
        }
        const tracked = isPingTarget || pingCallIds.has(update.toolCallId);
        if (isPingTarget) {
          record.attemptedCall = true;
        }
        const status = update.status || null;
        if (tracked && (status === 'failed' || status === 'error')) {
          record.cliToolFailureObserved = true;
        }
        if (record.toolEvents.length < 40) {
          record.toolEvents.push({
            type,
            tracked,
            toolCallId: typeof update.toolCallId === 'string' ? update.toolCallId.slice(0, 12) : null,
            qoderTool,
            targetTool,
            title: typeof update.title === 'string' ? update.title.slice(0, 120) : null,
            kind: update.kind || null,
            status,
            rawInputExcerpt: update.rawInput === undefined ? null : excerpt(update.rawInput, 200),
            outputExcerpt: update.rawOutput === undefined ? null : excerpt(update.rawOutput, 200),
          });
        }
      }
    }
  }

  let buffer = '';
  child.stdout.setEncoding('utf8');
  child.stdout.on('data', text => {
    buffer += text;
    let index;
    while ((index = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, index);
      buffer = buffer.slice(index + 1);
      const message = parseJson(line);
      if (message !== undefined) {
        handle(message, line);
      }
    }
  });

  send({ id: 1, method: 'initialize', params: { protocolVersion: 1 } });

  return done;
}

// ---- main ------------------------------------------------------------------

const failures = [];

function report(caseName, ok, details) {
  console.log(`[A3] ${ok ? 'PASS' : 'FAIL'}: ${caseName} ${details}`);
  if (!ok) {
    failures.push(caseName);
  }
}

async function main() {
  console.log(`[A3] model pin: QODER_E2E_MODEL=${MODEL} (spawn -m ${MODEL} + session/set_model)`);
  console.log(`[A3] plugin dir: ${PLUGIN_DIR || '<not set>'}`);
  console.log(`[A3] MCP stub bearer: synthetic placeholder (expected header present in the positive case)`);

  if (!process.env[TOKEN_ENV] || process.env[TOKEN_ENV].trim() === '') {
    console.log(`[A3] FAIL: ${TOKEN_ENV} is not present in the environment — the authenticated ACP `
      + 'session requires it (injected into the sandbox environment, never argv)');
    console.log('A3-MCP-AUTH-RESULT: FAIL (missing credential)');
    process.exit(2);
  }

  // Case 1: positive — the header entry is present in session/new.mcpServers.
  const positive = await runCase('positive', [{ name: 'Authorization', value: EXPECTED_AUTHORIZATION }]);
  const positiveOk = positive.sessionCreated
    && positive.stopReason === 'end_turn'
    && positive.answer.trim() === EXPECTED_TEXT
    && positive.stub.toolCalls === 1
    && positive.permissionGrants === 1
    && positive.stub.unauthorized === 0;
  report('positive (header present)',
    positiveOk,
    `sessionCreated=${positive.sessionCreated} stopReason=${positive.stopReason} `
    + `answer=${JSON.stringify(excerpt(positive.answer, 120))} pingCalls=${positive.stub.toolCalls} `
    + `permissionGrants=${positive.permissionGrants} permissionRequests=${positive.permissionRequests} `
    + `authFailures=${positive.stub.unauthorized} modelSet=${positive.modelSet}`
    + (positive.note ? ` note=${positive.note}` : ''));

  // Case 2: negative — header omitted entirely (empty header list).
  const missing = await runCase('missing', []);
  const missingSignal = {
    unauthorizedObserved: missing.stub.unauthorized >= 1,
    noSuccessfulCall: missing.stub.toolCalls === 0,
    noStubTextInAnswer: !missing.answer.includes(EXPECTED_TEXT),
    attemptedCallFailure: !missing.attemptedCall || missing.cliToolFailureObserved,
  };
  report('negative (header omitted)',
    missingSignal.unauthorizedObserved && missingSignal.noSuccessfulCall
      && missingSignal.noStubTextInAnswer && missingSignal.attemptedCallFailure,
    `stub 401s=${missing.stub.unauthorized} (${missing.stub.unauthorizedDetail.join(',') || '<none>'}) `
    + `pingCalls=${missing.stub.toolCalls} attemptedCall=${missing.attemptedCall} `
    + `cliToolFailureObserved=${missing.cliToolFailureObserved} `
    + `answer=${JSON.stringify(excerpt(missing.answer, 120))} stopReason=${missing.stopReason}`
    + (missing.note ? ` note=${missing.note}` : ''));

  // Case 3: negative — wrong header value (stub must compare the value).
  const wrong = await runCase('wrong', [{ name: 'Authorization', value: WRONG_AUTHORIZATION }]);
  const wrongSignal = {
    unauthorizedObserved: wrong.stub.unauthorized >= 1,
    mismatchRecorded: wrong.stub.unauthorizedDetail.some(detail => detail.endsWith(':mismatch')),
    noSuccessfulCall: wrong.stub.toolCalls === 0,
    noStubTextInAnswer: !wrong.answer.includes(EXPECTED_TEXT),
    attemptedCallFailure: !wrong.attemptedCall || wrong.cliToolFailureObserved,
  };
  report('negative (wrong header)',
    wrongSignal.unauthorizedObserved && wrongSignal.mismatchRecorded
      && wrongSignal.noSuccessfulCall && wrongSignal.noStubTextInAnswer && wrongSignal.attemptedCallFailure,
    `stub 401s=${wrong.stub.unauthorized} (${wrong.stub.unauthorizedDetail.join(',') || '<none>'}) `
    + `pingCalls=${wrong.stub.toolCalls} attemptedCall=${wrong.attemptedCall} `
    + `cliToolFailureObserved=${wrong.cliToolFailureObserved} `
    + `answer=${JSON.stringify(excerpt(wrong.answer, 120))} stopReason=${wrong.stopReason}`
    + (wrong.note ? ` note=${wrong.note}` : ''));

  // Case 4: negative — metadata without a header, the call itself 401s. This isolates
  // the failure AT the CLI's own tool call (the three cases above fail at session
  // establishment, the behaviour of a fully authenticated server).
  const callOnly = await runCase('call-401', [], { authScope: 'call-only' });
  const callOnlySignal = {
    unauthorizedAtCall: callOnly.stub.unauthorizedDetail.some(detail => detail.startsWith('tools/call:')),
    noSuccessfulCall: callOnly.stub.toolCalls === 0,
    noStubTextInAnswer: !callOnly.answer.includes(EXPECTED_TEXT),
    callAttempted: callOnly.attemptedCall,
    callFailed: callOnly.cliToolFailureObserved,
  };
  report('negative (call itself 401s)',
    callOnlySignal.unauthorizedAtCall && callOnlySignal.noSuccessfulCall
      && callOnlySignal.noStubTextInAnswer && callOnlySignal.callAttempted && callOnlySignal.callFailed,
    `stub 401s=${callOnly.stub.unauthorized} (${callOnly.stub.unauthorizedDetail.join(',') || '<none>'}) `
    + `publicRequests=${callOnly.stub.publicRequests} pingCalls=${callOnly.stub.toolCalls} `
    + `attemptedCall=${callOnly.attemptedCall} cliToolFailureObserved=${callOnly.cliToolFailureObserved} `
    + `permissionGrants=${callOnly.permissionGrants} `
    + `answer=${JSON.stringify(excerpt(callOnly.answer, 160))} stopReason=${callOnly.stopReason}`
    + (callOnly.note ? ` note=${callOnly.note}` : ''));

  // Case records for the evidence document (bounded, no token material).
  for (const record of [positive, missing, wrong, callOnly]) {
    const summary = {
      case: record.case,
      sessionCreated: record.sessionCreated,
      authMethods: record.authMethods,
      modelSet: record.modelSet,
      currentModeId: record.currentModeId,
      stopReason: record.stopReason,
      answerExcerpt: excerpt(record.answer, 200),
      permissionRequests: record.permissionRequests,
      permissionGrants: record.permissionGrants,
      permissionCancels: record.permissionCancels,
      identifiedToolName: record.identifiedToolName,
      stub: {
        posts: record.stub.posts,
        authorized: record.stub.authorized,
        publicRequests: record.stub.publicRequests,
        unauthorized: record.stub.unauthorized,
        unauthorizedDetail: record.stub.unauthorizedDetail,
        toolCalls: record.stub.toolCalls,
      },
      attemptedCall: record.attemptedCall,
      cliToolFailureObserved: record.cliToolFailureObserved,
      toolEvents: record.toolEvents.slice(0, 12),
      permissionSamples: record.permissionSamples.slice(0, 2),
      notificationTypes: record.notificationTypes,
      pluginMentions: record.pluginMentions,
      pluginTranscriptSamples: record.pluginTranscriptSamples,
      commandUpdateSample: record.commandUpdateSample,
      usageFragments: record.usageFragments.slice(0, 12),
      stderrTail: record.stderrTail || null,
      note: record.note,
    };
    console.log(`A3-CASE-JSON: ${JSON.stringify(summary)}`);
  }

  const totals = {
    pingCalls: positive.stub.toolCalls + missing.stub.toolCalls + wrong.stub.toolCalls + callOnly.stub.toolCalls,
    permissionGrants: positive.permissionGrants + missing.permissionGrants + wrong.permissionGrants + callOnly.permissionGrants,
    authFailures: positive.stub.unauthorized + missing.stub.unauthorized + wrong.stub.unauthorized + callOnly.stub.unauthorized,
    cases: [
      { case: 'positive', pingCalls: positive.stub.toolCalls, permissionGrants: positive.permissionGrants, authFailures: positive.stub.unauthorized },
      { case: 'missing', pingCalls: missing.stub.toolCalls, permissionGrants: missing.permissionGrants, authFailures: missing.stub.unauthorized },
      { case: 'wrong', pingCalls: wrong.stub.toolCalls, permissionGrants: wrong.permissionGrants, authFailures: wrong.stub.unauthorized },
      { case: 'call-401', pingCalls: callOnly.stub.toolCalls, permissionGrants: callOnly.permissionGrants, authFailures: callOnly.stub.unauthorized },
    ],
    usageFragments: [...positive.usageFragments, ...missing.usageFragments, ...wrong.usageFragments, ...callOnly.usageFragments].slice(0, 20),
  };
  console.log(`A3-SUMMARY-JSON: ${JSON.stringify(totals)}`);

  if (failures.length === 0) {
    console.log('A3-MCP-AUTH-RESULT: PASS');
    process.exit(0);
  }
  console.log(`A3-MCP-AUTH-RESULT: FAIL (${failures.length} case(s): ${failures.join(', ')})`);
  process.exit(failures.length);
}

main().catch(error => {
  console.log(`[A3] FAIL: unexpected error: ${error && error.stack ? error.stack : error}`);
  console.log('A3-MCP-AUTH-RESULT: FAIL (unexpected error)');
  process.exit(9);
});
