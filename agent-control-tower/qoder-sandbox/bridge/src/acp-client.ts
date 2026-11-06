/**
 * Minimal ACP (Agent Client Protocol) client for `qodercli --acp` (task B3a).
 *
 * Implements the frozen contracts C0.3 (ACP call sequence) and C0.4 (permission option
 * selection) of docs/superpowers/plans/2026-09-17-qoder-cli-provider.md against the facts
 * observed on the real CLI in Slice A:
 *
 *  - C0.3 step 1 (amended) — spawn `qodercli` with the ACP entry plus the pinned plugin
 *    dir (`DEFAULT_ARGS`), an environment allowlist (`src/env.ts`) and cwd `/workspace`;
 *    never through a shell. F8: on POSIX the CLI also leads its own process group
 *    (`detached`) so `terminate()` can signal the whole tree (design lines 257-260).
 *  - C0.3 steps 2-4 — `initialize {protocolVersion:1}`, `session/new {cwd, mcpServers}`,
 *    `session/set_model {sessionId, modelId}`, each awaited on the matching JSON-RPC
 *    response (response-driven, never fixed delays — spike section 3).
 *  - C0.3 step 5-6 — `session/prompt {sessionId, prompt:[{type:'text',text}]}` and a
 *    permission reply on the CLI's ORIGINAL JSON-RPC id.
 *  - C0.3 step 7 — cancel with the ACP `session/cancel` NOTIFICATION
 *    (`CANCEL_METHOD_DECISION`, A4) and `terminate()` only as the documented fallback.
 *  - C0.4 — option selection strictly by `kind`; `allow_always` is never selected; an
 *    approval without an `allow_once` option fails closed (`UNSUPPORTED_OPTIONS`); an
 *    observed mode escalation stops the run with a governance error.
 *
 * A4 dispatch rule (carried verbatim): the CLI numbers its OWN requests from 0 per
 * session, so numeric id ranges cannot separate CLI requests from client requests.
 * Messages are therefore dispatched by "has `method`?" — a message without `method` is a
 * response to one of our requests, anything else is a client-facing request/notification.
 * The A4 dev loop mis-dispatched a permission request as a response before this rule.
 *
 * A4 reply nesting (exercised four times on the real CLI): the JSON-RPC *result* of a
 * permission reply is nested — `{outcome:{outcome:'selected', optionId}}` — not the flat
 * shorthand of the plan text. The flat `{outcome:'cancelled'}` fallback shape was NOT
 * exercised by any gate and is deliberately not copied (see `sendCancelled`).
 *
 * The prompt completion is delivered as an event (`prompt_result` / `prompt_error`) and is
 * intentionally never timed out here: A5 showed a pending permission can sit for minutes
 * with zero traffic, so the run deadline is enforced by the host clock.
 *
 * Production dependency-free: node built-ins only (B4 copies `dist/` into the image).
 */
import { spawn } from 'node:child_process';
import type { ChildProcessWithoutNullStreams } from 'node:child_process';

import { buildChildEnv } from './env.js';

/** Executable spawned by default (C0.3 step 1). */
export const DEFAULT_COMMAND = 'qodercli';
/**
 * Pinned plugin bundle, loaded explicitly and kept non-writable by the CLI (design §7.2):
 * the root-owned baked copy at `/opt/qoder/plugin` in the `aria-conductor/qoder-sandbox`
 * image (Dockerfile:87). `--plugin-dir` + `--acp` is A3-verified (slice-a/03-mcp-auth.md:78).
 */
export const DEFAULT_PLUGIN_DIR = '/opt/qoder/plugin';
/** argv spawned by default: ACP entry plus the pinned plugin dir (C0.3 step 1, amended). */
export const DEFAULT_ARGS: readonly string[] = ['--acp', '--plugin-dir', DEFAULT_PLUGIN_DIR];
/** Working directory of the CLI process by default (C0.3 step 1). */
export const DEFAULT_CWD = '/workspace';
export const ACP_PROTOCOL_VERSION = 1;
export const JSON_RPC_METHOD_NOT_FOUND = -32601;
/** The CLI stays in `default`; any other current mode is a governance violation (design §3.2). */
export const GOVERNED_CLI_MODE = 'default';
export const DEFAULT_HANDSHAKE_TIMEOUT_MS = 30_000;
export const DEFAULT_KILL_GRACE_MS = 10_000;
const MAX_STDERR_TAIL_CHARS = 4_000;
const MAX_STDOUT_LINE_CHARS = 16 * 1024 * 1024;
const RESOLVED_PERMISSION_MEMORY = 1_000;

/**
 * A4 CANCEL-METHOD-DECISION, verbatim (e2e/qoder/slice-a/04-permissions.md; re-run
 * 2026-09-18):
 *
 *   session/cancel exists as notification only (request form -32601 method not found;
 *   notification accepted; pending turn aborted); fallback=none
 *
 * The request form answered `-32601 Method not found`; the notification form (no id)
 * aborted the pending turn (prompt response with `stopReason:"cancelled"`, observed
 * 274-480 ms after the notification). `cancel()` therefore sends the notification;
 * `terminate()` keeps the SIGTERM/SIGKILL path available as the documented fallback.
 */
export const CANCEL_METHOD_DECISION =
  'session/cancel exists as notification only (request form -32601 method not found; notification accepted; pending turn aborted); fallback=none';

// ---------------------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------------------

export class AcpError extends Error {
  readonly code: string;
  readonly detail?: unknown;

  constructor(message: string, code = 'ACP_ERROR', detail?: unknown) {
    super(message);
    this.name = new.target.name;
    this.code = code;
    this.detail = detail;
  }
}

/** The CLI process could not be started (e.g. ENOENT). */
export class AcpSpawnError extends AcpError {
  constructor(message: string, detail?: unknown) {
    super(message, 'SPAWN_FAILED', detail);
  }
}

