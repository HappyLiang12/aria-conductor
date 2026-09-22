// Entry point of a single run-bound Qoder ACP bridge.
//
// Launch shape (all inputs come from the trusted launcher, never from an HTTP
// caller):
//
//   node dist/main.js \
//     --run-id <uuid> --workspace <abs dir> --model <id> \
//     --cli <abs path to pinned executable> [--cli-arg <arg> ...] \
//     --control-secret-file <abs path> \
//     [--child-env KEY=VALUE ...] \
//     [--credential-env NAME --credential-file <abs path>] \
//     [--worker-mcp-name <name> --worker-mcp-url <url> --worker-mcp-token-file <abs path>] \
//     [--host 127.0.0.1] [--port 0] [--max-frame-bytes N] [--event-buffer-size N] \
//     [--permission-timeout-ms N] [--request-timeout-ms N] [--cancel-confirm-ms N]
//
// The bridge prints exactly one readiness line on stdout:
//   {"type":"bridge.ready","runId":...,"endpoint":"http://127.0.0.1:<port>",...}
// and one error line before a fatal exit. Child stderr is relayed as
// `child.diagnostic` events, never mixed into stdout.
import { existsSync, readFileSync, statSync } from 'node:fs';
import { isAbsolute, resolve } from 'node:path';

import {
  AcpClient,
  BridgeError,
  BRIDGE_ERROR_CODES,
  buildChildEnvironment,
  type AcpClientState,
  type PermissionRequestRecord,
} from './acp-client.js';
import { ControlLedger, EventBuffer, redactText } from './events.js';
import { parseChoice, selectOption } from './permissions.js';
import { createBridgeServer, type BridgeHandlers, type ControlAckBody, type PermissionDecisionBody } from './server.js';

export const EXIT_CODES = {
  ok: 0,
  usage: 64,
  protocol: 65,
  childLost: 69,
  handshake: 70,
  config: 78,
} as const;

const DEFAULT_MAX_FRAME_BYTES = 1048576;
const DEFAULT_EVENT_BUFFER_SIZE = 1000;
const DEFAULT_EVENT_BUFFER_MAX_BYTES = 8388608;
const DEFAULT_PERMISSION_TIMEOUT_MS = 600000;
const DEFAULT_REQUEST_TIMEOUT_MS = 30000;
const DEFAULT_CANCEL_CONFIRM_MS = 5000;
const MIN_REPLAY_BUFFER_SIZE = 8;

// Secrets observed while the launch is parsed and validated. The out-of-band
// failure path (main().catch) prints through the same redaction as every other
// output path, so a launch error that embeds a secret value is never leaked.
const observedSecrets: string[] = [];

interface BridgeOptions {
  runId: string;
  workspace: string;
  model: string;
  cli: string;
  cliArgs: string[];
  controlSecretFile: string;
  childEnv: Record<string, string>;
  credential: { name: string; value: string } | null;
  workerMcp: { name: string; url: string; token: string } | null;
  host: string;
  port: number;
  maxFrameBytes: number;
  eventBufferSize: number;
  eventBufferMaxBytes: number;
  permissionTimeoutMs: number;
  requestTimeoutMs: number;
  cancelConfirmMs: number;
}

