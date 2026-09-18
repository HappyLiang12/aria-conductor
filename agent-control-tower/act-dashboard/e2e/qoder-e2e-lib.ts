/**
 * C6a (part 1): shared helpers for the real-stack qoder Playwright specs.
 *
 * Frozen interface (coordinator ruling R41 — the plan's `e2e/qoder/lib/*` holds the
 * shell helpers owned by C6b; this TS module lives inside the dashboard e2e dir so
 * the repo's tsc gate sees it). Consumed by:
 *   - e2e/qoder-adk-e2e.spec.ts        (S1, S2, S3, S4, S6 — this dispatch)
 *   - e2e/qoder-governance-e2e.spec.ts (S7, S8, S9, S11, S12 — sibling dispatch)
 *
 * Every spec using this module MUST run these first in `test.beforeAll`:
 *
 *   test.beforeAll(async ({ request }) => {
 *     await assertZeroCreditModel(E2E_ENABLED ? request : undefined);
 *     requireEnabledOrSkip();
 *     skipUnlessPat();
 *   });
 *
 * The zero-credit env check hard-fails even when QODER_E2E is unset (plan RED
 * contract item 2: `QODER_E2E_MODEL=gpt-5 ... --grep "S1:"` must fail fast with the
 * zero-credit message and never reach the network); the live check (GET credential)
 * only runs when a request context is supplied.
 *
 * Secrets discipline: the PAT (QODER_E2E_PAT) is only ever placed in the PUT body
 * of the credential endpoint, in memory. It is never console.logged, never part of
 * a test title, URL, screenshot or error message. The platform MCP token is read
 * from `.run/mcp-token` and returned without logging.
 */
import { test, type APIRequestContext } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import * as path from 'node:path';
import { BACKEND, pollUntil } from './fixtures';

/** Re-export of fixtures' BACKEND (`${API_URL || http://localhost:8080}/api/v1`). */
export { BACKEND as API_BASE };

/** Dashboard base URL (same default as playwright.config.ts:15). */
export const BASE_URL = process.env.BASE_URL || 'http://localhost:5173';

/** Enable gate: everything is skipped unless QODER_E2E === '1'. */
export const E2E_ENABLED = process.env.QODER_E2E === '1';

/** PAT gate: QODER_E2E_PAT is the real Qoder PAT (never logged, never echoed). */
export const HAS_PAT = (process.env.QODER_E2E_PAT ?? '').trim().length > 0;

/** Escape hatch documented in the plan's zero-credit Global Constraint. */
export const ALLOW_PAID = process.env.QODER_E2E_ALLOW_PAID === '1';

/** Pinned run model; default `efficient` per the plan's Global Constraints. */
export const E2E_MODEL = (process.env.QODER_E2E_MODEL ?? '').trim() || 'efficient';

/** The zero-credit set (QoderSandboxHarness.java:57-61 semantics). */
export const ZERO_CREDIT_MODELS = ['efficient', 'lite'] as const;

/**
 * Plan citation used verbatim in the guard's failure message.
 * `docs/superpowers/plans/2026-09-17-qoder-cli-provider.md:16` (Global Constraints).
 */
export const ZERO_CREDIT_PLAN_REF =
  'plan docs/superpowers/plans/2026-09-17-qoder-cli-provider.md:16 Global Constraints: "All local'
  + ' E2E Qoder runs MUST use modelId: efficient (0.00x Credit ...). The E2E harness fails closed if'
  + ' the configured model is not in {efficient, lite} unless QODER_E2E_ALLOW_PAID=1 is explicitly set."';

/** Message for the enable-gate skip (verbatim wording required by the brief). */
export const ENABLED_SKIP_MESSAGE =
  'set QODER_E2E=1; this suite requires the real qoder stack (backend 8080, dashboard 5173,'
  + ' OpenSandbox 8090)';