/** The CLI process exited before the awaited operation completed. */
export class AcpProcessExitedError extends AcpError {
  constructor(message: string, detail?: unknown) {
    super(message, 'PROCESS_EXITED', detail);
  }
}

/** The client was closed (per run teardown) while an operation was pending. */
export class AcpClosedError extends AcpError {
  constructor(message = 'the ACP client is closed', detail?: unknown, code = 'CLIENT_CLOSED') {
    super(message, code, detail);
  }
}

/** C0.4: the run was stopped by a governance violation (e.g. mode escalation). */
export class GovernanceStopError extends AcpClosedError {
  constructor(message: string, detail?: unknown) {
    super(message, detail, 'GOVERNANCE_STOP');
  }
}

/** A handshake request received no response within the bounded handshake window. */
export class AcpRequestTimeoutError extends AcpError {
  constructor(message: string, detail?: unknown) {
    super(message, 'REQUEST_TIMEOUT', detail);
  }
}

/** The CLI answered with a JSON-RPC protocol error. */
export class AcpRpcError extends AcpError {
  readonly rpcCode: number;

  constructor(method: string, error: { code?: unknown; message?: unknown; data?: unknown }) {
    super(
      `ACP request '${method}' failed: ${error.code ?? 'unknown'} ${typeof error.message === 'string' ? error.message : ''}`.trim(),
      'RPC_ERROR',
      error,
    );
    this.rpcCode = typeof error.code === 'number' ? error.code : 0;
  }
}

/** A CLI response did not have the shape the contract requires. */
export class AcpProtocolError extends AcpError {
  constructor(message: string, detail?: unknown) {
    super(message, 'PROTOCOL_ERROR', detail);
  }
}

/** No pending permission request carries this id (B3b maps it to 404). */
export class UnknownPermissionRequestError extends AcpError {
  constructor(message: string, detail?: unknown) {
    super(message, 'UNKNOWN_PERMISSION_REQUEST', detail);
  }
}

/** The permission request is already resolved/abandoned (B3b maps it to 409). */
export class PermissionAlreadyResolvedError extends AcpError {
  constructor(message: string, detail?: unknown) {
    super(message, 'ALREADY_RESOLVED', detail);
  }
}

/** C0.4: an approval was requested but no `allow_once` option was offered (B3b maps it to 422). */
export class UnsupportedOptionsError extends AcpError {
  constructor(message: string, detail?: unknown) {
    super(message, 'UNSUPPORTED_OPTIONS', detail);
  }
}

// ---------------------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------------------

export interface PermissionOption {
  optionId: string;
  kind: string;
  name?: string;
}

export interface McpServerSpec {
  name: string;
  url: string;
  headers?: Array<{ name: string; value: string }>;
}

export interface SessionSpec {
  /** Absolute workspace of the session (`session/new.cwd`). */
  cwd: string;
  /** Explicit model pin (A2/A4/A5 used `efficient`); never defaulted. */
  modelId: string;
  /** HTTP MCP servers (A3: `{type:'http',name,url,headers}` is the observed valid shape). */
  mcpServers?: McpServerSpec[];
}

export interface AcpClientOptions {
  /** Executable to spawn; production default `qodercli`. Injectable for tests. */
  command?: string;
  /** Full argv for the command; production default `DEFAULT_ARGS`. Injectable for tests. */
  args?: readonly string[];
  /** Working directory of the CLI process; production default `/workspace`. */
  cwd?: string;
  /** Environment source; only `CHILD_ENV_ALLOWLIST` keys are forwarded (default `process.env`). */
  env?: NodeJS.ProcessEnv;
  /** Bounded window for initialize / session/new / session/set_model. Prompts are never timed. */
  handshakeTimeoutMs?: number;
  /** SIGTERM -> SIGKILL grace used by `terminate()` (C0.2: within 10 s by default). */
  killGraceMs?: number;
  /**
   * F8 test seam: whether `terminate()` signals the CLI's whole process group instead of the
   * direct child. Defaults to `process.platform !== 'win32'` — Windows cannot deliver a
   * signal to a group — so a Windows test pins `true` to exercise the POSIX decision with an
   * injected `signalProcess`.
   */
  processGroupKill?: boolean;
  /**
   * F8 test seam: the pid-level signal sink. The default sends a negative pid to
   * `process.kill` (the POSIX group) and a positive pid to the child handle
   * (`child.kill`), which keeps the direct fallback free of pid reuse after a reap.
   */
  signalProcess?: CliSignalSink;
}

export interface SpawnPlan {
  command: string;
  args: string[];
  cwd: string;
  env: Record<string, string>;
  /** Always false: the CLI is spawned without a shell (C0.3 step 1). */
  shell: false;
  /**
   * F8: true on POSIX — the CLI is spawned as its own process-group leader so `terminate()`
   * can signal the whole tree (design lines 257-260, "terminate its process tree"). Windows
   * has no signal-based group kill, so the child stays attached there and `terminate()` uses
   * the direct child.
   */
  detached: boolean;
}

/**
 * F8: the pid-level signal sink used by `terminate()`; a negative pid names a POSIX process
 * group, a positive one a single process. Injectable so a test can pin the group-first
 * ordering on a host that cannot deliver real group signals.
 */
export type CliSignalSink = (pid: number, signal: NodeJS.Signals) => void;

