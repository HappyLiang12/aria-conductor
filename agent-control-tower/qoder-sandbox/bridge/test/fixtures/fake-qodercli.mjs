/**
 * Committed fake `qodercli` for the B3a vitest suite (plan Task B3a, Step 1).
 *
 * It speaks scripted NDJSON over stdin/stdout exactly like the observed real CLI
 * (`qodercli --acp`, evidence: e2e/qoder/slice-a/03-mcp-auth.md and 04-permissions.md),
 * so the tests can pin the client's outward sequence, message shapes and reply nesting
 * without a credential:
 *
 *  - The CLI numbers its OWN JSON-RPC requests from 0 per session (A4 concern 3), so for
 *    the permission scenarios this fixture reuses the id of the client's pending
 *    `session/prompt` request as the id of its `session/request_permission` request.
 *    A client that dispatches by JSON-RPC id instead of by "has `method`?" consumes the
 *    permission request as the prompt response and breaks immediately.
 *  - `session/request_permission` offers the observed option kinds (`allow_always`
 *    first, then `allow_once`, then `reject_once`) but deliberately uses ids that were
 *    never observed (`zz_*`), so option selection must happen strictly by `kind`.
 *  - The prompt response carries only the A5-observed model attestation
 *    (`result._meta.quota.model_usage[0].model`) plus zero usage counters; zero counters
 *    are unavailable accounting recorded as-is, never a free-run proof.
 *
 * Started by the tests as `node test/fixtures/fake-qodercli.mjs <scenario>` through
 * `process.execPath` (no shebang reliance, Windows/Git Bash friendly). It receives only
 * the allowlisted child environment and never a real credential (the synthetic
 * `test-worker-token` is reported as a key name / presence flag only).
 *
 * Scenarios: happy | deny | allow-always-only | unsupported | cancel | exit-early |
 * exit-mid-turn | handshake-error | unknown-model | mode-escalation |
 * session-new-escalation | silent | sigterm-ignored | stderr-token | stderr-token-split |
 * stderr-plain
 *
 * Synthetic-input warning (F6): the `initialize` and `session/new` result payloads below
 * contain fixture-invented scaffolding fields (`agentInfo`, `authMethods`,
 * `agentCapabilities`, `modes.availableModes`) that NO gate documents. Slice A evidence
 * backs only `protocolVersion` (as an input), `sessionId`, `currentModeId`, the `modelId`
 * menu, `_meta.qoder.toolName` and `title:null`; tests must not cite the synthetic fields
 * as evidence about the real CLI.
 */
import process from 'node:process';

const scenario = process.argv[2] ?? 'happy';

const out = message => process.stdout.write(`${JSON.stringify({ jsonrpc: '2.0', ...message })}\n`);
const notify = (method, params) => out({ method, params });
const respond = (id, result) => out({ id, result });
const respondError = (id, code, message) => out({ id, error: { code, message } });

const SESSION_ID = 'sess-1';
const MODEL_MENU = [{ modelId: 'auto' }, { modelId: 'ultimate' }, { modelId: 'performance' }, { modelId: 'efficient' }];
const PAID_MODEL_MENU = [{ modelId: 'auto' }, { modelId: 'performance' }];
const OPTION_MENU = [
  { optionId: 'zz_always', name: 'Allow for this session', kind: 'allow_always' },
  { optionId: 'zz_once', name: 'Allow', kind: 'allow_once' },
  { optionId: 'zz_reject', name: 'Reject', kind: 'reject_once' },
];
const ALWAYS_ONLY_MENU = [{ optionId: 'zz_always', name: 'Allow for this session', kind: 'allow_always' }];
const ZERO_USAGE = { inputTokens: 0, outputTokens: 0, totalTokens: 0 };
const ZERO_QUOTA = {
  token_count: { input_tokens: 0, output_tokens: 0 },
  model_usage: [{ model: 'efficient', token_count: { input_tokens: 0, output_tokens: 0 } }],
};

const facts = {
  // slice(1): the test asserts the spawned argv verbatim (`node <fixture> <scenario>`),
  // which also proves no shell wrapped the command.
  argv: process.argv.slice(1),
  sessionNewParams: null,
  setModelSeen: false,
  modelIdSeen: null,
  permissionRequestId: null,
  permissionReplySeen: false,
  permissionReplyRaw: null,
  cancelSeen: false,
  cancelRaw: null,
  cancelHadId: null,
  unsupportedErrorSeen: false,
  unsupportedResultPresent: null,
  unsupportedRaw: null,
};

let finished = false;
let promptRequestId = null;
/** String(id) -> kind of the request THIS fixture sent; responses route by kind. */
const pendingCliRequests = new Map();

function finish(note) {
  if (finished) {
    return;
  }
  finished = true;
  notify('fixture/done', { scenario, note, facts });
}

