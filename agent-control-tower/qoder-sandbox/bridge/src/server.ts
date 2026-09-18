/**
 * Bridge HTTP/SSE control surface: the frozen C0.2 contract (plan
 * docs/superpowers/plans/2026-09-17-qoder-cli-provider.md, Task B3b), consumed by the Java
 * host (`QoderBridgeClient`, B5).
 *
 * The bridge is a thin, fail-closed projection of one `AcpClient` per bridge session:
 *
 *  - `Authorization: Bearer <BRIDGE_TOKEN>` on every route except `GET /health`; the
 *    comparison is timing-safe and the token never reaches a response or a log line.
 *  - Bodies are capped at 1 MiB (413) and drained without buffering past the cap.
 *  - ACP event names are mapped to the frozen C0.2 event names (see `onAcpEvent`); every
 *    string the CLI supplies is treated as untrusted and redacted against the session's
 *    known secrets (the bridge token plus the session's MCP header values) before it is
 *    republished, and raw payloads are never logged.
 *  - Decisions are resolved against the bridge's own pending map (which owns the
 *    deadline), so `delivered | already_resolved | expired | unknown` and the
 *    `ALREADY_RESOLVED` conflict are answered without re-entering the ACP client.
 *  - `POST /probe` (C2 ruling R1) performs one MCP `initialize` POST from inside the sandbox
 *    to a caller-named URL with exactly the caller-supplied headers; any HTTP response proves
 *    liveness. The headers (a worker credential) and any response body never reach the answer
 *    or a log line, and redirects are never followed.
 *
 * Recorded decisions of this task (see the B3b report): the deadline source is the
 * server-side default capped by the session run deadline; a missing/blank `after` replays
 * the whole retained buffer; unmatched (method, path) pairs are 404; unknown-session bodies
 * use `{"error":"NOT_FOUND"}`; `runId`/`agentId` are accepted but not used for routing;
 * `reason` on a decision is accepted and ignored (the ACP permission reply has no slot for
 * it); an ended session stays registered (bounded) so a late reader can still replay it.
 */
import { createHash, randomUUID, timingSafeEqual } from 'node:crypto';
import { createServer, request as httpRequest, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { request as httpsRequest } from 'node:https';

import {
  AcpClient,
  AcpError,
  UnknownPermissionRequestError,
  PermissionAlreadyResolvedError,
  UnsupportedOptionsError,
  type AcpClientOptions,
  type AcpEvent,
  type McpServerSpec,
  type PermissionDecision,
  type SessionSpec,
} from './acp-client.js';
import { SessionEventStream } from './sse.js';

/**
 * The CLI version the sandbox image ships (`ARG QODERCLI_VERSION=1.1.41`, A1 evidence
 * e2e/qoder/slice-a/01-boot.md). `/health` reports the pin, never a live probe: it is
 * bridge liveness only and must stay fast and credential-free.
 */
export const PINNED_CLI_VERSION = '1.1.41';

/** C0.2: request bodies larger than 1 MiB are rejected with 413. */
export const MAX_BODY_BYTES = 1024 * 1024;

/** C2 ruling R1 (C0.2 amendment A1): the `permission_request.rawInput` character bound. */
export const MAX_RAW_INPUT_CHARS = 65536;

/** C2 ruling R1: the `POST /probe` timeout used when the caller sends none. */
export const DEFAULT_PROBE_TIMEOUT_MS = 3000;

/** Default permission deadline: the A5 gate held a pending request for 5 minutes. */
export const DEFAULT_PERMISSION_DEADLINE_MS = 15 * 60 * 1000;

/** Resolved-permission memory, mirroring the ACP client's own bound. */
const MAX_RESOLVED_PERMISSION_MEMORY = 1000;

/** Ended sessions are retained for late replay, but only this many of them. */
const MAX_RETAINED_ENDED_SESSIONS = 32;

const MAX_PREVIEW_CHARS = 256;
const MAX_ERROR_REASON_CHARS = 512;

/** The probe reads at most this much of a 2xx body, and only to classify a JSON-RPC result. */
const MAX_PROBE_BODY_BYTES = 16 * 1024;

/** RFC 7230 token: Node's HTTP client throws a synchronous error on any other header name. */
const HEADER_NAME_PATTERN = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/;

const SECRET_PLACEHOLDER = '[redacted]';

// ---------------------------------------------------------------------------------------
// Client surface
// ---------------------------------------------------------------------------------------

/** The client surface the bridge uses; the real `AcpClient` satisfies it structurally. */
export interface BridgeAcpClient {
  createSession(spec: SessionSpec): Promise<{ sessionId: string }>;
  prompt(sessionId: string, text: string): void;
  onEvent(handler: (event: AcpEvent) => void): () => void;
  decide(requestId: string, approved: boolean): PermissionDecision;
  cancel(sessionId: string): void;
  terminate(): void;
  close(): void;
}

export interface BridgeServerOptions {
  /** `BRIDGE_TOKEN`; required, non-empty (fail closed). */
  token: string;
  /** One line per request: route, status and session id at most, never a body. */
  log?: (line: string) => void;
  /** Overrides `DEFAULT_PERMISSION_DEADLINE_MS` (tests inject a short one). */
  permissionDeadlineMs?: number;
  /** Spawn-plan options forwarded to the default `AcpClient` factory. */
  acpClientOptions?: AcpClientOptions;
  /** Test seam: replaces the default `AcpClient` construction. */
  createAcpClient?: () => BridgeAcpClient;
}

export interface BridgeServer {
  readonly http: Server;
  /** The listening port (0 before `listen`). */
  readonly port: number;
  listen(port: number, host?: string): Promise<number>;
  /** End every stream, terminate every CLI, stop accepting connections. */
  close(): Promise<void>;
}

// ---------------------------------------------------------------------------------------
// Internal state
// ---------------------------------------------------------------------------------------

interface HttpErrorBody {
  error: string;
  reason?: string;
}

class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly body: HttpErrorBody,
  ) {
    super(body.error);
  }
}

