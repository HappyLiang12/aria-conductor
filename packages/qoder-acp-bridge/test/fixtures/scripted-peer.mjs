#!/usr/bin/env node
// Scripted ACP peer for the bridge event-contract tests.
//
// It speaks the recorded newline-delimited JSON-RPC framing of the pinned Qoder
// CLI (initialize -> session/new -> session/set_model -> session/prompt) but
// scripts exactly the payload shapes the bridge contract tests need:
//
//   - a tool call whose rawInput carries credential-shaped keys (F7/F17
//     redaction and input pass-through),
//   - a permission request offering the recorded allow-always / allow-once /
//     reject option kinds (F7: the option list is surfaced unchanged),
//   - a second, oversized tool call input and a failed result (F17: payloads
//     are capped and marked, never dropped),
//   - a prompt result carrying provider usage (F9).
//
// The peer writes nothing to the workspace and never contacts a model.
import { randomUUID } from 'node:crypto';

const MODELS = [{ modelId: 'efficient', name: 'efficient', description: 'scripted fixture model' }];
const LARGE_COMMAND = `deploy ${'y'.repeat(20000)}`;
const RESULT_TEXT = 'issue #81 body';

const TOOL_CALL_ID = 'call_scripted_permission_0001';
const LARGE_TOOL_CALL_ID = 'call_scripted_large_0002';

const OPTIONS = [
  { optionId: 'proceed_always', kind: 'allow_always', name: 'Allow for this session' },
  { optionId: 'proceed_once', kind: 'allow_once', name: 'Allow' },
  { optionId: 'cancel', kind: 'reject_once', name: 'Reject' },
];

const PERMISSION_TOOL_CALL = {
  toolCallId: TOOL_CALL_ID,
  status: 'pending',
  title: 'gh issue view 81 --json title,body',
  kind: 'execute',
  rawInput: {
    command: 'gh issue view 81 --json title,body',
    api_key: 'sk-scripted-do-not-leak',
    headers: { Authorization: 'Bearer ghp-scripted-do-not-leak' },
    note: 'visible',
  },
  _meta: { qoder: { toolName: 'Bash' } },
};

let buffer = '';
let sessionId = null;
let promptId = null;
let pendingDecision = null;

function write(frame) {
  process.stdout.write(`${JSON.stringify(frame)}\n`);
}

function update(updatePayload) {
  write({ jsonrpc: '2.0', method: 'session/update', params: { sessionId, update: updatePayload } });
}

function finishPrompt() {
  if (promptId === null) return;
  const id = promptId;
  promptId = null;
  write({
    jsonrpc: '2.0',
    id,
    result: {
      stopReason: 'end_turn',
      userMessageId: randomUUID(),
      usage: { inputTokens: 12, outputTokens: 7, totalTokens: 19 },
      _meta: {
        quota: {
          token_count: { input_tokens: 12, output_tokens: 7 },
          model_usage: [{ model: 'efficient', token_count: { input_tokens: 12, output_tokens: 7 } }],
        },
      },
    },
  });
}

async function runScript() {
  update({ sessionUpdate: 'tool_call', ...PERMISSION_TOOL_CALL });
  const decision = new Promise((resolve) => {
    pendingDecision = resolve;
  });
  write({
    jsonrpc: '2.0',
    id: 0,
    method: 'session/request_permission',
    params: { sessionId, options: OPTIONS, toolCall: PERMISSION_TOOL_CALL },
  });
  const optionId = await decision;
  const allowed = optionId === 'proceed_once';
  update({
    sessionUpdate: 'tool_call_update',
    toolCallId: TOOL_CALL_ID,
    status: allowed ? 'completed' : 'failed',
    content: [{ type: 'content', content: { type: 'text', text: allowed ? RESULT_TEXT : 'permission denied' } }],
    rawOutput: allowed ? RESULT_TEXT : 'permission denied',
  });

  update({
    sessionUpdate: 'tool_call',
    toolCallId: LARGE_TOOL_CALL_ID,
    status: 'pending',
    title: 'deploy',
    kind: 'execute',
    rawInput: { command: LARGE_COMMAND },
    _meta: { qoder: { toolName: 'Bash' } },
  });
  update({
    sessionUpdate: 'tool_call_update',
    toolCallId: LARGE_TOOL_CALL_ID,
    status: 'failed',
    content: [{ type: 'content', content: { type: 'text', text: 'exit 2: deploy failed' } }],
    rawOutput: 'exit 2: deploy failed',
  });

  finishPrompt();
}

function handleFrame(frame) {
  if (frame.method === 'initialize') {
    write({
      jsonrpc: '2.0',
      id: frame.id,
      result: {
        protocolVersion: 1,
        agentInfo: { name: 'qoder-cli', title: 'Qoder CLI', version: '1.1.61' },
        agentCapabilities: {},
      },
    });
    return;
  }
  if (frame.method === 'session/new') {
    sessionId = randomUUID();
    write({
      jsonrpc: '2.0',
      id: frame.id,
      result: { sessionId, models: { availableModels: MODELS, currentModelId: 'efficient' } },
    });
    return;
  }
  if (frame.method === 'session/set_model') {
    write({ jsonrpc: '2.0', id: frame.id, result: {} });
    return;
  }
  if (frame.method === 'session/prompt') {
    promptId = frame.id;
    void runScript();
    return;
  }
  if (frame.method === 'session/cancel') {
    finishPrompt();
    return;
  }
  if (frame.id === 0 && frame.result !== undefined && pendingDecision !== null) {
    const resolve = pendingDecision;
    pendingDecision = null;
    resolve(frame.result?.outcome?.optionId ?? 'cancel');
    return;
  }
  if (frame.id !== undefined) {
    write({ jsonrpc: '2.0', id: frame.id, error: { code: -32601, message: 'Method not found' } });
  }
}

process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => {
  buffer += chunk;
  for (;;) {
    const at = buffer.indexOf('\n');
    if (at < 0) break;
    const line = buffer.slice(0, at).replace(/\r$/, '');
    buffer = buffer.slice(at + 1);
    if (line.trim().length === 0) continue;
    handleFrame(JSON.parse(line));
  }
});
process.stdin.on('end', () => process.exit(0));