export type AcpEvent =
  | { type: 'child_started'; pid: number | undefined; command: string; args: string[]; cwd: string; envKeys: string[] }
  | { type: 'child_exit'; code: number | null; signal: NodeJS.Signals | null }
  | { type: 'child_error'; message: string }
  | { type: 'stderr'; text: string }
  | { type: 'protocol_error'; message: string; detail?: unknown }
  | { type: 'notification'; method: string; params?: unknown }
  | { type: 'unsupported_request'; requestId: number | string; method: string }
  | { type: 'session_created'; sessionId: string; currentModeId: string | null; availableModels: string[] }
  | { type: 'session_update'; update: Record<string, unknown> }
  | {
      type: 'permission_request';
      requestId: string;
      toolCallId: string | null;
      toolName: string | null;
      title: string | null;
      options: PermissionOption[];
      params: unknown;
    }
  | { type: 'permission_reply'; requestId: string; outcome: 'selected' | 'cancelled'; optionId: string | null }
  | { type: 'prompt_result'; requestId: string; result: unknown }
  | { type: 'prompt_error'; requestId: string; code: string; message: string; rpcCode?: number }
  | { type: 'governance_error'; code: string; message: string; detail?: unknown };

export interface PermissionDecision {
  outcome: 'selected' | 'cancelled';
  optionId: string | null;
}

// ---------------------------------------------------------------------------------------
// Spawn plan
// ---------------------------------------------------------------------------------------

/**
 * Resolve the CLI spawn plan (C0.3 step 1, amended). Tests assert the production default is
 * the `qodercli` command with argv `DEFAULT_ARGS` (`--acp --plugin-dir <DEFAULT_PLUGIN_DIR>`)
 * and cwd `/workspace`; the fixture suite overrides `command`/`args`/`cwd` to run the
 * committed fake CLI through `process.execPath`. F8: `detached` follows the real platform —
 * a POSIX child leads its own process group, a Windows child cannot.
 */
export function resolveSpawnPlan(options: AcpClientOptions = {}): SpawnPlan {
  return {
    command: options.command ?? DEFAULT_COMMAND,
    args: [...(options.args ?? DEFAULT_ARGS)],
    cwd: options.cwd ?? DEFAULT_CWD,
    env: buildChildEnv(options.env ?? process.env),
    shell: false,
    detached: process.platform !== 'win32',
  };
}

// ---------------------------------------------------------------------------------------
// Internals
// ---------------------------------------------------------------------------------------

interface JsonRpcMessage {
  id?: number | string;
  method?: string;
  params?: unknown;
  result?: unknown;
  error?: { code?: unknown; message?: unknown; data?: unknown };
}

interface PendingRequest {
  method: string;
  resolve: (result: unknown) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout | null;
}

interface PendingPermission {
  /** The id as received from the CLI; the reply echoes it verbatim. */
  rawId: number | string;
  requestId: string;
  options: PermissionOption[];
}

/** Keep only well-formed offered options; anything malformed shrinks the menu (fail closed). */
function sanitizeOptions(value: unknown): PermissionOption[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const options: PermissionOption[] = [];
  for (const candidate of value) {
    if (candidate === null || typeof candidate !== 'object') {
      continue;
    }
    const record = candidate as Record<string, unknown>;
    if (typeof record.optionId !== 'string' || typeof record.kind !== 'string') {
      continue;
    }
    const option: PermissionOption = { optionId: record.optionId, kind: record.kind };
    if (typeof record.name === 'string') {
      option.name = record.name;
    }
    options.push(option);
  }
  return options;
}

function selectOptionByKind(options: readonly PermissionOption[], kind: 'allow_once' | 'reject_once'): PermissionOption | null {
  // C0.4: select strictly by `kind`. Ids are never hardcoded (A4 observed
  // proceed_once/proceed_always/cancel but those are evidence, not contract).
  return options.find(candidate => candidate.kind === kind) ?? null;
}

/** Runtime guard for caller-supplied header entries (the spec type is not trusted at runtime). */
function isHeaderEntry(header: unknown): header is { name: string; value: string } {
  if (header === null || typeof header !== 'object') {
    return false;
  }
  const candidate = header as { name?: unknown; value?: unknown };
  return typeof candidate.name === 'string' && typeof candidate.value === 'string';
}

function mcpServersToAcp(servers: readonly McpServerSpec[] | undefined): Array<Record<string, unknown>> {
  if (servers === undefined) {
    return [];
  }
  if (!Array.isArray(servers)) {
    throw new AcpError('session spec mcpServers must be an array', 'INVALID_SESSION_SPEC');
  }
  return servers.map(server => {
    if (
      server === null ||
      typeof server !== 'object' ||
      typeof server.name !== 'string' ||
      server.name === '' ||
      typeof server.url !== 'string' ||
      server.url === ''
    ) {
      throw new AcpError('each mcpServers entry requires a non-empty name and url', 'INVALID_SESSION_SPEC');
    }
    // The observed valid ACP shape needs both `type` and `headers` (spike §5: earlier
    // requests missing them returned invalid-params errors); only `http` was verified.
    const rawHeaders: unknown = server.headers;
    const headers = Array.isArray(rawHeaders)
      ? rawHeaders.filter(isHeaderEntry).map(header => ({ name: header.name, value: header.value }))
      : [];
    return { type: 'http', name: server.name, url: server.url, headers };
  });
}

function extractAvailableModelIds(created: Record<string, unknown> | null): string[] {
  const models = created?.models as Record<string, unknown> | null | undefined;
  const available = models?.availableModels;
  if (!Array.isArray(available)) {
    return [];
  }
  return available
    .map(entry =>
      entry !== null && typeof entry === 'object' ? (entry as Record<string, unknown>).modelId : undefined,
    )
    .filter((modelId): modelId is string => typeof modelId === 'string');
}

function extractCurrentModeId(created: Record<string, unknown> | null): string | null {
  const modes = created?.modes as Record<string, unknown> | null | undefined;
  const currentModeId = modes?.currentModeId;
  return typeof currentModeId === 'string' ? currentModeId : null;
}