type DecisionState = 'pending' | 'delivered' | 'expired' | 'resolved';

interface PermissionRecord {
  state: DecisionState;
  /** The value delivered to the CLI (only meaningful in state `delivered`). */
  approved: boolean | null;
  expiresAt: number;
}

interface BridgeSession {
  readonly id: string;
  readonly acp: BridgeAcpClient;
  readonly model: string;
  readonly stream: SessionEventStream;
  /** Strings that must never leave the bridge in CLI-derived text. */
  readonly secrets: readonly string[];
  readonly decisions: Map<string, PermissionRecord>;
  /** Run deadline (epoch ms) from the create request's `deadlineSeconds`, else Infinity. */
  readonly deadlineAt: number;
  acpSessionId: string | null;
  ended: boolean;
  governanceReported: boolean;
}

interface Routed {
  status: number;
  sessionId: string | null;
}

interface CreateSessionRequest {
  cwd: string;
  model: string;
  mcpServers: McpServerSpec[];
  deadlineAt: number;
}

// ---------------------------------------------------------------------------------------
// Server
// ---------------------------------------------------------------------------------------

export function createBridgeServer(options: BridgeServerOptions): BridgeServer {
  const token = options.token;
  if (typeof token !== 'string' || token === '') {
    // Fail closed (brief note 6): without a token every route would be open.
    throw new Error('BRIDGE_TOKEN is required; refusing to start without a bearer token');
  }
  const log = options.log ?? ((line: string): void => console.log(line));
  const permissionDeadlineMs = options.permissionDeadlineMs ?? DEFAULT_PERMISSION_DEADLINE_MS;
  if (!Number.isFinite(permissionDeadlineMs) || permissionDeadlineMs <= 0) {
    throw new Error('permissionDeadlineMs must be a positive number');
  }
  const createClient = options.createAcpClient ?? ((): BridgeAcpClient => new AcpClient(options.acpClientOptions ?? {}));
  const sessions = new Map<string, BridgeSession>();

  // ------------------------------------------------------------------------------------
  // Routing
  // ------------------------------------------------------------------------------------

  async function route(req: IncomingMessage, res: ServerResponse, method: string, url: URL): Promise<Routed> {
    if (method === 'GET' && url.pathname === '/health') {
      sendJson(res, 200, { status: 'ok', cliVersion: PINNED_CLI_VERSION });
      return { status: 200, sessionId: null };
    }
    if (!isAuthorized(req.headers.authorization)) {
      // Drain (discard) the rejected body so the client can read the 401 instead of a reset.
      req.resume();
      throw new HttpError(401, { error: 'UNAUTHORIZED' });
    }

    const segments = url.pathname.split('/').filter(part => part !== '');
    if (segments[0] === 'probe' && segments.length === 1 && method === 'POST') {
      await probe(req, res);
      return { status: 200, sessionId: null };
    }
    if (segments[0] === 'sessions' && segments.length === 1 && method === 'POST') {
      return createSession(req, res);
    }
    if (segments[0] === 'sessions' && segments.length >= 2) {
      const sessionId = decodeSegment(segments[1] ?? '');
      const session = sessions.get(sessionId);
      if (session === undefined) {
        throw new HttpError(404, { error: 'NOT_FOUND' });
      }
      if (method === 'POST' && segments.length === 3 && segments[2] === 'prompt') {
        await prompt(session, req, res);
        return { status: 202, sessionId };
      }
      if (method === 'GET' && segments.length === 3 && segments[2] === 'events') {
        events(session, res, url);
        return { status: 200, sessionId };
      }
      if (method === 'POST' && segments.length === 3 && segments[2] === 'cancel') {
        cancel(session, res);
        return { status: 202, sessionId };
      }
      if (method === 'POST' && segments.length === 4 && segments[2] === 'permissions') {
        await decide(session, decodeSegment(segments[3] ?? ''), req, res);
        return { status: 200, sessionId };
      }
    }
    // C0.2 (as amended by C2 ruling R1) defines exactly seven (method, path) pairs;
    // anything else is not a route.
    throw new HttpError(404, { error: 'NOT_FOUND' });
  }

  async function createSession(req: IncomingMessage, res: ServerResponse): Promise<Routed> {
    const request = validateCreateRequest(await readJsonBody(req));
    const sessionId = randomUUID();
    const secrets = collectSecrets(token, request.mcpServers);
    const client = createClient();
    const session: BridgeSession = {
      id: sessionId,
      acp: client,
      model: request.model,
      stream: new SessionEventStream(),
      secrets,
      decisions: new Map<string, PermissionRecord>(),
      deadlineAt: request.deadlineAt,
      acpSessionId: null,
      ended: false,
      governanceReported: false,
    };
    client.onEvent(event => onAcpEvent(session, event));
    try {
      const created = await client.createSession({
        cwd: request.cwd,
        modelId: request.model,
        mcpServers: request.mcpServers,
      });
      session.acpSessionId = created.sessionId;
    } catch (error) {
      client.close();
      throw mapCreateFailure(error, secrets);
    }
    sessions.set(sessionId, session);
    pruneEndedSessions();
    sendJson(res, 201, { bridgeSessionId: sessionId });
    return { status: 201, sessionId };
  }

  async function prompt(session: BridgeSession, req: IncomingMessage, res: ServerResponse): Promise<void> {
    const body = requireRecord(await readJsonBody(req));
    const text = requireText(body.text);
    if (session.ended || session.acpSessionId === null) {
      throw new HttpError(409, { error: 'SESSION_ENDED' });
    }
    try {
      session.acp.prompt(session.acpSessionId, text);
    } catch {
      // The client is closed / the CLI is gone: the turn cannot be accepted.
      throw new HttpError(409, { error: 'SESSION_ENDED' });
    }
    sendJson(res, 202, { accepted: true });
  }

  function events(session: BridgeSession, res: ServerResponse, url: URL): void {
    const rawAfter = url.searchParams.get('after');
    let after: number;
    if (rawAfter === null || rawAfter.trim() === '') {
      after = session.stream.replayFromMissingAfter();
    } else {
      const trimmed = rawAfter.trim();
      if (!/^\d+$/.test(trimmed)) {
        throw new HttpError(400, { error: 'INVALID_REQUEST' });
      }
      after = Number(trimmed);
      if (!Number.isSafeInteger(after)) {
        throw new HttpError(400, { error: 'INVALID_REQUEST' });
      }
    }
    if (session.stream.isReplayGap(after)) {
      throw new HttpError(409, { error: 'REPLAY_GAP' });
    }
    res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' });
    res.flushHeaders();
    session.stream.attach(res, after);
    if (session.ended) {
      // Session end already happened: hand the retained tail over and close the stream.
      res.end();
    }
  }

  function cancel(session: BridgeSession, res: ServerResponse): void {
    const terminated = !session.ended;
    if (terminated) {
      // C0.2: reject pending requests first (fail closed — no approval can be delivered on
      // a cancelled session), then the ACP cancel notification (A4: notification only),
      // then SIGTERM with the client's SIGKILL escalation in grace.
      for (const record of session.decisions.values()) {
        if (record.state === 'pending') {
          record.state = 'resolved';
        }
      }
      try {
        if (session.acpSessionId !== null) {
          session.acp.cancel(session.acpSessionId);
        }
      } catch {
        /* already closed */
      }
      try {
        session.acp.terminate();
      } catch {
        /* already gone */
      }
    }
    sendJson(res, 202, { terminated });
  }

  async function decide(
    session: BridgeSession,
    requestId: string,
    req: IncomingMessage,
    res: ServerResponse,
  ): Promise<void> {
    const body = requireRecord(await readJsonBody(req));
    if (typeof body.approved !== 'boolean') {
      throw new HttpError(400, { error: 'INVALID_REQUEST' });
    }
    if (body.reason !== undefined && typeof body.reason !== 'string') {
      throw new HttpError(400, { error: 'INVALID_REQUEST' });
    }
    const approved = body.approved;
    const record = session.decisions.get(requestId);
    if (record === undefined) {
      sendJson(res, 200, { outcome: 'unknown' });
      return;
    }
    if (record.state === 'delivered') {
      if (record.approved === approved) {
        sendJson(res, 200, { outcome: 'already_resolved' });
      } else {
        throw new HttpError(409, { error: 'ALREADY_RESOLVED' });
      }
      return;
    }
    if (record.state === 'resolved') {
      sendJson(res, 200, { outcome: 'already_resolved' });
      return;
    }
    if (record.state === 'expired' || Date.now() > record.expiresAt) {
      record.state = 'expired';
      sendJson(res, 200, { outcome: 'expired' });
      return;
    }
    try {
      session.acp.decide(requestId, approved);
    } catch (error) {
      if (error instanceof UnsupportedOptionsError) {
        // C0.4: never fall back to another option. The client left the request pending, so a
        // later denial can still resolve it.
        throw new HttpError(422, { error: 'UNSUPPORTED_OPTIONS' });
      }
      if (error instanceof PermissionAlreadyResolvedError) {
        record.state = 'resolved';
        sendJson(res, 200, { outcome: 'already_resolved' });
        return;
      }
      if (error instanceof UnknownPermissionRequestError) {
        record.state = 'resolved';
        sendJson(res, 200, { outcome: 'unknown' });
        return;
      }
      throw error;
    }
    record.state = 'delivered';
    record.approved = approved;
    sendJson(res, 200, { outcome: 'delivered' });
  }

  /** C2 ruling R1: one sandbox-side reachability check of one candidate MCP endpoint. */
  async function probe(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const request = validateProbeRequest(await readJsonBody(req));
    const result = await probeMcpEndpoint(request);
    sendJson(res, 200, { reachable: result.reachable, status: result.status, detail: result.detail });
  }

  // ------------------------------------------------------------------------------------
  // ACP event projection (the recorded C0.2 -> client mapping)
  // ------------------------------------------------------------------------------------

  function onAcpEvent(session: BridgeSession, event: AcpEvent): void {
    switch (event.type) {
      case 'session_created':
        session.stream.append('session_started', { model: session.model });
        return;
      case 'session_update':
        for (const projected of projectUpdate(session, event.update)) {
          session.stream.append(projected.type, projected.fields);
        }
        return;
      case 'permission_request': {
        const expiresAt = Math.min(Date.now() + permissionDeadlineMs, session.deadlineAt);
        session.decisions.set(event.requestId, { state: 'pending', approved: null, expiresAt });
        pruneDecisionMemory(session);
        const params = event.params as { toolCall?: { rawInput?: unknown } } | undefined;
        const rawInput = boundedRawInput(session, params?.toolCall?.rawInput);
        session.stream.append('permission_request', {
          requestId: event.requestId,
          toolCallId: event.toolCallId,
          toolName: event.toolName === null ? null : redact(session, event.toolName),
          title: event.title === null ? null : redact(session, event.title),
          redactedPreview: buildPreview(session, params?.toolCall?.rawInput),
          inputDigest: inputDigest(params?.toolCall?.rawInput),
          rawInput: rawInput.text,
          rawInputTruncated: rawInput.truncated,
          options: event.options.map(option => ({
            optionId: option.optionId,
            kind: option.kind,
            name: option.name === undefined ? null : redact(session, option.name),
          })),
          expiresAt: new Date(expiresAt).toISOString(),
        });
        return;
      }
      case 'prompt_result': {
        const result = (event.result ?? null) as Record<string, unknown> | null;
        const usage = extractRecord(result, 'usage');
        session.stream.append('usage', {
          // A5: no credit/cost field occurs anywhere in the ACP message stream, so credits
          // stay explicitly unknown instead of a fabricated zero.
          credits: null,
          inputTokens: optionalNumber(usage?.inputTokens),
          outputTokens: optionalNumber(usage?.outputTokens),
        });
        session.stream.append('completed', {
          stopReason: typeof result?.stopReason === 'string' ? result.stopReason : null,
        });
        return;
      }
      case 'prompt_error':
        if (event.code === 'GOVERNANCE_STOP' && session.governanceReported) {
          // The client emits governance_error and then rejects the pending turn with the same
          // decision; the cause was already reported, so the echo is not a second failure.
          return;
        }
        session.stream.append('failed', {
          reason: truncate(redact(session, event.message), MAX_ERROR_REASON_CHARS),
          code: event.code,
        });
        return;
      case 'governance_error':
        session.governanceReported = true;
        session.stream.append('failed', {
          reason: truncate(redact(session, event.message), MAX_ERROR_REASON_CHARS),
          code: event.code,
        });
        endSession(session);
        return;
      case 'child_exit':
      case 'child_error':
        endSession(session);
        return;
      default:
        // child_started / stderr / notification / protocol_error / unsupported_request /
        // permission_reply are not part of the frozen C0.2 event list; stderr and CLI
        // notifications also carry verbatim CLI text, so they are dropped, never logged.
        return;
    }
  }

  function projectUpdate(
    session: BridgeSession,
    update: Record<string, unknown>,
  ): Array<{ type: string; fields: Record<string, unknown> }> {
    switch (update.sessionUpdate) {
      case 'agent_message_chunk': {
        const text = extractText(update.content);
        // Redacted, never truncated: this text is the agent's answer (the host's run output).
        return text === null ? [] : [{ type: 'agent_message', fields: { text: redact(session, text) } }];
      }
      case 'tool_call': {
        const toolName = readToolName(update);
        return [
          {
            type: 'tool_call',
            fields: {
              toolCallId: optionalString(update.toolCallId),
              toolName: toolName === null ? null : redact(session, toolName),
              kind: optionalString(update.kind),
              status: optionalString(update.status),
            },
          },
        ];
      }
      case 'tool_call_update':
        return [
          {
            type: 'tool_call_update',
            fields: { toolCallId: optionalString(update.toolCallId), status: optionalString(update.status) },
          },
        ];
      case 'current_mode_update': {
        const currentModeId = optionalString(update.currentModeId);
        return currentModeId === null ? [] : [{ type: 'mode_changed', fields: { currentModeId } }];
      }
      default:
        // A5 observed available_commands_update and agent_thought_chunk as well; neither is
        // in the frozen C0.2 event list, so they are not republished.
        return [];
    }
  }

  function endSession(session: BridgeSession): void {
    if (session.ended) {
      return;
    }
    session.ended = true;
    for (const record of session.decisions.values()) {
      if (record.state === 'pending') {
        record.state = 'resolved';
      }
    }
    // The client emits `child_exit` BEFORE it rejects the pending request, so the
    // `prompt_error` that explains the end arrives in the same tick; ending the streams
    // synchronously here would drop it from live subscribers. One deferred close covers
    // both, and the deferral makes this idempotent for the concurrent death paths.
    setImmediate(() => session.stream.endAll());
  }

  function pruneEndedSessions(): void {
    let ended = 0;
    for (const session of sessions.values()) {
      if (session.ended) {
        ended += 1;
      }
    }
    let excess = ended - MAX_RETAINED_ENDED_SESSIONS;
    if (excess <= 0) {
      return;
    }
    for (const [id, session] of [...sessions]) {
      if (excess <= 0) {
        return;
      }
      if (session.ended) {
        sessions.delete(id);
        excess -= 1;
      }
    }
  }

  function pruneDecisionMemory(session: BridgeSession): void {
    while (session.decisions.size > MAX_RESOLVED_PERMISSION_MEMORY) {
      let evicted = false;
      for (const [id, record] of session.decisions) {
        if (record.state !== 'pending') {
          session.decisions.delete(id);
          evicted = true;
          break;
        }
      }
      if (!evicted) {
        return;
      }
    }
  }

  // ------------------------------------------------------------------------------------
  // HTTP plumbing
  // ------------------------------------------------------------------------------------

  function isAuthorized(header: string | undefined): boolean {
    if (typeof header !== 'string' || !header.startsWith('Bearer ')) {
      return false;
    }
    const provided = Buffer.from(header.slice('Bearer '.length), 'utf8');
    const expected = Buffer.from(token, 'utf8');
    return provided.length === expected.length && timingSafeEqual(provided, expected);
  }

  async function readJsonBody(req: IncomingMessage): Promise<unknown> {
    const declared = Number(req.headers['content-length']);
    if (Number.isFinite(declared) && declared > MAX_BODY_BYTES) {
      // Declared oversize: reject without reading, but drain (discarding) so the client can
      // still read the response instead of a reset connection.
      req.resume();
      throw new HttpError(413, { error: 'PAYLOAD_TOO_LARGE' });
    }
    const chunks: Buffer[] = [];
    let size = 0;
    const outcome = await new Promise<'complete' | 'overflow'>((resolve, reject) => {
      req.on('data', (chunk: Buffer) => {
        if (size > MAX_BODY_BYTES) {
          return;
        }
        size += chunk.length;
        if (size > MAX_BODY_BYTES) {
          // Never buffer past the cap; stop accumulating and answer as soon as the body ends.
          chunks.length = 0;
          resolve('overflow');
          return;
        }
        chunks.push(chunk);
      });
      req.on('end', () => resolve('complete'));
      req.on('error', () => reject(new HttpError(400, { error: 'INVALID_REQUEST' })));
    });
    if (outcome === 'overflow') {
      req.resume();
      throw new HttpError(413, { error: 'PAYLOAD_TOO_LARGE' });
    }
    if (size === 0) {
      throw new HttpError(400, { error: 'INVALID_REQUEST' });
    }
    try {
      return JSON.parse(Buffer.concat(chunks).toString('utf8'));
    } catch {
      throw new HttpError(400, { error: 'INVALID_REQUEST' });
    }
  }

  function sendJson(res: ServerResponse, status: number, body: Record<string, unknown>): void {
    if (res.headersSent) {
      res.destroy();
      return;
    }
    const payload = Buffer.from(JSON.stringify(body), 'utf8');
    res.writeHead(status, { 'content-type': 'application/json', 'content-length': String(payload.length) });
    res.end(payload);
  }

  const http = createServer((req, res) => {
    const method = req.method ?? 'GET';
    const url = new URL(req.url ?? '/', 'http://bridge.internal');
    void (async () => {
      let routed: Routed;
      try {
        routed = await route(req, res, method, url);
      } catch (error) {
        routed = {
          status: error instanceof HttpError ? error.status : 500,
          sessionId: null,
        };
        // Error bodies carry a fixed code (and, for provider failures, a redacted reason);
        // never a raw payload and never the bearer token.
        sendJson(res, routed.status, error instanceof HttpError ? { ...error.body } : { error: 'INTERNAL_ERROR' });
      }
      if (routed.sessionId === null) {
        log(`${method} ${url.pathname} -> ${routed.status}`);
      } else {
        log(`${method} ${url.pathname} -> ${routed.status} session=${routed.sessionId}`);
      }
    })();
  });

  return {
    http,
    get port(): number {
      const address = http.address();
      return address !== null && typeof address === 'object' ? address.port : 0;
    },
    listen(port: number, host = '0.0.0.0'): Promise<number> {
      return new Promise<number>((resolve, reject) => {
        const onError = (error: Error): void => {
          http.off('listening', onListening);
          reject(error);
        };
        const onListening = (): void => {
          http.off('error', onError);
          resolve(this.port);
        };
        http.once('error', onError);
        http.once('listening', onListening);
        http.listen(port, host);
      });
    },
    async close(): Promise<void> {
      for (const session of sessions.values()) {
        session.stream.endAll();
        try {
          session.acp.close();
        } catch {
          /* already dead */
        }
      }
      sessions.clear();
      await new Promise<void>(resolve => {
        http.close(() => resolve());
        http.closeAllConnections();
      });
    },
  };
}

