#!/usr/bin/env node
// Deterministic mock Qoder CLI (Task 7 fixture peer).
//
// It is a real Node process that speaks the newline-delimited ACP framing recorded
// in e2e/agent-core/fixtures/qoder-host-cli-1.1.61-protocol.jsonl: initialize ->
// session/new -> session/set_model -> session/prompt, session/update streaming,
// session/request_permission with the recorded option kinds, session/cancel and a
// prompt result carrying stopReason/usage/_meta.quota.
//
// It never opens a database connection, never writes a run outcome and never
// contacts an external model: the only writes are fixture writes inside the
// admitted temporary workspace, performed through applyDecision() after a genuine
// allow-once decision. There is deliberately no ACP pause/resume method: Task 1
// verified the control technique as process-level suspension of the owned tree
// (nativePauseRpcUsed false), so the harness suspends this process instead.
import { randomBytes, randomUUID } from 'node:crypto';
import { basename, join } from 'node:path';
import {
  EXIT_CODES,
  applyDecision,
  assertInsideWorkspace,
  bootPeer,
  createLineReader,
  encodeFrame,
  gitPushTargetFromPrompt,
  grantWriterFixture,
  loadScenarioManifest,
  parsedChunk,
  pushGitBranch,
  record,
  recordedOptions,
  startBackgroundWriter,
} from './peer-actions.mjs';

const PEER_ID = 'qoder';
const boot = bootPeer({ peerId: PEER_ID, argv: process.argv.slice(2) });
const SCENARIO = boot.scenario;
const WORKSPACE = boot.workspace;
const fixtures = loadScenarioManifest().fixtures;
const AVAILABLE_MODELS = fixtures.models.available;
const DEFAULT_MODEL = fixtures.models.default;
const THOUGHT_CHUNKS = ['The', ' user wants exactly', ' the', ' word', ' "pong".\n'];
const WRITER_LOG = fixtures.writer.logName;
const WRITER_COMMAND = fixtures.writer.command;
// Fixture-defined completion values of the task 11 contract scenarios. The
// envelopes stay the recorded ones; only the values are fixture-defined.
const COMPLETION_TEXT = {
  'reported-usage': 'fixture-complete',
  'unknown-usage': 'fixture-usage-unknown',
};
const REPORTED_USAGE_RESULT = {
  usage: { inputTokens: 12, outputTokens: 7, totalTokens: 19 },
  quota: {
    token_count: { input_tokens: 12, output_tokens: 7 },
    model_usage: [{ model: 'efficient', token_count: { input_tokens: 12, output_tokens: 7 } }],
  },
};

const AGENT_INFO = { name: 'qoder-cli', title: 'Qoder CLI', version: '1.1.61' };
const AUTH_METHODS = [
  {
    id: 'qodercli-login',
    name: 'Use qodercli login',
    description:
      'Use your existing qodercli login for this agent. If needed, sign in from qodercli first.',
  },
];
const AGENT_CAPABILITIES = {
  _meta: { qoder: { promptQueueing: true } },
  loadSession: true,
  sessionCapabilities: {
    additionalDirectories: {},
    close: {},
    delete: {},
    fork: {},
    list: {},
    resume: {},
  },
  promptCapabilities: { image: true, embeddedContext: true },
  mcpCapabilities: { http: true, sse: true },
};
const MODES = [
  { id: 'default', name: 'Default', description: 'Prompts for approval' },
  { id: 'acceptEdits', name: 'Accept Edits', description: 'Auto-approves edit tools' },
  { id: 'auto', name: 'Auto', description: 'Auto-approves via the safety classifier' },
  { id: 'dontAsk', name: "Don't Ask", description: 'Refuses instead of prompting' },
  { id: 'yolo', name: 'Bypass Permissions', description: 'Auto-approves all tools' },
];
// The vendor command list is not reproduced; the envelope shape is the recorded one.
const FIXTURE_COMMANDS = [
  {
    name: 'fixture-status',
    description: 'Deterministic fixture command (vendor command list intentionally not reproduced)',
    input: { hint: '[status]' },
  },
];

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const flushStdout = () => new Promise((resolve) => process.stdout.write('', () => resolve()));

let sessionId = null;
let currentModel = DEFAULT_MODEL;
let promptId = null;
let turnPromptText = null;
let outboundId = 0;
let writerPid = null;
let streaming = false;
const pendingRequests = new Map();
const expiredRequests = new Map();

function send(frame) {
  process.stdout.write(encodeFrame(frame));
}

function emitUpdate(update) {
  send({ jsonrpc: '2.0', method: 'session/update', params: { sessionId, update } });
}