/**
 * F4 follow-up: length of the longest suffix of `text` that is a proper prefix of `token`
 * (at most `token.length - 1`, so a complete token at the very end is sized 0 and stays
 * subject to redaction instead of being held). The stderr path withholds exactly those
 * characters so a token split across two pipe chunks cannot be rejoined from the emitted
 * events or the tail; the hold is flushed once the child has exited and its stdio drained
 * (`close`), never on `exit` alone (the last chunk can still arrive after `exit`).
 */
function tokenPrefixHoldLength(text: string, token: string): number {
  const longest = Math.min(token.length - 1, text.length);
  for (let length = longest; length > 0; length--) {
    if (text.endsWith(token.slice(0, length))) {
      return length;
    }
  }
  return 0;
}

// ---------------------------------------------------------------------------------------
// Client
// ---------------------------------------------------------------------------------------

/**
 * One client owns one CLI process (one bridge-owned CLI process/session per active run).
 * Events are delivered to `onEvent` subscribers in arrival order; permission decisions
 * are synchronous (they write one JSON-RPC reply) and throw typed `AcpError`s when the
 * request is unknown, already resolved, or the offered menu cannot satisfy C0.4.
 */
export class AcpClient {
  /** The resolved spawn plan (command/argv/cwd/allowlisted env) used by this client. */
  readonly spawnPlan: SpawnPlan;

  private readonly options: AcpClientOptions;
  private readonly child: ChildProcessWithoutNullStreams;
  /** F8: the pid-level signal sink `terminate()` uses (defaults to group-then-child). */
  private readonly signalProcess: CliSignalSink;
  private readonly handlers = new Set<(event: AcpEvent) => void>();
  private readonly pending = new Map<number, PendingRequest>();
  private readonly pendingPermissions = new Map<string, PendingPermission>();
  private readonly resolvedPermissions = new Map<string, { outcome: string; optionId: string | null }>();
  private nextRequestId = 1;
  private stdoutBuffer = '';
  private stderrText = '';
  /**
   * F4 follow-up: trailing suffix of the redacted stderr stream withheld because it is a
   * proper prefix of the allowlisted PAT (≤ `token.length - 1` chars). Emitted from the
   * child `close` handler (full stdio drain), never earlier; see wireChild.
   */
  private stderrCarry = '';
  private spawnError: Error | null = null;
  private exited = false;
  private exitInfo: { code: number | null; signal: NodeJS.Signals | null } | null = null;
  private closed = false;
  private governanceStopped = false;
  private killTimer: NodeJS.Timeout | null = null;

  constructor(options: AcpClientOptions = {}) {
    this.options = options;
    this.spawnPlan = resolveSpawnPlan(options);
    this.child = spawn(this.spawnPlan.command, this.spawnPlan.args, {
      cwd: this.spawnPlan.cwd,
      env: this.spawnPlan.env,
      stdio: ['pipe', 'pipe', 'pipe'],
      // C0.3 step 1: no shell anywhere — argv reaches the CLI process verbatim.
      shell: false,
      // F8: see SpawnPlan.detached. Never `unref()`ed: the client keeps waiting for the
      // child's exit events, the child just leads its own group on POSIX.
      detached: this.spawnPlan.detached,
    });
    const child = this.child;
    this.signalProcess =
      options.signalProcess ??
      ((pid, signal) => {
        if (pid < 0) {
          process.kill(pid, signal);
        } else {
          // Handle-based kill for the direct child: after a reap a recycled pid must never
          // make the fallback hit an unrelated process.
          child.kill(signal);
        }
      });
    this.wireChild();
    queueMicrotask(() => {
      this.emit({
        type: 'child_started',
        pid: this.child.pid,
        command: this.spawnPlan.command,
        args: this.spawnPlan.args,
        cwd: this.spawnPlan.cwd,
        envKeys: Object.keys(this.spawnPlan.env).sort(),
      });
    });
  }

  // ----- public API -------------------------------------------------------------------

  /**
   * C0.3 steps 2-4: initialize, `session/new` (with the validated MCP servers) and
   * `session/set_model`, each awaited before the next request. The model is taken from
   * the spec and checked against the runtime's advertised list (no silent fallback).
   */
  async createSession(spec: SessionSpec): Promise<{ sessionId: string }> {
    this.assertOpen();
    if (spec === null || typeof spec !== 'object') {
      throw new AcpError('createSession requires a session spec', 'INVALID_SESSION_SPEC');
    }
    if (typeof spec.cwd !== 'string' || spec.cwd.trim() === '') {
      throw new AcpError('createSession requires a non-empty cwd', 'INVALID_SESSION_SPEC');
    }
    if (typeof spec.modelId !== 'string' || spec.modelId.trim() === '') {
      throw new AcpError(
        'createSession requires an explicit modelId (never silently default to a paid model)',
        'INVALID_SESSION_SPEC',
      );
    }

    const initialized = (await this.request('initialize', { protocolVersion: ACP_PROTOCOL_VERSION })) as
      | { protocolVersion?: unknown }
      | null
      | undefined;
    if (initialized === null || typeof initialized !== 'object' || initialized.protocolVersion !== ACP_PROTOCOL_VERSION) {
      throw new AcpProtocolError(
        `initialize returned protocolVersion ${String(initialized?.protocolVersion)}; expected ${ACP_PROTOCOL_VERSION}`,
      );
    }

    const created = (await this.request('session/new', {
      cwd: spec.cwd,
      mcpServers: mcpServersToAcp(spec.mcpServers),
    })) as Record<string, unknown> | null | undefined;
    const sessionId = created?.sessionId;
    if (typeof sessionId !== 'string' || sessionId === '') {
      throw new AcpProtocolError('session/new returned no sessionId');
    }

    // C0.4/F2: the mode reported by `session/new` is enforced exactly like the
    // `session/update` path (A2 hit a CLI image starting in `acceptEdits`): a session
    // created outside the governed mode is stopped before any `session/set_model`, so
    // the caller sees the governance error instead of a confusing AcpClosedError.
    const currentModeId = extractCurrentModeId(created ?? null);
    if (currentModeId !== null && currentModeId !== GOVERNED_CLI_MODE) {
      const message = `mode escalation observed: currentModeId=${currentModeId} (governed mode is '${GOVERNED_CLI_MODE}')`;
      this.stopForGovernance(message, { currentModeId });
      throw new GovernanceStopError(`run stopped by governance: ${message}`, { currentModeId });
    }

    const availableModels = extractAvailableModelIds(created ?? null);
    if (availableModels.length > 0 && !availableModels.includes(spec.modelId)) {
      // C0.1: unknown model id is an explicit provider error; no set_model is sent and
      // no fallback model is chosen.
      throw new AcpError(
        `model '${spec.modelId}' is not among the CLI's advertised models [${availableModels.join(', ')}]`,
        'UNKNOWN_MODEL',
        { availableModels },
      );
    }

    await this.request('session/set_model', { sessionId, modelId: spec.modelId });
    this.emit({
      type: 'session_created',
      sessionId,
      currentModeId: extractCurrentModeId(created ?? null),
      availableModels,
    });
    return { sessionId };
  }