// ---------------------------------------------------------------------------------------
// Request validation
// ---------------------------------------------------------------------------------------

function validateCreateRequest(body: unknown): CreateSessionRequest {
  const record = requireRecord(body);
  const cwd = requireText(record.cwd);
  const model = requireText(record.model);
  const mcpServers = validateMcpServers(record.mcpServers);
  return { cwd, model, mcpServers, deadlineAt: resolveDeadlineAt(record.deadlineSeconds) };
}

function validateMcpServers(value: unknown): McpServerSpec[] {
  if (value === undefined || value === null) {
    return [];
  }
  if (!Array.isArray(value)) {
    throw new HttpError(400, { error: 'INVALID_REQUEST' });
  }
  return value.map(entry => {
    const record = requireRecord(entry);
    const name = requireText(record.name);
    const url = requireText(record.url);
    const headers =
      record.headers === undefined || record.headers === null ? [] : validateHeaders(record.headers);
    return { name, url, headers };
  });
}

/** Header list shared by `mcpServers[].headers` and the R1 `POST /probe` request. */
function validateHeaders(value: unknown): Array<{ name: string; value: string }> {
  if (!Array.isArray(value)) {
    throw new HttpError(400, { error: 'INVALID_REQUEST' });
  }
  return value.map(header => {
    const record = requireRecord(header);
    const name = requireText(record.name);
    const headerValue = requireText(record.value);
    // Node's HTTP client rejects a non-token name and a CR/LF value with a synchronous
    // throw; rejecting both here keeps a malformed header a 400 instead of a 500.
    if (!HEADER_NAME_PATTERN.test(name) || /[\r\n]/.test(headerValue)) {
      throw new HttpError(400, { error: 'INVALID_REQUEST' });
    }
    return { name, value: headerValue };
  });
}