async function shutdown(exitCode) {
  await flushStdout();
  process.exit(exitCode);
}

function errorFrame(id, code, message) {
  return { jsonrpc: '2.0', id, error: { code, message } };
}

function newToolCallId() {
  return `call_${randomBytes(12).toString('hex')}`;
}

// ------------------------------------------------------------------ turn helpers

/**
 * The recorded `session/update` tool_call payload (protocol.jsonl lines 40/83):
 * edit requests carry title `Write <abs>` plus `locations`, execute requests
 * carry the bare command and no `locations`; neither update diff carries `_meta`.
 */
function toolCallUpdateFor(spec, toolCallId) {
  if (spec.kind === 'edit') {
    return {
      toolCallId,
      status: 'pending',
      title: `Write ${spec.target}`,
      content: [{ type: 'diff', path: spec.target, oldText: null, newText: spec.contents }],
      kind: 'edit',
      rawInput: { content: spec.contents, file_path: spec.target },
      locations: [{ path: spec.target }],
      _meta: { qoder: { toolName: 'Write' } },
    };
  }
  return {
    toolCallId,
    status: 'pending',
    title: spec.command,
    content: [{ type: 'content', content: { type: 'text', text: spec.command } }],
    kind: 'execute',
    rawInput: { command: spec.command, description: `Create ${spec.target} with exact content` },
    _meta: { qoder: { toolName: spec.toolName ?? 'Bash' } },
  };
}

/**
 * The recorded permission-request copy of the tool call (protocol.jsonl lines
 * 41/66/136/165). For edit requests it differs from the update: title
 * `Edit <basename>`, diff `_meta.kind=add` present, `locations` absent. For
 * execute requests the recording repeats the update payload verbatim.
 */
function permissionToolCallFor(spec, toolCallId) {
  if (spec.kind !== 'edit') return toolCallUpdateFor(spec, toolCallId);
  return {
    toolCallId,
    status: 'pending',
    title: `Edit ${basename(spec.target)}`,
    content: [
      { type: 'diff', path: spec.target, oldText: null, newText: spec.contents, _meta: { kind: 'add' } },
    ],
    kind: 'edit',
    rawInput: { content: spec.contents, file_path: spec.target },
    _meta: { qoder: { toolName: 'Write' } },
  };
}

function completeToolCall(spec, toolCallId, shape) {
  const output = shape?.pushed
    ? `Fixture push completed: ${shape.pushed}`
    : `Fixture write completed at: ${spec.target}`;
  const update = {
    sessionUpdate: 'tool_call_update',
    toolCallId,
    status: 'completed',
    content: [{ type: 'content', content: { type: 'text', text: output } }],
    rawOutput: output,
  };
  emitUpdate(update);
  return shape;
}

function failToolCall(toolCallId, message) {
  emitUpdate({
    sessionUpdate: 'tool_call_update',
    toolCallId,
    status: 'failed',
    content: [{ type: 'content', content: { type: 'text', text: message } }],
    rawOutput: message,
  });
}

function requestPermission(spec) {
  const requestId = outboundId;
  outboundId += 1;
  const toolCallId = newToolCallId();
  const options = recordedOptions(spec.kind, { command: spec.command });
  const promise = new Promise((resolve) => {
    const entry = { requestId, toolCallId, spec, options, resolve, expired: false };
    pendingRequests.set(requestId, entry);
    if (spec.expiryMs) {
      setTimeout(() => {
        if (!pendingRequests.has(requestId)) return;
        pendingRequests.delete(requestId);
        expiredRequests.set(requestId, options);
        record('peer.permission_expired', { requestId, windowMs: spec.expiryMs });
        failToolCall(toolCallId, 'Fixture permission window expired without a decision');
        resolve({ status: 'expired' });
      }, spec.expiryMs);
    }
  });
  emitUpdate({ sessionUpdate: 'tool_call', ...toolCallUpdateFor(spec, toolCallId) });
  send({
    jsonrpc: '2.0',
    id: requestId,
    method: 'session/request_permission',
    params: { sessionId, options, toolCall: permissionToolCallFor(spec, toolCallId) },
  });
  return promise;
}