export function parseArgs(argv: string[]): BridgeOptions {
  const values = new Map<string, string>();
  const repeated = new Map<string, string[]>();
  const single = new Set([
    'run-id',
    'workspace',
    'model',
    'cli',
    'control-secret-file',
    'credential-env',
    'credential-file',
    'worker-mcp-name',
    'worker-mcp-url',
    'worker-mcp-token-file',
    'host',
    'port',
    'max-frame-bytes',
    'event-buffer-size',
    'event-buffer-max-bytes',
    'permission-timeout-ms',
    'request-timeout-ms',
    'cancel-confirm-ms',
  ]);
  const repeatable = new Set(['cli-arg', 'child-env']);
  function fail(message: string): never {
    throw new BridgeError(BRIDGE_ERROR_CODES.usage, message);
  }
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index] ?? '';
    if (!arg.startsWith('--')) fail(`Unexpected positional argument: ${arg}`);
    const name = arg.slice(2);
    const value: string | undefined = argv[index + 1];
    // Values are taken verbatim, so a trusted launch profile can pass an argv
    // such as ["--acp", "--config-dir", ...] through --cli-arg.
    if (value === undefined) fail(`Missing value for --${name}`);
    index += 1;
    if (single.has(name)) values.set(name, value);
    else if (repeatable.has(name)) {
      const list = repeated.get(name) ?? [];
      list.push(value);
      repeated.set(name, list);
    } else fail(`Unknown flag: --${name}`);
  }
  const requireValue = (name: string): string => {
    const value = values.get(name);
    if (value === undefined) fail(`Missing required flag --${name}`);
    return value;
  };
  const optionalNumber = (name: string, fallback: number, minimum: number): number => {
    const raw = values.get(name);
    if (raw === undefined) return fallback;
    const parsed = Number(raw);
    if (!Number.isInteger(parsed) || parsed < minimum) fail(`--${name} must be an integer >= ${minimum}, got ${raw}`);
    return parsed;
  };
  const childEnv: Record<string, string> = {};
  for (const pair of repeated.get('child-env') ?? []) {
    const at = pair.indexOf('=');
    if (at <= 0) fail(`--child-env must be KEY=VALUE, got ${pair}`);
    childEnv[pair.slice(0, at)] = pair.slice(at + 1);
  }
  const host = values.get('host') ?? '127.0.0.1';
  if (host !== '127.0.0.1' && host !== '::1') fail(`--host must be a loopback address, got ${host}`);

  const credentialEnv = values.get('credential-env') ?? null;
  const credentialFile = values.get('credential-file') ?? null;
  if ((credentialEnv === null) !== (credentialFile === null)) {
    fail('--credential-env and --credential-file must be given together');
  }

  const workerUrl = values.get('worker-mcp-url') ?? null;
  const workerTokenFile = values.get('worker-mcp-token-file') ?? null;
  if ((workerUrl === null) !== (workerTokenFile === null)) {
    fail('--worker-mcp-url and --worker-mcp-token-file must be given together');
  }

  return {
    runId: requireValue('run-id'),
    workspace: requireValue('workspace'),
    model: requireValue('model'),
    cli: requireValue('cli'),
    cliArgs: repeated.get('cli-arg') ?? [],
    controlSecretFile: requireValue('control-secret-file'),
    childEnv,
    credential:
      credentialEnv !== null && credentialFile !== null
        ? { name: credentialEnv, value: readSecretFile(credentialFile, '--credential-file') }
        : null,
    workerMcp:
      workerUrl !== null && workerTokenFile !== null
        ? {
            name: values.get('worker-mcp-name') ?? 'aria-worker',
            url: workerUrl,
            token: readSecretFile(workerTokenFile, '--worker-mcp-token-file'),
          }
        : null,
    host,
    port: optionalNumber('port', 0, 0),
    maxFrameBytes: optionalNumber('max-frame-bytes', DEFAULT_MAX_FRAME_BYTES, 64),
    eventBufferSize: optionalNumber('event-buffer-size', DEFAULT_EVENT_BUFFER_SIZE, MIN_REPLAY_BUFFER_SIZE),
    eventBufferMaxBytes: optionalNumber('event-buffer-max-bytes', DEFAULT_EVENT_BUFFER_MAX_BYTES, 65536),
    permissionTimeoutMs: optionalNumber('permission-timeout-ms', DEFAULT_PERMISSION_TIMEOUT_MS, 0),
    requestTimeoutMs: optionalNumber('request-timeout-ms', DEFAULT_REQUEST_TIMEOUT_MS, 0),
    cancelConfirmMs: optionalNumber('cancel-confirm-ms', DEFAULT_CANCEL_CONFIRM_MS, 1),
  };
}

function readSecretFile(path: string, flag: string): string {
  if (!isAbsolute(path)) throw new BridgeError(BRIDGE_ERROR_CODES.config, `${flag} must be an absolute path: ${path}`);
  if (!existsSync(path) || !statSync(path).isFile()) {
    throw new BridgeError(BRIDGE_ERROR_CODES.config, `${flag} is not a readable file: ${path}`);
  }
  const value = readFileSync(path, 'utf8').trim();
  if (value.length === 0) throw new BridgeError(BRIDGE_ERROR_CODES.config, `${flag} is empty: ${path}`);
  return value;
}