/** `deadlineSeconds` is optional; when present it must be a positive finite number. */
function resolveDeadlineAt(value: unknown): number {
  if (value === undefined || value === null) {
    return Number.POSITIVE_INFINITY;
  }
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) {
    throw new HttpError(400, { error: 'INVALID_REQUEST' });
  }
  return Date.now() + value * 1000;
}

/**
 * C2 ruling R1: `POST /probe` body. `headers` is optional (absent means no caller header) but
 * must be a list of usable HTTP headers when present; `timeoutMs` defaults to 3000.
 */
interface ProbeRequest {
  readonly url: URL;
  readonly headers: Array<{ name: string; value: string }>;
  readonly timeoutMs: number;
}

function validateProbeRequest(body: unknown): ProbeRequest {
  const record = requireRecord(body);
  const rawUrl = requireText(record.url);
  let url: URL;
  try {
    url = new URL(rawUrl);
  } catch {
    throw new HttpError(400, { error: 'INVALID_REQUEST' });
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new HttpError(400, { error: 'INVALID_REQUEST' });
  }
  const headers = record.headers === undefined || record.headers === null ? [] : validateHeaders(record.headers);
  const timeoutMs =
    record.timeoutMs === undefined || record.timeoutMs === null ? DEFAULT_PROBE_TIMEOUT_MS : record.timeoutMs;
  if (typeof timeoutMs !== 'number' || !Number.isFinite(timeoutMs) || timeoutMs <= 0) {
    throw new HttpError(400, { error: 'INVALID_REQUEST' });
  }
  return { url, headers, timeoutMs };
}

