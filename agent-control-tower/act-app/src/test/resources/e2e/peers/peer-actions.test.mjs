// Deterministic protocol-peer contract tests (Task 7).
//
// These tests drive the real peer processes (mock-qoder.mjs over newline-delimited
// JSON-RPC on stdio; mock-opencode.mjs over the verified native HTTP subset) and
// assert concrete frames, ids, bytes and write counts. They are simulated-peer
// results only: they are never native-core acceptance evidence.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  EXIT_CODES,
  applyDecision,
  assertInsideWorkspace,
  implementedScenarios,
  readFixtureFile,
  resumeProcess,
  snapshotDirectory,
  suspendProcess,
  terminateTree,
} from './peer-actions.mjs';

const PEER_DIR = dirname(fileURLToPath(import.meta.url));
const QODER = join(PEER_DIR, 'mock-qoder.mjs');
const OPENCODE = join(PEER_DIR, 'mock-opencode.mjs');
const CONTROL_TOKEN = 'fixture-control-token-0123456789abcdef';
const RUN_ID = '1f0d0a52-6d61-4a1e-9c76-0a4d6f6b7c81';
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const TOOL_CALL_ID = /^call_[0-9a-f]{24}$/;
// The recorded edit option list (qoder-host-cli-1.1.61-protocol.jsonl lines 41/66/136).
const EDIT_OPTIONS = [
  { optionId: 'proceed_always', name: 'Allow for this session', kind: 'allow_always' },
  { optionId: 'proceed_once', name: 'Allow', kind: 'allow_once' },
  { optionId: 'cancel', name: 'Reject', kind: 'reject_once' },
];
// The recorded execute option list (lines 84/165): proceed_always_and_save plus
// `Always allow "<program>"`, the program being the command's first token.
const executeOptions = (program) => [
  { optionId: 'proceed_always_and_save', name: `Always allow "${program}"`, kind: 'allow_always' },
  { optionId: 'proceed_once', name: 'Allow', kind: 'allow_once' },
  { optionId: 'cancel', name: 'Reject', kind: 'reject_once' },
];

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const sha256Hex = (text) => createHash('sha256').update(text, 'utf8').digest('hex');

async function freshWorkspace(label) {
  return mkdtemp(join(tmpdir(), `aria-peer-${label}-`));
}

/** Remove a disposable workspace; retried because a just-killed peer may hold it. */
async function removeWorkspace(dir) {
  await rm(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
}

/** Run a body against one disposable workspace and always remove it afterwards. */
async function withTempDir(label, body) {
  const dir = await freshWorkspace(label);
  try {
    return await body(dir);
  } finally {
    await removeWorkspace(dir);
  }
}

class PeerProcess {
  constructor(child, { label, workspace }) {
    this.child = child;
    this.label = label;
    this.workspace = workspace;
    this.stdoutBytes = 0;
    this.rawLines = [];
    this.frames = [];
    this.sent = [];
    this.stderrText = '';
    this.pending = '';
    this.exit = null;
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
      this.stdoutBytes += Buffer.byteLength(chunk, 'utf8');
      this.pending += chunk;
      for (;;) {
        const at = this.pending.indexOf('\n');
        if (at < 0) break;
        const line = this.pending.slice(0, at);
        this.pending = this.pending.slice(at + 1);
        this.rawLines.push(line);
        try {
          this.frames.push(JSON.parse(line));
        } catch {
          /* scripted malformed frame */
        }
      }
    });
    child.stderr.on('data', (chunk) => {
      this.stderrText += chunk;
    });
    child.on('exit', (code, signal) => {
      this.exit = { code, signal };
    });
  }

  get pid() {
    return this.child.pid;
  }

  send(frame) {
    this.sent.push(frame);
    this.child.stdin.write(`${JSON.stringify(frame)}\n`);
  }

  closeStdin() {
    this.child.stdin.end();
  }

  stderrRecords() {
    return this.stderrText
      .split('\n')
      .filter((line) => line.trim().length > 0)
      .map((line) => {
        try {
          return JSON.parse(line);
        } catch {
          return { type: 'peer.raw_diagnostic', text: line };
        }
      });
  }

  framesOf(method) {
    return this.frames.filter((frame) => frame.method === method);
  }

  sentFramesOf(method) {
    return this.sent.filter((frame) => frame.method === method);
  }

  async waitFor(predicate, { label = 'condition', timeoutMs = 5000 } = {}) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const hit = predicate(this);
      if (hit !== undefined && hit !== false && hit !== null) return hit;
      if (Date.now() > deadline) {
        throw new Error(
          `${this.label}: timed out waiting for ${label}; frames=${JSON.stringify(this.frames)} stderr=${this.stderrText}`,
        );
      }
      await sleep(20);
    }
  }

  waitExit({ timeoutMs = 5000 } = {}) {
    return this.waitFor((peer) => peer.exit, { label: 'process exit', timeoutMs });
  }

  async stop() {
    if (this.exit === null && this.pid) {
      await terminateTree(this.pid).catch(() => undefined);
      await this.waitFor((peer) => peer.exit, { label: 'process exit', timeoutMs: 5000 }).catch(() => undefined);
    }
  }
}

function spawnPeer(script, { scenario, workspace, env = {}, args = [], cwd = workspace }) {
  const child = spawn(
    process.execPath,
    [script, '--scenario', scenario, '--workspace', workspace, ...args],
    {
      cwd,
      env: { ...process.env, ARIA_PEER_CONTROL_TOKEN: CONTROL_TOKEN, ARIA_PEER_RUN_ID: RUN_ID, ...env },
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
      detached: process.platform !== 'win32',
    },
  );
  return new PeerProcess(child, { label: scenario, workspace });
}

async function withQoder(scenario, body) {
  const workspace = await freshWorkspace(scenario);
  const peer = spawnPeer(QODER, { scenario, workspace });
  try {
    return await body(peer, workspace);
  } finally {
    await peer.stop();
    await removeWorkspace(workspace);
  }
}

async function qoderSession(peer, { model = 'efficient', promptText = 'fixture prompt' } = {}) {
  peer.send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: 1 } });
  const init = await peer.waitFor((p) => p.frames.find((frame) => frame.id === 1 && frame.result), {
    label: 'initialize result',
  });
  peer.send({ jsonrpc: '2.0', id: 2, method: 'session/new', params: { cwd: peer.workspace, mcpServers: [] } });
  const created = await peer.waitFor((p) => p.frames.find((frame) => frame.id === 2 && frame.result), {
    label: 'session/new result',
  });
  const sessionId = created.result.sessionId;
  if (model !== null) {
    peer.send({ jsonrpc: '2.0', id: 3, method: 'session/set_model', params: { sessionId, modelId: model } });
    await peer.waitFor((p) => p.frames.find((frame) => frame.id === 3), { label: 'set_model response' });
  }
  peer.send({
    jsonrpc: '2.0',
    id: 4,
    method: 'session/prompt',
    params: { sessionId, prompt: [{ type: 'text', text: promptText }] },
  });
  return { init: init.result, sessionId, promptId: 4 };
}

function permissionFrames(peer) {
  return peer.frames.filter((frame) => frame.method === 'session/request_permission');
}

async function nextPermission(peer, index) {
  return peer.waitFor((p) => permissionFrames(p)[index], { label: `permission request ${index}` });
}

const allowOnce = (request) => ({
  jsonrpc: '2.0',
  id: request.id,
  result: { outcome: { outcome: 'selected', optionId: 'proceed_once' } },
});
const rejectOnce = (request) => ({
  jsonrpc: '2.0',
  id: request.id,
  result: { outcome: { outcome: 'selected', optionId: 'cancel' } },
});

async function waitForGrowth(path, fromSize, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const snapshot = await readFixtureFile(path);
    if (snapshot.exists && snapshot.size > fromSize) return snapshot;
    if (Date.now() > deadline) {
      throw new Error(`no growth beyond ${fromSize} bytes in ${path} within ${timeoutMs}ms`);
    }
    await sleep(50);
  }
}

function fileSize(path) {
  try {
    return readFileSync(path).length;
  } catch {
    return 0;
  }
}

function isAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

// ---------------------------------------------------------------- fixture writes

test('denial preserves the original bytes', async () => {
  await withTempDir('denial', async (dir) => {
    const target = join(dir, 'result.txt');
    await writeFile(target, 'before\n');
    const result = await applyDecision(
      'deny-7',
      [
        { optionId: 'allow-7', kind: 'allow_once' },
        { optionId: 'deny-7', kind: 'reject_once' },
      ],
      target,
      'after\n',
      dir,
    );
    assert.deepEqual(result, { status: 'denied', writes: 0 });
    assert.equal(await readFile(target, 'utf8'), 'before\n');
  });
});

test('allow-once writes the exact granted bytes exactly once', async () => {
  await withTempDir('allow-once', async (dir) => {
    const target = join(dir, 'granted.txt');
    const result = await applyDecision('proceed_once', EDIT_OPTIONS, target, 'granted-bytes', dir);
    assert.deepEqual(result, { status: 'written', writes: 1 });
    assert.equal(await readFile(target, 'utf8'), 'granted-bytes');
  });
});

test('an unknown option id is rejected and the target is untouched', async () => {
  await withTempDir('unknown-option', async (dir) => {
    const target = join(dir, 'unknown.txt');
    await writeFile(target, 'before\n');
    await assert.rejects(
      () => applyDecision('not-offered', EDIT_OPTIONS, target, 'after\n', dir),
      (error) => error.message === 'Unsupported fixture decision',
    );
    assert.equal(await readFile(target, 'utf8'), 'before\n');
  });
});

