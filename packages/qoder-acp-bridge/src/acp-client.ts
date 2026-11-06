// ACP client: owns the pinned Qoder CLI child process and speaks the recorded
// newline-delimited JSON-RPC protocol (e2e/agent-core/fixtures/
// qoder-host-cli-1.1.61-protocol.jsonl) over its stdout/stdin.
//
// Boundaries enforced here:
//   - spawn with shell:false, an explicit cwd and an explicitly constructed
//     environment (no ambient inheritance, no bridge control secret);
//   - the child's stdout is protocol only; stderr is diagnostics only;
//   - frames are newline-delimited with a maximum accepted size; malformed or
//     oversized input fails the run explicitly instead of being skipped;
//   - initialize and session creation are awaited before any prompt;
//   - permission decisions answer the child's exact JSON-RPC request id with
//     the exact offered option id, and only while that request is pending.
import { spawn, type ChildProcessWithoutNullStreams, type SpawnOptions } from 'node:child_process';
import { createHash } from 'node:crypto';

import { permissionResponseFrame, type PermissionOption } from './permissions.js';

export const BRIDGE_ERROR_CODES = {
  usage: 'E_USAGE',
  config: 'E_CONFIG',
  malformedFrame: 'E_MALFORMED_FRAME',
  frameTooLarge: 'E_FRAME_TOO_LARGE',
  authRequired: 'E_AUTH_REQUIRED',
  handshakeRejected: 'E_HANDSHAKE_REJECTED',
  unsupportedModel: 'E_UNSUPPORTED_MODEL',
  modelRefused: 'E_MODEL_REFUSED',
  childExited: 'E_CHILD_EXITED',
  coreError: 'E_CORE_ERROR',
} as const;

export class BridgeError extends Error {
  readonly code: string;
  readonly details: Record<string, unknown>;

  constructor(code: string, message: string, details: Record<string, unknown> = {}) {
    super(message);
    this.name = 'BridgeError';
    this.code = code;
    this.details = details;
  }
}

// ------------------------------------------------------------------ child launch

export interface AcpLaunch {
  executable: string;
  args: string[];
  cwd: string;
  env: Record<string, string>;
}

export interface ChildEnvironmentConfig {
  /** Platform of the *bridge host* (injectable so the construction is testable). */
  platform: NodeJS.Platform;
  /** The bridge process environment; only non-credential host variables are considered. */
  base: NodeJS.ProcessEnv;
  /** Explicit `--child-env KEY=VALUE` pairs declared by the trusted launcher. */
  childEnv: Record<string, string>;
  /** The model credential destination: child environment variable name + value. */
  credential?: { name: string; value: string };
}

/**
 * Build the child environment from an explicit allowlist. Nothing is inherited
 * implicitly: only the platform-essential variables a child needs to start
 * (`PATH`, plus `SystemRoot` on Windows), the variables the trusted launcher
 * declared and the model credential under its own name reach the child. Every
 * other ambient, credential-shaped and SDK-entrypoint variable (and the bridge
 * control secret) stays in the bridge process.
 */
export function buildChildEnvironment(config: ChildEnvironmentConfig): Record<string, string> {
  const env: Record<string, string> = {};
  const isWindows = config.platform === 'win32';
  for (const name of isWindows ? ['PATH', 'SystemRoot'] : ['PATH']) {
    const value = readBaseEnvironment(config.base, name, isWindows);
    if (typeof value === 'string' && value.length > 0) env[name] = value;
  }
  for (const [key, value] of Object.entries(config.childEnv)) {
    if (!isEnvironmentKey(key)) throw new BridgeError(BRIDGE_ERROR_CODES.config, `Invalid child environment key: ${key}`);
    env[key] = value;
  }
  if (config.credential) {
    if (!isEnvironmentKey(config.credential.name)) {
      throw new BridgeError(
        BRIDGE_ERROR_CODES.config,
        `Invalid credential environment name: ${config.credential.name}`,
      );
    }
    env[config.credential.name] = config.credential.value;
  }
  return env;
}