function toolCall() {
  return {
    toolCallId: 'call_1',
    kind: 'edit',
    status: 'pending',
    // A4 observed `title: null` on permission requests in the pinned Linux build.
    rawInput: { file_path: '/workspace/hello.txt', content: 'hi' },
    _meta: { qoder: { toolName: 'Write' } },
  };
}

function issuePermissionRequest(permissionId, menu) {
  facts.permissionRequestId = permissionId;
  pendingCliRequests.set(String(permissionId), 'permission');
  notify('session/update', {
    sessionId: SESSION_ID,
    update: { sessionUpdate: 'tool_call', title: null, ...toolCall() },
  });
  out({
    id: permissionId,
    method: 'session/request_permission',
    params: { sessionId: SESSION_ID, title: null, toolCall: toolCall(), options: menu },
  });
}

function finishTurn() {
  notify('session/update', {
    sessionId: SESSION_ID,
    update: {
      sessionUpdate: 'tool_call_update',
      toolCallId: 'call_1',
      status: 'completed',
      rawOutput: 'File created successfully at: /workspace/hello.txt',
    },
  });
  notify('session/update', {
    sessionId: SESSION_ID,
    update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'hi' } },
  });
  respond(promptRequestId, { stopReason: 'end_turn', usage: ZERO_USAGE, _meta: { quota: ZERO_QUOTA } });
  finish('turn-completed');
}

function onClientRequest(message) {
  if (scenario === 'silent') {
    // F1: a wedged CLI — it starts (fixture/hello above) but never answers anything, so
    // the client's bounded handshake must time out; `close()` must still kill this child
    // (it dies on SIGTERM like any node process).
    return;
  }
  switch (message.method) {
    case 'initialize': {
      if (scenario === 'exit-early') {
        process.exit(3);
      }
      if (scenario === 'handshake-error') {
        respondError(message.id, -32000, 'Authentication required: Authentication is required.');
        return;
      }
      // F6 SCAFFOLDING: `agentInfo`, `authMethods` and `agentCapabilities` (including
      // `mcpCapabilities.sse`) are invented for this fixture — no gate documents them.
      // A4/A5 evidence backs only `protocolVersion` (as an INPUT), `sessionId`,
      // `currentModeId`, the `modelId` menu, `_meta.qoder.toolName` and `title:null`.
      // Scenario assertions must not lean on the synthetic fields as evidence about the
      // real CLI.
      respond(message.id, {
        protocolVersion: 1,
        agentInfo: { name: 'qoder-cli', title: 'Qoder CLI (fixture)', version: '1.1.41-fixture' },
        authMethods: [{ id: 'qodercli-login', name: 'Use qodercli login' }],
        agentCapabilities: {
          loadSession: true,
          promptCapabilities: { image: true, embeddedContext: true },
          mcpCapabilities: { http: true, sse: true },
        },
      });
      return;
    }
    case 'session/new': {
      facts.sessionNewParams = message.params ?? null;
      // F6 SCAFFOLDING: `modes.availableModes` is invented; only `sessionId` and
      // `currentModeId` are gate-backed (see the initialize comment above), and the
      // `session-new-escalation` variant reports the A2 config-isolation drift (F2).
      respond(message.id, {
        sessionId: SESSION_ID,
        modes: {
          currentModeId: scenario === 'session-new-escalation' ? 'acceptEdits' : 'default',
          availableModes: [{ id: 'default' }],
        },
        models: { availableModels: scenario === 'unknown-model' ? PAID_MODEL_MENU : MODEL_MENU },
      });
      if (scenario === 'unknown-model') {
        // Bounded window: a client that ignored the advertised menu would send
        // session/set_model here and the fact would flip before the report.
        setTimeout(() => finish('no-set-model'), 300);
      }
      return;
    }
    case 'session/set_model': {
      facts.setModelSeen = true;
      facts.modelIdSeen = message.params?.modelId ?? null;
      respond(message.id, {});
      return;
    }
    case 'session/prompt': {
      promptRequestId = message.id;
      if (scenario === 'exit-mid-turn') {
        process.exit(4);
      }
      if (scenario === 'mode-escalation') {
        notify('session/update', {
          sessionId: SESSION_ID,
          update: { sessionUpdate: 'current_mode_update', currentModeId: 'acceptEdits' },
        });
        return;
      }
      if (scenario === 'cancel') {
        issuePermissionRequest(message.id, OPTION_MENU);
        return;
      }
      if (scenario === 'allow-always-only') {
        // A non-numeric JSON-RPC id: the client must echo it back verbatim.
        issuePermissionRequest('cli-perm-77', ALWAYS_ONLY_MENU);
        return;
      }
      issuePermissionRequest(message.id, OPTION_MENU);
      return;
    }
    case 'session/cancel': {
      // A4 CANCEL-METHOD-DECISION: the notification form aborts the pending turn
      // (observed prompt response ~274-480 ms later); the request form is -32601.
      facts.cancelSeen = true;
      facts.cancelHadId = message.id !== undefined;
      setTimeout(() => {
        respond(promptRequestId, { stopReason: 'cancelled', usage: ZERO_USAGE, _meta: { quota: ZERO_QUOTA } });
        finish('cancelled');
      }, 40);
      return;
    }
    default:
      return;
  }
}