test('allow-always is never accepted as a fixture decision', async () => {
  await withTempDir('allow-always', async (dir) => {
    await assert.rejects(
      () => applyDecision('proceed_always', EDIT_OPTIONS, join(dir, 'always.txt'), 'after\n', dir),
      (error) => error.message === 'Unsupported fixture decision',
    );
    assert.deepEqual(await snapshotDirectory(dir), []);
  });
});

test('fixture writes are confined to the admitted workspace', async () => {
  const workspace = await freshWorkspace('confinement');
  try {
    await assert.throws(
      () => assertInsideWorkspace(workspace, join(workspace, '..', 'outside.txt')),
      (error) => error.exitCode === EXIT_CODES.config,
    );
    assert.equal(
      assertInsideWorkspace(workspace, join(workspace, 'nested', 'inside.txt')),
      join(workspace, 'nested', 'inside.txt'),
    );
  } finally {
    await removeWorkspace(workspace);
  }
});

test('applyDecision itself refuses a target outside the admitted workspace', async () => {
  await withTempDir('apply-confinement', async (workspace) => {
    const outside = join(workspace, '..', `aria-peer-outside-${process.pid}.txt`);
    await assert.rejects(
      () => applyDecision('proceed_once', EDIT_OPTIONS, outside, 'leaked\n', workspace),
      (error) => error.exitCode === EXIT_CODES.config,
      'an escaping target must fail closed even when the caller skipped assertInsideWorkspace',
    );
    assert.equal(existsSync(outside), false);
    await assert.rejects(
      () => applyDecision('proceed_once', EDIT_OPTIONS, workspace, 'leaked\n', workspace),
      (error) => error.exitCode === EXIT_CODES.config,
      'the workspace root itself is not a writable fixture target',
    );
    const missing = join(workspace, 'missing-workspace.txt');
    await assert.rejects(
      () => applyDecision('proceed_once', EDIT_OPTIONS, missing, 'leaked\n'),
      (error) => /admitted workspace/.test(error.message),
      'a missing admitted workspace must fail closed',
    );
    assert.equal(existsSync(missing), false);
    const inside = join(workspace, 'inside.txt');
    assert.deepEqual(await applyDecision('proceed_once', EDIT_OPTIONS, inside, 'ok\n', workspace), {
      status: 'written',
      writes: 1,
    });
    assert.equal(await readFile(inside, 'utf8'), 'ok\n');
  });
});

// ---------------------------------------------------------------- scenario manifest

test('scenarios.json declares every scenario both peers implement', async () => {
  const manifest = JSON.parse(readFileSync(join(PEER_DIR, '..', 'scenarios.json'), 'utf8'));
  const declared = manifest.scenarios.map((scenario) => scenario.id);
  assert.deepEqual([...declared].sort(), [...new Set(declared)].sort(), 'scenario ids must be unique');
  assert.deepEqual(implementedScenarios('qoder'), declared);
  assert.deepEqual(implementedScenarios('opencode'), declared);
  assert.equal(manifest.control.controlTokenEnv, 'ARIA_PEER_CONTROL_TOKEN');
  assert.equal(manifest.control.bindAddress, '127.0.0.1');
  assert.equal(manifest.control.portArgv, '--port');
  assert.equal(manifest.control.databaseAccess, false);
  assert.deepEqual(manifest.fixtures.options.edit, EDIT_OPTIONS);
  assert.deepEqual(manifest.fixtures.options.execute, [
    { optionId: 'proceed_always_and_save', nameTemplate: 'Always allow "${program}"', kind: 'allow_always' },
    { optionId: 'proceed_once', name: 'Allow', kind: 'allow_once' },
    { optionId: 'cancel', name: 'Reject', kind: 'reject_once' },
  ]);
  for (const scenario of manifest.scenarios) {
    assert.equal(typeof scenario.title, 'string', `${scenario.id} needs a title`);
    assert.equal(typeof scenario.prompt, 'string', `${scenario.id} needs a prompt`);
    assert.ok(scenario.qoder && scenario.opencode, `${scenario.id} must describe both peers`);
  }
  assert.deepEqual(
    declared.slice().sort(),
    [
      'background-writer',
      'cancel-pending',
      'complete',
      'deny-write',
      'disconnect',
      'handshake-failure',
      'invalid-auth',
      'malformed-frame',
      'non-cooperative',
      'pause-resume',
      'permission-expiry',
      'reported-usage',
      'timeout',
      'two-turn-nonce',
      'unknown-usage',
      'unsupported-mode',
      'unsupported-model',
      'write-twice',
    ],
  );
});

// ---------------------------------------------------------------- mock Qoder ACP

test('qoder peer performs the recorded handshake order, acks the model and completes', async () => {
  await withQoder('complete', async (peer, workspace) => {
    const { init, sessionId } = await qoderSession(peer, {
      promptText: 'Reply with exactly the single word: pong',
    });
    assert.equal(init.protocolVersion, 1);
    assert.deepEqual(init.agentInfo, { name: 'qoder-cli', title: 'Qoder CLI', version: '1.1.61' });
    assert.deepEqual(init.authMethods.map((method) => method.id), ['qodercli-login']);
    assert.equal(/pause|suspend/i.test(JSON.stringify(init.agentCapabilities)), false);
    assert.equal(init.agentCapabilities.sessionCapabilities.resume !== undefined, true);
    assert.match(sessionId, UUID_V4);

    const modelFrame = peer.sentFramesOf('session/set_model')[0];
    assert.equal(modelFrame.id, 3);
    assert.deepEqual(modelFrame.params, { sessionId, modelId: 'efficient' });
    assert.deepEqual(
      peer.frames.find((frame) => frame.id === 3 && frame.result),
      { jsonrpc: '2.0', id: 3, result: {} },
    );

    const commands = peer.frames.find(
      (frame) => frame.params?.update?.sessionUpdate === 'available_commands_update',
    );
    assert.ok(commands, 'peer must advertise its fixture commands');
    assert.equal(commands.id, undefined);
    assert.equal(commands.params.sessionId, sessionId);

    const prompt = peer.sentFramesOf('session/prompt')[0];
    assert.equal(prompt.id, 4);
    assert.deepEqual(prompt.params, {
      sessionId,
      prompt: [{ type: 'text', text: 'Reply with exactly the single word: pong' }],
    });

    const result = await peer.waitFor((p) => p.frames.find((frame) => frame.id === 4 && frame.result), {
      label: 'prompt result',
    });
    const thoughts = peer.frames.filter(
      (frame) => frame.params?.update?.sessionUpdate === 'agent_thought_chunk',
    );
    assert.equal(
      thoughts.map((frame) => frame.params.update.content.text).join(''),
      'The user wants exactly the word "pong".\n',
    );
    const messages = peer.frames.filter(
      (frame) => frame.params?.update?.sessionUpdate === 'agent_message_chunk',
    );
    assert.equal(messages.length, 1);
    assert.equal(messages[0].params.update.content.text, 'pong');
    for (const chunk of [...thoughts, ...messages]) {
      assert.equal(chunk.params.sessionId, sessionId);
      assert.equal(chunk.id, undefined);
    }

    assert.equal(result.result.stopReason, 'end_turn');
    assert.match(result.result.userMessageId, UUID_V4);
    assert.deepEqual(result.result.usage, { inputTokens: 0, outputTokens: 0, totalTokens: 0 });
    assert.deepEqual(result.result._meta.quota.model_usage, [
      { model: 'efficient', token_count: { input_tokens: 0, output_tokens: 0 } },
    ]);
    assert.deepEqual(await snapshotDirectory(workspace), [], 'the complete scenario performs no fixture write');
    assert.deepEqual(peer.stderrRecords(), []);
    peer.closeStdin();
    const exit = await peer.waitExit({ timeoutMs: 5000 });
    assert.equal(exit.code, EXIT_CODES.ok);
  });
});