  /**
   * C0.3 step 5: send one `session/prompt`. Completion arrives as a `prompt_result`
   * event (or `prompt_error`); the turn is never timed out here (A5: the run deadline is
   * a host-clock concern, and a pending permission can sit for minutes with no traffic).
   */
  prompt(sessionId: string, text: string): void {
    this.assertOpen();
    if (typeof sessionId !== 'string' || sessionId === '') {
      throw new AcpError('prompt requires a non-empty sessionId', 'INVALID_PROMPT');
    }
    if (typeof text !== 'string' || text === '') {
      throw new AcpError('prompt requires non-empty text', 'INVALID_PROMPT');
    }
    const id = this.nextRequestId++;
    this.pending.set(id, {
      method: 'session/prompt',
      resolve: result => {
        this.emit({ type: 'prompt_result', requestId: String(id), result });
      },
      reject: error => {
        if (error instanceof AcpRpcError) {
          this.emit({
            type: 'prompt_error',
            requestId: String(id),
            code: error.code,
            message: error.message,
            rpcCode: error.rpcCode,
          });
          return;
        }
        this.emit({
          type: 'prompt_error',
          requestId: String(id),
          code: error instanceof AcpError ? error.code : 'ACP_ERROR',
          message: error.message,
        });
      },
      timer: null,
    });
    this.send({ id, method: 'session/prompt', params: { sessionId, prompt: [{ type: 'text', text }] } });
  }

  /** Subscribe to client events; returns an unsubscribe function. */
  onEvent(handler: (event: AcpEvent) => void): () => void {
    this.handlers.add(handler);
    return () => {
      this.handlers.delete(handler);
    };
  }

  /**
   * C0.4 decision for one pending permission request, delivered on the CLI's original
   * JSON-RPC id. `approved` selects the offered `allow_once` option; without one the call
   * throws `UNSUPPORTED_OPTIONS` and sends nothing (the request stays pending so a later
   * denial can still resolve it). `denied` selects `reject_once`, or replies with the
   * nested `cancelled` outcome when no `reject_once` option exists.
   */
  decide(requestId: string, approved: boolean): PermissionDecision {
    this.assertOpen();
    const pending = this.pendingPermissions.get(requestId);
    if (!pending) {
      if (this.resolvedPermissions.has(requestId)) {
        throw new PermissionAlreadyResolvedError(`permission request ${requestId} is already resolved`);
      }
      throw new UnknownPermissionRequestError(`unknown permission request ${requestId}`);
    }
    if (approved) {
      const option = selectOptionByKind(pending.options, 'allow_once');
      if (!option) {
        throw new UnsupportedOptionsError(
          `no allow_once option offered for permission request ${requestId} `
            + `(offered kinds: [${pending.options.map(candidate => candidate.kind).join(', ')}]); failing closed`,
          { offeredKinds: pending.options.map(candidate => candidate.kind) },
        );
      }
      this.sendSelected(pending, option);
      return { outcome: 'selected', optionId: option.optionId };
    }
    const reject = selectOptionByKind(pending.options, 'reject_once');
    if (reject) {
      this.sendSelected(pending, reject);
      return { outcome: 'selected', optionId: reject.optionId };
    }
    this.sendCancelled(pending);
    return { outcome: 'cancelled', optionId: null };
  }

  /**
   * C0.3 step 7 / A4 CANCEL-METHOD-DECISION: `session/cancel` is sent as a NOTIFICATION
   * (no id). Pending permission requests are abandoned locally and never answered (A4: the
   * pending request stayed unanswered and the turn aborted with `stopReason:"cancelled"`).
   */
  cancel(sessionId: string): void {
    this.assertOpen();
    if (typeof sessionId !== 'string' || sessionId === '') {
      throw new AcpError('cancel requires a non-empty sessionId', 'INVALID_CANCEL');
    }
    this.send({ method: 'session/cancel', params: { sessionId } });
    this.abandonPermissions('cancelled');
  }

