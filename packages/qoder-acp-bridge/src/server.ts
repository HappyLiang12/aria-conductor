// Authenticated HTTP surface of a single run-bound bridge.
//
// Every route except `/health` requires the per-run control secret in the
// `x-bridge-control-secret` header. The secret is compared in constant time and
// is never echoed in a response. `/health` is exempt by contract and exposes
// only non-secret liveness/binding metadata.
//
// The route table is fixed: `/session` only confirms the launch-time binding
// (run id, workspace, model) and refuses any other field, `/control` accepts the
// Task 1 verified control action (cooperative cancel) and explicitly refuses
// pause/resume, `/permission-response` accepts a choice (ALLOW_ONCE/DENY) and
// never a native option id.
import { createHash, timingSafeEqual } from 'node:crypto';
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';

import { BridgeError } from './acp-client.js';
import { EventBuffer } from './events.js';

export const CONTROL_SECRET_HEADER = 'x-bridge-control-secret';
export const DEFAULT_MAX_BODY_BYTES = 65536;
/** Bounded backlog per SSE consumer: past this the lagging stream is closed. */
export const DEFAULT_MAX_SSE_BACKLOG_BYTES = 1048576;

export interface SessionBindingView {
  runId: string;
  workspace: string;
  model: string;
  sessionId: string | null;
  state: string;
  core: { name?: string; title?: string; version?: string };
  protocolVersion: unknown;
  observedModel: string | null;
}

export interface ControlAckBody {
  action: string;
  state: 'RUNNING' | 'PAUSED' | 'STOPPED';
  verified: boolean;
  reason?: string;
  stopReason?: string;
  delivery: string;
  deduplicated: boolean;
}

export interface PermissionDecisionBody {
  requestId: number;
  choice: string;
  optionId: string;
  raw: string;
  deduplicated: boolean;
}

export interface PromptAckBody {
  promptId: number;
  sessionId: string | null;
  state: string;
}

export interface BridgeHandlers {
  health(): Record<string, unknown>;
  sessionBinding(body: Record<string, unknown> | null): SessionBindingView;
  prompt(body: Record<string, unknown> | null): PromptAckBody;
  control(body: Record<string, unknown> | null): Promise<ControlAckBody>;
  permissionResponse(body: Record<string, unknown> | null): Promise<PermissionDecisionBody>;
}

export interface BridgeServerOptions {
  secret: string;
  events: EventBuffer;
  handlers: BridgeHandlers;
  maxBodyBytes?: number;
  /** SSE backlog ceiling per consumer; a stream past it is closed. */
  maxSseBacklogBytes?: number;
}

const ROUTE_METHODS: Record<string, string[]> = {
  '/health': ['GET'],
  '/session': ['GET', 'POST'],
  '/prompt': ['POST'],
  '/events': ['GET'],
  '/permission-response': ['POST'],
  '/control': ['POST'],
};

const STATUS_BY_CODE: Record<string, number> = {
  E_BAD_REQUEST: 400,
  E_BAD_JSON: 400,
  E_INVALID_CHOICE: 400,
  E_SESSION_OVERRIDE_REJECTED: 400,
  E_UNSUPPORTED_CONTROL: 400,
  E_UNAUTHORIZED: 401,
  E_NOT_FOUND: 404,
  E_UNKNOWN_REQUEST: 404,
  E_BINDING_MISMATCH: 409,
  E_NOT_READY: 409,
  E_RUNTIME_EXITED: 409,
  E_PROMPT_IN_PROGRESS: 409,
  E_OPTION_UNAVAILABLE: 409,
  E_CONTROL_CONFLICT: 409,
  E_REPLAY_GAP: 409,
  E_REPLAY_AHEAD: 409,
  E_BODY_TOO_LARGE: 413,
  E_METHOD_NOT_ALLOWED: 405,
  E_PAUSE_UNSUPPORTED: 501,
  E_INTERNAL: 500,
};

export function createBridgeServer(options: BridgeServerOptions): Server {
  const maxBodyBytes = options.maxBodyBytes ?? DEFAULT_MAX_BODY_BYTES;
  const maxSseBacklogBytes = options.maxSseBacklogBytes ?? DEFAULT_MAX_SSE_BACKLOG_BYTES;
  return createServer((req, res) => {
    handleRequest(req, res, options, maxBodyBytes, maxSseBacklogBytes).catch((error: unknown) => {
      try {
        sendError(res, error);
      } catch {
        res.destroy();
      }
    });
  });
}