test('qoder peer grants exactly the allowed bytes and re-asks with a distinct request id', async () => {
  await withQoder('write-twice', async (peer, workspace) => {
    const { sessionId } = await qoderSession(peer);
    const first = await nextPermission(peer, 0);
    assert.equal(first.id, 0);
    assert.equal(first.params.sessionId, sessionId);
    assert.deepEqual(first.params.options, EDIT_OPTIONS);
    assert.equal(first.params.toolCall.kind, 'edit');
    assert.equal(first.params.toolCall.status, 'pending');
    assert.match(first.params.toolCall.toolCallId, TOOL_CALL_ID);
    assert.deepEqual(first.params.toolCall._meta, { qoder: { toolName: 'Write' } });
    const firstTarget = first.params.toolCall.rawInput.file_path;
    assert.equal(firstTarget, join(workspace, 'probe-allow-once.txt'));
    assert.equal(first.params.toolCall.rawInput.content, 'alpha-allow-once');
    // Recorded permission copy (protocol.jsonl line 41): title `Edit <basename>`,
    // diff `_meta.kind=add` present, no `locations`.
    assert.equal(first.params.toolCall.title, 'Edit probe-allow-once.txt');
    assert.deepEqual(first.params.toolCall.content, [
      { type: 'diff', path: firstTarget, oldText: null, newText: 'alpha-allow-once', _meta: { kind: 'add' } },
    ]);
    assert.equal('locations' in first.params.toolCall, false);
    // Recorded tool_call update (line 40) differs: `Write <abs>` plus locations,
    // and its diff carries no `_meta`.
    const firstUpdate = peer.frames.find((frame) => frame.params?.update?.sessionUpdate === 'tool_call');
    assert.equal(firstUpdate.params.update.toolCallId, first.params.toolCall.toolCallId);
    assert.equal(firstUpdate.params.update.title, `Write ${firstTarget}`);
    assert.equal(firstUpdate.params.update.status, 'pending');
    assert.deepEqual(firstUpdate.params.update.locations, [{ path: firstTarget }]);
    assert.deepEqual(firstUpdate.params.update.content, [
      { type: 'diff', path: firstTarget, oldText: null, newText: 'alpha-allow-once' },
    ]);
    assert.deepEqual(await snapshotDirectory(workspace), [], 'nothing may be written before the allow-once reply');

    peer.send(allowOnce(first));
    const firstWrite = await peer.waitFor(
      (p) => p.frames.find((frame) => frame.params?.update?.sessionUpdate === 'tool_call_update'),
      { label: 'first tool_call_update' },
    );
    assert.equal(firstWrite.params.update.toolCallId, first.params.toolCall.toolCallId);
    assert.equal(firstWrite.params.update.status, 'completed');
    assert.deepEqual(await readFixtureFile(firstTarget), {
      exists: true,
      size: 16,
      sha256: sha256Hex('alpha-allow-once'),
    });

    const second = await nextPermission(peer, 1);
    assert.equal(second.id, 1, 'the second request must use a distinct request id');
    assert.notEqual(second.params.toolCall.toolCallId, first.params.toolCall.toolCallId);
    assert.equal(second.params.toolCall.kind, 'execute');
    assert.deepEqual(second.params.toolCall._meta, { qoder: { toolName: 'Bash' } });
    const secondTarget = join(workspace, 'probe-write-twice-2.txt');
    const secondCommand = `printf 'gamma-permission-twice' > "${secondTarget}"`;
    assert.equal(second.params.toolCall.rawInput.command, secondCommand);
    // Recorded execute request (line 84): the Bash option list, the command as
    // title, and a permission copy identical to the tool_call update.
    assert.deepEqual(second.params.options, executeOptions('printf'));
    assert.equal(second.params.toolCall.title, secondCommand);
    assert.equal('locations' in second.params.toolCall, false);
    const secondUpdate = peer.frames.filter((frame) => frame.params?.update?.sessionUpdate === 'tool_call')[1];
    assert.equal(secondUpdate.params.update.title, secondCommand);
    assert.equal(secondUpdate.params.update.toolCallId, second.params.toolCall.toolCallId);
    assert.equal('locations' in secondUpdate.params.update, false);
    assert.deepEqual(secondUpdate.params.update.content, [
      { type: 'content', content: { type: 'text', text: secondCommand } },
    ]);
    const { sessionUpdate: ignoredUpdateKind, ...secondToolCallUpdate } = secondUpdate.params.update;
    assert.equal(ignoredUpdateKind, 'tool_call');
    assert.deepEqual(second.params.toolCall, secondToolCallUpdate, 'the recorded Bash copy repeats the update payload');

    peer.send(rejectOnce(second));
    const done = await peer.waitFor((p) => p.frames.find((frame) => frame.id === 4 && frame.result), {
      label: 'prompt result',
    });
    assert.equal(done.result.stopReason, 'end_turn');
    assert.equal(await readFile(secondTarget, 'utf8').catch(() => null), null, 'the denied write must not exist');
    assert.deepEqual(
      (await snapshotDirectory(workspace)).map((entry) => entry.path),
      ['probe-allow-once.txt'],
    );
    assert.equal((await readFixtureFile(firstTarget)).sha256, sha256Hex('alpha-allow-once'));
  });
});

test('qoder peer leaves pre-existing bytes untouched on denial', async () => {
  await withQoder('deny-write', async (peer, workspace) => {
    const target = join(workspace, 'probe-deny.txt');
    await writeFile(target, 'before\n');
    const { sessionId } = await qoderSession(peer);
    const request = await nextPermission(peer, 0);
    assert.equal(request.params.sessionId, sessionId);
    assert.equal(request.params.toolCall.rawInput.file_path, target);
    assert.equal(request.params.toolCall.rawInput.content, 'beta-should-not-exist');
    assert.deepEqual(request.params.options, EDIT_OPTIONS);
    assert.equal(request.params.toolCall.title, 'Edit probe-deny.txt');
    assert.deepEqual(request.params.toolCall.content, [
      { type: 'diff', path: target, oldText: null, newText: 'beta-should-not-exist', _meta: { kind: 'add' } },
    ]);
    peer.send(rejectOnce(request));
    const done = await peer.waitFor((p) => p.frames.find((frame) => frame.id === 4 && frame.result), {
      label: 'prompt result',
    });
    assert.equal(done.result.stopReason, 'end_turn');
    assert.equal(await readFile(target, 'utf8'), 'before\n');
    const updates = peer.frames.filter((frame) => frame.params?.update?.sessionUpdate === 'tool_call_update');
    assert.equal(updates.length, 1);
    assert.equal(updates[0].params.update.status, 'failed');
    assert.equal(updates[0].params.update.toolCallId, request.params.toolCall.toolCallId);
    assert.deepEqual(
      (await snapshotDirectory(workspace)).map((entry) => entry.path),
      ['probe-deny.txt'],
    );
  });
});

test('qoder peer refuses an allow-always decision without writing', async () => {
  await withQoder('write-twice', async (peer, workspace) => {
    await qoderSession(peer);
    const request = await nextPermission(peer, 0);
    peer.send({
      jsonrpc: '2.0',
      id: request.id,
      result: { outcome: { outcome: 'selected', optionId: 'proceed_always' } },
    });
    const done = await peer.waitFor((p) => p.frames.find((frame) => frame.id === 4 && frame.result), {
      label: 'prompt result',
    });
    assert.equal(done.result.stopReason, 'end_turn');
    const updates = peer.frames.filter((frame) => frame.params?.update?.sessionUpdate === 'tool_call_update');
    assert.equal(updates[0].params.update.status, 'failed');
    assert.equal(updates[0].params.update.toolCallId, request.params.toolCall.toolCallId);
    assert.deepEqual(await snapshotDirectory(workspace), []);
    const invalid = peer.stderrRecords().filter((record) => record.type === 'peer.invalid_decision');
    assert.equal(invalid.length, 1);
    assert.equal(invalid[0].optionId, 'proceed_always');
    assert.equal(invalid[0].kind, 'allow_always');
    assert.equal(invalid[0].writes, 0);
  });
});

test('qoder peer expires a pending permission and rejects a late decision', async () => {
  await withQoder('permission-expiry', async (peer, workspace) => {
    await qoderSession(peer);
    const request = await nextPermission(peer, 0);
    const update = await peer.waitFor(
      (p) => p.frames.find((frame) => frame.params?.update?.sessionUpdate === 'tool_call_update'),
      { label: 'expired tool_call_update' },
    );
    assert.equal(update.params.update.status, 'failed');
    assert.equal(update.params.update.toolCallId, request.params.toolCall.toolCallId);
    const done = await peer.waitFor((p) => p.frames.find((frame) => frame.id === 4 && frame.result), {
      label: 'prompt result',
    });
    assert.equal(done.result.stopReason, 'end_turn');
    const expiry = peer.stderrRecords().find((record) => record.type === 'peer.permission_expired');
    assert.deepEqual(expiry, { type: 'peer.permission_expired', requestId: request.id, windowMs: 300 });
    peer.send(allowOnce(request));
    const late = await peer.waitFor(
      (p) => p.stderrRecords().find((record) => record.type === 'peer.late_decision'),
      { label: 'late decision record' },
    );
    assert.deepEqual(late, { type: 'peer.late_decision', requestId: request.id, optionId: 'proceed_once', writes: 0 });
    assert.deepEqual(await snapshotDirectory(workspace), []);
  });
});

test('qoder peer cancels a pending permission with zero writes', async () => {
  await withQoder('cancel-pending', async (peer, workspace) => {
    const { sessionId } = await qoderSession(peer);
    const request = await nextPermission(peer, 0);
    peer.send({ jsonrpc: '2.0', method: 'session/cancel', params: { sessionId } });
    const done = await peer.waitFor((p) => p.frames.find((frame) => frame.id === 4 && frame.result), {
      label: 'cancelled prompt result',
    });
    assert.equal(done.result.stopReason, 'cancelled');
    assert.match(done.result.userMessageId, UUID_V4);
    await sleep(300);
    assert.equal(peer.frames.filter((frame) => frame.id === 4).length, 1, 'exactly one cancelled result frame');
    const cancel = peer.stderrRecords().find((record) => record.type === 'peer.cancel');
    assert.deepEqual(cancel, {
      type: 'peer.cancel',
      sessionId,
      pending: 'permission',
      pendingRequestIds: [request.id],
    });
    assert.deepEqual(await snapshotDirectory(workspace), []);
  });
});

test('qoder cancel after completion emits exactly one result and no further frames', async () => {
  await withQoder('complete', async (peer) => {
    const { sessionId } = await qoderSession(peer);
    const result = await peer.waitFor((p) => p.frames.find((frame) => frame.id === 4 && frame.result), {
      label: 'prompt result',
    });
    assert.equal(result.result.stopReason, 'end_turn');
    await sleep(300);
    const framesAtCompletion = peer.frames.length;
    const bytesAtCompletion = peer.stdoutBytes;

    peer.send({ jsonrpc: '2.0', method: 'session/cancel', params: { sessionId } });
    await sleep(700);

    assert.equal(
      peer.frames.filter((frame) => frame.id === 4).length,
      1,
      'the completed prompt answers exactly once (the recording contains no duplicate response)',
    );
    assert.equal(peer.frames.length, framesAtCompletion, 'a cancel without a live prompt emits no frame');
    assert.equal(peer.stdoutBytes, bytesAtCompletion, 'a cancel without a live prompt writes no bytes');
    assert.equal(peer.exit, null);
    assert.deepEqual(
      peer.stderrRecords().filter((entry) => entry.type === 'peer.cancel'),
      [{ type: 'peer.cancel', sessionId, pending: null, pendingRequestIds: [] }],
    );
  });
});