function requireRecord(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new HttpError(400, { error: 'INVALID_REQUEST' });
  }
  return value as Record<string, unknown>;
}

function requireText(value: unknown): string {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new HttpError(400, { error: 'INVALID_REQUEST' });
  }
  return value;
}

function decodeSegment(segment: string): string {
  try {
    return decodeURIComponent(segment);
  } catch {
    throw new HttpError(400, { error: 'INVALID_REQUEST' });
  }
}

function mapCreateFailure(error: unknown, secrets: readonly string[]): HttpError {
  const code = error instanceof AcpError ? error.code : 'SESSION_CREATE_FAILED';
  const reason = truncate(
    redactText(secrets, error instanceof Error ? error.message : String(error)),
    MAX_ERROR_REASON_CHARS,
  );
  switch (code) {
    case 'INVALID_SESSION_SPEC':
      return new HttpError(400, { error: 'INVALID_REQUEST' });
    case 'UNKNOWN_MODEL':
      // C0.1: an unknown model is an explicit provider error, never a silent fallback.
      return new HttpError(400, { error: 'UNKNOWN_MODEL' });
    default:
      return new HttpError(502, { error: code === 'GOVERNANCE_STOP' ? 'GOVERNANCE_STOP' : 'SESSION_CREATE_FAILED', reason });
  }
}