async function resolveDecision(frame) {
  const entry = pendingRequests.get(frame.id);
  const outcome = frame.result?.outcome;
  const optionId = outcome?.outcome === 'selected' ? outcome.optionId : 'cancel';
  if (!entry) {
    if (expiredRequests.has(frame.id)) {
      record('peer.late_decision', { requestId: frame.id, optionId, writes: 0 });
      return;
    }
    record('peer.unknown_decision', { requestId: frame.id, optionId });
    return;
  }
  pendingRequests.delete(frame.id);
  const kind = entry.options.find((option) => option.optionId === optionId)?.kind ?? null;
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
    failToolCall(entry.toolCallId, error.message);
    entry.resolve({ status: 'refused' });
    return;
  }
  if (decision.status === 'written') {
    completeToolCall(entry.spec, entry.toolCallId, decision);
    entry.resolve({ status: 'written' });
  } else {
    failToolCall(entry.toolCallId, 'Fixture decision denied the write');
    entry.resolve({ status: 'denied' });
  }
}

function cancelPendingRequests() {
  const ids = [...pendingRequests.keys()];
  for (const id of ids) {
    const entry = pendingRequests.get(id);
    pendingRequests.delete(id);
    failToolCall(entry.toolCallId, 'Fixture run cancelled');
    entry.resolve({ status: 'cancelled' });
  }
  return ids;
}

/**
 * Close the live prompt with exactly one result frame. Clearing `promptId`
 * first makes finalization idempotent, so a later cancel can never answer the
 * same JSON-RPC id a second time (the recording contains no duplicate response).
 *
 * The `reported-usage` scenario reports the fixture's exact counters and quota;
 * the `unknown-usage` scenario reports neither member, so the adapter must keep
 * the unknown values unknown instead of inventing zeros.
 */
function finishPrompt(stopReason) {
  if (promptId === null) return;
  const id = promptId;
  promptId = null;
  const extras =
    SCENARIO === 'reported-usage'
      ? { usage: REPORTED_USAGE_RESULT.usage, _meta: { quota: REPORTED_USAGE_RESULT.quota } }
      : SCENARIO === 'unknown-usage'
        ? {}
        : {
            usage: { inputTokens: 0, outputTokens: 0, totalTokens: 0 },
            _meta: {
              quota: {
                token_count: { input_tokens: 0, output_tokens: 0 },
                model_usage: [{ model: currentModel, token_count: { input_tokens: 0, output_tokens: 0 } }],
              },
            },
          };
  send({
    jsonrpc: '2.0',
    id,
    result: {
      stopReason,
      userMessageId: randomUUID(),
      ...extras,
    },
  });
}

function streamCompletions() {
  if (SCENARIO === 'two-turn-nonce') {
    // Echo: the assertion on what the core received is the reply itself.
    emitUpdate({ sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: turnPromptText ?? '' } });
    return;
  }
  for (const text of THOUGHT_CHUNKS) {
    emitUpdate({ sessionUpdate: 'agent_thought_chunk', content: { type: 'text', text } });
  }
  emitUpdate({
    sessionUpdate: 'agent_message_chunk',
    content: { type: 'text', text: COMPLETION_TEXT[SCENARIO] ?? 'pong' },
  });
}

// ------------------------------------------------------------------ turn machines

async function runTwoWrites() {
  const first = await requestPermission({
    kind: 'edit',
    target: assertInsideWorkspace(WORKSPACE, join(WORKSPACE, 'probe-allow-once.txt')),
    contents: 'alpha-allow-once',
  });
  if (first.status === 'refused') {
    finishPrompt('end_turn');
    return;
  }
  const secondTarget = assertInsideWorkspace(WORKSPACE, join(WORKSPACE, 'probe-write-twice-2.txt'));
  await requestPermission({
    kind: 'execute',
    target: secondTarget,
    contents: 'gamma-permission-twice',
    command: `printf 'gamma-permission-twice' > "${secondTarget}"`,
  });
  finishPrompt('end_turn');
}

async function runSingleWrite() {
  await requestPermission({
    kind: 'edit',
    target: assertInsideWorkspace(WORKSPACE, join(WORKSPACE, 'probe-deny.txt')),
    contents: 'beta-should-not-exist',
  });
  finishPrompt('end_turn');
}

async function runExpiry() {
  await requestPermission({
    kind: 'edit',
    target: assertInsideWorkspace(WORKSPACE, join(WORKSPACE, 'probe-expiry.txt')),
    contents: 'epsilon-expired',
    expiryMs: 300,
  });
  finishPrompt('end_turn');
}

async function runCancelPending() {
  await requestPermission({
    kind: 'edit',
    target: assertInsideWorkspace(WORKSPACE, join(WORKSPACE, 'probe-cancel-pending.txt')),
    contents: 'gamma-cancel-pending',
  });
  finishPrompt('end_turn');
}