test('qoder peer reports invalid authentication with the recorded error', async () => {
  await withQoder('invalid-auth', async (peer) => {
    peer.send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: 1 } });
    await peer.waitFor((p) => p.frames.find((frame) => frame.id === 1 && frame.result), {
      label: 'initialize result',
    });
    peer.send({ jsonrpc: '2.0', id: 2, method: 'session/new', params: { cwd: peer.workspace, mcpServers: [] } });
    const failure = await peer.waitFor((p) => p.frames.find((frame) => frame.id === 2), {
      label: 'session/new failure',
    });
    assert.equal(failure.result, undefined);
    assert.deepEqual(failure.error, {
      code: -32000,
      message: 'Authentication required: Authentication is required.',
    });
    assert.equal(peer.frames.filter((frame) => frame.id === 2).length, 1);
    assert.equal(peer.frames.some((frame) => frame.params?.sessionId), false, 'no session may be created');
    assert.equal(peer.exit, null);
  });
});

test('qoder peer refuses an unsupported model and exits without a fallback', async () => {
  await withQoder('unsupported-model', async (peer, workspace) => {
    peer.send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: 1 } });
    await peer.waitFor((p) => p.frames.find((frame) => frame.id === 1 && frame.result), {
      label: 'initialize result',
    });
    peer.send({ jsonrpc: '2.0', id: 2, method: 'session/new', params: { cwd: peer.workspace, mcpServers: [] } });
    const created = await peer.waitFor((p) => p.frames.find((frame) => frame.id === 2 && frame.result), {
      label: 'session/new result',
    });
    peer.send({
      jsonrpc: '2.0',
      id: 3,
      method: 'session/set_model',
      params: { sessionId: created.result.sessionId, modelId: 'qmodel_fixture-missing' },
    });
    const failure = await peer.waitFor((p) => p.frames.find((frame) => frame.id === 3), {
      label: 'set_model failure',
    });
    assert.deepEqual(failure.error, { code: -32000, message: 'Unsupported model id: qmodel_fixture-missing' });
    const exit = await peer.waitExit({ timeoutMs: 5000 });
    assert.equal(exit.code, EXIT_CODES.scripted);
    assert.equal(peer.sentFramesOf('session/prompt').length, 0);
    assert.equal(peer.frames.filter((frame) => frame.result !== undefined).length, 2, 'initialize and session/new');
    assert.equal(peer.frames.filter((frame) => frame.error !== undefined).length, 1);
    assert.deepEqual(await snapshotDirectory(workspace), []);
  });
});

test('qoder peer refuses unknown and invented pause/mode methods with -32601', async () => {
  await withQoder('unsupported-mode', async (peer) => {
    const { sessionId } = await qoderSession(peer);
    await peer.waitFor((p) => p.frames.find((frame) => frame.id === 4 && frame.result), { label: 'prompt result' });
    peer.send({ jsonrpc: '2.0', id: 5, method: 'session/set_mode', params: { sessionId, modeId: 'yolo' } });
    peer.send({ jsonrpc: '2.0', id: 6, method: 'session/pause', params: { sessionId } });
    await peer.waitFor((p) => p.frames.filter((frame) => frame.error && (frame.id === 5 || frame.id === 6)).length === 2, {
      label: 'two -32601 refusals',
    });
    for (const frame of peer.frames.filter((entry) => entry.id === 5 || entry.id === 6)) {
      assert.deepEqual(frame.error, { code: -32601, message: 'Method not found' });
    }
    assert.equal(peer.exit, null);
  });
});

test('qoder peer emits malformed, oversized and split frames deterministically', async () => {
  await withQoder('malformed-frame', async (peer) => {
    peer.send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: 1 } });
    await peer.waitFor((p) => p.frames.find((frame) => frame.id === 1 && frame.result), {
      label: 'initialize result',
    });
    peer.send({ jsonrpc: '2.0', id: 2, method: 'session/new', params: { cwd: peer.workspace, mcpServers: [] } });
    const createdIndex = await peer.waitFor((p) => {
      const at = p.rawLines.findIndex((line) => {
        try {
          const frame = JSON.parse(line);
          return frame.id === 2 && frame.result;
        } catch {
          return false;
        }
      });
      return at >= 0 ? at : undefined;
    }, { label: 'session/new result index' });
    const scripted = await peer.waitFor(
      (p) => (p.rawLines.length >= createdIndex + 4 ? p.rawLines.slice(createdIndex + 1, createdIndex + 4) : undefined),
      { label: 'three scripted lines' },
    );
    assert.equal(scripted[0], '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"');
    assert.ok(Buffer.byteLength(scripted[1], 'utf8') >= 1048577, 'the oversized frame exceeds 1 MiB');
    const oversized = peer.frames.find((frame) => frame.params?.update?.sessionUpdate === 'fixture_oversized_payload');
    assert.ok(oversized, 'the oversized line must still be valid JSON');
    assert.equal(oversized.params.update.payload.length, 1048576);
    const split = peer.frames.find((frame) => frame.params?.update?.sessionUpdate === 'fixture_split_frame');
    assert.ok(split, 'the split frame must reassemble into one valid frame');
    assert.equal(split.params.update.note, 'partial write reassembled');
    assert.match(split.params.sessionId, UUID_V4);
    peer.send({
      jsonrpc: '2.0',
      id: 3,
      method: 'session/prompt',
      params: { sessionId: split.params.sessionId, prompt: [{ type: 'text', text: 'continue' }] },
    });
    const done = await peer.waitFor((p) => p.frames.find((frame) => frame.id === 3 && frame.result), {
      label: 'prompt result after malformed frames',
    });
    assert.equal(done.result.stopReason, 'end_turn');
  });
});

test('qoder peer disconnect drops the stream without a prompt result', async () => {
  await withQoder('disconnect', async (peer) => {
    await qoderSession(peer);
    const exit = await peer.waitExit({ timeoutMs: 5000 });
    assert.equal(exit.code, EXIT_CODES.disconnect);
    assert.equal(peer.frames.some((frame) => frame.id === 4), false, 'no prompt result may be flushed');
    assert.equal(
      peer.frames.filter((frame) => frame.params?.update?.sessionUpdate === 'agent_thought_chunk').length,
      2,
    );
  });
});

test('qoder peer handshake failure is explicit and terminal', async () => {
  await withQoder('handshake-failure', async (peer) => {
    peer.send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: 1 } });
    const failure = await peer.waitFor((p) => p.frames.find((frame) => frame.id === 1), {
      label: 'initialize failure',
    });
    assert.deepEqual(failure.error, {
      code: -32000,
      message: 'Handshake rejected: fixture core does not accept protocolVersion 1',
    });
    const exit = await peer.waitExit({ timeoutMs: 5000 });
    assert.equal(exit.code, EXIT_CODES.scripted);
    assert.equal(peer.frames.length, 1, 'the handshake rejection is the only frame');
  });
});

test('qoder peer leaves a hung request unanswered while the process stays alive', async () => {
  await withQoder('timeout', async (peer) => {
    peer.send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: 1 } });
    await peer.waitFor((p) => p.frames.find((frame) => frame.id === 1 && frame.result), {
      label: 'initialize result',
    });
    peer.send({ jsonrpc: '2.0', id: 2, method: 'session/new', params: { cwd: peer.workspace, mcpServers: [] } });
    const recorded = await peer.waitFor(
      (p) => p.stderrRecords().find((entry) => entry.type === 'peer.request_pending'),
      { label: 'pending request record' },
    );
    assert.deepEqual(recorded, { type: 'peer.request_pending', method: 'session/new', id: 2 });
    await sleep(900);
    assert.equal(peer.frames.some((frame) => frame.id === 2), false);
    assert.equal(peer.exit, null, 'the hung core must stay alive');
  });
});

test('qoder background writer keeps writing after prompt completion', async () => {
  await withQoder('background-writer', async (peer, workspace) => {
    await qoderSession(peer);
    const request = await nextPermission(peer, 0);
    // Recorded execute request for the writer command (protocol.jsonl line 165).
    assert.deepEqual(request.params.options, executeOptions('node'));
    assert.equal(request.params.toolCall.title, 'node spawn-writer.mjs ticks.log');
    assert.equal(request.params.toolCall.rawInput.command, 'node spawn-writer.mjs ticks.log');
    peer.send(allowOnce(request));
    const childRecord = await peer.waitFor(
      (p) => p.stderrRecords().find((entry) => entry.type === 'peer.child_started'),
      { label: 'child started record' },
    );
    assert.equal(childRecord.executable, process.execPath);
    assert.equal(childRecord.command, 'node spawn-writer.mjs ticks.log');
    assert.ok(Number.isInteger(childRecord.pid) && childRecord.pid > 0);
    const done = await peer.waitFor((p) => p.frames.find((frame) => frame.id === 4 && frame.result), {
      label: 'prompt result',
    });
    assert.equal(done.result.stopReason, 'end_turn');
    const ticks = join(workspace, 'ticks.log');
    const first = await waitForGrowth(ticks, 0, 5000);
    assert.ok(first.size > 0);
    const later = await waitForGrowth(ticks, first.size, 5000);
    assert.ok(later.size > first.size, `writer must grow after completion: ${first.size} -> ${later.size}`);
    await terminateTree(childRecord.pid);
    const frozen = fileSize(ticks);
    await sleep(700);
    assert.equal(fileSize(ticks), frozen, 'bytes must stay frozen after the writer is stopped');
    assert.equal(isAlive(childRecord.pid), false);
    assert.equal(peer.exit, null, 'only the writer child is terminated here');
  });
});