async function handleRequest(
  req: IncomingMessage,
  res: ServerResponse,
  options: BridgeServerOptions,
  maxBodyBytes: number,
  maxSseBacklogBytes: number,
): Promise<void> {
  let pathname: string;
  let url: URL;
  try {
    url = new URL(req.url ?? '/', 'http://127.0.0.1');
    pathname = url.pathname;
  } catch {
    sendError(res, new BridgeError('E_BAD_REQUEST', `Unparseable request URL: ${req.url ?? ''}`));
    return;
  }
  const method = req.method ?? 'GET';
  const allowed = ROUTE_METHODS[pathname];
  if (!allowed) {
    sendError(res, new BridgeError('E_NOT_FOUND', `Unknown route: ${method} ${pathname}`));
    return;
  }
  if (!allowed.includes(method)) {
    sendError(res, new BridgeError('E_METHOD_NOT_ALLOWED', `${method} is not allowed on ${pathname}`));
    return;
  }
  if (pathname !== '/health' && !authorized(req, options.secret)) {
    sendError(res, new BridgeError('E_UNAUTHORIZED', `Missing or invalid ${CONTROL_SECRET_HEADER} header`));
    return;
  }
  try {
    if (pathname === '/health') {
      sendJson(res, 200, options.handlers.health());
      return;
    }
    if (pathname === '/events') {
      handleEvents(res, url, options, maxSseBacklogBytes);
      return;
    }
    if (pathname === '/session' && method === 'GET') {
      sendJson(res, 200, options.handlers.sessionBinding(null));
      return;
    }
    const body = await readJsonBody(req, maxBodyBytes);
    if (pathname === '/session') {
      sendJson(res, 200, options.handlers.sessionBinding(body));
      return;
    }
    if (pathname === '/prompt') {
      sendJson(res, 200, options.handlers.prompt(body));
      return;
    }
    if (pathname === '/control') {
      sendJson(res, 200, await options.handlers.control(body));
      return;
    }
    if (pathname === '/permission-response') {
      sendJson(res, 200, await options.handlers.permissionResponse(body));
      return;
    }
    sendError(res, new BridgeError('E_NOT_FOUND', `Unknown route: ${method} ${pathname}`));
  } catch (error) {
    sendError(res, error);
  }
}

function handleEvents(
  res: ServerResponse,
  url: URL,
  options: BridgeServerOptions,
  maxSseBacklogBytes: number,
): void {
  const afterRaw = url.searchParams.get('after');
  const after = afterRaw === null ? 0 : Number(afterRaw);
  const follow = url.searchParams.get('follow') === '1';
  const since = options.events.since(after);
  if (!since.ok) {
    const code = since.reason === 'gap' ? 'E_REPLAY_GAP' : since.reason === 'ahead' ? 'E_REPLAY_AHEAD' : 'E_BAD_REQUEST';
    const message =
      since.reason === 'gap'
        ? `Event ${since.requestedAfter + 1} was evicted; retained events start at ${since.retainedFrom}`
        : since.reason === 'ahead'
          ? `Requested events after seq ${since.requestedAfter} but the last seq is ${since.lastSeq}`
          : `Invalid after value: ${afterRaw}`;
    sendJson(res, STATUS_BY_CODE[code] ?? 400, {
      error: { code, message, requestedAfter: since.requestedAfter, retainedFrom: since.retainedFrom, lastSeq: since.lastSeq },
    });
    return;
  }
  if (!follow) {
    sendJson(res, 200, { lastSeq: since.lastSeq, retainedFrom: since.retainedFrom, events: since.events });
    return;
  }
  res.writeHead(200, {
    'content-type': 'text/event-stream',
    'cache-control': 'no-store',
    connection: 'keep-alive',
  });
  // SSE consumers must see the stream open immediately (and be able to apply
  // backpressure) rather than waiting for the first buffered body write.
  res.flushHeaders();
  // SSE delivery is backpressure-aware and bounded. Frames queue only while the
  // socket is not draining; a consumer whose backlog passes the ceiling is
  // disconnected instead of growing the bridge's memory, and it can resume from
  // its last `seq` with a plain replay. The response error handler keeps a write
  // racing a client abort from surfacing as an unhandled stream error.
  let finished = false;
  let paused = false;
  let queuedBytes = 0;
  const queue: string[] = [];
  let keepAlive: NodeJS.Timeout | null = null;
  let unsubscribe = (): void => {};
  const finish = (): void => {
    if (finished) return;
    finished = true;
    if (keepAlive) clearInterval(keepAlive);
    unsubscribe();
    if (!res.writableEnded) res.destroy();
  };
  const pump = (): void => {
    if (finished || paused) return;
    while (queue.length > 0) {
      const text = queue.shift() as string;
      queuedBytes -= Buffer.byteLength(text, 'utf8');
      if (res.write(text) === false) {
        paused = true;
        return;
      }
    }
  };
  const enqueue = (text: string): void => {
    if (finished) return;
    const bytes = Buffer.byteLength(text, 'utf8');
    if (queuedBytes + bytes > maxSseBacklogBytes) {
      finish();
      return;
    }
    queue.push(text);
    queuedBytes += bytes;
    pump();
  };
  res.on('drain', () => {
    paused = false;
    pump();
  });
  res.on('error', finish);
  res.on('close', finish);
  keepAlive = setInterval(() => enqueue(': keep-alive\n\n'), 15000);
  keepAlive.unref?.();
  unsubscribe = options.events.subscribe((event) => enqueue(sseEventText(event)));
  for (const event of since.events) enqueue(sseEventText(event));
}