/**
 * The governed git push: one execute-kind permission request for the recorded
 * `git_push` tool, with the branch and remote named by the run prompt. The push
 * itself runs only after a genuine allow-once decision (resolveDecision); a
 * denial leaves the remote untouched.
 */
async function runGitPush() {
  const target = gitPushTargetFromPrompt(turnPromptText);
  record('peer.git_push_requested', { branch: target.branch, remote: target.remote });
  await requestPermission({
    kind: 'execute',
    toolName: 'git_push',
    command: `git push ${target.remote} HEAD:refs/heads/${target.branch}`,
    gitPush: target,
    target: assertInsideWorkspace(WORKSPACE, join(WORKSPACE, 'git-push.probe')),
    contents: `${target.branch}\n`,
  });
  finishPrompt('end_turn');
}

async function startOwnedWriter() {
  const started = await startBackgroundWriter({ workspace: WORKSPACE, logName: WRITER_LOG });
  writerPid = started.writerPid;
  record('peer.child_started', {
    pid: started.writerPid,
    launcherPid: started.launcher.pid,
    command: WRITER_COMMAND,
    executable: process.execPath,
  });
  return started;
}

async function runWriterScenario() {
  const decision = await requestPermission({
    kind: 'execute',
    target: assertInsideWorkspace(WORKSPACE, join(WORKSPACE, WRITER_LOG)),
    contents: 'fixture-writer-start\n',
    command: WRITER_COMMAND,
    scaffold: true,
  });
  if (decision.status !== 'written') {
    finishPrompt('end_turn');
    return;
  }
  await startOwnedWriter();
  emitUpdate({ sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'DONE' } });
  if (SCENARIO !== 'pause-resume') {
    finishPrompt('end_turn');
  }
  if (SCENARIO === 'pause-resume' || SCENARIO === 'non-cooperative') {
    // Stay active: keep streaming so process suspension and escalation are observable.
    streaming = true;
    while (streaming) {
      await sleep(1000);
      if (!streaming) return;
      emitUpdate({
        sessionUpdate: 'agent_message_chunk',
        content: { type: 'text', text: 'fixture-streaming\n' },
      });
    }
  }
}

async function runDisconnect() {
  emitUpdate({ sessionUpdate: 'agent_thought_chunk', content: { type: 'text', text: 'connection' } });
  emitUpdate({ sessionUpdate: 'agent_thought_chunk', content: { type: 'text', text: ' lost' } });
  await flushStdout();
  process.exit(EXIT_CODES.disconnect);
}

// ------------------------------------------------------------------ protocol