test('qoder non-cooperative peer ignores a cooperative cancel and keeps writing', async () => {
  await withQoder('non-cooperative', async (peer, workspace) => {
    const { sessionId } = await qoderSession(peer);
    const request = await nextPermission(peer, 0);
    peer.send(allowOnce(request));
    const childRecord = await peer.waitFor(
      (p) => p.stderrRecords().find((entry) => entry.type === 'peer.child_started'),
      { label: 'child started record' },
    );
    await peer.waitFor((p) => p.frames.find((frame) => frame.id === 4 && frame.result), { label: 'prompt result' });
    peer.send({ jsonrpc: '2.0', method: 'session/cancel', params: { sessionId } });
    peer.closeStdin();
    await sleep(900);
    assert.equal(peer.exit, null, 'the non-cooperative peer survives a cancel and a stdin close');
    assert.equal(peer.frames.filter((frame) => frame.id === 4).length, 1, 'no second prompt response after cancel');
    const ticks = join(workspace, 'ticks.log');
    const before = fileSize(ticks);
    const after = await waitForGrowth(ticks, before, 5000);
    assert.ok(after.size > before, 'the writer keeps writing after the ignored cancel');
    await terminateTree(peer.pid);
    await peer.waitExit({ timeoutMs: 5000 });
    assert.equal(isAlive(childRecord.pid), false, 'the cancelled descendant must be gone');
    const frozen = fileSize(ticks);
    await sleep(600);
    assert.equal(fileSize(ticks), frozen);
  });
});

test('a suspended peer tree emits no frames and no writes, then resumes', async () => {
  await withQoder('pause-resume', async (peer, workspace) => {
    await qoderSession(peer);
    const request = await nextPermission(peer, 0);
    peer.send(allowOnce(request));
    const childRecord = await peer.waitFor(
      (p) => p.stderrRecords().find((entry) => entry.type === 'peer.child_started'),
      { label: 'child started record' },
    );
    const ticks = join(workspace, 'ticks.log');
    const growing = await waitForGrowth(ticks, 0, 5000);
    assert.ok(growing.size > 0);
    const streamed = await peer.waitFor(
      (p) => p.frames.find((frame) => frame.params?.update?.sessionUpdate === 'agent_message_chunk'),
      { label: 'streaming while active' },
    );
    assert.equal(streamed.params.sessionId, request.params.sessionId);

    await suspendProcess(peer.pid);
    await suspendProcess(childRecord.pid);
    await sleep(100);
    const framesFrozen = peer.frames.length;
    const bytesFrozen = peer.stdoutBytes;
    const tickFrozen = fileSize(ticks);
    await sleep(700);
    assert.equal(fileSize(ticks), tickFrozen, 'no bytes may advance while the tree is suspended');
    assert.equal(peer.frames.length, framesFrozen, 'no protocol frames may advance while suspended');
    assert.equal(peer.stdoutBytes, bytesFrozen);
    assert.equal(peer.exit, null);

    await resumeProcess(peer.pid);
    await resumeProcess(childRecord.pid);
    const resumed = await waitForGrowth(ticks, tickFrozen, 5000);
    assert.ok(resumed.size > tickFrozen, 'writes must resume after the tree is resumed');
    await peer.waitFor((p) => p.frames.length > framesFrozen, {
      label: 'streaming resumes after resume',
      timeoutMs: 5000,
    });
    assert.equal(
      peer.frames.some((frame) => frame.id === 4),
      false,
      'the pause-resume prompt stays open until the session is cancelled',
    );
  });
});

test('qoder peer fails closed without the harness control token', async () => {
  const workspace = await freshWorkspace('no-token');
  const peer = spawnPeer(QODER, { scenario: 'complete', workspace, env: { ARIA_PEER_CONTROL_TOKEN: '' } });
  try {
    const exit = await peer.waitExit({ timeoutMs: 5000 });
    assert.equal(exit.code, EXIT_CODES.config);
    assert.deepEqual(peer.frames, []);
    assert.deepEqual(peer.stderrRecords(), [
      { type: 'peer.control_token_missing', variable: 'ARIA_PEER_CONTROL_TOKEN' },
    ]);
  } finally {
    await peer.stop();
    await removeWorkspace(workspace);
  }
});

test('qoder peer fails closed on an unknown scenario and on a missing workspace', async () => {
  const workspace = await freshWorkspace('unknown-scenario');
  try {
    const unknown = spawnPeer(QODER, { scenario: 'not-a-scenario', workspace });
    try {
      const exit = await unknown.waitExit({ timeoutMs: 5000 });
      assert.equal(exit.code, EXIT_CODES.usage);
      assert.deepEqual(unknown.stderrRecords(), [
        { type: 'peer.unknown_scenario', peer: 'qoder', scenario: 'not-a-scenario' },
      ]);
    } finally {
      await unknown.stop();
    }
    const missing = spawnPeer(QODER, {
      scenario: 'complete',
      workspace: join(workspace, 'absent'),
      cwd: workspace,
    });
    try {
      const exit = await missing.waitExit({ timeoutMs: 5000 });
      assert.equal(exit.code, EXIT_CODES.config);
      const record = missing.stderrRecords().find((entry) => entry.type === 'peer.workspace_missing');
      assert.equal(record.variable, '--workspace');
      assert.equal(record.value, join(workspace, 'absent'));
    } finally {
      await missing.stop();
    }
  } finally {
    await removeWorkspace(workspace);
  }
});

// ---------------------------------------------------------------- mock OpenCode

async function freePort() {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

async function launchOpencode(scenario) {
  const workspace = await freshWorkspace(scenario);
  const port = await freePort();
  const peer = spawnPeer(OPENCODE, {
    scenario,
    workspace,
    args: ['serve', '--hostname', '127.0.0.1', '--port', String(port)],
  });
  peer.base = `http://127.0.0.1:${port}`;
  const listening = await peer.waitFor((p) => p.stderrRecords().find((entry) => entry.type === 'peer.listening'), {
    label: 'listening record',
    timeoutMs: 8000,
  });
  assert.equal(listening.host, '127.0.0.1', 'the peer binds to loopback only');
  assert.equal(listening.port, port);
  assert.equal(listening.scenario, peer.label);
  return peer;
}

async function withOpencode(scenario, body) {
  const peer = await launchOpencode(scenario);
  try {
    return await body(peer, peer.workspace);
  } finally {
    await peer.stop();
    await removeWorkspace(peer.workspace);
  }
}

/** Poll the streamed message list until it holds at least `atLeast` entries. */
async function waitForMessages(peer, sessionId, atLeast, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const response = await fetch(`${peer.base}/session/${sessionId}/message`);
    const messages = await response.json();
    if (messages.length >= atLeast) return messages;
    if (Date.now() > deadline) {
      throw new Error(`fewer than ${atLeast} messages after ${timeoutMs}ms (saw ${messages.length})`);
    }
    await sleep(50);
  }
}

async function opencodePending(peer) {
  const response = await fetch(`${peer.base}/__peer/pending`, { headers: controlHeaders() });
  assert.equal(response.status, 200);
  return response.json();
}

async function opencodeDecision(peer, optionId) {
  return fetch(`${peer.base}/__peer/decision`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ optionId }),
  });
}

async function opencodeState(peer) {
  const response = await fetch(`${peer.base}/__peer/state`, { headers: controlHeaders() });
  assert.equal(response.status, 200);
  return response.json();
}

async function opencodePrompt(peer, sessionId, text = 'fixture prompt') {
  return fetch(`${peer.base}/session/${sessionId}/message`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ parts: [{ type: 'text', text }] }),
  });
}

const controlHeaders = (token = CONTROL_TOKEN) => ({ 'x-peer-control-token': token });
const jsonHeaders = (token = CONTROL_TOKEN) => ({ ...controlHeaders(token), 'content-type': 'application/json' });

async function opencodeSession(peer) {
  const response = await fetch(`${peer.base}/session`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ title: RUN_ID }),
  });
  return response;
}

test('opencode peer serves the recorded health and session subset', async () => {
  await withOpencode('complete', async (peer, workspace) => {
    const health = await fetch(`${peer.base}/global/health`);
    assert.equal(health.status, 200);
    assert.deepEqual(await health.json(), { healthy: true, version: '1.14.31' });

    const session = await opencodeSession(peer);
    assert.equal(session.status, 200);
    const created = await session.json();
    assert.match(created.id, /^ses_[0-9a-f]{24}$/);

    const message = await fetch(`${peer.base}/session/${created.id}/message`, {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify({ parts: [{ type: 'text', text: 'Reply with exactly the single word: pong' }] }),
    });
    assert.equal(message.status, 200);
    const body = await message.json();
    assert.equal(body.info.role, 'assistant');
    assert.equal(body.info.modelID, 'efficient');
    assert.equal(body.info.providerID, 'opencode');
    assert.equal(body.info.mode, 'build');
    assert.equal(body.info.agent, 'build');
    assert.deepEqual(body.info.path, { cwd: workspace, root: '/' });
    assert.equal(body.info.cost, 0);
    assert.deepEqual(body.info.tokens, { input: 0, output: 0, reasoning: 0, cache: { read: 0, write: 0 } });
    assert.deepEqual(body.parts, [{ type: 'text', text: 'pong' }]);
    assert.match(body.info.id, /^msg_[0-9a-zA-Z]{26}$/);
    assert.deepEqual((await snapshotDirectory(workspace)).map((entry) => entry.path), []);

    const listed = await fetch(`${peer.base}/session/${created.id}/message`);
    assert.equal(listed.status, 200);
    const messages = await listed.json();
    assert.equal(messages.length, 1);
    assert.equal(messages[0].info.id, body.info.id);
    assert.deepEqual(messages[0].parts, [{ type: 'text', text: 'pong' }]);

    const aborted = await fetch(`${peer.base}/session/${created.id}/abort`, { method: 'POST' });
    assert.equal(aborted.status, 200);
    assert.equal(await aborted.json(), true);
  });
});