function exitCodeFor(error: BridgeError): number {
  switch (error.code) {
    case BRIDGE_ERROR_CODES.usage:
      return EXIT_CODES.usage;
    case BRIDGE_ERROR_CODES.config:
      return EXIT_CODES.config;
    case BRIDGE_ERROR_CODES.malformedFrame:
    case BRIDGE_ERROR_CODES.frameTooLarge:
      return EXIT_CODES.protocol;
    case BRIDGE_ERROR_CODES.childExited:
      return EXIT_CODES.childLost;
    default:
      return EXIT_CODES.handshake;
  }
}

function extractObservedModel(result: Record<string, unknown>): string | null {
  const meta = result._meta;
  if (!meta || typeof meta !== 'object') return null;
  const quota = (meta as Record<string, unknown>).quota;
  if (!quota || typeof quota !== 'object') return null;
  const usage = (quota as Record<string, unknown>).model_usage;
  if (!Array.isArray(usage) || usage.length === 0) return null;
  const first = usage[0];
  if (!first || typeof first !== 'object') return null;
  const model = (first as Record<string, unknown>).model;
  return typeof model === 'string' ? model : null;
}

async function main(): Promise<void> {
  const options = parseArgs(process.argv.slice(2));
  const workspace = resolve(options.workspace);
  if (!existsSync(workspace) || !statSync(workspace).isDirectory()) {
    throw new BridgeError(BRIDGE_ERROR_CODES.config, `--workspace is not an existing directory: ${options.workspace}`);
  }
  if (!isAbsolute(options.cli) || !existsSync(options.cli)) {
    throw new BridgeError(BRIDGE_ERROR_CODES.config, `--cli is not an existing absolute executable path: ${options.cli}`);
  }
  const controlSecret = readSecretFile(options.controlSecretFile, '--control-secret-file');
  if (controlSecret.length < 16) {
    throw new BridgeError(BRIDGE_ERROR_CODES.config, 'The control secret must be at least 16 characters');
  }
  const secrets = [controlSecret, options.credential?.value ?? '', options.workerMcp?.token ?? ''].filter(
    (secret) => secret.length > 0,
  );
  observedSecrets.splice(0, observedSecrets.length, ...secrets);
  const redact = (text: string): string => redactText(text, secrets);
  const writeStdout = (payload: Record<string, unknown>): void => {
    process.stdout.write(`${redact(JSON.stringify(payload))}\n`);
  };

  const mcpServers: Array<Record<string, unknown>> = options.workerMcp
    ? [
        {
          name: options.workerMcp.name,
          type: 'http',
          url: options.workerMcp.url,
          headers: { Authorization: `Bearer ${options.workerMcp.token}` },
        },
      ]
    : [];
  const events = new EventBuffer({
    capacity: options.eventBufferSize,
    maxBytes: options.eventBufferMaxBytes,
    secrets,
  });
  const ledger = new ControlLedger<ControlAckBody>();
  let deliveryCounter = 0;

  let fatalHandled = false;
  const client = new AcpClient({
    launch: {
      executable: options.cli,
      args: options.cliArgs,
      cwd: workspace,
      env: buildChildEnvironment({
        platform: process.platform,
        base: process.env,
        childEnv: options.childEnv,
        credential: options.credential ?? undefined,
      }),
    },
    workspace,
    model: options.model,
    maxFrameBytes: options.maxFrameBytes,
    requestTimeoutMs: options.requestTimeoutMs,
    permissionTimeoutMs: options.permissionTimeoutMs,
    mcpServers,
    onEvent: (type, payload) => {
      events.push(type, payload);
    },
    onPermissionRequest: (request: PermissionRequestRecord) => {
      events.push('permission.request', {
        requestId: request.requestId,
        sessionId: request.sessionId,
        toolCallId: request.toolCallId,
        toolName: request.toolName,
        toolKind: request.toolKind,
        toolCallTitle: request.toolCallTitle,
        argumentsDigest: request.argumentsDigest,
        options: request.options,
      });
    },
    onFatal: (error) => {
      handleFatal(error);
    },
  });

  let observedModel: string | null = null;
  let activePrompt: { promptId: number; completion: Promise<{ ok: true; stopReason: string | null } | { ok: false; error: unknown }> } | null =
    null;

  const stateView = (): { state: AcpClientState; childAlive: boolean } => ({
    state: client.getState(),
    childAlive: client.isChildAlive(),
  });

  const handlers: BridgeHandlers = {
    health: () => ({
      status: client.getFatalError() ? 'failed' : 'ok',
      state: client.getState(),
      runId: options.runId,
      pid: process.pid,
      childPid: client.getChildPid(),
      childAlive: client.isChildAlive(),
      sessionId: client.getSessionId(),
      model: { requested: options.model, observed: observedModel },
      core: client.getCoreInfo(),
      protocolVersion: client.getProtocolVersion(),
      promptInFlight: activePrompt !== null,
      pendingPermissionRequests: client.pendingPermissionIds(),
      lastSeq: events.lastSeq(),
      retainedFrom: events.retainedFrom(),
    }),

    sessionBinding: (body) => {
      const { state } = stateView();
      if (!client.isChildAlive() || state === 'failed') {
        throw new BridgeError('E_RUNTIME_EXITED', 'The run-owned Qoder runtime has exited');
      }
      if (body !== null) {
        const allowed = new Set(['runId', 'workspace', 'model']);
        for (const key of Object.keys(body)) {
          if (!allowed.has(key)) {
            throw new BridgeError(
              'E_SESSION_OVERRIDE_REJECTED',
              `Session binding rejects field ${JSON.stringify(key)}; the bridge is bound to runId, workspace and model at launch`,
              { field: key },
            );
          }
        }
        for (const key of ['runId', 'workspace', 'model']) {
          if (typeof body[key] !== 'string') {
            throw new BridgeError('E_BINDING_MISMATCH', `Session binding is missing string field ${JSON.stringify(key)}`, {
              field: key,
            });
          }
        }
        if (body.runId !== options.runId) {
          throw new BridgeError('E_BINDING_MISMATCH', `Session binding runId ${String(body.runId)} does not match this bridge`, {
            field: 'runId',
          });
        }
        if (body.model !== options.model) {
          throw new BridgeError(
            'E_BINDING_MISMATCH',
            `Session binding model ${JSON.stringify(body.model)} does not match the pinned model ${options.model}`,
            { field: 'model' },
          );
        }
        if (resolve(body.workspace as string) !== workspace) {
          throw new BridgeError(
            'E_BINDING_MISMATCH',
            `Session binding workspace ${JSON.stringify(body.workspace)} does not match this bridge workspace ${workspace}`,
            { field: 'workspace' },
          );
        }
      }
      return {
        runId: options.runId,
        workspace,
        model: options.model,
        sessionId: client.getSessionId(),
        state: client.getState(),
        core: client.getCoreInfo(),
        protocolVersion: client.getProtocolVersion(),
        observedModel,
      };
    },

    prompt: (body) => {
      if (body === null || typeof body.text !== 'string' || body.text.trim().length === 0) {
        throw new BridgeError('E_BAD_REQUEST', 'Prompt body requires a non-empty "text" string');
      }
      if (!client.isChildAlive()) {
        throw new BridgeError('E_RUNTIME_EXITED', 'The run-owned Qoder runtime has exited');
      }
      if (activePrompt !== null) {
        throw new BridgeError('E_PROMPT_IN_PROGRESS', `A prompt is already in flight (promptId ${activePrompt.promptId})`);
      }
      // A new prompt is a new cancellation epoch: a recorded cancel outcome
      // belongs to the prompt that was live when it was made and must never be
      // replayed for this one (it would report a stale STOPPED/verified ack for
      // a prompt that is still streaming).
      ledger.delete('cancel');
      const handle = client.prompt(body.text);
      events.push('prompt.started', {
        promptId: handle.promptId,
        sessionId: client.getSessionId(),
        text: body.text,
      });
      const completion = handle.result.then(
        (result) => ({ ok: true as const, stopReason: typeof result.stopReason === 'string' ? result.stopReason : null }),
        (error: unknown) => ({ ok: false as const, error }),
      );
      activePrompt = { promptId: handle.promptId, completion };
      handle.result.then(
        (result) => {
          activePrompt = null;
          const stopReason = typeof result.stopReason === 'string' ? result.stopReason : null;
          const model = extractObservedModel(result);
          if (model !== null) observedModel = model;
          events.push('prompt.result', {
            promptId: handle.promptId,
            stopReason,
            usage: result.usage ?? null,
            userMessageId: result.userMessageId ?? null,
            observedModel: model,
          });
          const invalidated = client.invalidatePermissions('cancelled');
          if (invalidated.length > 0) {
            events.push('permission.cancelled', { requestIds: invalidated, reason: 'prompt-settled' });
          }
        },
        (error: unknown) => {
          activePrompt = null;
          const bridgeError = error instanceof BridgeError ? error : new BridgeError('E_INTERNAL', String(error));
          events.push('prompt.error', {
            promptId: handle.promptId,
            code: bridgeError.code,
            message: bridgeError.message,
            details: bridgeError.details,
          });
          const invalidated = client.invalidatePermissions('expired');
          if (invalidated.length > 0) {
            // A failed prompt is not a cancellation: the leftover permissions
            // are expired and the event reason states the failure.
            events.push('permission.cancelled', { requestIds: invalidated, reason: 'prompt-failed' });
          }
        },
      );
      return { promptId: handle.promptId, sessionId: client.getSessionId(), state: 'streaming' };
    },

    control: async (body): Promise<ControlAckBody> => {
      if (body === null || typeof body.action !== 'string') {
        throw new BridgeError('E_BAD_REQUEST', 'Control body requires a string "action"');
      }
      if (body.action === 'pause' || body.action === 'resume') {
        throw new BridgeError(
          'E_PAUSE_UNSUPPORTED',
          'pause/resume is not a native ACP method (nativePauseRpcUsed=false): process-level suspension is performed by the owning backend. The bridge exposes cancellation only.',
          { action: body.action },
        );
      }
      if (body.action !== 'cancel') {
        throw new BridgeError('E_UNSUPPORTED_CONTROL', `Unsupported control action: ${JSON.stringify(body.action)}`, {
          action: body.action,
        });
      }
      const existing = ledger.get('cancel');
      if (existing) return { ...existing, deduplicated: true };
      deliveryCounter += 1;
      const delivery = String(deliveryCounter);
      const prompt = activePrompt;
      let ack: ControlAckBody;
      if (!prompt) {
        ack = {
          action: 'cancel',
          state: 'RUNNING',
          verified: false,
          reason: 'no-prompt-in-flight',
          delivery,
          deduplicated: false,
        };
      } else {
        client.cancel();
        const timeout = new Promise<{ ok: 'timeout' }>((resolveTimeout) => {
          const timer = setTimeout(() => resolveTimeout({ ok: 'timeout' }), options.cancelConfirmMs);
          timer.unref?.();
        });
        const outcome = await Promise.race([prompt.completion, timeout]);
        if (outcome.ok === 'timeout') {
          ack = {
            action: 'cancel',
            state: 'RUNNING',
            verified: false,
            reason: 'core-did-not-confirm-cancellation',
            delivery,
            deduplicated: false,
          };
        } else if (outcome.ok === true && outcome.stopReason === 'cancelled') {
          ack = {
            action: 'cancel',
            state: 'STOPPED',
            verified: true,
            stopReason: 'cancelled',
            delivery,
            deduplicated: false,
          };
        } else if (outcome.ok === true) {
          ack = {
            action: 'cancel',
            state: 'STOPPED',
            verified: false,
            reason: `prompt-settled-with-${outcome.stopReason ?? 'no-stop-reason'}`,
            delivery,
            deduplicated: false,
          };
        } else {
          ack = {
            action: 'cancel',
            state: 'STOPPED',
            verified: false,
            reason: 'child-exited',
            delivery,
            deduplicated: false,
          };
        }
      }
      // Only a cancel delivered against a live prompt is recorded. An idle
      // "no prompt in flight" observation has no prompt identity to be keyed on,
      // and recording it would replay it for every later cancel of the run
      // (including a prompt that is still streaming), permanently disabling
      // cancellation.
      const recorded = prompt ? ledger.record('cancel', ack) : { value: ack, deduplicated: false };
      events.push('control.cancel', { ...ack, deduplicated: recorded.deduplicated });
      return recorded.value;
    },

    permissionResponse: async (body): Promise<PermissionDecisionBody> => {
      if (body === null) {
        throw new BridgeError('E_BAD_REQUEST', 'Permission response body must be a JSON object');
      }
      if ('optionId' in body) {
        throw new BridgeError(
          'E_INVALID_CHOICE',
          'Permission decisions select a choice (ALLOW_ONCE or DENY), not a native optionId',
          { field: 'optionId' },
        );
      }
      let choice: ReturnType<typeof parseChoice>;
      try {
        choice = parseChoice(body.choice);
      } catch (error) {
        throw new BridgeError('E_INVALID_CHOICE', (error as Error).message);
      }
      if (!Number.isInteger(body.requestId)) {
        throw new BridgeError('E_BAD_REQUEST', 'Permission response requires an integer "requestId"');
      }
      const requestId = body.requestId as number;
      const status = client.permissionStatus(requestId);
      if (status === 'answered') {
        const answer = client.permissionAnswer(requestId);
        if (answer && answer.choice === choice) {
          return {
            requestId,
            choice,
            optionId: answer.optionId,
            raw: answer.raw,
            deduplicated: true,
          };
        }
        throw new BridgeError(
          'E_CONTROL_CONFLICT',
          `Permission request ${requestId} was already answered with ${answer ? answer.choice : 'another choice'}; cannot change it to ${choice}`,
          { requestId },
        );
      }
      if (status !== 'pending') {
        throw new BridgeError(
          'E_UNKNOWN_REQUEST',
          `No pending permission request ${requestId} (status: ${status ?? 'unknown'})`,
          { requestId, status },
        );
      }
      const pendingOptions = client.pendingPermissionOptions(requestId);
      let optionId: string;
      try {
        optionId = selectOption(pendingOptions, choice);
      } catch (error) {
        throw new BridgeError('E_OPTION_UNAVAILABLE', (error as Error).message, {
          requestId,
          options: pendingOptions,
        });
      }
      const raw = client.respondPermission(requestId, optionId, choice);
      events.push('permission.response', { requestId, choice, optionId, raw });
      return { requestId, choice, optionId, raw, deduplicated: false };
    },
  };

  const server = createBridgeServer({ secret: controlSecret, events, handlers });

  function handleFatal(error: BridgeError): void {
    if (fatalHandled) return;
    fatalHandled = true;
    events.push('bridge.error', { code: error.code, message: error.message, ...error.details });
    writeStdout({ type: 'bridge.error', code: error.code, message: error.message, ...error.details });
    client.kill();
    server.close();
    setTimeout(() => process.exit(exitCodeFor(error)), 150);
  }

  try {
    await client.start();
  } catch (error) {
    handleFatal(error instanceof BridgeError ? error : new BridgeError('E_INTERNAL', String(error)));
    return;
  }

  await new Promise<void>((resolveListen, rejectListen) => {
    server.once('error', rejectListen);
    server.listen({ host: options.host, port: options.port }, () => resolveListen());
  });
  const address = server.address();
  const port = typeof address === 'object' && address !== null ? address.port : options.port;
  const endpoint = `http://${options.host === '::1' ? '[::1]' : options.host}:${port}`;
  events.push('bridge.ready', {
    runId: options.runId,
    endpoint,
    pid: process.pid,
    sessionId: client.getSessionId(),
    model: options.model,
  });
  writeStdout({
    type: 'bridge.ready',
    runId: options.runId,
    endpoint,
    pid: process.pid,
    sessionId: client.getSessionId(),
    model: options.model,
  });

  const shutdown = (): void => {
    if (fatalHandled) return;
    fatalHandled = true;
    client.kill();
    server.close();
    setTimeout(() => process.exit(EXIT_CODES.ok), 200);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

main().catch((error: unknown) => {
  const bridgeError = error instanceof BridgeError ? error : new BridgeError('E_INTERNAL', String(error));
  const line = redactText(
    JSON.stringify({ type: 'bridge.error', code: bridgeError.code, message: bridgeError.message }),
    observedSecrets,
  );
  process.stderr.write(`${line}\n`);
  process.exit(exitCodeFor(bridgeError));
});