/**
 * Read one platform-essential variable from the bridge environment. Windows
 * exposes `PATH` with unpredictable casing (`Path`), so the lookup is
 * case-insensitive there; the canonical uppercase name is what reaches the child.
 */
function readBaseEnvironment(base: NodeJS.ProcessEnv, name: string, caseInsensitive: boolean): string | undefined {
  const direct = base[name];
  if (typeof direct === 'string') return direct;
  if (!caseInsensitive) return undefined;
  const lower = name.toLowerCase();
  for (const key of Object.keys(base)) {
    if (key.toLowerCase() === lower) {
      const value = base[key];
      if (typeof value === 'string') return value;
    }
  }
  return undefined;
}

export function childSpawnOptions(launch: AcpLaunch): SpawnOptions {
  return {
    cwd: launch.cwd,
    env: { ...launch.env },
    shell: false,
    windowsHide: true,
    stdio: ['pipe', 'pipe', 'pipe'],
  };
}

export function spawnChild(launch: AcpLaunch): ChildProcessWithoutNullStreams {
  return spawn(launch.executable, [...launch.args], childSpawnOptions(launch)) as ChildProcessWithoutNullStreams;
}

function isEnvironmentKey(key: string): boolean {
  return /^[A-Za-z_][A-Za-z0-9_]*$/.test(key);
}

// ------------------------------------------------------------------ protocol types

export interface PermissionRequestRecord {
  requestId: number;
  sessionId: string;
  toolCallId: string | null;
  toolName: string | null;
  toolKind: string | null;
  toolCallTitle: string | null;
  argumentsDigest: string;
  options: PermissionOption[];
}

export interface PromptHandle {
  promptId: number;
  result: Promise<Record<string, unknown>>;
}

export interface HandshakeResult {
  protocolVersion: unknown;
  agentInfo: { name?: string; title?: string; version?: string };
  agentCapabilities: unknown;
  availableModels: string[] | null;
  currentModelId: string | null;
  mcpServers: Array<Record<string, unknown>>;
}

export type AcpClientState = 'idle' | 'starting' | 'ready' | 'prompting' | 'exited' | 'failed';

export interface AcpClientOptions {
  launch: AcpLaunch;
  workspace: string;
  model: string;
  maxFrameBytes: number;
  requestTimeoutMs: number;
  permissionTimeoutMs: number;
  mcpServers: Array<Record<string, unknown>>;
  onEvent: (type: string, payload: Record<string, unknown>) => void;
  onPermissionRequest: (request: PermissionRequestRecord) => void;
  onFatal: (error: BridgeError) => void;
}

interface PendingRequest {
  id: number;
  method: string;
  resolve: (result: Record<string, unknown>) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout | null;
}

type PermissionStatus = 'pending' | 'answered' | 'expired' | 'cancelled' | 'refused';

interface PendingPermission extends PermissionRequestRecord {
  status: PermissionStatus;
  answer: { choice: string; optionId: string; raw: string } | null;
  timer: NodeJS.Timeout | null;
}

export class AcpClient {
  private readonly options: AcpClientOptions;
  private child: ChildProcessWithoutNullStreams | null = null;
  private stdoutBuffer = '';
  private stderrBuffer = '';
  private nextRequestId = 1;
  private readonly pendingRequests = new Map<number, PendingRequest>();
  private readonly pendingPermissions = new Map<number, PendingPermission>();
  private readonly requestMethods = new Map<number, string>();
  private state: AcpClientState = 'idle';
  private sessionIdValue: string | null = null;
  private observedModel: string | null = null;
  private coreInfo: { name?: string; title?: string; version?: string } = {};
  private protocolVersion: unknown = null;
  private fatalError: BridgeError | null = null;

  constructor(options: AcpClientOptions) {
    this.options = options;
  }

  getState(): AcpClientState {
    return this.state;
  }

  getSessionId(): string | null {
    return this.sessionIdValue;
  }

  getObservedModel(): string | null {
    return this.observedModel;
  }

  getCoreInfo(): { name?: string; title?: string; version?: string } {
    return { ...this.coreInfo };
  }

  getProtocolVersion(): unknown {
    return this.protocolVersion;
  }