test('opencode control surface is authenticated and scenario-locked', async () => {
  await withOpencode('write-twice', async (peer) => {
    const anonymous = await fetch(`${peer.base}/__peer/pending`);
    assert.equal(anonymous.status, 401);
    assert.deepEqual(await anonymous.json(), { error: 'unauthorized' });
    const wrong = await fetch(`${peer.base}/__peer/pending`, { headers: controlHeaders('wrong-token-wrong-token-wrong') });
    assert.equal(wrong.status, 401);
    const reselect = await fetch(`${peer.base}/__peer/scenario`, {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify({ scenario: 'complete' }),
    });
    assert.equal(reselect.status, 404, 'scenarios are selected at launch time, not per request');
    const authorised = await fetch(`${peer.base}/__peer/pending`, { headers: controlHeaders() });
    assert.equal(authorised.status, 200);
    assert.deepEqual(await authorised.json(), { pending: [] });
    const state = await fetch(`${peer.base}/__peer/state`, { headers: controlHeaders() });
    assert.equal(state.status, 200);
    const body = await state.json();
    assert.equal(body.scenario, 'write-twice');
    assert.equal(body.writes, 0);
    assert.equal(body.decisions, 0);
    assert.equal(body.writerPid, null);
  });
});

test('opencode peer writes only the allowed bytes and never on denial', async () => {
  await withOpencode('write-twice', async (peer, workspace) => {
    const created = await (await opencodeSession(peer)).json();
    const accepted = await opencodePrompt(peer, created.id, 'fixture write');
    assert.equal(accepted.status, 200);
    const pending = await opencodePending(peer);
    assert.equal(pending.pending.length, 1);
    assert.equal(pending.pending[0].id, 0);
    assert.equal(pending.pending[0].kind, 'edit');
    assert.deepEqual(pending.pending[0].options, EDIT_OPTIONS);
    const firstTarget = pending.pending[0].path;
    assert.equal(firstTarget, join(workspace, 'probe-allow-once.txt'));
    assert.equal(pending.pending[0].contents, 'alpha-allow-once');
    assert.deepEqual(await snapshotDirectory(workspace), [], 'no write before the allow-once decision');

    const illegal = await opencodeDecision(peer, 'proceed_always');
    assert.equal(illegal.status, 409);
    assert.deepEqual(await illegal.json(), { error: 'unsupported fixture decision' });
    const stillPending = await opencodePending(peer);
    assert.equal(stillPending.pending.length, 1, 'an unoffered decision must not consume the request');
    const afterIllegal = await opencodeState(peer);
    assert.equal(afterIllegal.writes, 0);
    assert.equal(afterIllegal.decisions, 0);
    assert.deepEqual(await snapshotDirectory(workspace), []);

    const allowed = await opencodeDecision(peer, 'proceed_once');
    assert.equal(allowed.status, 200);
    assert.deepEqual(await allowed.json(), { status: 'written', writes: 1, optionId: 'proceed_once' });
    assert.equal(await readFile(firstTarget, 'utf8'), 'alpha-allow-once');

    const second = await opencodePending(peer);
    assert.equal(second.pending.length, 1);
    assert.equal(second.pending[0].id, 1);
    assert.equal(second.pending[0].kind, 'execute');
    assert.deepEqual(second.pending[0].options, executeOptions('printf'));
    const denied = await opencodeDecision(peer, 'cancel');
    assert.deepEqual(await denied.json(), { status: 'denied', writes: 0, optionId: 'cancel' });
    assert.deepEqual(
      (await snapshotDirectory(workspace)).map((entry) => entry.path),
      ['probe-allow-once.txt'],
    );
    assert.equal(await readFile(firstTarget, 'utf8'), 'alpha-allow-once');

    const noPending = await opencodeDecision(peer, 'proceed_once');
    assert.equal(noPending.status, 409);
    assert.deepEqual(await noPending.json(), { error: 'no pending fixture decision' });
    const state = await opencodeState(peer);
    assert.equal(state.writes, 1);
    assert.equal(state.decisions, 2);
  });
});

test('opencode peer denies without writing and applies a decision exactly once', async () => {
  await withOpencode('deny-write', async (peer, workspace) => {
    const target = join(workspace, 'probe-deny.txt');
    await writeFile(target, 'before\n');
    const created = await (await opencodeSession(peer)).json();
    const accepted = await opencodePrompt(peer, created.id, 'overwrite the fixture file');
    assert.equal(accepted.status, 200);
    assert.deepEqual((await accepted.json()).parts, [{ type: 'text', text: 'fixture decision window reached' }]);

    const pending = await opencodePending(peer);
    assert.equal(pending.pending.length, 1);
    assert.equal(pending.pending[0].id, 0);
    assert.equal(pending.pending[0].kind, 'edit');
    assert.equal(pending.pending[0].path, target);
    assert.equal(pending.pending[0].contents, 'beta-should-not-exist');
    assert.deepEqual(pending.pending[0].options, EDIT_OPTIONS);

    const anonymous = await fetch(`${peer.base}/__peer/decision`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ optionId: 'proceed_once' }),
    });
    assert.equal(anonymous.status, 401, 'the write gate needs the control token');
    assert.deepEqual(await anonymous.json(), { error: 'unauthorized' });
    assert.equal((await opencodeState(peer)).decisions, 0);

    const unoffered = await opencodeDecision(peer, 'proceed_always_and_save');
    assert.equal(unoffered.status, 409);
    assert.deepEqual(await unoffered.json(), { error: 'unsupported fixture decision' });
    assert.equal((await opencodePending(peer)).pending.length, 1, 'the unoffered decision must not consume the request');
    const invalid = peer.stderrRecords().filter((record) => record.type === 'peer.invalid_decision');
    assert.equal(invalid.length, 1);
    assert.deepEqual(invalid[0], { type: 'peer.invalid_decision', optionId: 'proceed_always_and_save', kind: null, writes: 0 });

    const denied = await opencodeDecision(peer, 'cancel');
    assert.equal(denied.status, 200);
    assert.deepEqual(await denied.json(), { status: 'denied', writes: 0, optionId: 'cancel' });
    assert.equal(await readFile(target, 'utf8'), 'before\n');
    const afterDenial = await opencodeState(peer);
    assert.equal(afterDenial.decisions, 1);
    assert.equal(afterDenial.writes, 0);

    const replay = await opencodeDecision(peer, 'cancel');
    assert.equal(replay.status, 409);
    assert.deepEqual(await replay.json(), { error: 'no pending fixture decision' });
    const finalState = await opencodeState(peer);
    assert.equal(finalState.decisions, 1, 'the same decision cannot be applied twice');
    assert.equal(finalState.writes, 0);
    assert.equal(await readFile(target, 'utf8'), 'before\n');
    assert.deepEqual(
      (await snapshotDirectory(workspace)).map((entry) => entry.path),
      ['probe-deny.txt'],
    );
  });
});

test('opencode peer expires a pending decision and refuses a late decision', async () => {
  await withOpencode('permission-expiry', async (peer, workspace) => {
    const created = await (await opencodeSession(peer)).json();
    assert.equal((await opencodePrompt(peer, created.id, 'create the expiry fixture')).status, 200);
    const pending = await opencodePending(peer);
    assert.equal(pending.pending.length, 1);
    assert.equal(pending.pending[0].id, 0);
    assert.equal(pending.pending[0].kind, 'edit');
    assert.equal(pending.pending[0].path, join(workspace, 'probe-expiry.txt'));
    assert.equal(pending.pending[0].contents, 'epsilon-expired');

    const expired = await peer.waitFor(
      (p) => p.stderrRecords().find((entry) => entry.type === 'peer.permission_expired'),
      { label: 'expiry record' },
    );
    assert.deepEqual(expired, { type: 'peer.permission_expired', requestId: 0, windowMs: 300 });
    assert.deepEqual(await opencodePending(peer), { pending: [] });

    const late = await opencodeDecision(peer, 'proceed_once');
    assert.equal(late.status, 409);
    assert.deepEqual(await late.json(), { error: 'no pending fixture decision' });
    const state = await opencodeState(peer);
    assert.equal(state.writes, 0);
    assert.equal(state.decisions, 0);
    assert.deepEqual(await snapshotDirectory(workspace), []);
  });
});