/** Message for the PAT-gate skip: names the credential and cites the design section. */
export const PAT_SKIP_MESSAGE =
  "the 'qoder' runtime credential is not available: export QODER_E2E_PAT (the real Qoder PAT). The"
  + ' credential is the qoder runtime credential set via the Providers page or'
  + ' PUT /api/v1/adk/providers/qoder/credential — see'
  + ' docs/superpowers/specs/2026-09-17-qoder-cli-agent-core-design.md section 4.1 "PAT lifecycle"';

// ────────────────────────────────────────────────────────────────────────────
// Gates
// ────────────────────────────────────────────────────────────────────────────

/**
 * Skips every test in the file when QODER_E2E !== '1'. Call in `test.beforeAll`
 * (Playwright supports `test.skip` inside beforeEach/beforeAll hooks).
 */
export function requireEnabledOrSkip(): void {
  test.skip(!E2E_ENABLED, ENABLED_SKIP_MESSAGE);
}

/** Skips every test in the file when the QODER_E2E_PAT gate fails. */
export function skipUnlessPat(): void {
  test.skip(!HAS_PAT, PAT_SKIP_MESSAGE);
}

/**
 * Zero-credit guard, mirroring QoderSandboxHarness.java:57-61,172-177:
 *  1. env check (sync, no network): QODER_E2E_MODEL must be in {efficient, lite}
 *     unless QODER_E2E_ALLOW_PAID=1 — otherwise HARD-FAIL (throws).
 *  2. live check (only when `request` is given): GET the qoder credential status
 *     and refuse to run when the backend-reported `model` (QoderProperties.model,
 *     the model pinned via `qoder.model=<id>`) is not zero-credit.
 */
export async function assertZeroCreditModel(request?: APIRequestContext): Promise<void> {
  assertZeroCreditEnv();
  if (!request) return;

  const live = await credentialGet(request, 5_000);
  if (live.status !== 200) {
    throw new Error(
      `[qoder-e2e zero-credit guard] GET ${BACKEND}/adk/providers/qoder/credential returned HTTP`
      + ` ${live.status} ${JSON.stringify(live.data)?.slice(0, 200)} — cannot verify the live run`
      + ` model. ${ZERO_CREDIT_PLAN_REF}`,
    );
  }
  const liveModel = typeof live.data?.model === 'string' ? live.data.model.trim() : '';
  if (!liveModel) {
    throw new Error(
      '[qoder-e2e zero-credit guard] the live credential status carries no `model` field — cannot'
      + ` identify the model this stack will run (qoder.model / QoderProperties.model). ${ZERO_CREDIT_PLAN_REF}`,
    );
  }
  if (!isZeroCredit(liveModel) && !ALLOW_PAID) {
    throw new Error(
      `[qoder-e2e zero-credit guard] the live qoder.model='${liveModel}' is not a zero-credit model`
      + ` {efficient, lite}. ${ZERO_CREDIT_PLAN_REF}`,
    );
  }
  if (!isZeroCredit(liveModel)) {
    console.log(
      `[qoder-e2e] QODER_E2E_ALLOW_PAID=1: live qoder.model='${liveModel}' is NOT zero-credit —`
      + ' any credit/cost number observed in this run is a paid observation.',
    );
  } else {
    console.log(`[qoder-e2e] live zero-credit model confirmed: qoder.model='${liveModel}'`);
  }
}

/** Env half of the guard, exported for specs that gate on the pin before the stack exists. */
export function assertZeroCreditEnv(): void {
  if (!isZeroCredit(E2E_MODEL) && !ALLOW_PAID) {
    throw new Error(
      `[qoder-e2e zero-credit guard] QODER_E2E_MODEL='${E2E_MODEL}' is not in the zero-credit set`
      + ` {efficient, lite} and QODER_E2E_ALLOW_PAID is not '1'. ${ZERO_CREDIT_PLAN_REF}`,
    );
  }
  if (!isZeroCredit(E2E_MODEL)) {
    console.log(
      `[qoder-e2e] QODER_E2E_ALLOW_PAID=1: QODER_E2E_MODEL='${E2E_MODEL}' is NOT zero-credit.`,
    );
  }
}