// ---------------------------------------------------------------------------------------
// MCP probe (C2 ruling R1)
// ---------------------------------------------------------------------------------------

interface ProbeResult {
  readonly reachable: boolean;
  readonly status: number | null;
  readonly detail: string;
}

/**
 * One MCP `initialize` POST from inside the sandbox. Any HTTP response proves the endpoint is
 * live; a connection/DNS/TLS failure or the timeout proves the opposite. Redirects are never
 * followed: a 3xx already proves liveness, and the probe must not replay the worker
 * credential to an origin the caller did not name. Neither the caller's headers nor the
 * response body ever appear in the verdict, and nothing here is logged. The probe is an MCP
 * client, so its `Accept` must allow `text/event-stream`, as the streamable transport requires.
 */
function probeMcpEndpoint(request: ProbeRequest): Promise<ProbeResult> {
  return new Promise<ProbeResult>(resolve => {
    let settled = false;
    let observedStatus: number | null = null;
    let timer: NodeJS.Timeout | undefined;
    const settle = (result: ProbeResult): void => {
      if (settled) {
        return;
      }
      settled = true;
      if (timer !== undefined) {
        clearTimeout(timer);
      }
      resolve(result);
    };
    const requestFn = request.url.protocol === 'https:' ? httpsRequest : httpRequest;
    const call = requestFn(
      request.url,
      {
        method: 'POST',
        headers: {
          ...Object.fromEntries(request.headers.map(header => [header.name, header.value])),
          'content-type': 'application/json',
          // The streamable HTTP transport rejects a request whose Accept excludes it (400).
          accept: 'application/json, text/event-stream',
        },
      },
      response => {
        const status = response.statusCode ?? null;
        observedStatus = status;
        const base = `HTTP ${status}`;
        if (status === null || status < 200 || status >= 300) {
          response.resume(); // drain without buffering; the body is never echoed
          settle({ reachable: true, status, detail: base });
          return;
        }
        const chunks: Buffer[] = [];
        let size = 0;
        response.on('data', (chunk: Buffer) => {
          size += chunk.length;
          if (size <= MAX_PROBE_BODY_BYTES) {
            chunks.push(chunk);
          }
        });
        response.on('end', () => {
          const detail = isJsonRpcResult(Buffer.concat(chunks)) ? `${base} (JSON-RPC result)` : base;
          settle({ reachable: true, status, detail });
        });
        response.on('error', () => settle({ reachable: true, status, detail: base }));
      },
    );
    timer = setTimeout(() => {
      call.destroy();
      if (observedStatus === null) {
        settle({ reachable: false, status: null, detail: `timeout after ${request.timeoutMs}ms` });
      } else {
        // Headers answered before the deadline: liveness is proven, only the body lagged.
        settle({ reachable: true, status: observedStatus, detail: `HTTP ${observedStatus}` });
      }
    }, request.timeoutMs);
    call.on('error', (error: NodeJS.ErrnoException) => {
      // Codes only: a message can embed the caller's URL, and headers/body never belong here.
      settle({
        reachable: false,
        status: null,
        detail: error.code === undefined ? 'connection failed' : `connect ${error.code}`,
      });
    });
    call.end(
      JSON.stringify({
        jsonrpc: '2.0',
        id: 1,
        method: 'initialize',
        params: {
          // The version the A3 stub negotiated (e2e/qoder/slice-a/03-mcp-auth.mjs:168); any
          // answer proves reachability, so the value is informational.
          protocolVersion: '2024-11-05',
          capabilities: {},
          clientInfo: { name: 'aria-bridge-probe', version: '0.1.0' },
        },
      }),
    );
  });
}