test('opencode peer cancels a pending decision on abort with zero writes', async () => {
  await withOpencode('cancel-pending', async (peer, workspace) => {
    const created = await (await opencodeSession(peer)).json();
    assert.equal((await opencodePrompt(peer, created.id, 'create the cancel fixture')).status, 200);
    const pending = await opencodePending(peer);
    assert.equal(pending.pending.length, 1);
    assert.equal(pending.pending[0].id, 0);
    assert.equal(pending.pending[0].path, join(workspace, 'probe-cancel-pending.txt'));

    const aborted = await fetch(`${peer.base}/session/${created.id}/abort`, { method: 'POST' });
    assert.equal(aborted.status, 200);
    assert.equal(await aborted.json(), true);
    const cancel = await peer.waitFor((p) => p.stderrRecords().find((entry) => entry.type === 'peer.cancel'), {
      label: 'cancel record',
    });
    assert.deepEqual(cancel, {
      type: 'peer.cancel',
      sessionId: created.id,
      pending: 'decision',
      pendingRequestIds: [0],
    });
    assert.deepEqual(await opencodePending(peer), { pending: [] });

    const afterAbort = await opencodeDecision(peer, 'proceed_once');
    assert.equal(afterAbort.status, 409);
    assert.deepEqual(await afterAbort.json(), { error: 'no pending fixture decision' });
    const state = await opencodeState(peer);
    assert.equal(state.writes, 0);
    assert.equal(state.decisions, 0);
    assert.deepEqual(await snapshotDirectory(workspace), []);
  });
});

test('opencode peer refuses unsupported mode routes and unknown sessions', async () => {
  await withOpencode('unsupported-mode', async (peer, workspace) => {
    const created = await (await opencodeSession(peer)).json();
    for (const method of ['pause', 'resume']) {
      const refused = await fetch(`${peer.base}/session/${created.id}/${method}`, { method: 'POST' });
      assert.equal(refused.status, 404, `POST /session/:id/${method} must not exist`);
      assert.deepEqual(await refused.json(), { error: 'not found' });
    }
    const control = await fetch(`${peer.base}/__peer/pause`, { method: 'POST', headers: controlHeaders() });
    assert.equal(control.status, 404, 'the control surface must not invent a pause route');
    const wrongSession = await fetch(`${peer.base}/session/ses_000000000000000000000000/abort`, { method: 'POST' });
    assert.equal(wrongSession.status, 404);
    assert.deepEqual(await wrongSession.json(), { error: 'unknown session' });
    const state = await opencodeState(peer);
    assert.equal(state.writes, 0);
    assert.equal(state.decisions, 0);
    assert.equal(state.messages, 0);
    assert.deepEqual(await snapshotDirectory(workspace), []);
    assert.equal(peer.exit, null);
  });
});

test('opencode peer tree freezes while suspended and resumes streaming and writing', async () => {
  await withOpencode('pause-resume', async (peer, workspace) => {
    const created = await (await opencodeSession(peer)).json();
    const accepted = await opencodePrompt(peer, created.id, 'run the fixture writer');
    assert.equal(accepted.status, 200);
    assert.deepEqual((await accepted.json()).parts, [{ type: 'text', text: 'DONE' }]);
    const pending = await opencodePending(peer);
    assert.equal(pending.pending.length, 1);
    assert.equal(pending.pending[0].kind, 'execute');
    assert.deepEqual(pending.pending[0].options, executeOptions('node'));
    assert.equal(pending.pending[0].command, 'node spawn-writer.mjs ticks.log');

    const allowed = await opencodeDecision(peer, 'proceed_once');
    assert.deepEqual(await allowed.json(), { status: 'written', writes: 1, optionId: 'proceed_once' });
    const state = await opencodeState(peer);
    assert.ok(Number.isInteger(state.writerPid) && state.writerPid > 0);
    const ticks = join(workspace, 'ticks.log');
    const growing = await waitForGrowth(ticks, 0, 5000);
    assert.ok(growing.size > 0);
    const activeMessages = await waitForMessages(peer, created.id, 2, 5000);
    assert.equal(activeMessages.at(-1).parts[0].text, 'fixture-streaming\n');

    await suspendProcess(peer.pid);
    await suspendProcess(state.writerPid);
    await sleep(100);
    const tickFrozen = fileSize(ticks);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 600);
    await assert.rejects(
      () => fetch(`${peer.base}/global/health`, { signal: controller.signal }),
      (error) => error.name === 'AbortError',
      'a suspended peer must not answer HTTP requests',
    );
    clearTimeout(timer);
    await sleep(400);
    assert.equal(fileSize(ticks), tickFrozen, 'no writer bytes may advance while the tree is suspended');
    assert.equal(peer.exit, null, 'the suspended peer must still be alive');

    await resumeProcess(peer.pid);
    await resumeProcess(state.writerPid);
    const resumed = await waitForGrowth(ticks, tickFrozen, 5000);
    assert.ok(resumed.size > tickFrozen, 'the writer must resume after the tree is resumed');
    const health = await fetch(`${peer.base}/global/health`);
    assert.equal(health.status, 200, 'the peer must serve again after the tree is resumed');
    const resumedMessages = await waitForMessages(peer, created.id, activeMessages.length + 1, 5000);
    assert.ok(resumedMessages.length > activeMessages.length, 'streaming resumes after resume');

    const aborted = await fetch(`${peer.base}/session/${created.id}/abort`, { method: 'POST' });
    assert.equal(aborted.status, 200);
    assert.equal(await aborted.json(), true);
  });
});

test('opencode non-cooperative peer ignores abort until its tree is terminated', async () => {
  await withOpencode('non-cooperative', async (peer, workspace) => {
    const created = await (await opencodeSession(peer)).json();
    assert.equal((await opencodePrompt(peer, created.id, 'run the fixture writer')).status, 200);
    const pending = await opencodePending(peer);
    assert.equal(pending.pending.length, 1);
    assert.equal(pending.pending[0].kind, 'execute');
    assert.deepEqual(await (await opencodeDecision(peer, 'proceed_once')).json(), {
      status: 'written',
      writes: 1,
      optionId: 'proceed_once',
    });
    const state = await opencodeState(peer);
    assert.ok(Number.isInteger(state.writerPid) && state.writerPid > 0, 'the writer must be a real descendant');
    const ticks = join(workspace, 'ticks.log');
    await waitForGrowth(ticks, 0, 5000);
    const beforeAbort = await waitForMessages(peer, created.id, 2, 5000);

    const aborted = await fetch(`${peer.base}/session/${created.id}/abort`, { method: 'POST' });
    assert.equal(aborted.status, 200);
    assert.equal(await aborted.json(), true);
    const ignored = await peer.waitFor((p) => p.stderrRecords().find((entry) => entry.type === 'peer.abort_ignored'), {
      label: 'abort_ignored record',
    });
    assert.deepEqual(ignored, { type: 'peer.abort_ignored', sessionId: created.id });

    const bytesAtAbort = fileSize(ticks);
    const grown = await waitForGrowth(ticks, bytesAtAbort, 5000);
    assert.ok(grown.size > bytesAtAbort, 'the writer keeps writing after the ignored abort');
    const streamed = await waitForMessages(peer, created.id, beforeAbort.length + 1, 5000);
    assert.ok(streamed.length > beforeAbort.length, 'the message stream keeps flowing after the ignored abort');
    assert.equal(peer.exit, null, 'the non-cooperative peer survives the abort');

    await terminateTree(peer.pid);
    await peer.waitExit({ timeoutMs: 5000 });
    assert.equal(isAlive(state.writerPid), false, 'the cancelled descendant must be gone');
    const frozen = fileSize(ticks);
    await sleep(600);
    assert.equal(fileSize(ticks), frozen, 'bytes must stay frozen after the tree is terminated');
  });
});

test('opencode peer refuses a non-numeric --port with the usage exit code', async () => {
  const workspace = await freshWorkspace('invalid-port');
  const peer = spawnPeer(OPENCODE, {
    scenario: 'complete',
    workspace,
    args: ['serve', '--hostname', '127.0.0.1', '--port', 'http'],
  });
  try {
    const exit = await peer.waitExit({ timeoutMs: 5000 });
    assert.equal(exit.code, EXIT_CODES.usage);
    assert.deepEqual(peer.stderrRecords(), [{ type: 'peer.invalid_port', flag: '--port', value: 'http' }]);
    assert.equal(
      peer.stderrRecords().some((entry) => entry.type === 'peer.listening'),
      false,
      'the peer must not bind before the port is validated',
    );
  } finally {
    await peer.stop();
    await removeWorkspace(workspace);
  }
});

test('opencode peer scripts invalid auth, unsupported model and handshake failure', async () => {
  await withOpencode('invalid-auth', async (peer) => {
    const session = await opencodeSession(peer);
    assert.equal(session.status, 401);
    assert.deepEqual(await session.json(), { error: 'unauthorized', message: 'invalid authentication' });
  });
  await withOpencode('unsupported-model', async (peer) => {
    const created = await (await opencodeSession(peer)).json();
    const message = await fetch(`${peer.base}/session/${created.id}/message`, {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify({ model: 'fixture-missing-model', parts: [{ type: 'text', text: 'x' }] }),
    });
    assert.equal(message.status, 400);
    assert.deepEqual(await message.json(), {
      error: {
        name: 'APIError',
        data: {
          message: 'Unsupported model id: fixture-missing-model',
          statusCode: 400,
          isRetryable: false,
        },
      },
    });
  });
  await withOpencode('handshake-failure', async (peer) => {
    const health = await fetch(`${peer.base}/global/health`);
    assert.equal(health.status, 503);
    assert.deepEqual(await health.json(), { healthy: false, version: '1.14.31' });
  });
});