  /**
   * Documented fallback for the cancel path (C0.3 step 7): SIGTERM, then SIGKILL after the
   * grace window. A4 did not need it (the notification aborted the turn), so it is kept
   * available rather than used by `cancel()`.
   *
   * F8 (design lines 257-260): the signals target the CLI's whole process group on POSIX, so
   * a background descendant cannot survive the termination. See `signalCli` for the
   * direct-child fallback.
   */
  terminate(): void {
    if (this.exited) {
      return;
    }
    this.signalCli('SIGTERM');
    if (this.spawnError !== null) {
      // P1 (B3a review): the child never spawned, so there is no live process to escalate
      // against. Arming the timer here would leak a referenced one when a LATER close()
      // follows the failure: the child's `error` + `close` already fired, so nothing is
      // left to clear it. The same-tick race (close() before the failure is delivered)
      // still arms — `spawnError` is null there — and the `close` handler clears it.
      return;
    }
    if (this.killTimer) {
      clearTimeout(this.killTimer);
    }
    this.killTimer = setTimeout(() => {
      if (this.exited) {
        return;
      }
      this.signalCli('SIGKILL');
    }, this.options.killGraceMs ?? DEFAULT_KILL_GRACE_MS);
    // F3: the escalation timer stays REFERENCED while the CLI may still be alive, so a
    // host that exits right after close()/governance stop cannot skip the SIGKILL and
    // leave a SIGTERM-ignoring qodercli behind. It is cleared by whichever of the child's
    // `exit`/`close` handlers fires first (see wireChild); a never-spawned child does not
    // arm it at all (see the spawnError return above).
  }

  /**
   * F8: signal the CLI's process GROUP, falling back to the direct child only when the group
   * call is unavailable — Windows (`processGroupKill` defaults to false there; the platform
   * cannot deliver a signal to a group) or a group call that throws (ESRCH when the group is
   * already gone, EPERM when the signal is not permitted, or any other group failure).
   * Every path ends in the direct child, so `terminate()` can never become inert; a child
   * that never spawned has nothing to signal.
   */
  private signalCli(signal: NodeJS.Signals): void {
    const pid = this.child.pid;
    if (pid === undefined) {
      return;
    }
    if (this.options.processGroupKill ?? process.platform !== 'win32') {
      try {
        this.signalProcess(-pid, signal);
        return;
      } catch {
        /* group unavailable (ESRCH/EPERM) or unsupported: fall back to the direct child */
      }
    }
    try {
      this.signalProcess(pid, signal);
    } catch {
      /* already gone */
    }
  }

  /** Close the client: reject pending requests, abandon permissions, terminate the CLI. */
  close(): void {
    if (this.closed) {
      return;
    }
    this.closed = true;
    this.abandonPermissions('client-closed');
    this.failPending(new AcpClosedError('the ACP client was closed'));
    this.terminate();
  }

  /** Bounded tail of the CLI stderr, for diagnostics (never credentials by design). */
  get stderrTail(): string {
    return this.stderrText;
  }

  // ----- outbound ---------------------------------------------------------------------

  private send(message: Record<string, unknown>): void {
    if (this.closed || this.exited) {
      return;
    }
    try {
      this.child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', ...message })}\n`);
    } catch (error) {
      this.emit({
        type: 'protocol_error',
        message: `failed to write to the CLI stdin: ${(error as Error).message}`,
      });
    }
  }