export function isZeroCredit(model: string | null | undefined): boolean {
  return !!model && (ZERO_CREDIT_MODELS as readonly string[]).includes(model);
}

// ────────────────────────────────────────────────────────────────────────────
// HTTP plumbing
// ────────────────────────────────────────────────────────────────────────────

export interface JsonResult<T = any> {
  status: number;
  data: T;
}

/**
 * Direct REST call against the backend, with a caller-controlled timeout so the
 * guard/no-stack failures stay fast (fixtures.apiCall has no timeout knob).
 */
export async function requestJson<T = any>(
  request: APIRequestContext,
  method: string,
  apiPath: string,
  body?: object,
  timeoutMs = 30_000,
): Promise<JsonResult<T>> {
  const resp = await request.fetch(`${BACKEND}${apiPath}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    data: body ? JSON.stringify(body) : undefined,
    timeout: timeoutMs,
  });
  const data = (await resp.json().catch(() => null)) as T;
  return { status: resp.status(), data };
}

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

// ────────────────────────────────────────────────────────────────────────────
// Agents, runs, asks, credentials
// ────────────────────────────────────────────────────────────────────────────

export interface SeedQoderAgentOpts {
  name?: string;
  /**
   * Agent config as a JSON object — the REST contract is a Map
   * (CreateAgentRequest.config, act-agent CreateAgentRequest.java:35); a string
   * body fails deserialization with a LinkedHashMap creator error. Defaults to
   * `{"taskApprovalRequired": false}` so the legacy task-level gate
   * (AgentLoopEngine.taskApprovalGate, default-on) does not pause every scenario
   * before the ACP ask under test (per-tool HITL is the qoder gate). Pass an
   * explicit config to re-enable it.
   */
  config?: Record<string, unknown>;
}

/** POST /agents — ADK agent pinned to the `qoder` provider (fixtures' seeder defaults to opencode). */
export async function seedQoderAgent(request: APIRequestContext, opts: SeedQoderAgentOpts = {}) {
  const body: Record<string, unknown> = {
    name: opts.name ?? `e2e-qoder-${Date.now()}-${Math.floor(Math.random() * 10_000)}`,
    agentType: 'ADK',
    adkProvider: 'qoder',
    role: 'dev',
  };
  body.config = opts.config ?? { taskApprovalRequired: false };
  const { status, data } = await requestJson(request, 'POST', '/agents', body);
  if (status !== 201) {
    throw new Error(`seedQoderAgent failed: HTTP ${status} ${JSON.stringify(data)?.slice(0, 300)}`);
  }
  return data;
}

/** POST /runs — starts a run; the run executes asynchronously (poll, never assume). */
export async function startRun(
  request: APIRequestContext,
  agentId: string,
  promptSeed: string,
  maxIterations = 1,
): Promise<any> {
  const { status, data } = await requestJson(request, 'POST', '/runs', {
    agentId,
    promptSeed,
    maxIterations,
  });
  if (status !== 201 && status !== 200) {
    throw new Error(`startRun failed: HTTP ${status} ${JSON.stringify(data)?.slice(0, 300)}`);
  }
  return data;
}

export interface QoderAsk {
  id: string;
  runId?: string | null;
  kanbanItemId?: string | null;
  status?: string;
  source?: string | null;
  askType?: string | null;
  reason?: string | null;
  deliveryState?: string | null;
  displayJson?: string | null;
  expiresAt?: string | null;
  requestedAt?: string | null;
  decidedAt?: string | null;
  toolCallId?: string | null;
  [k: string]: unknown;
}

/** GET /approvals/{id}. */
export function askDetail(request: APIRequestContext, askId: string): Promise<JsonResult<QoderAsk>> {
  return requestJson<QoderAsk>(request, 'GET', `/approvals/${askId}`);
}

/**
 * Waits for a PENDING ACP permission ask (`source === 'ACP_PERMISSION'`, the exact
 * backend dispatch rule — see src/utils/acpAsk.ts:35) matching `predicate`.
 * Polls `GET /approvals?status=PENDING`; pass a runId-scoped predicate to avoid
 * matching a leftover ask from an earlier scenario.
 */
export async function waitForPendingAcpAsk(
  request: APIRequestContext,
  predicate: (ask: QoderAsk) => boolean = () => true,
  timeoutMs = 300_000,
): Promise<QoderAsk> {
  const deadline = Date.now() + timeoutMs;
  let last: unknown = null;
  while (Date.now() < deadline) {
    const { status, data } = await requestJson<QoderAsk[]>(request, 'GET', '/approvals?status=PENDING');
    last = data;
    if (status === 200 && Array.isArray(data)) {
      const hit = data.find((a) => a?.source === 'ACP_PERMISSION' && predicate(a));
      if (hit) return hit;
    }
    await sleep(2_000);
  }
  throw new Error(
    `waitForPendingAcpAsk: no PENDING ACP_PERMISSION ask within ${timeoutMs}ms;`
    + ` last list=${JSON.stringify(last)?.slice(0, 400)}`,
  );
}

/** All asks (any status) whose runId matches — there is no runId filter server-side. */
export async function listAsksForRun(request: APIRequestContext, runId: string): Promise<QoderAsk[]> {
  const { status, data } = await requestJson<QoderAsk[]>(request, 'GET', '/approvals');
  if (status !== 200 || !Array.isArray(data)) {
    throw new Error(`listAsksForRun failed: HTTP ${status} ${JSON.stringify(data)?.slice(0, 200)}`);
  }
  return data.filter((a) => a?.runId === runId);
}

/** Card ask list: `GET /approvals?kanbanItemId=` (all statuses; no status filter). */
export async function listAsksForCard(request: APIRequestContext, cardId: string): Promise<QoderAsk[]> {
  const { status, data } = await requestJson<QoderAsk[]>(
    request,
    'GET',
    `/approvals?kanbanItemId=${encodeURIComponent(cardId)}`,
  );
  if (status !== 200 || !Array.isArray(data)) {
    throw new Error(`listAsksForCard failed: HTTP ${status} ${JSON.stringify(data)?.slice(0, 200)}`);
  }
  return data;
}

/**
 * `POST /approvals/{id}/decide` `{approved, reason}`. Returns the raw result so
 * callers can assert typed failures verbatim: 200
 * `{approvalId, approved, status:'processed', decision, deliveryState}`, or a typed
 * 409 `{code: EXPIRED|ALREADY_DECIDED|UNSUPPORTED_OPTIONS|INCONSISTENT_ASK, error}`.
 */
export function decideAsk(
  request: APIRequestContext,
  askId: string,
  approved: boolean,
  reason?: string,
): Promise<JsonResult<any>> {
  return requestJson(request, 'POST', `/approvals/${askId}/decide`, { approved, reason });
}

/** GET /runs/{id}. */
export function runDetail(request: APIRequestContext, runId: string): Promise<JsonResult<any>> {
  return requestJson(request, 'GET', `/runs/${runId}`);
}

/** GET /runs/{id}/tool-calls (run-correlated audit rows). */
export function runToolCalls(request: APIRequestContext, runId: string): Promise<JsonResult<any[]>> {
  return requestJson(request, 'GET', `/runs/${runId}/tool-calls`);
}

/**
 * GET /runs/{id}/progress — the run's progress entries. The qoder bridge pins the
 * model by publishing a STATUS event with content `qoder.model=<id>`
 * (QoderProgressPump.java:207-208), surfaced here as
 * `{kind:'STATUS', content:'qoder.model=efficient', seq, ...}` (dashboard type
 * `RunProgressEntry`, src/api/runs.ts:36-47).
 */
export function runProgress(request: APIRequestContext, runId: string): Promise<JsonResult<any[]>> {
  return requestJson(request, 'GET', `/runs/${runId}/progress?afterSeq=0`, undefined, 30_000);
}

// ── Credential API (the qoder runtime credential, never the raw PAT) ──────────

/** GET /adk/providers/qoder/credential → `{providerId, configured, patMasked|null, updatedAt|null, model}`. */
export function credentialGet(request: APIRequestContext, timeoutMs = 30_000) {
  return requestJson(request, 'GET', '/adk/providers/qoder/credential', undefined, timeoutMs);
}

/** PUT the PAT (in-memory only; the response masks it as `****last4`). */
export function credentialSet(request: APIRequestContext, pat: string) {
  return requestJson(request, 'PUT', '/adk/providers/qoder/credential', { pat });
}

/** DELETE (204, idempotent). */
export function credentialDelete(request: APIRequestContext) {
  return requestJson(request, 'DELETE', '/adk/providers/qoder/credential');
}

/** POST /test → `{success, reason?, model, billable, costNote, message?}` (no inference, no billing). */
export function credentialTest(request: APIRequestContext) {
  return requestJson(request, 'POST', '/adk/providers/qoder/credential/test');
}

/** The `****last4` mask shape emitted by RuntimeCredentialService. */
export function isMaskedPat(value: unknown): value is string {
  return typeof value === 'string' && /^\*{4}.{1,4}$/.test(value);
}

// ────────────────────────────────────────────────────────────────────────────
// Platform MCP token (worker/operator surfaces, S7/S8/S11)
// ────────────────────────────────────────────────────────────────────────────

/**
 * The platform MCP bearer minted by `scripts/start.ps1:327-330` into
 * `<repo>/.run/mcp-token` (env `MCP_TOKEN_FILE` overrides). This is a local dev
 * token, NOT the Qoder PAT. Returned in memory only — never logged.
 */
export function platformMcpToken(): string {
  const explicit = (process.env.MCP_TOKEN_FILE ?? '').trim();
  const candidates = [
    explicit,
    path.resolve(process.cwd(), '.run', 'mcp-token'),
    path.resolve(process.cwd(), '..', '..', '.run', 'mcp-token'),
    path.resolve(process.cwd(), '..', '..', '..', '.run', 'mcp-token'),
  ].filter(Boolean);
  for (const candidate of candidates) {
    if (existsSync(candidate)) {
      const token = readFileSync(candidate, 'utf8').trim();
      if (token) return token;
    }
  }
  throw new Error(
    `platform MCP token file not found (looked at: ${candidates.join(', ')}). Start the qoder stack`
    + ' with scripts/start.ps1 (it writes <repo>/.run/mcp-token) or export MCP_TOKEN_FILE.',
  );
}

// ────────────────────────────────────────────────────────────────────────────
// Qoder sandbox file side effects (S2/S3/S4)
// ────────────────────────────────────────────────────────────────────────────

/**
 * The pinned sandbox image. There is NO backend route that reads a file inside a
 * qoder sandbox: `SandboxLifecycle` runs files in-sandbox (`sandbox.files().write`,
 * `runCommand`) and `GET /api/v1/runs/{id}/workspace-diff` only reads the HOST
 * workspace (WorkspaceDiffController.java:44-52), which the sandbox never writes
 * back. The honest host-side route is the one the bash harness already uses —
 * `e2e/qoder/lib/stack.sh:94-101` lists the run's sandbox container via
 * `<runtime> ps --filter ancestor=<image>` and `stack.sh:114-131` execs `/proc`
 * scans inside it. These helpers mirror that route with `<runtime> exec`.
 * The run's CLI cwd is `/workspace` (QoderAdkProvider.java:89 SANDBOX_CWD).
 */
export const SANDBOX_IMAGE = process.env.QODER_SANDBOX_IMAGE || 'aria-conductor/qoder-sandbox:0.1';

let runtimeCache: string | null | undefined;

/** Resolves CONTAINER_RUNTIME || podman || docker; throws a descriptive error when none exists. */
export function containerRuntime(): string {
  if (runtimeCache === undefined) {
    const explicit = (process.env.CONTAINER_RUNTIME ?? '').trim();
    runtimeCache = null;
    for (const candidate of [explicit, 'podman', 'docker'].filter(Boolean)) {
      try {
        execFileSync(candidate, ['--version'], { stdio: 'ignore' });
        runtimeCache = candidate;
        break;
      } catch {
        /* try the next candidate */
      }
    }
  }
  if (!runtimeCache) {
    throw new Error(
      'no container runtime CLI available (tried CONTAINER_RUNTIME, podman, docker). The S2/S3/S4'
      + ' file side-effect check needs `<runtime> exec` against the run sandbox container'
      + ' (mirrors e2e/qoder/lib/stack.sh:94-131). Start the stack with podman or export CONTAINER_RUNTIME.',
    );
  }
  return runtimeCache;
}

/** Running/created containers of the qoder sandbox image (ids only). */
export function listSandboxContainerIds(): string[] {
  const out = execFileSync(
    containerRuntime(),
    ['ps', '--filter', `ancestor=${SANDBOX_IMAGE}`, '--format', '{{.ID}}'],
    { encoding: 'utf8' },
  );
  return out
    .split(/\r?\n/)
    .map((line: string) => line.trim())
    .filter(Boolean);
}

/** Waits until a container id not present in `beforeIds` appears (the run's fresh sandbox). */
export async function waitForNewSandboxContainer(
  beforeIds: string[],
  timeoutMs = 180_000,
  intervalMs = 2_000,
): Promise<string> {
  const known = new Set(beforeIds);
  const deadline = Date.now() + timeoutMs;
  let last: string[] = [];
  while (Date.now() < deadline) {
    last = listSandboxContainerIds();
    const fresh = last.find((id) => !known.has(id));
    if (fresh) return fresh;
    await sleep(intervalMs);
  }
  throw new Error(
    `no new sandbox container from image ${SANDBOX_IMAGE} appeared within ${timeoutMs}ms`
    + ` (containers seen: ${JSON.stringify(last)})`,
  );
}

export function containerExists(containerId: string): boolean {
  try {
    execFileSync(containerRuntime(), ['inspect', '--type', 'container', containerId], { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

export interface SandboxFileProbe {
  containerId: string;
  path: string;
  exists: boolean;
  content: string | null;
  /** How the observation was made — quoted verbatim into the report. */
  note: string;
}

/**
 * Reads `filePath` inside the sandbox container via `<runtime> exec <id> sh -c
 * 'test -f "$1" && cat "$1"'`. A non-zero exit is disambiguated with `inspect`:
 * a live container means the file is absent; a vanished container means the
 * absence was not directly observed (`note` records which).
 */
export function readFileInSandbox(containerId: string, filePath: string): SandboxFileProbe {
  const rt = containerRuntime();
  try {
    const content = execFileSync(
      rt,
      ['exec', containerId, 'sh', '-c', 'test -f "$1" && cat "$1"', 'sh', filePath],
      { encoding: 'utf8' },
    );
    return { containerId, path: filePath, exists: true, content, note: 'cat via container exec' };
  } catch {
    if (!containerExists(containerId)) {
      return {
        containerId,
        path: filePath,
        exists: false,
        content: null,
        note: 'container no longer exists — absence NOT directly observed',
      };
    }
    return {
      containerId,
      path: filePath,
      exists: false,
      content: null,
      note: 'test -f failed inside a live container (file absent)',
    };
  }
}

/** Polls until the sandbox file exists (or the deadline elapses); returns the probe either way. */
export async function waitForSandboxFile(
  containerId: string,
  filePath: string,
  timeoutMs = 120_000,
  intervalMs = 3_000,
): Promise<SandboxFileProbe> {
  const deadline = Date.now() + timeoutMs;
  let probe = readFileInSandbox(containerId, filePath);
  while (!probe.exists && Date.now() < deadline) {
    await sleep(intervalMs);
    probe = readFileInSandbox(containerId, filePath);
  }
  return probe;
}

// ────────────────────────────────────────────────────────────────────────────
// Convenience re-exports used by both spec files
// ────────────────────────────────────────────────────────────────────────────

export { pollUntil };