test('opencode peer scripts malformed bodies, timeouts and mid-body disconnects', async () => {
  await withOpencode('malformed-frame', async (peer) => {
    const created = await (await opencodeSession(peer)).json();
    const message = await fetch(`${peer.base}/session/${created.id}/message`, {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify({ parts: [{ type: 'text', text: 'x' }] }),
    });
    assert.equal(message.status, 200);
    assert.equal(await message.text(), '{"info":{"id":"msg_');
  });
  await withOpencode('timeout', async (peer) => {
    const created = await (await opencodeSession(peer)).json();
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 800);
    await assert.rejects(
      () => fetch(`${peer.base}/session/${created.id}/message`, {
        method: 'POST',
        headers: jsonHeaders(),
        body: JSON.stringify({ parts: [{ type: 'text', text: 'x' }] }),
        signal: controller.signal,
      }),
      (error) => error.name === 'AbortError',
      'the caller timeout must be the failure, not a refused connection',
    );
    clearTimeout(timer);
    const health = await fetch(`${peer.base}/global/health`);
    assert.equal(health.status, 200, 'the hung server stays alive');
  });
  await withOpencode('disconnect', async (peer) => {
    const created = await (await opencodeSession(peer)).json();
    await assert.rejects(
      () => fetch(`${peer.base}/session/${created.id}/message`, {
        method: 'POST',
        headers: jsonHeaders(),
        body: JSON.stringify({ parts: [{ type: 'text', text: 'x' }] }),
      }).then((response) => response.text()),
      (error) =>
        error.name === 'TypeError' &&
        ['UND_ERR_SOCKET', 'ECONNRESET', 'UND_ERR_SOCKET_HANG_UP'].includes(error.cause?.code),
      'the mid-body socket destroy must be the failure, not a refused connection',
    );
  });
});

test('opencode background writer survives the message response and stops with its tree', async () => {
  await withOpencode('background-writer', async (peer, workspace) => {
    const created = await (await opencodeSession(peer)).json();
    await fetch(`${peer.base}/session/${created.id}/message`, {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify({ parts: [{ type: 'text', text: 'start writer' }] }),
    });
    const pending = await (await fetch(`${peer.base}/__peer/pending`, { headers: controlHeaders() })).json();
    assert.equal(pending.pending.length, 1);
    assert.equal(pending.pending[0].kind, 'execute');
    assert.equal(pending.pending[0].command, 'node spawn-writer.mjs ticks.log');
    const allowed = await fetch(`${peer.base}/__peer/decision`, {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify({ optionId: 'proceed_once' }),
    });
    assert.deepEqual(await allowed.json(), { status: 'written', writes: 1, optionId: 'proceed_once' });
    const state = await (await fetch(`${peer.base}/__peer/state`, { headers: controlHeaders() })).json();
    assert.ok(Number.isInteger(state.writerPid) && state.writerPid > 0, 'the peer must report the writer pid');
    assert.equal(state.writes, 1);
    assert.equal(state.decisions, 1);
    const ticks = join(workspace, 'ticks.log');
    const first = await waitForGrowth(ticks, 0, 5000);
    const later = await waitForGrowth(ticks, first.size, 5000);
    assert.ok(later.size > first.size, 'the writer keeps writing after the message response');
    await terminateTree(state.writerPid);
    assert.equal(isAlive(state.writerPid), false);
    const frozen = fileSize(ticks);
    await sleep(600);
    assert.equal(fileSize(ticks), frozen);
  });
});

// ------------------------------------------------ task 11 contract scenarios

// The three scenarios below were added for the native core session adapters
// (task 11). Their envelopes are the recorded ones; the values are
// fixture-defined, exactly like the failure texts Task 7 introduced. They are
// pinned here so the adapters' contract test can rely on the peer shapes.

test('qoder peer reports the fixture usage counters and the observed model exactly', async () => {
  await withQoder('reported-usage', async (peer, workspace) => {
    const { promptId } = await qoderSession(peer, { promptText: 'Reply with the fixture completion' });
    const result = await peer.waitFor((p) => p.frames.find((frame) => frame.id === promptId && frame.result), {
      label: 'prompt result',
    });

    assert.equal(result.result.stopReason, 'end_turn');
    assert.deepEqual(result.result.usage, { inputTokens: 12, outputTokens: 7, totalTokens: 19 });
    assert.deepEqual(result.result._meta.quota, {
      token_count: { input_tokens: 12, output_tokens: 7 },
      model_usage: [{ model: 'efficient', token_count: { input_tokens: 12, output_tokens: 7 } }],
    });
    const chunks = peer.frames.filter(
      (frame) => frame.params?.update?.sessionUpdate === 'agent_message_chunk',
    );
    assert.deepEqual(chunks.map((frame) => frame.params.update.content.text), ['fixture-complete']);
    assert.deepEqual(await snapshotDirectory(workspace), [], 'the scenario performs no fixture write');
  });
});

test('qoder peer omits usage and quota when the scenario reports them unknown', async () => {
  await withQoder('unknown-usage', async (peer, workspace) => {
    const { promptId } = await qoderSession(peer, { promptText: 'Reply with the fixture completion' });
    const result = await peer.waitFor((p) => p.frames.find((frame) => frame.id === promptId && frame.result), {
      label: 'prompt result',
    });

    assert.equal(result.result.stopReason, 'end_turn');
    assert.deepEqual(Object.keys(result.result).sort(), ['stopReason', 'userMessageId']);
    const chunks = peer.frames.filter(
      (frame) => frame.params?.update?.sessionUpdate === 'agent_message_chunk',
    );
    assert.deepEqual(chunks.map((frame) => frame.params.update.content.text), ['fixture-usage-unknown']);
    assert.deepEqual(await snapshotDirectory(workspace), [], 'the scenario performs no fixture write');
  });
});

test('qoder peer echoes the exact prompt text of every turn', async () => {
  await withQoder('two-turn-nonce', async (peer, workspace) => {
    const first = await qoderSession(peer, { promptText: 'nonce-first' });
    await peer.waitFor((p) => p.frames.find((frame) => frame.id === first.promptId && frame.result), {
      label: 'first result',
    });

    const secondText = 'fixture system material\n\nuser: nonce-first\nassistant: nonce-first\n\nnonce-second';
    peer.send({
      jsonrpc: '2.0',
      id: 5,
      method: 'session/prompt',
      params: { sessionId: first.sessionId, prompt: [{ type: 'text', text: secondText }] },
    });
    await peer.waitFor((p) => p.frames.find((frame) => frame.id === 5 && frame.result), {
      label: 'second result',
    });

    const chunks = peer.frames.filter(
      (frame) => frame.params?.update?.sessionUpdate === 'agent_message_chunk',
    );
    assert.deepEqual(chunks.map((frame) => frame.params.update.content.text), ['nonce-first', secondText]);
    assert.equal(permissionFrames(peer).length, 0, 'the scenario requests no permission');
    assert.deepEqual(await snapshotDirectory(workspace), [], 'the scenario performs no fixture write');
  });
});

test('opencode peer reports the fixture counters and the observed model exactly', async () => {
  await withOpencode('reported-usage', async (peer, workspace) => {
    const created = await opencodeSession(peer);
    const session = await created.json();
    const response = await fetch(`${peer.base}/session/${session.id}/message`, {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify({ model: 'efficient', parts: [{ type: 'text', text: 'Reply with the fixture completion' }] }),
    });
    const body = await response.json();

    assert.equal(response.status, 200);
    assert.equal(body.info.tokens.input, 12);
    assert.equal(body.info.tokens.output, 7);
    assert.equal(body.info.modelID, 'efficient');
    assert.deepEqual(body.parts, [{ type: 'text', text: 'fixture-complete' }]);
    assert.deepEqual(await snapshotDirectory(workspace), [], 'the scenario performs no fixture write');
  });
});

test('opencode peer omits tokens and modelID when the scenario reports them unknown', async () => {
  await withOpencode('unknown-usage', async (peer) => {
    const created = await opencodeSession(peer);
    const session = await created.json();
    const response = await fetch(`${peer.base}/session/${session.id}/message`, {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify({ model: 'efficient', parts: [{ type: 'text', text: 'Reply with the fixture completion' }] }),
    });
    const body = await response.json();

    assert.equal('tokens' in body.info, false, 'an unreported counter must stay absent');
    assert.equal('modelID' in body.info, false, 'an unreported model must stay absent');
    assert.deepEqual(body.parts, [{ type: 'text', text: 'fixture-usage-unknown' }]);
  });
});

test('opencode peer records the exact message request and echoes the received text', async () => {
  await withOpencode('two-turn-nonce', async (peer, workspace) => {
    const created = await opencodeSession(peer);
    const session = await created.json();

    const first = await (await fetch(`${peer.base}/session/${session.id}/message`, {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify({ model: 'efficient', parts: [{ type: 'text', text: 'nonce-first' }] }),
    })).json();
    assert.deepEqual(first.parts, [{ type: 'text', text: 'nonce-first' }]);

    const secondRequest = {
      model: 'efficient',
      system: 'fixture system material',
      parts: [
        { type: 'text', text: 'user: nonce-first\nassistant: nonce-first' },
        { type: 'text', text: 'nonce-second' },
      ],
    };
    const second = await (await fetch(`${peer.base}/session/${session.id}/message`, {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify(secondRequest),
    })).json();
    assert.deepEqual(second.parts, [
      { type: 'text', text: 'user: nonce-first\nassistant: nonce-first\nnonce-second' },
    ]);

    const requests = peer.stderrRecords().filter((record) => record.type === 'peer.message_request');
    assert.equal(requests.length, 2);
    assert.deepEqual(requests[0].body, { model: 'efficient', parts: [{ type: 'text', text: 'nonce-first' }] });
    assert.deepEqual(requests[1].body, secondRequest);
    assert.deepEqual(await snapshotDirectory(workspace), [], 'the scenario performs no fixture write');
  });
});