  private request(method: string, params: unknown): Promise<unknown> {
    this.assertOpen();
    const id = this.nextRequestId++;
    return new Promise<unknown>((resolve, reject) => {
      const timeoutMs = this.options.handshakeTimeoutMs ?? DEFAULT_HANDSHAKE_TIMEOUT_MS;
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new AcpRequestTimeoutError(`no ${method} response within ${timeoutMs} ms`));
      }, timeoutMs);
      timer.unref?.();
      this.pending.set(id, { method, resolve, reject, timer });
      this.send({ id, method, params });
    });
  }

  private sendSelected(pending: PendingPermission, option: PermissionOption): void {
    if (option.kind === 'allow_always') {
      // C0.4 hard constraint (A4: offered 4/4, selected 0): unreachable through `decide`
      // because it only looks up allow_once/reject_once, but never let it slip out.
      throw new AcpError('allow_always must never be selected', 'GOVERNANCE_OPTION_SELECTED', {
        optionId: option.optionId,
      });
    }
    // A4-exercised nested wrapper; the reply targets the CLI's ORIGINAL request id.
    this.send({ id: pending.rawId, result: { outcome: { outcome: 'selected', optionId: option.optionId } } });
    this.pendingPermissions.delete(pending.requestId);
    this.rememberResolved(pending.requestId, 'selected', option.optionId);
    this.emit({
      type: 'permission_reply',
      requestId: pending.requestId,
      outcome: 'selected',
      optionId: option.optionId,
    });
  }

  private sendCancelled(pending: PendingPermission): void {
    // INFERRED, NOT EXERCISED: no gate confirmed a `cancelled` reply against the real CLI
    // (A4 only exercised the nested `selected` wrapper; the probe's flat
    // `{outcome:'cancelled'}` fallback sites are annotated shape-unverified). The nested
    // wrapper is kept because it is the only exercised outer shape; the flat form is
    // deliberately not copied. A fixture test pins OUR intent, not the CLI's behavior.
    this.send({ id: pending.rawId, result: { outcome: { outcome: 'cancelled' } } });
    this.pendingPermissions.delete(pending.requestId);
    this.rememberResolved(pending.requestId, 'cancelled', null);
    this.emit({ type: 'permission_reply', requestId: pending.requestId, outcome: 'cancelled', optionId: null });
  }

  // ----- inbound ----------------------------------------------------------------------

  private wireChild(): void {
    const { child } = this;
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (text: string) => this.onStdout(text));
    child.stderr.setEncoding('utf8');
    child.stderr.on('data', (text: string) => this.onStderr(text));
    child.on('error', error => {
      this.spawnError = error;
      this.emit({ type: 'child_error', message: error.message });
      this.failPending(
        new AcpSpawnError(`failed to spawn '${this.spawnPlan.command}': ${error.message}`, {
          command: this.spawnPlan.command,
        }),
      );
    });
    child.on('exit', (code, signal) => {
      this.exited = true;
      this.exitInfo = { code, signal };
      if (this.killTimer) {
        clearTimeout(this.killTimer);
        this.killTimer = null;
      }
      this.emit({ type: 'child_exit', code, signal });
      this.abandonPermissions('process-exited');
      this.failPending(new AcpProcessExitedError(this.exitMessage(), { code, signal }));
    });
    child.on('close', () => {
      // F4 follow-up: the carry flush belongs HERE, not in `exit`. Node emits `exit` as
      // soon as the process ends, which may be BEFORE the stdio pipes are drained — the
      // final stderr chunk can still arrive after it. Flushing on `exit` could therefore
      // emit a held token prefix and then let one more chunk complete the token unredacted.
      // Node emits `close` only after the process ended AND all stdio streams are closed,
      // so every `data` event (including the last stderr chunk) has already reached
      // onStderr and the carry is final. Should `close` never fire (child not reaped), the
      // held suffix is at most `token.length - 1` characters and is dropped: dropping that
      // bounded fragment is the safe failure mode, whereas flushing early can leak a token.
      this.flushStderrCarry();
      // B3a re-review: a failed spawn (e.g. ENOENT) reports `error` + `close` but never
      // `exit`, so clear the F3 escalation timer here too or a dead child keeps it pending.
      if (this.killTimer) {
        clearTimeout(this.killTimer);
        this.killTimer = null;
      }
    });
    child.stdin.on('error', error => {
      // A broken stdin (e.g. the CLI is already gone) must never crash the bridge.
      this.emit({ type: 'protocol_error', message: `CLI stdin error: ${error.message}` });
    });
  }

  private onStdout(text: string): void {
    this.stdoutBuffer += text;
    let index: number;
    while ((index = this.stdoutBuffer.indexOf('\n')) >= 0) {
      const line = this.stdoutBuffer.slice(0, index);
      this.stdoutBuffer = this.stdoutBuffer.slice(index + 1);
      if (line.trim() === '') {
        continue;
      }
      this.handleLine(line);
    }
    if (this.stdoutBuffer.length > MAX_STDOUT_LINE_CHARS) {
      // Bounded reader: a single unterminated line must not grow without limit.
      this.emit({
        type: 'protocol_error',
        message: `dropping an unterminated stdout line longer than ${MAX_STDOUT_LINE_CHARS} characters`,
      });
      this.stdoutBuffer = '';
    }
  }

  private handleLine(line: string): void {
    let message: JsonRpcMessage;
    try {
      message = JSON.parse(line) as JsonRpcMessage;
    } catch {
      this.emit({ type: 'protocol_error', message: 'stdout line is not valid JSON (dropped)' });
      return;
    }
    if (message === null || typeof message !== 'object' || Array.isArray(message)) {
      this.emit({ type: 'protocol_error', message: 'stdout line is not a JSON-RPC object (dropped)' });
      return;
    }
    if (typeof message.method !== 'string') {
      if (message.method === undefined && message.id !== undefined) {
        this.handleResponse(message);
        return;
      }
      this.emit({ type: 'protocol_error', message: 'JSON-RPC message without a string `method` (dropped)' });
      return;
    }
    if (message.method === 'session/update') {
      this.handleSessionUpdate(message);
      return;
    }
    if (message.method === 'session/request_permission') {
      this.handlePermissionRequest(message);
      return;
    }
    if (message.id !== undefined) {
      this.handleUnsupportedRequest(message);
      return;
    }
    this.emit({ type: 'notification', method: message.method, params: message.params });
  }

  private handleResponse(message: JsonRpcMessage): void {
    const id = typeof message.id === 'number' ? message.id : Number(message.id);
    const entry = Number.isInteger(id) ? this.pending.get(id) : undefined;
    if (!entry) {
      this.emit({
        type: 'protocol_error',
        message: `response for unknown request id ${String(message.id)} (dropped)`,
      });
      return;
    }
    this.pending.delete(id);
    if (entry.timer) {
      clearTimeout(entry.timer);
      entry.timer = null;
    }
    if (message.error) {
      entry.reject(new AcpRpcError(entry.method, message.error));
      return;
    }
    entry.resolve(message.result);
  }

  private handleSessionUpdate(message: JsonRpcMessage): void {
    const update = (message.params as { update?: unknown } | undefined)?.update;
    if (update === null || typeof update !== 'object') {
      this.emit({ type: 'protocol_error', message: 'session/update without a `params.update` object (dropped)' });
      return;
    }
    const record = update as Record<string, unknown>;
    this.emit({ type: 'session_update', update: record });
    // C0.4: mode escalation stops the run. The CLI stays in `default` (design §3.2) and
    // the spike showed an accidental allow_always grant escalating to `acceptEdits`.
    const currentModeId = record.currentModeId;
    if (typeof currentModeId === 'string' && currentModeId !== GOVERNED_CLI_MODE) {
      this.stopForGovernance(
        `mode escalation observed: currentModeId=${currentModeId} (governed mode is '${GOVERNED_CLI_MODE}')`,
        { currentModeId },
      );
    }
  }

  private handlePermissionRequest(message: JsonRpcMessage): void {
    if (message.id === undefined) {
      this.emit({ type: 'protocol_error', message: 'session/request_permission without an id (cannot be answered)' });
      return;
    }
    const requestId = String(message.id);
    if (this.pendingPermissions.has(requestId) || this.resolvedPermissions.has(requestId)) {
      // Never answer the same JSON-RPC id twice.
      this.emit({ type: 'protocol_error', message: `duplicate permission request id ${requestId} (ignored)` });
      return;
    }
    const params = (message.params ?? {}) as Record<string, unknown>;
    const toolCall = (params.toolCall ?? {}) as Record<string, unknown>;
    const qoderMeta = ((toolCall._meta as Record<string, unknown> | undefined)?.qoder ?? {}) as Record<string, unknown>;
    const rawToolCallId = toolCall.toolCallId;
    const options = sanitizeOptions(params.options);
    this.pendingPermissions.set(requestId, { rawId: message.id, requestId, options });
    this.emit({
      type: 'permission_request',
      requestId,
      toolCallId:
        typeof rawToolCallId === 'string'
          ? rawToolCallId
          : typeof rawToolCallId === 'number'
            ? String(rawToolCallId)
            : null,
      toolName: typeof qoderMeta.toolName === 'string' ? qoderMeta.toolName : null,
      title: typeof params.title === 'string' ? params.title : null,
      options,
      params,
    });
  }

  private handleUnsupportedRequest(message: JsonRpcMessage): void {
    // Design §3.3: unsupported client-side filesystem/terminal methods return explicit
    // JSON-RPC errors; never a fabricated success (and never a crash).
    this.send({
      id: message.id,
      error: {
        code: JSON_RPC_METHOD_NOT_FOUND,
        message: `Unsupported client method: ${String(message.method)}`,
      },
    });
    this.emit({
      type: 'unsupported_request',
      requestId: message.id as number | string,
      method: String(message.method),
    });
  }

  // ----- state ------------------------------------------------------------------------

  private stopForGovernance(message: string, detail?: unknown): void {
    if (this.governanceStopped) {
      return;
    }
    this.governanceStopped = true;
    this.emit({ type: 'governance_error', code: 'MODE_ESCALATION', message, detail });
    this.closed = true;
    this.abandonPermissions('governance-stop');
    this.failPending(new GovernanceStopError(`run stopped by governance: ${message}`, detail));
    this.terminate();
  }

  private abandonPermissions(reason: string): void {
    for (const pending of this.pendingPermissions.values()) {
      this.rememberResolved(pending.requestId, reason, null);
    }
    this.pendingPermissions.clear();
  }

  private rememberResolved(requestId: string, outcome: string, optionId: string | null): void {
    this.resolvedPermissions.set(requestId, { outcome, optionId });
    while (this.resolvedPermissions.size > RESOLVED_PERMISSION_MEMORY) {
      const oldest = this.resolvedPermissions.keys().next();
      if (oldest.done) {
        break;
      }
      this.resolvedPermissions.delete(oldest.value);
    }
  }

  private failPending(error: Error): void {
    const entries = [...this.pending.values()];
    this.pending.clear();
    for (const entry of entries) {
      if (entry.timer) {
        clearTimeout(entry.timer);
      }
      entry.reject(error);
    }
  }

  private exitMessage(): string {
    const info = this.exitInfo;
    if (!info) {
      return 'the qodercli process is not running';
    }
    return `the qodercli process exited (code ${info.code ?? 'null'}, signal ${info.signal ?? 'null'})`;
  }

  /**
   * F4: stderr may echo credentials (e.g. a rejected auth header); redact the allowlisted
   * PAT before it reaches events (B3b republishes them) and the tail. F4 follow-up: the
   * redacted stream's trailing suffix is held back in `stderrCarry` while it is still a
   * proper prefix of the token, so a token split across two pipe chunks cannot be rejoined
   * from the emitted events or the tail. Only text that cannot start a token is emitted.
   */
  private onStderr(text: string): void {
    const token = this.spawnPlan.env.QODER_PERSONAL_ACCESS_TOKEN;
    if (typeof token !== 'string' || token === '') {
      this.emitStderr(text);
      return;
    }
    const redacted = this.redactAllowlistedToken(this.stderrCarry + text);
    const hold = tokenPrefixHoldLength(redacted, token);
    this.stderrCarry = redacted.slice(redacted.length - hold);
    const visible = redacted.slice(0, redacted.length - hold);
    if (visible !== '') {
      this.emitStderr(visible);
    }
  }

  /** F4: never let the allowlisted PAT leave through stderr (events, tail). */
  private redactAllowlistedToken(text: string): string {
    const token = this.spawnPlan.env.QODER_PERSONAL_ACCESS_TOKEN;
    return typeof token === 'string' && token !== '' ? text.split(token).join('[redacted]') : text;
  }

  /** Emit one redacted stderr chunk with the unchanged tail semantics (bounded accumulation). */
  private emitStderr(text: string): void {
    this.stderrText = (this.stderrText + text).slice(-MAX_STDERR_TAIL_CHARS);
    this.emit({ type: 'stderr', text });
  }

  /** F4 follow-up: the child is gone — emit the held suffix instead of dropping it. */
  private flushStderrCarry(): void {
    if (this.stderrCarry !== '') {
      const carry = this.stderrCarry;
      this.stderrCarry = '';
      this.emitStderr(carry);
    }
  }

  private assertOpen(): void {
    if (this.spawnError) {
      throw new AcpSpawnError(`the qodercli process failed to start: ${this.spawnError.message}`, {
        command: this.spawnPlan.command,
      });
    }
    if (this.governanceStopped) {
      // Checked before `exited`: the terminate that follows a governance stop also ends
      // the child, but callers must see the governance decision, not a generic exit.
      throw new GovernanceStopError('the run was stopped by a governance error');
    }
    if (this.exited) {
      throw new AcpProcessExitedError(this.exitMessage(), this.exitInfo ?? undefined);
    }
    if (this.closed) {
      throw new AcpClosedError('the ACP client is closed');
    }
  }

  private emit(event: AcpEvent): void {
    for (const handler of [...this.handlers]) {
      try {
        handler(event);
      } catch {
        // A broken subscriber must not break the protocol client.
      }
    }
  }
}