  getChildPid(): number | null {
    return this.child && this.child.pid !== undefined ? this.child.pid : null;
  }

  getFatalError(): BridgeError | null {
    return this.fatalError;
  }

  pendingPermissionIds(): number[] {
    return [...this.pendingPermissions.entries()]
      .filter(([, entry]) => entry.status === 'pending')
      .map(([id]) => id);
  }

  isChildAlive(): boolean {
    return this.child !== null && this.child.exitCode === null && this.state !== 'exited' && this.state !== 'failed';
  }

  /** Spawn the child and perform the recorded startup order: initialize -> session/new -> session/set_model. */
  async start(): Promise<HandshakeResult> {
    this.state = 'starting';
    const child = spawnChild(this.options.launch);
    this.child = child;
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk: string) => this.onStdout(chunk));
    child.stderr.on('data', (chunk: string) => this.onStderr(chunk));
    child.on('error', (error: Error) => {
      this.fatal(new BridgeError(BRIDGE_ERROR_CODES.childExited, `Qoder child failed to start: ${error.message}`));
    });
    child.on('exit', (code, signal) => {
      this.onChildExit(code, signal);
    });

    const initialize = await this.request('initialize', { protocolVersion: 1 });
    this.protocolVersion = initialize.protocolVersion ?? null;
    if (initialize.agentInfo && typeof initialize.agentInfo === 'object') {
      this.coreInfo = initialize.agentInfo as { name?: string; title?: string; version?: string };
    }

    const session = await this.request('session/new', {
      cwd: this.options.workspace,
      mcpServers: this.options.mcpServers,
    });
    const sessionId = session.sessionId;
    if (typeof sessionId !== 'string' || sessionId.length === 0) {
      throw new BridgeError(BRIDGE_ERROR_CODES.handshakeRejected, 'Qoder core returned no sessionId for session/new');
    }
    this.sessionIdValue = sessionId;

    const models = extractAvailableModels(session);
    if (models !== null && !models.includes(this.options.model)) {
      throw new BridgeError(
        BRIDGE_ERROR_CODES.unsupportedModel,
        `Model ${JSON.stringify(this.options.model)} is not offered by the pinned core; offered: ${JSON.stringify(models)}`,
        { offered: models, requested: this.options.model },
      );
    }

    await this.request('session/set_model', { sessionId, modelId: this.options.model });
    this.state = 'ready';
    return {
      protocolVersion: this.protocolVersion,
      agentInfo: { ...this.coreInfo },
      agentCapabilities: initialize.agentCapabilities ?? null,
      availableModels: models,
      currentModelId: extractCurrentModelId(session),
      mcpServers: this.options.mcpServers,
    };
  }

  prompt(text: string): PromptHandle {
    if (this.state === 'exited' || this.state === 'failed') {
      throw new BridgeError(BRIDGE_ERROR_CODES.childExited, 'Qoder child is no longer running');
    }
    if (this.state !== 'ready') {
      throw new BridgeError(BRIDGE_ERROR_CODES.handshakeRejected, `Cannot prompt while state is ${this.state}`);
    }
    this.state = 'prompting';
    const handle = this.sendRequest('session/prompt', {
      sessionId: this.sessionIdValue,
      prompt: [{ type: 'text', text }],
    });
    const result = handle.result.finally(() => {
      if (this.state === 'prompting') this.state = 'ready';
    });
    return { promptId: handle.id, result };
  }

  /** Cooperative cancellation: the recorded `session/cancel` notification. */
  cancel(): void {
    if (!this.isChildAlive()) {
      throw new BridgeError(BRIDGE_ERROR_CODES.childExited, 'Qoder child is no longer running');
    }
    const params = { sessionId: this.sessionIdValue };
    this.writeFrame({ jsonrpc: '2.0', method: 'session/cancel', params });
    this.options.onEvent('acp.notification', { method: 'session/cancel', params });
  }

  /** Answer a pending permission request with the exact offered option id. */
  respondPermission(requestId: number, optionId: string, choice: string): string {
    const entry = this.pendingPermissions.get(requestId);
    if (!entry || entry.status !== 'pending') {
      throw new BridgeError('E_UNKNOWN_REQUEST', `No pending permission request ${requestId}`, { requestId });
    }
    const raw = permissionResponseFrame(requestId, optionId);
    this.writeFrameText(raw);
    if (entry.timer) {
      clearTimeout(entry.timer);
      entry.timer = null;
    }
    entry.status = 'answered';
    entry.answer = { choice, optionId, raw };
    return raw;
  }

  permissionStatus(requestId: number): PermissionStatus | null {
    return this.pendingPermissions.get(requestId)?.status ?? null;
  }

  pendingPermissionOptions(requestId: number): PermissionOption[] {
    const entry = this.pendingPermissions.get(requestId);
    if (!entry || entry.status !== 'pending') {
      throw new BridgeError('E_UNKNOWN_REQUEST', `No pending permission request ${requestId}`, { requestId });
    }
    return entry.options;
  }

  permissionAnswer(requestId: number): { choice: string; optionId: string; raw: string } | null {
    return this.pendingPermissions.get(requestId)?.answer ?? null;
  }

  /**
   * Invalidate every still-pending permission request; late decisions must fail.
   * The outcome is explicit: a genuine cancellation marks the requests
   * `cancelled`, while a prompt failure or child exit marks them `expired`, so a
   * failed prompt can never be reported as a user cancellation.
   */
  invalidatePermissions(status: 'cancelled' | 'expired'): number[] {
    const invalidated: number[] = [];
    for (const [requestId, entry] of this.pendingPermissions) {
      if (entry.status !== 'pending') continue;
      if (entry.timer) {
        clearTimeout(entry.timer);
        entry.timer = null;
      }
      entry.status = status;
      invalidated.push(requestId);
    }
    return invalidated;
  }

  /** Terminate the child after a bounded graceful window. */
  kill(graceMs = 2000): void {
    const child = this.child;
    if (!child || child.exitCode !== null) return;
    try {
      child.stdin.end();
    } catch {
      // stdin may already be gone; termination below still applies.
    }
    const killTimer = setTimeout(() => {
      try {
        child.kill('SIGKILL');
      } catch {
        // already gone
      }
    }, graceMs);
    killTimer.unref?.();
    try {
      child.kill();
    } catch {
      // already gone
    }
  }

  // ------------------------------------------------------------------ framing

  private onStdout(chunk: string): void {
    this.stdoutBuffer += chunk;
    for (;;) {
      const at = this.stdoutBuffer.indexOf('\n');
      if (at < 0) break;
      const line = this.stdoutBuffer.slice(0, at).replace(/\r$/, '');
      this.stdoutBuffer = this.stdoutBuffer.slice(at + 1);
      this.handleLine(line);
      if (this.state === 'failed') return;
    }
    if (Buffer.byteLength(this.stdoutBuffer, 'utf8') > this.options.maxFrameBytes) {
      this.fatal(
        new BridgeError(
          BRIDGE_ERROR_CODES.frameTooLarge,
          `Unterminated frame exceeds the ${this.options.maxFrameBytes}-byte limit`,
          { limit: this.options.maxFrameBytes, bytes: Buffer.byteLength(this.stdoutBuffer, 'utf8') },
        ),
      );
    }
  }

  private onStderr(chunk: string): void {
    this.stderrBuffer += chunk;
    for (;;) {
      const at = this.stderrBuffer.indexOf('\n');
      if (at < 0) break;
      const line = this.stderrBuffer.slice(0, at).replace(/\r$/, '');
      this.stderrBuffer = this.stderrBuffer.slice(at + 1);
      this.options.onEvent('child.diagnostic', { line });
    }
  }

  private handleLine(line: string): void {
    const bytes = Buffer.byteLength(line, 'utf8');
    if (bytes > this.options.maxFrameBytes) {
      this.fatal(
        new BridgeError(
          BRIDGE_ERROR_CODES.frameTooLarge,
          `Frame of ${bytes} bytes exceeds the ${this.options.maxFrameBytes}-byte limit`,
          { limit: this.options.maxFrameBytes, bytes },
        ),
      );
      return;
    }
    if (line.trim().length === 0) return;
    let frame: Record<string, unknown>;
    try {
      frame = JSON.parse(line) as Record<string, unknown>;
    } catch (error) {
      this.fatal(
        new BridgeError(
          BRIDGE_ERROR_CODES.malformedFrame,
          `Malformed frame (${bytes} bytes): ${(error as Error).message}`,
          { bytes },
        ),
      );
      return;
    }
    this.handleFrame(frame);
  }

  private handleFrame(frame: Record<string, unknown>): void {
    const id = frame.id;
    if (typeof id === 'number' && (frame.result !== undefined || frame.error !== undefined)) {
      this.handleResponse(id, frame);
      return;
    }
    if (typeof frame.method === 'string' && id !== undefined) {
      this.handleChildRequest(frame, id);
      return;
    }
    if (typeof frame.method === 'string') {
      this.handleNotification(frame);
      return;
    }
    this.options.onEvent('acp.unknown_frame', { raw: JSON.stringify(frame) });
  }

  private handleResponse(id: number, frame: Record<string, unknown>): void {
    const pending = this.pendingRequests.get(id);
    const method = this.requestMethods.get(id) ?? null;
    this.requestMethods.delete(id);
    if (!pending) {
      this.options.onEvent('acp.unknown_response', { id, frame: JSON.stringify(frame) });
      return;
    }
    this.pendingRequests.delete(id);
    if (pending.timer) clearTimeout(pending.timer);
    if (frame.error !== undefined) {
      const error = frame.error as { code?: unknown; message?: unknown };
      const rpcCode = typeof error.code === 'number' ? error.code : null;
      const rpcMessage = typeof error.message === 'string' ? error.message : '';
      this.options.onEvent('acp.error', { id, method, rpcCode, rpcMessage });
      pending.reject(mapCoreError(method, rpcCode, rpcMessage));
      return;
    }
    const result = (frame.result ?? {}) as Record<string, unknown>;
    this.options.onEvent('acp.result', { id, method, result });
    pending.resolve(result);
  }

  private handleChildRequest(frame: Record<string, unknown>, id: unknown): void {
    if (frame.method === 'session/request_permission') {
      this.handlePermissionRequest(frame, id);
      return;
    }
    if (typeof id === 'number') {
      this.writeFrame({ jsonrpc: '2.0', id, error: { code: -32601, message: 'Method not found' } });
    }
    this.options.onEvent('acp.unknown_request', { method: frame.method, id: typeof id === 'number' ? id : null });
  }

  private handlePermissionRequest(frame: Record<string, unknown>, id: unknown): void {
    if (typeof id !== 'number') {
      this.options.onEvent('permission.refused', { reason: 'non-numeric-request-id' });
      return;
    }
    const params = (frame.params ?? {}) as Record<string, unknown>;
    const sessionId = typeof params.sessionId === 'string' ? params.sessionId : null;
    if (sessionId === null || sessionId !== this.sessionIdValue) {
      this.writeFrame({
        jsonrpc: '2.0',
        id,
        error: { code: -32602, message: `Permission request for unknown session: ${String(sessionId)}` },
      });
      this.options.onEvent('permission.refused', { requestId: id, reason: 'session-mismatch', sessionId });
      return;
    }
    const toolCall = (params.toolCall ?? {}) as Record<string, unknown>;
    const options = Array.isArray(params.options)
      ? (params.options as Array<Record<string, unknown>>)
          .filter((option) => typeof option.optionId === 'string' && typeof option.kind === 'string')
          .map((option) => ({
            optionId: option.optionId as string,
            kind: option.kind as string,
            name: typeof option.name === 'string' ? option.name : undefined,
          }))
      : [];
    const record: PermissionRequestRecord = {
      requestId: id,
      sessionId,
      toolCallId: typeof toolCall.toolCallId === 'string' ? toolCall.toolCallId : null,
      toolName: extractToolName(toolCall),
      toolKind: typeof toolCall.kind === 'string' ? toolCall.kind : null,
      toolCallTitle: typeof toolCall.title === 'string' ? toolCall.title : null,
      argumentsDigest: digestArguments(toolCall.rawInput),
      options,
    };
    const entry: PendingPermission = { ...record, status: 'pending', answer: null, timer: null };
    if (this.options.permissionTimeoutMs > 0) {
      entry.timer = setTimeout(() => {
        this.expirePermission(entry);
      }, this.options.permissionTimeoutMs);
      entry.timer.unref?.();
    }
    this.pendingPermissions.set(id, entry);
    this.options.onPermissionRequest(record);
  }

  /**
   * Fail-closed expiry: an unanswered permission is answered with the reject
   * option when one is offered; a request with no deny option is never
   * auto-allowed.
   */
  private expirePermission(entry: PendingPermission): void {
    if (entry.status !== 'pending') return;
    const reject = entry.options.filter((option) => option.kind === 'reject_once');
    const rejectOption = reject.length === 1 ? reject[0] : undefined;
    if (rejectOption === undefined) {
      entry.status = 'refused';
      entry.timer = null;
      this.options.onEvent('permission.unanswerable', {
        requestId: entry.requestId,
        options: entry.options,
        reason: 'no single reject_once option to answer with',
      });
      this.writeFrame({
        jsonrpc: '2.0',
        id: entry.requestId,
        error: { code: -32602, message: 'Unusable permission options: no single reject_once option' },
      });
      return;
    }
    const optionId = rejectOption.optionId;
    const raw = permissionResponseFrame(entry.requestId, optionId);
    this.writeFrameText(raw);
    entry.status = 'expired';
    entry.answer = { choice: 'DENY', optionId, raw };
    entry.timer = null;
    this.options.onEvent('permission.expired', {
      requestId: entry.requestId,
      optionId,
      raw,
      windowMs: this.options.permissionTimeoutMs,
    });
  }

  // ------------------------------------------------------------------ transport

  private sendRequest(method: string, params: unknown): { id: number; result: Promise<Record<string, unknown>> } {
    const id = this.nextRequestId;
    this.nextRequestId += 1;
    this.requestMethods.set(id, method);
    this.options.onEvent('acp.request', { id, method, params });
    const result = new Promise<Record<string, unknown>>((resolve, reject) => {
      const entry: PendingRequest = {
        id,
        method,
        resolve,
        reject,
        timer:
          this.options.requestTimeoutMs > 0 && method !== 'session/prompt'
            ? setTimeout(() => {
                this.pendingRequests.delete(id);
                reject(
                  new BridgeError(
                    'E_REQUEST_TIMEOUT',
                    `Qoder core did not answer ${method} within ${this.options.requestTimeoutMs}ms`,
                    { id, method },
                  ),
                );
              }, this.options.requestTimeoutMs)
            : null,
      };
      entry.timer?.unref?.();
      this.pendingRequests.set(id, entry);
      try {
        this.writeFrame({ jsonrpc: '2.0', id, method, params });
      } catch (error) {
        this.pendingRequests.delete(id);
        if (entry.timer) clearTimeout(entry.timer);
        reject(error as Error);
      }
    });
    return { id, result };
  }

  private async request(method: string, params: unknown): Promise<Record<string, unknown>> {
    return this.sendRequest(method, params).result;
  }

  private handleNotification(frame: Record<string, unknown>): void {
    const params = (frame.params ?? {}) as Record<string, unknown>;
    if (frame.method === 'session/update') {
      const update = params.update ?? null;
      this.options.onEvent('session.update', { sessionId: params.sessionId ?? null, update });
      return;
    }
    this.options.onEvent('acp.notification_in', { method: frame.method, params });
  }

  private writeFrame(frame: Record<string, unknown>): void {
    this.writeFrameText(`${JSON.stringify(frame)}\n`);
  }

  private writeFrameText(text: string): void {
    const child = this.child;
    if (!child || child.exitCode !== null || !child.stdin.writable) {
      throw new BridgeError(BRIDGE_ERROR_CODES.childExited, 'Qoder child is no longer running');
    }
    const frame = text.endsWith('\n') ? text.slice(0, -1) : text;
    const bytes = Buffer.byteLength(frame, 'utf8');
    if (bytes > this.options.maxFrameBytes) {
      // The outbound bound mirrors the inbound one: a frame above the accepted
      // maximum fails the run explicitly instead of being written.
      this.fatal(
        new BridgeError(
          BRIDGE_ERROR_CODES.frameTooLarge,
          `Frame of ${bytes} bytes exceeds the ${this.options.maxFrameBytes}-byte limit`,
          { limit: this.options.maxFrameBytes, bytes, direction: 'outbound' },
        ),
      );
      return;
    }
    child.stdin.write(text);
  }

  private onChildExit(code: number | null, signal: NodeJS.Signals | null): void {
    const message = `Qoder child exited with code ${code === null ? 'null' : code}${signal ? ` (signal ${signal})` : ''}`;
    this.options.onEvent('child.exit', { code, signal, message });
    if (this.state !== 'failed') this.state = 'exited';
    const error = new BridgeError(BRIDGE_ERROR_CODES.childExited, message, { code, signal });
    for (const pending of this.pendingRequests.values()) {
      if (pending.timer) clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.pendingRequests.clear();
    // A dead child is a prompt failure, not a cancellation: its still-pending
    // permissions become unanswerable (expired), never "cancelled".
    this.invalidatePermissions('expired');
  }

  private fatal(error: BridgeError): void {
    if (this.state === 'failed') return;
    this.fatalError = error;
    this.state = 'failed';
    for (const pending of this.pendingRequests.values()) {
      if (pending.timer) clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.pendingRequests.clear();
    this.kill();
    this.options.onFatal(error);
  }
}

// ------------------------------------------------------------------ helpers

function mapCoreError(method: string | null, rpcCode: number | null, rpcMessage: string): BridgeError {
  if (method === 'initialize' || method === 'session/new') {
    if (rpcCode === -32000) {
      return new BridgeError(BRIDGE_ERROR_CODES.authRequired, rpcMessage, { rpcCode, method });
    }
    return new BridgeError(
      BRIDGE_ERROR_CODES.handshakeRejected,
      `Qoder core rejected ${method} (${rpcCode}): ${rpcMessage}`,
      { rpcCode, method },
    );
  }
  if (method === 'session/set_model') {
    return new BridgeError(BRIDGE_ERROR_CODES.modelRefused, rpcMessage, { rpcCode, method });
  }
  return new BridgeError(BRIDGE_ERROR_CODES.coreError, rpcMessage, { rpcCode, method });
}

function extractAvailableModels(session: Record<string, unknown>): string[] | null {
  const models = session.models;
  if (!models || typeof models !== 'object') return null;
  const available = (models as Record<string, unknown>).availableModels;
  if (!Array.isArray(available)) return null;
  const ids = available
    .map((entry) => (entry && typeof entry === 'object' ? (entry as Record<string, unknown>).modelId : null))
    .filter((entry): entry is string => typeof entry === 'string');
  return ids.length === available.length ? ids : null;
}

function extractCurrentModelId(session: Record<string, unknown>): string | null {
  const models = session.models;
  if (!models || typeof models !== 'object') return null;
  const current = (models as Record<string, unknown>).currentModelId;
  return typeof current === 'string' ? current : null;
}

function extractToolName(toolCall: Record<string, unknown>): string | null {
  const meta = toolCall._meta;
  if (meta && typeof meta === 'object') {
    const qoder = (meta as Record<string, unknown>).qoder;
    if (qoder && typeof qoder === 'object') {
      const name = (qoder as Record<string, unknown>).toolName;
      if (typeof name === 'string') return name;
    }
  }
  return null;
}

export function digestArguments(rawInput: unknown): string {
  return createHash('sha256').update(canonicalJson(rawInput)).digest('hex');
}

function canonicalJson(value: unknown): string {
  if (value === undefined) return 'null';
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map((entry) => canonicalJson(entry)).join(',')}]`;
  const record = value as Record<string, unknown>;
  const keys = Object.keys(record).sort();
  return `{${keys.map((key) => `${JSON.stringify(key)}:${canonicalJson(record[key])}`).join(',')}}`;
}