function sseEventText(event: { seq: number } & Record<string, unknown>): string {
  return `id: ${event.seq}\ndata: ${JSON.stringify(event)}\n\n`;
}

function authorized(req: IncomingMessage, secret: string): boolean {
  const provided = req.headers[CONTROL_SECRET_HEADER];
  if (typeof provided !== 'string' || provided.length === 0) return false;
  const a = createHash('sha256').update(provided).digest();
  const b = createHash('sha256').update(secret).digest();
  return timingSafeEqual(a, b);
}

function readJsonBody(req: IncomingMessage, maxBodyBytes: number): Promise<Record<string, unknown> | null> {
  return new Promise((resolve, reject) => {
    const declared = req.headers['content-length'];
    if (typeof declared === 'string' && Number(declared) > maxBodyBytes) {
      req.resume();
      reject(new BridgeError('E_BODY_TOO_LARGE', `Request body exceeds the ${maxBodyBytes}-byte limit`));
      return;
    }
    const chunks: Buffer[] = [];
    let size = 0;
    req.on('data', (chunk: Buffer) => {
      size += chunk.length;
      if (size > maxBodyBytes) {
        req.resume();
        reject(new BridgeError('E_BODY_TOO_LARGE', `Request body exceeds the ${maxBodyBytes}-byte limit`));
        return;
      }
      chunks.push(chunk);
    });
    req.on('error', (error) => {
      reject(new BridgeError('E_BAD_REQUEST', `Request stream failed: ${error.message}`));
    });
    req.on('end', () => {
      const text = Buffer.concat(chunks).toString('utf8').trim();
      if (text.length === 0) {
        resolve(null);
        return;
      }
      let parsed: unknown;
      try {
        parsed = JSON.parse(text);
      } catch (error) {
        reject(new BridgeError('E_BAD_JSON', `Request body is not valid JSON: ${(error as Error).message}`));
        return;
      }
      if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
        reject(new BridgeError('E_BAD_REQUEST', 'Request body must be a JSON object'));
        return;
      }
      resolve(parsed as Record<string, unknown>);
    });
  });
}

function sendError(res: ServerResponse, error: unknown): void {
  if (res.headersSent) {
    res.end();
    return;
  }
  if (error instanceof BridgeError) {
    sendJson(res, STATUS_BY_CODE[error.code] ?? 500, {
      error: { code: error.code, message: error.message, ...error.details },
    });
    return;
  }
  sendJson(res, 500, { error: { code: 'E_INTERNAL', message: (error as Error).message } });
}

function sendJson(res: ServerResponse, status: number, payload: unknown): void {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body, 'utf8'),
    'cache-control': 'no-store',
  });
  res.end(body);
}