function handleFrame(frame) {
  if (frame.method === 'initialize') {
    if (SCENARIO === 'handshake-failure') {
      send(
        errorFrame(
          frame.id,
          -32000,
          'Handshake rejected: fixture core does not accept protocolVersion 1',
        ),
      );
      record('peer.handshake_rejected', { protocolVersion: frame.params?.protocolVersion ?? null });
      void shutdown(EXIT_CODES.scripted);
      return;
    }
    send({
      jsonrpc: '2.0',
      id: frame.id,
      result: {
        protocolVersion: frame.params?.protocolVersion ?? 1,
        authMethods: AUTH_METHODS,
        agentInfo: AGENT_INFO,
        agentCapabilities: AGENT_CAPABILITIES,
      },
    });
    return;
  }
  if (frame.method === 'session/new') {
    if (SCENARIO === 'invalid-auth') {
      send(errorFrame(frame.id, -32000, 'Authentication required: Authentication is required.'));
      return;
    }
    if (SCENARIO === 'timeout') {
      record('peer.request_pending', { method: 'session/new', id: frame.id });
      return;
    }
    sessionId = randomUUID();
    currentModel = DEFAULT_MODEL;
    send({
      jsonrpc: '2.0',
      id: frame.id,
      result: {
        sessionId,
        modes: { availableModes: MODES, currentModeId: 'default' },
        models: {
          availableModels: AVAILABLE_MODELS.map((modelId) => ({
            modelId,
            name: modelId,
            description: 'fixture model',
          })),
          currentModelId: DEFAULT_MODEL,
        },
        configOptions: [
          {
            type: 'select',
            id: 'mode',
            name: 'Mode',
            category: 'mode',
            currentValue: 'default',
            options: MODES.map((mode) => ({ value: mode.id, name: mode.name, description: mode.description })),
          },
          {
            type: 'select',
            id: 'model',
            name: 'Model',
            category: 'model',
            currentValue: DEFAULT_MODEL,
            options: AVAILABLE_MODELS.map((modelId) => ({ value: modelId, name: modelId })),
          },
        ],
      },
    });
    if (SCENARIO === 'malformed-frame') {
      emitMalformedFrames();
    } else {
      emitUpdate({ sessionUpdate: 'available_commands_update', availableCommands: FIXTURE_COMMANDS });
    }
    return;
  }
  if (frame.method === 'session/set_model') {
    const requested = frame.params?.modelId;
    if (!AVAILABLE_MODELS.includes(requested)) {
      send(errorFrame(frame.id, -32000, `Unsupported model id: ${requested}`));
      if (SCENARIO === 'unsupported-model') {
        record('peer.model_refused', { modelId: requested, available: AVAILABLE_MODELS });
        void shutdown(EXIT_CODES.scripted);
      }
      return;
    }
    currentModel = requested;
    send({ jsonrpc: '2.0', id: frame.id, result: {} });
    return;
  }
  if (frame.method === 'session/prompt') {
    promptId = frame.id;
    if (sessionId === null) sessionId = frame.params?.sessionId ?? randomUUID();
    turnPromptText = (Array.isArray(frame.params?.prompt) ? frame.params.prompt : [])
      .filter((part) => part?.type === 'text')
      .map((part) => part.text ?? '')
      .join('\n');
    void runPrompt();
    return;
  }
  if (frame.method === 'session/cancel') {
    if (SCENARIO === 'non-cooperative') {
      record('peer.cancel_ignored', { sessionId: frame.params?.sessionId ?? null });
      return;
    }
    if (promptId === null) {
      // The recording answers a cancel without a live prompt with silence
      // (protocol.jsonl line 121: no frame follows the notification).
      record('peer.cancel', { sessionId: frame.params?.sessionId ?? null, pending: null, pendingRequestIds: [] });
      return;
    }
    const cancelled = cancelPendingRequests();
    if (cancelled.length > 0) {
      record('peer.cancel', {
        sessionId: frame.params?.sessionId ?? null,
        pending: 'permission',
        pendingRequestIds: cancelled,
      });
    } else {
      record('peer.cancel', { sessionId: frame.params?.sessionId ?? null, pending: null, pendingRequestIds: [] });
    }
    streaming = false;
    finishPrompt('cancelled');
    return;
  }
  if (frame.id !== undefined && (frame.result !== undefined || frame.error !== undefined)) {
    void resolveDecision(frame);
    return;
  }
  if (frame.id !== undefined) {
    send(errorFrame(frame.id, -32601, 'Method not found'));
    return;
  }
  record('peer.unknown_notification', { method: frame.method ?? null });
}

function emitMalformedFrames() {
  process.stdout.write('{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"\n');
  const payload = 'x'.repeat(1048576);
  send({
    jsonrpc: '2.0',
    method: 'session/update',
    params: { sessionId, update: { sessionUpdate: 'fixture_oversized_payload', payload } },
  });
  process.stdout.write(
    `{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"${sessionId}","update":{"sessionUpdate":"fixture_split_frame",`,
  );
  setTimeout(() => {
    process.stdout.write('"note":"partial write reassembled"}}}\n');
  }, 20);
}

async function runPrompt() {
  switch (SCENARIO) {
    case 'write-twice':
      await runTwoWrites();
      return;
    case 'deny-write':
      await runSingleWrite();
      return;
    case 'permission-expiry':
      await runExpiry();
      return;
    case 'cancel-pending':
      await runCancelPending();
      return;
    case 'git-push':
      await runGitPush();
      return;
    case 'background-writer':
    case 'pause-resume':
    case 'non-cooperative':
      await runWriterScenario();
      return;
    case 'disconnect':
      await runDisconnect();
      return;
    default:
      streamCompletions();
      finishPrompt('end_turn');
  }
}

const reader = createLineReader((line) => {
  if (line.trim().length === 0) return;
  const frame = parsedChunk(line);
  if (frame === null) {
    record('peer.malformed_input', { bytes: Buffer.byteLength(line, 'utf8') });
    send(errorFrame(null, -32700, 'Parse error'));
    return;
  }
  try {
    handleFrame(frame);
  } catch (error) {
    record('peer.handler_error', { message: error.message });
  }
});

process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => reader.push(chunk));
process.stdin.on('end', () => {
  reader.flush();
  if (SCENARIO === 'non-cooperative') {
    record('peer.stdin_close_ignored', { writerPid });
    return;
  }
  void shutdown(EXIT_CODES.ok);
});
process.on('unhandledRejection', (error) => {
  record('peer.unhandled_rejection', { message: String(error) });
  void shutdown(EXIT_CODES.scripted);
});