function isJsonRpcResult(body: Buffer): boolean {
  try {
    const parsed = JSON.parse(body.toString('utf8')) as unknown;
    return (
      parsed !== null &&
      typeof parsed === 'object' &&
      !Array.isArray(parsed) &&
      (parsed as Record<string, unknown>).jsonrpc === '2.0' &&
      'result' in parsed
    );
  } catch {
    return false;
  }
}

// ---------------------------------------------------------------------------------------
// Event field helpers
// ---------------------------------------------------------------------------------------

/**
 * The PAT travels in the session's MCP `Authorization` header (C0.3 step 3). For a
 * `Bearer <token>` value only the token itself is a secret: the scheme word stays visible
 * so a republished title reads `Authorization: Bearer [redacted]` (the fix for the B3a
 * re-review observation). Any other header value is treated as wholly secret. The bridge
 * token is always included.
 */
function collectSecrets(token: string, mcpServers: readonly McpServerSpec[]): string[] {
  const secrets = new Set<string>([token]);
  for (const server of mcpServers) {
    for (const header of server.headers ?? []) {
      const bearer = /^Bearer\s+(.+)$/i.exec(header.value);
      secrets.add(bearer?.[1] ?? header.value);
    }
  }
  return [...secrets].filter(secret => secret !== '');
}