function onClientResponse(message, raw) {
  const kind = pendingCliRequests.get(String(message.id));
  if (kind === 'permission') {
    facts.permissionReplySeen = true;
    facts.permissionReplyRaw = raw;
    if (scenario === 'allow-always-only') {
      finish('cancelled-reply-observed');
      return;
    }
    finishTurn();
    return;
  }
  if (kind === 'unsupported') {
    facts.unsupportedRaw = raw;
    facts.unsupportedResultPresent = Object.prototype.hasOwnProperty.call(message, 'result');
    facts.unsupportedErrorSeen = Boolean(message.error && message.error.code === -32601 && !facts.unsupportedResultPresent);
    finish('unsupported-refused');
    return;
  }
  // A response-ish message for a request this fixture never sent: ignored.
}

function handle(message, raw) {
  if (message.method === undefined) {
    onClientResponse(message, raw);
    return;
  }
  onClientRequest(message);
}

notify('fixture/hello', {
  scenario,
  argv: facts.argv,
  pid: process.pid,
  ppid: process.ppid,
  cwd: process.cwd(),
  envKeys: Object.keys(process.env).sort(),
  tokenPresent: typeof process.env.QODER_PERSONAL_ACCESS_TOKEN === 'string',
});

if (scenario === 'unsupported') {
  // A client-facing method the bridge does not implement (fs/read_text_file class);
  // it must receive an explicit JSON-RPC error, never a fabricated success.
  const id = 0; // the CLI numbers its own requests from 0 per session (A4)
  pendingCliRequests.set(String(id), 'unsupported');
  out({ id, method: 'fs/read_text_file', params: { path: '/workspace/notes.txt' } });
}

if (scenario === 'sigterm-ignored') {
  // F3: ignore SIGTERM and keep heartbeating, so the test can observe that the child is
  // still alive inside the grace window and dies only via the client's SIGKILL
  // escalation. (Node on Windows cannot receive a real SIGTERM — the POSIX-only test is
  // skipped there.)
  process.on('SIGTERM', () => {
    notify('fixture/sigterm-received', { pid: process.pid });
  });
  setInterval(() => notify('fixture/alive', { pid: process.pid }), 20);
}

if (scenario === 'stderr-token') {
  // F4: echo the allowlisted PAT (read from the child env at runtime — never hardcoded)
  // on stderr; the client must redact it before it reaches `stderr` events / stderrTail.
  process.stderr.write(
    `qodercli: auth failed for token=${process.env.QODER_PERSONAL_ACCESS_TOKEN ?? ''} (fixture)\n`,
  );
}

if (scenario === 'stderr-token-split') {
  // F4 follow-up: write the PAT in two pieces separated by a delay, so Node delivers two
  // stderr chunks; the client must carry the partial token across them and never emit
  // either half unredacted. A third (truncated) write leaves a partial prefix in the
  // carry, proving the exit flush emits held text instead of silently dropping it. The
  // token is read from the child env at runtime — never hardcoded.
  const token = process.env.QODER_PERSONAL_ACCESS_TOKEN ?? '';
  const splitAt = Math.ceil(token.length / 2);
  process.stderr.write(`qodercli: auth failed for token=${token.slice(0, splitAt)}`);
  setTimeout(() => {
    process.stderr.write(`${token.slice(splitAt)} (fixture)\n`);
  }, 100);
  setTimeout(() => {
    process.stderr.write(`retrying with token=${token.slice(0, splitAt)}`);
  }, 200);
}

if (scenario === 'stderr-plain') {
  // F4 follow-up: ordinary stderr with no token anywhere must pass through complete and
  // unmodified. The last character IS the token's first character (a one-character proper
  // prefix), so the client carries it until exit; it must be flushed, not swallowed.
  const token = process.env.QODER_PERSONAL_ACCESS_TOKEN ?? '';
  process.stderr.write('qodercli: warning: plain diagnostic line\n');
  process.stderr.write(`ends with a ${token.slice(0, 1)}`);
}

let buffer = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', text => {
  buffer += text;
  let index;
  while ((index = buffer.indexOf('\n')) >= 0) {
    const raw = buffer.slice(0, index);
    buffer = buffer.slice(index + 1);
    if (raw.trim() === '') {
      continue;
    }
    notify('fixture/recv', { raw });
    let message;
    try {
      message = JSON.parse(raw);
    } catch {
      continue;
    }
    handle(message, raw);
  }
});
process.stdin.on('end', () => process.exit(0));