function redact(session: BridgeSession, text: string): string {
  return redactText(session.secrets, text);
}

function redactText(secrets: readonly string[], text: string): string {
  let redacted = text;
  for (const secret of secrets) {
    redacted = redacted.split(secret).join(SECRET_PLACEHOLDER);
  }
  return redacted;
}

function readToolName(update: Record<string, unknown>): string | null {
  const meta = extractRecord(update, '_meta');
  const qoder = extractRecord(meta, 'qoder');
  return optionalString(qoder?.toolName);
}

function extractRecord(source: Record<string, unknown> | null | undefined, key: string): Record<string, unknown> | null {
  const value = source?.[key];
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

function extractText(content: unknown): string | null {
  if (typeof content === 'string') {
    return content === '' ? null : content;
  }
  const record = content !== null && typeof content === 'object' ? (content as Record<string, unknown>) : null;
  const text = record?.text;
  return typeof text === 'string' && text !== '' ? text : null;
}

function optionalString(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}

function optionalNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function safeJson(value: unknown): string | null {
  try {
    const text = JSON.stringify(value);
    return typeof text === 'string' ? text : null;
  } catch {
    return null;
  }
}

function buildPreview(session: BridgeSession, rawInput: unknown): string | null {
  if (rawInput === undefined || rawInput === null) {
    return null;
  }
  const text = typeof rawInput === 'string' ? rawInput : safeJson(rawInput);
  if (text === null || text === '') {
    return null;
  }
  // Redact BEFORE truncating: slicing first could cut a secret in half and emit an
  // un-redactable prefix (the B3a F4 split-token lesson, applied to previews).
  return truncate(redact(session, text), MAX_PREVIEW_CHARS);
}

/**
 * C2 ruling R1: the bounded raw tool input the host recomputes its own preview and digest
 * from. Strings pass through as-is, everything else is serialized; redaction runs before
 * truncation for the same reason as `buildPreview`.
 */
function boundedRawInput(session: BridgeSession, rawInput: unknown): { text: string | null; truncated: boolean } {
  if (rawInput === undefined || rawInput === null) {
    return { text: null, truncated: false };
  }
  const serialized = typeof rawInput === 'string' ? rawInput : safeJson(rawInput);
  if (serialized === null) {
    return { text: null, truncated: false };
  }
  const redacted = redact(session, serialized);
  if (redacted.length > MAX_RAW_INPUT_CHARS) {
    return { text: redacted.slice(0, MAX_RAW_INPUT_CHARS), truncated: true };
  }
  return { text: redacted, truncated: false };
}

/** Hash of the tool input, so the host can correlate an ask without republishing it. */
function inputDigest(rawInput: unknown): string {
  const text = typeof rawInput === 'string' ? rawInput : (safeJson(rawInput) ?? '');
  return createHash('sha256').update(text).digest('hex');
}

function truncate(text: string, maxChars: number): string {
  return text.length > maxChars ? `${text.slice(0, maxChars)}...` : text;
}
