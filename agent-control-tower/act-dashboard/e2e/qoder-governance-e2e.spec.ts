/**
 * C6a (part 2 of 2): real-stack qoder governance scenarios S7, S8, S9, S11, S12
 * (plan docs/superpowers/plans/2026-09-17-qoder-cli-provider.md §C0.8 matrix; brief
 * .superpowers/sdd/2026-09-17-qoder-cli-provider/task-C6a-brief.md).
 *
 * S1/S2/S3/S4/S6 live in e2e/qoder-adk-e2e.spec.ts (part 1); S5/S10 are bash-owned
 * by C6b. Shared helpers come from ./qoder-e2e-lib (frozen, part 1) and ./fixtures.
 *
 * Gates (same contract as part 1): QODER_E2E=1 (else skip), QODER_E2E_PAT (else
 * skip, names the qoder runtime credential), QODER_E2E_MODEL in {efficient, lite}
 * (else hard-fail, plan Global Constraint) plus the live `qoder.model` check
 * against GET /adk/providers/qoder/credential.
 *
 * ── The worker-token fallback (S7/S8) — evidence split, stated per half ────────
 * The run-scoped worker token is an in-memory `wcp_`-prefixed opaque string
 * (RunScopedCredentialService.java:58,116-135), issued only at run start by
 * QoderAdkProvider.java:288-297 and consumed by the sandbox bridge config
 * (:885-918). There is no host-side issuance route and no endpoint that echoes a
 * live token, so the specs below CANNOT act as a real worker:
 *
 *   - S7 E2E half: the platform MCP endpoint rejects an unknown worker-SHAPED
 *     bearer (HTTP 401, McpTokenFilter.java:74-77 via WorkerScopeResolver.java:65-83)
 *     and the target ask keeps its status. The positive control (the platform
 *     bearer initializes the same endpoint, HTTP 200) keeps the 401 non-vacuous.
 *   - S7 integration-evidence half: a LIVE worker token reaching the tool seam is
 *     denied `decide_approval` with the OPERATOR_ONLY code — in-repo evidence:
 *     McpWorkerEndpointIntegrationTest.java:261-267 (streamable HTTP, real worker
 *     token) + ToolPolicyRegistry.java:119-121 (pinned denial) +
 *     ToolPolicyRegistryTest.java:159. The brief cites "McpEndpointIntegrationTest.java"
 *     for this; that file covers the operator handshake/tool inventory and is used
 *     here as the operator-parity test only (the worker-denial class is
 *     McpWorkerEndpointIntegrationTest).
 *   - S8 E2E half (caller-independent parts): the identical write replayed over the
 *     operator bearer executes twice for real (no accidental grant/dedup machinery
 *     on the operator path — WorkerGovernanceAspect.java:69-72), and an unknown
 *     worker-shaped bearer cannot make that write execute at all (401, no side
 *     effect). The worker one-use grant consumption (grant removed by the first
 *     matching call, GRANT_REQUIRED on replay — WorkerGovernanceAspect.java:84-91,
 *     WriteGrantService.java:26-44,100-116) needs a live worker token; it is
 *     recorded as NOT VERIFIED by the test and evidenced in-repo by
 *     McpWorkerEndpointIntegrationTest.workerToken_writeWithoutGrant_isDenied...
 *     plus WriteGrantServiceTest.
 *
 * No token is ever fabricated as a *real* worker credential: the unknown bearer is
 * minted locally as an obviously-foreign value and is only ever expected to be
 * REJECTED. No secrets are logged.
 */
import { test, expect, type APIRequestContext } from '@playwright/test';
import {
  apiCall,
  pollRunTerminal,
  pollUntil,
  seedAdkAgent,
  seedKanbanItem,
  transitionKanban,
  uniqueName,
} from './fixtures';
import {
  BASE_URL,
  E2E_ENABLED,
  askDetail,
  assertZeroCreditModel,
  decideAsk,
  listAsksForCard,
  listAsksForRun,
  platformMcpToken,
  requestJson,
  requireEnabledOrSkip,
  runProgress,
  runToolCalls,
  seedQoderAgent,
  skipUnlessPat,
  startRun,
  waitForPendingAcpAsk,
  type QoderAsk,
} from './qoder-e2e-lib';

// Real sandbox boot + CLI session per qoder run; the config default (120s) is far too small.
test.setTimeout(600_000);

test.beforeAll(async ({ request }) => {
  // Env half hard-fails even when the suite is otherwise disabled (RED contract
  // item 2); the live credential-model check only runs when the suite is enabled.
  await assertZeroCreditModel(E2E_ENABLED ? request : undefined);
  requireEnabledOrSkip();
  skipUnlessPat();
});

// ────────────────────────────────────────────────────────────────────────────
// Platform MCP HTTP driver (streamable transport, raw JSON-RPC over POST /mcp)
// ────────────────────────────────────────────────────────────────────────────

/**
 * The backend-embedded MCP server listens on the backend port at /mcp — NOT under
 * /api/v1 (application.yml:65-74 `aria.mcp.port: 8080`; McpTokenFilter.java:47).
 * The qoder stack pins `aria.mcp.auth-mode=token` with the `.run/mcp-token` bearer
 * (scripts/start.ps1:129-141,322-342) because a qoder sandbox shares the host
 * network and could otherwise reach the operator API unauthenticated.
 */
const BACKEND_ORIGIN = (process.env.API_URL || 'http://localhost:8080').replace(/\/+$/, '');
const MCP_ENDPOINT = `${BACKEND_ORIGIN}/mcp`;

/**
 * True when a URL addresses the backend. Browser-issued calls carry the dashboard
 * origin (the axios client uses a relative baseURL, src/api/client.ts:3-6, and the
 * Vite dev server proxies /api to the backend, vite.config.ts:14) — NOT
 * BACKEND_ORIGIN; context-API calls (request.*) do hit BACKEND_ORIGIN directly.
 * S12's failed-request filter must match both shapes or it records nothing.
 */
const isBackendUrl = (u: string) =>
  u.startsWith(BACKEND_ORIGIN)
  || (u.startsWith(BASE_URL) && (u.includes('/api/') || u.includes('/actuator/') || u.includes('/mcp')));

/** Accepted initialize protocol versions, newest first (tried in order). */
const MCP_PROTOCOL_VERSIONS = ['2025-03-26', '2024-11-05'];

interface McpHttpResult {
  status: number;
  sessionId: string | null;
  body: string;
  /** Parsed JSON-RPC payload: plain JSON body, or the last `data:` line of an SSE body. */
  jsonRpc: any | null;
}

function parseSseOrJson(body: string): any | null {
  const trimmed = body.trim();
  if (!trimmed) return null;
  if (trimmed.startsWith('{')) {
    try {
      return JSON.parse(trimmed);
    } catch {
      return null;
    }
  }
  // SSE: keep the LAST data line (the streamable transport writes one event per response).
  const dataLines = trimmed.split(/\r?\n/).filter((l) => l.startsWith('data:'));
  for (let i = dataLines.length - 1; i >= 0; i -= 1) {
    try {
      return JSON.parse(dataLines[i].slice('data:'.length).trim());
    } catch {
      /* try the previous line */
    }
  }
  return null;
}

/** One POST /mcp round-trip. `bearer === null` sends no Authorization header at all. */
async function mcpPost(
  request: APIRequestContext,
  bearer: string | null,
  payload: object,
  sessionId?: string,
): Promise<McpHttpResult> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'application/json, text/event-stream',
  };
  if (bearer !== null) headers.Authorization = `Bearer ${bearer}`;
  if (sessionId) headers['mcp-session-id'] = sessionId;
  const resp = await request.fetch(MCP_ENDPOINT, {
    method: 'POST',
    headers,
    data: JSON.stringify(payload),
    timeout: 20_000,
  });
  const body = await resp.text();
  const headerSession = resp.headers()['mcp-session-id'] ?? null;
  return {
    status: resp.status(),
    sessionId: headerSession ?? sessionId ?? null,
    body,
    jsonRpc: parseSseOrJson(body),
  };
}

/** True when the response is an accepted initialize (HTTP 200 + a JSON-RPC `result`). */
function mcpInitAccepted(res: McpHttpResult): boolean {
  return res.status === 200 && (res.jsonRpc?.result != null || /"result"\s*:/.test(res.body));
}

/**
 * Raw initialize handshake. Tries the pinned protocol versions in order and
 * returns the accepted attempt (or the last attempt when none was accepted — the
 * caller asserts and prints both). No credentials are logged anywhere here.
 */
async function mcpInitialize(
  request: APIRequestContext,
  bearer: string,
): Promise<{ accepted: McpHttpResult | null; attempts: McpHttpResult[] }> {
  const attempts: McpHttpResult[] = [];
  for (const protocolVersion of MCP_PROTOCOL_VERSIONS) {
    const res = await mcpPost(request, bearer, {
      jsonrpc: '2.0',
      id: 1,
      method: 'initialize',
      params: {
        protocolVersion,
        capabilities: {},
        clientInfo: { name: 'qoder-governance-e2e', version: '1.0.0' },
      },
    });
    attempts.push(res);
    if (mcpInitAccepted(res)) return { accepted: res, attempts };
  }
  return { accepted: null, attempts };
}

/** Completes the session (initialize + notifications/initialized) and returns the session id. */
async function mcpOpenSession(request: APIRequestContext, bearer: string): Promise<string> {
  const init = await mcpInitialize(request, bearer);
  expect(
    init.accepted,
    `MCP initialize was refused with the platform bearer — attempts: `
    + init.attempts
      .map((a) => `[${a.status}] ${a.body.slice(0, 160)}`)
      .join(' | '),
  ).toBeTruthy();
  const sessionId = init.accepted?.sessionId ?? null;
  expect(
    sessionId,
    'the streamable transport must issue an mcp-session-id on initialize — tool calls need it',
  ).toBeTruthy();
  const notified = await mcpPost(
    request,
    bearer,
    { jsonrpc: '2.0', method: 'notifications/initialized' },
    sessionId!,
  );
  expect([200, 202], `notifications/initialized answered HTTP ${notified.status}`).toContain(
    notified.status,
  );
  return sessionId!;
}

/** tools/call over an open session. Returns the HTTP result plus the text of the tool result. */
async function mcpCallTool(
  request: APIRequestContext,
  bearer: string,
  sessionId: string,
  name: string,
  args: Record<string, unknown>,
): Promise<McpHttpResult & { toolText: string; isError: boolean }> {
  const res = await mcpPost(
    request,
    bearer,
    { jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name, arguments: args } },
    sessionId,
  );
  const content = Array.isArray(res.jsonRpc?.result?.content) ? res.jsonRpc.result.content : [];
  const toolText = content
    .filter((c: any) => c?.type === 'text')
    .map((c: any) => String(c.text ?? ''))
    .join('\n');
  return { ...res, toolText, isError: res.jsonRpc?.result?.isError === true };
}

function toolJson(text: string): any {
  const start = text.indexOf('{');
  if (start < 0) return null;
  try {
    return JSON.parse(text.slice(start));
  } catch {
    return null;
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Shared snippet helpers
// ────────────────────────────────────────────────────────────────────────────

/** Same shape as the A4 probe prompt (e2e/qoder/slice-a/04-permissions.mjs) and part 1. */
function writePrompt(filePath: string): string {
  return `Create a new file at ${filePath} containing exactly the two characters "hi" (no trailing period).`
    + ' Use the Write file tool and nothing else; do not use shell commands.';
}

/** The run's CLI cwd is /workspace (QoderAdkProvider.java:89 SANDBOX_CWD). */
function sandboxFilePath(label: string): string {
  return `/workspace/e2e-${label}-${Date.now()}-${Math.floor(Math.random() * 10_000)}.txt`;
}

function acpOptions(ask: QoderAsk): Array<{ optionId?: string; kind?: string; name?: string }> {
  try {
    const parsed = JSON.parse(ask.displayJson ?? '');
    return Array.isArray(parsed?.options) ? parsed.options : [];
  } catch {
    return [];
  }
}

/** Progress entries whose toolName names one of `names` (substring match, CLI prefixes tolerated). */
function progressToolEntries(entries: any[], kind: string, names: string[]): any[] {
  return (entries ?? []).filter((e: any) => {
    if (e?.kind !== kind) return false;
    const toolName = String(e?.toolName ?? '');
    return names.some((n) => toolName.includes(n));
  });
}

// ────────────────────────────────────────────────────────────────────────────
// S7 — worker self-approval denied
// ────────────────────────────────────────────────────────────────────────────

/**
 * Matrix S7: "`decide_approval` → 403; no status change" with a run-scoped token.
 *
 * Deviations from the matrix wording, both grounded:
 *  - the real HTTP status for a rejected identity is 401, not 403: the filter
 *    answers SC_UNAUTHORIZED before the request is routed (McpTokenFilter.java:74-77),
 *    and in token mode there is no anonymous fallback (WorkerScopeResolver.java:66-71).
 *  - the 403-equivalent tool-level denial (OPERATOR_ONLY) needs a LIVE worker token,
 *    which has no host-side seam (see the file header). That half is integration-test
 *    evidence; the E2E half below uses an unknown worker-shaped bearer, which the
 *    platform must reject at the transport before any tool dispatch.
 */
test('S7: worker self-approval denied — decide_approval unreachable with an unknown worker-shaped MCP bearer', async ({ request }) => {
  // ── Positive control ────────────────────────────────────────────────────────
  // The same endpoint with the platform bearer must work, or the 401 below would
  // be vacuous ("endpoint rejects everything").
  const platformToken = platformMcpToken();
  const init = await mcpInitialize(request, platformToken);
  console.log(
    `[S7] control initialize (platform bearer): HTTP ${init.attempts.map((a) => a.status).join(',')}`,
  );
  expect(
    init.accepted,
    `the platform bearer was refused by ${MCP_ENDPOINT} — the stack must run with `
    + `aria.mcp.auth-mode=token and ARIA_MCP_TOKEN=<.run/mcp-token> (start.ps1:129-141,322-342). `
    + `attempts: ${init.attempts.map((a) => `[${a.status}] ${a.body.slice(0, 160)}`).join(' | ')}`,
  ).toBeTruthy();

  // ── A real PENDING ask is the decision target ───────────────────────────────
  // The default-on task-level gate creates the ask BEFORE any provider call
  // (kanban-hitl.spec.ts:136-158), so no LLM key and no sandbox are needed. The
  // MCP surface is provider-independent — the identity filter and policy table do
  // not know which provider owns the ask.
  const agent = await seedAdkAgent(request, {
    name: uniqueName('e2e-oc-s7'),
    adkProvider: 'opencode',
  });
  const card = await seedKanbanItem(request, {
    title: `s7-${uniqueName('card')}`,
    agentTemplateId: agent.name,
  });
  // A card created in TODO is itself a dispatch intent: KanbanAutoDispatchListener
  // (auto-dispatch-on-create, on by default) dispatches it right after the create
  // commits and races this explicit operator move. Losing that race answers 409
  // ("Card was modified by another move") on an already-dispatched card, so only a
  // status outside {200, 409} is a real dispatch failure — same stance as
  // qoder-adk-e2e.spec.ts's dispatchWriteCard and kanban-hitl.spec.ts.
  const moved = await transitionKanban(request, card.id, 'IN_PROGRESS');
  if (moved.status !== 200 && moved.status !== 409) {
    throw new Error(`TODO→IN_PROGRESS dispatch rejected: ${JSON.stringify(moved.data)}`);
  }
  const pendingList = await pollUntil<any[]>(
    request,
    `/approvals?kanbanItemId=${card.id}`,
    (list) => Array.isArray(list) && list.some((a) => a.status === 'PENDING'),
    60_000,
    2_000,
  );
  const ask = pendingList.find((a) => a.status === 'PENDING');
  expect(ask, 'no PENDING ask materialized for the dispatched card').toBeTruthy();
  console.log(`[S7] target ask=${ask.id} status=${ask.status} runId=${ask.runId}`);

  // ── The attempt: an unknown worker-SHAPED bearer (never presented as a real token) ──
  // `wcp_` is the real worker prefix (RunScopedCredentialService.java:58) — the value
  // is minted locally and can only ever be unknown to the credential service.
  const unknownWorkerBearer = `wcp_e2e_unknown_${Date.now()}_${Math.floor(Math.random() * 1_000_000)}`;
  const attempt = await mcpPost(request, unknownWorkerBearer, {
    jsonrpc: '2.0',
    id: 3,
    method: 'tools/call',
    params: {
      name: 'decide_approval',
      arguments: { approvalId: ask.id, approved: true, reason: 'S7 worker self-approval attempt' },
    },
  });
  console.log(
    `[S7] unknown worker-shaped bearer on decide_approval: HTTP ${attempt.status}`
    + ` body=${JSON.stringify(attempt.body)?.slice(0, 200)}`,
  );
  expect(
    attempt.status,
    'an unknown worker-shaped bearer must be rejected before any tool dispatch'
    + ` (McpTokenFilter.java:74-77); got HTTP ${attempt.status}`
    + ` body=${JSON.stringify(attempt.body)?.slice(0, 200)}`,
  ).toBe(401);

  // ── No status change ────────────────────────────────────────────────────────
  const after = await askDetail(request, ask.id);
  console.log(`[S7] ask after the rejected attempt: status=${after.data?.status}`);
  expect(after.status).toBe(200);
  expect(after.data?.status).toBe('PENDING');

  // ── Evidence split (also in the report) ────────────────────────────────────
  console.log(
    '[S7] E2E-verified half: unknown worker-shaped bearer rejected HTTP 401 on /mcp'
    + ' (transport), ask unchanged. Integration-test half (NOT E2E): a LIVE worker token'
    + ' reaches the tool seam and is denied OPERATOR_ONLY —'
    + ' act-mcp/src/test/java/io/aria/conductor/mcp/McpWorkerEndpointIntegrationTest.java:261-267'
    + ' (decide_approval → OPERATOR_ONLY), pinned by ToolPolicyRegistry.java:119-121 and'
    + ' ToolPolicyRegistryTest.java:159.',
  );

  // Cleanup: cancel denies the open ask and cancels the linked run (kanban-hitl.spec.ts:174-178).
  const cancelled = await transitionKanban(request, card.id, 'CANCELLED');
  console.log(`[S7] cleanup: card CANCELLED transition HTTP ${cancelled.status}`);
});

// ────────────────────────────────────────────────────────────────────────────
// S8 — one-use write grant replay
// ────────────────────────────────────────────────────────────────────────────

/**
 * Matrix S8: "second identical write call without a new approval fails; no
 * duplicate side effect".
 *
 * Honest fallback per the coordinator ruling (task-C6a part 2): the worker token
 * has no host-side seam, so the worker-side grant consumption cannot be driven
 * E2E. This test asserts the caller-independent halves that ARE observable:
 *  1. control: the platform (operator) bearer opens a streamable session;
 *  2. replay: the identical WORKER_WRITE tool call twice over the operator bearer
 *     executes twice for real (operator calls bypass governance and grants —
 *     WorkerGovernanceAspect.java:69-72), proving no accidental grant/dedup
 *     machinery sits on the operator path;
 *  3. an unknown worker-shaped bearer cannot make the same write execute (401, no
 *     side effect) — the replay side of the boundary that IS transport-visible.
 * The worker one-use consumption half (grant consumed once →
 * GRANT_REQUIRED on replay) is printed as NOT VERIFIED with the in-repo evidence:
 * McpWorkerEndpointIntegrationTest.workerToken_writeWithoutGrant_isDenied...,
 * WriteGrantServiceTest (single use), WorkerGovernanceAspectTest.
 */
test('S8: one-use write-grant replay — operator replay control + forged worker write denied (worker one-use half NOT VERIFIED: no worker-token seam)', async ({ request }) => {
  const platformToken = platformMcpToken();
  const sessionId = await mcpOpenSession(request, platformToken);

  // Identical WORKER_WRITE invocation, twice. `create_kanban_item` is a reviewed
  // WORKER_WRITE row (ToolPolicyRegistry.java:103-104) with no dispatch side effect
  // (no agentTemplateId), so its only observable is the created card itself.
  const title = `s8-replay-${uniqueName('card')}`;
  const args = { title };
  const call1 = await mcpCallTool(request, platformToken, sessionId, 'create_kanban_item', args);
  const call2 = await mcpCallTool(request, platformToken, sessionId, 'create_kanban_item', args);
  console.log(
    `[S8] operator replay: call1 HTTP ${call1.status} isError=${call1.isError}`
    + ` | call2 HTTP ${call2.status} isError=${call2.isError}`,
  );
  for (const [i, call] of [call1, call2].entries()) {
    expect(call.status, `replay call ${i + 1} answered HTTP ${call.status}`).toBe(200);
    expect(
      call.isError,
      `replay call ${i + 1} must execute for the operator — tool text: ${call.toolText.slice(0, 300)}`,
    ).toBe(false);
  }
  const id1 = toolJson(call1.toolText)?.data?.id ?? null;
  const id2 = toolJson(call2.toolText)?.data?.id ?? null;
  expect(id1, `call 1 returned no item id: ${call1.toolText.slice(0, 300)}`).toBeTruthy();
  expect(id2, `call 2 returned no item id: ${call2.toolText.slice(0, 300)}`).toBeTruthy();
  expect(id2, 'an operator replay must be a new execution, not a dedup of call 1').not.toBe(id1);

  const items = await requestJson<any[]>(request, 'GET', '/kanban/items');
  expect(items.status).toBe(200);
  const created = (items.data ?? []).filter((i: any) => i?.title === title);
  console.log(`[S8] REST confirms ${created.length} item(s) titled '${title}' (ids ${id1}, ${id2})`);
  expect(created.length, 'both identical operator calls must have produced a real card').toBe(2);

  // The forged worker bearer cannot make the same write execute at all.
  const forgedTitle = `${title}-forged`;
  const forged = await mcpPost(
    request,
    `wcp_e2e_unknown_${Date.now()}_${Math.floor(Math.random() * 1_000_000)}`,
    {
      jsonrpc: '2.0',
      id: 4,
      method: 'tools/call',
      params: { name: 'create_kanban_item', arguments: { title: forgedTitle } },
    },
  );
  console.log(
    `[S8] unknown worker-shaped bearer on the same write: HTTP ${forged.status}`
    + ` body=${JSON.stringify(forged.body)?.slice(0, 200)}`,
  );
  expect(forged.status).toBe(401);
  const itemsAfter = await requestJson<any[]>(request, 'GET', '/kanban/items');
  const forgedRows = (itemsAfter.data ?? []).filter((i: any) => i?.title === forgedTitle);
  expect(forgedRows.length, 'the rejected worker-shaped write must leave no side effect').toBe(0);

  console.log(
    '[S8] NOT VERIFIED (worker one-use half): grant consumed by the first matching worker call and'
    + ' GRANT_REQUIRED on replay — needs a LIVE worker token, not obtainable host-side'
    + ' (RunScopedCredentialService.java:58,116-135; QoderAdkProvider.java:288-297). In-repo evidence:'
    + ' McpWorkerEndpointIntegrationTest.workerToken_writeWithoutGrant_isDenied_andTheServiceIsUntouched,'
    + ' WriteGrantServiceTest (single use), WorkerGovernanceAspectTest.',
  );
  console.log(
    '[S8] E2E-verified halves: operator replay executes twice (no dedup/grant on the operator path)'
    + ' and an unknown worker-shaped bearer is rejected 401 with no side effect.',
  );
});

// ────────────────────────────────────────────────────────────────────────────
// S9 — kanban-dispatched run
// ────────────────────────────────────────────────────────────────────────────

test('S9: kanban-dispatched qoder run — board moves the card, the Review surface shows the live ask, the decision does not mark it DONE', async ({ page, request }) => {
  const filePath = sandboxFilePath('s9');
  const agent = await seedQoderAgent(request, { name: uniqueName('e2e-qoder-s9') });

  // Auto-dispatch on create is ON by default (aria.kanban.auto-dispatch-on-create):
  // a TODO card pinned to an agent template dispatches itself. The description is
  // the dispatch prompt (part 1's dispatchWriteCard pattern), so the run attempts a
  // write and the CLI surfaces the ACP permission ask.
  const card = await seedKanbanItem(request, {
    title: `qoder s9 ${uniqueName('card')}`,
    description: writePrompt(filePath),
    agentTemplateId: agent.name,
    status: 'TODO',
  });
  console.log(`[S9] seeded card=${card.id} agent=${agent.id} file=${filePath}`);

  const dispatched = await pollUntil<any>(
    request,
    `/kanban/items/${card.id}`,
    (item) => item?.status === 'IN_PROGRESS' && Boolean(item?.linkedRunId),
    240_000,
    3_000,
  );
  const runId: string = dispatched.linkedRunId;
  console.log(`[S9] auto-dispatch: card status=${dispatched.status} linkedRunId=${runId}`);

  // The live ask: ACP permission for the run, linked to the card (what the Review
  // surface renders — KanbanReviewCardListener links it via the run).
  const ask = await waitForPendingAcpAsk(request, (a) => a.runId === runId, 420_000);
  console.log(
    `[S9] live ask=${ask.id} toolName=${ask.toolName} status=${ask.status}`
    + ` kanbanItemId=${ask.kanbanItemId} expiresAt=${ask.expiresAt}`,
  );
  const cardAsks = await listAsksForCard(request, card.id);
  expect(
    cardAsks.some((a) => a.id === ask.id),
    `the card's ask list must carry the live ask (got ${JSON.stringify(cardAsks.map((a) => a.id))})`,
  ).toBe(true);
  expect(cardAsks.find((a) => a.id === ask.id)?.status).toBe('PENDING');

  // ── Board UI: the card sits in IN_PROGRESS (dispatched, run linked) ─────────
  await page.goto('/');
  const inProgressCard = page.locator(`[data-col="IN_PROGRESS"] [data-card="${card.id}"]`);
  await expect(inProgressCard).toBeVisible({ timeout: 30_000 });

  // Move it to REVIEW (the card-level move the board performs; API call here like
  // review-decision-zone.spec.ts:58 — the UI assertion below observes the move).
  expect((await transitionKanban(request, card.id, 'REVIEW')).status).toBe(200);
  const reviewCard = page.locator(`[data-col="REVIEW"] [data-card="${card.id}"]`);
  await expect(reviewCard).toBeVisible({ timeout: 30_000 });
  await expect(inProgressCard).toHaveCount(0);
  // The card face advertises the ask count (kanban-hitl.spec.ts:166).
  await expect(reviewCard.locator('.pill.warn')).toContainText('asks');

  // The Review surface: drawer decision zone with the ACP ask card (C5 UI).
  await reviewCard.click();
  const zone = page.getByRole('region', { name: 'Decision panel' });
  await expect(zone).toBeVisible({ timeout: 30_000 });
  await expect(zone.locator('.pill.acp')).toContainText('ACP permission');
  await expect(zone.locator('pre.acp-preview')).toBeVisible();
  const allowOnce = zone.getByRole('button', { name: 'Allow once' });
  await expect(allowOnce).toBeEnabled();

  // ── Decide: the decision settles the ASK, never the card ───────────────────
  await allowOnce.click();
  await expect
    .poll(async () => (await askDetail(request, ask.id)).data?.status, { timeout: 90_000 })
    .toBe('APPROVED');
  const decided = await askDetail(request, ask.id);
  console.log(
    `[S9] ask decided: status=${decided.data?.status} deliveryState=${decided.data?.deliveryState}`,
  );
  expect(decided.data?.deliveryState).toBe('DELIVERED');

  const cardAfter = await requestJson<any>(request, 'GET', `/kanban/items/${card.id}`);
  console.log(`[S9] card right after the decision: status=${cardAfter.data?.status}`);
  expect(
    cardAfter.data?.status,
    'an ask decision must not mark the card DONE (HITL: DONE is an operator transition)',
  ).not.toBe('DONE');

  // Honest run outcome (recorded, never asserted as success) + the card stays
  // non-DONE while the linked run settles (RunKanbanAutoCreator maps COMPLETED and
  // FAILED to REVIEW; only ABORTED/CANCELLED map to CANCELLED — never DONE).
  const terminal = await pollRunTerminal(request, runId, 300_000).catch(() => null);
  const finalCard = await requestJson<any>(request, 'GET', `/kanban/items/${card.id}`);
  console.log(
    `[S9] run terminal=${terminal ? terminal.status : '<not terminal within 300s>'}`
    + ` finalCardStatus=${finalCard.data?.status}`,
  );
  expect(finalCard.data?.status).not.toBe('DONE');
});

// ────────────────────────────────────────────────────────────────────────────
// S11 — read auto-allow vs write gate on the platform MCP
// ────────────────────────────────────────────────────────────────────────────

test('S11: platform-MCP read runs without an ask, the write waits for approval, executions are run-correlated', async ({ request }) => {
  const agent = await seedQoderAgent(request, { name: uniqueName('e2e-qoder-s11') });
  const marker = `s11-marker-${Date.now()}-${Math.floor(Math.random() * 10_000)}`;
  const prompt = [
    'Use the platform MCP tools for both steps and nothing else.',
    'Step 1: call the MCP tool list_knowledge (server "aria", no arguments) exactly once to list knowledge items.',
    `Step 2: call the MCP tool store_knowledge (server "aria") exactly once with name "${marker}"`
      + ' and content "s11 governed write probe".',
    'Do not use shell commands, do not read or write files, and do not call any other tool.',
  ].join(' ');
  const run = await startRun(request, agent.id, prompt);
  console.log(`[S11] agent=${agent.id} run=${run.id} marker=${marker}`);

  // The write surfaces the ACP ask; the CLI asks before executing the MCP call, so
  // at this point nothing of the write has run.
  const ask = await waitForPendingAcpAsk(request, (a) => a.runId === run.id, 420_000);
  console.log(
    `[S11] write ask=${ask.id} toolName=${ask.toolName} status=${ask.status}`
    + ` deliveryState=${ask.deliveryState} options=${JSON.stringify(acpOptions(ask).map((o) => o.kind))}`,
  );
  expect(
    String(ask.toolName ?? ''),
    `the ask must be the WRITE tool; displayJson=${String(ask.displayJson).slice(0, 300)}`,
  ).toMatch(/store_knowledge/);
  expect(acpOptions(ask).some((o) => o.kind === 'allow_once')).toBe(true);

  // ── Read half: executed without an ask ─────────────────────────────────────
  const asksNow = await listAsksForRun(request, run.id);
  const readAsks = asksNow.filter((a) => String(a.toolName ?? '').includes('list_knowledge'));
  expect(
    readAsks.length,
    `the read must not create an ask (worker reads are auto-allowed); got ${JSON.stringify(
      asksNow.map((a) => `${a.toolName}:${a.status}`),
    )}`,
  ).toBe(0);
  const beforeProgress = await runProgress(request, run.id);
  expect(beforeProgress.status).toBe(200);
  const readExecuted = progressToolEntries(beforeProgress.data, 'TOOL_RESULT', ['list_knowledge']);
  console.log(
    `[S11] read execution entries (TOOL_RESULT list_knowledge): ${readExecuted.length}`
    + ` ${JSON.stringify(readExecuted.map((e: any) => e.content)?.slice(0, 3))}`,
  );
  expect(
    readExecuted.length,
    'the read must EXECUTE (a TOOL_RESULT progress entry) while producing no ask',
  ).toBeGreaterThan(0);

  // ── Write half: unexecuted before the decision ─────────────────────────────
  const knowledgeBefore = await requestJson<any[]>(request, 'GET', '/knowledge');
  expect(knowledgeBefore.status).toBe(200);
  expect(
    (knowledgeBefore.data ?? []).some((k: any) => k?.name === marker),
    'the gated write must not exist before the approval',
  ).toBe(false);

  // Approve (the operator decision; the UI half of this path is S9/S2).
  const decision = await decideAsk(request, ask.id, true, 'S11: approve the gated write');
  console.log(`[S11] decide: HTTP ${decision.status} body=${JSON.stringify(decision.data)?.slice(0, 300)}`);
  expect(decision.status).toBe(200);
  await expect
    .poll(async () => (await askDetail(request, ask.id)).data?.status, { timeout: 90_000 })
    .toBe('APPROVED');
  const decided = await askDetail(request, ask.id);
  expect(decided.data?.deliveryState).toBe('DELIVERED');

  // The approved write executes exactly once: the knowledge item appears.
  const knowledgeAfter = await pollUntil<any[]>(
    request,
    '/knowledge',
    (list) => Array.isArray(list) && list.some((k) => k?.name === marker),
    240_000,
    3_000,
  );
  const items = knowledgeAfter.filter((k: any) => k?.name === marker);
  console.log(
    `[S11] approved write side effect: ${items.length} knowledge item(s) named '${marker}'`
    + ` status=${items[0]?.status}`,
  );
  expect(items.length).toBe(1);

  // ── Audit: run-correlated execution records ────────────────────────────────
  const afterProgress = await runProgress(request, run.id);
  const writeExecuted = progressToolEntries(afterProgress.data, 'TOOL_RESULT', ['store_knowledge']);
  console.log(
    `[S11] write execution entries (TOOL_RESULT store_knowledge): ${writeExecuted.length}`
    + ` ${JSON.stringify(writeExecuted.map((e: any) => e.content)?.slice(0, 3))}`,
  );
  expect(
    writeExecuted.length,
    'the approved write must execute (a TOOL_RESULT progress entry) after delivery',
  ).toBeGreaterThan(0);

  // The `tool_calls` audit table: assert the run-correlation invariant on whatever
  // rows exist, and record NOT VERIFIED honestly when the endpoint returns none.
  // Verified by reading the code: ToolCall rows are written ONLY by the Java tool
  // loop (AgentLoopEngine.java:1067-1120) and the legacy ApprovalGate (:86-190);
  // the qoder/MCP path records its executions in the run progress stream instead
  // (QoderProgressPump.java:205-235 → RunController.java:60-72 `GET /runs/{id}/progress`).
  const toolCalls = await runToolCalls(request, run.id);
  const rows = Array.isArray(toolCalls.data) ? toolCalls.data : [];
  console.log(`[S11] GET /runs/${run.id}/tool-calls: HTTP ${toolCalls.status} rows=${rows.length}`);
  if (rows.length === 0) {
    console.log(
      '[S11] NOT VERIFIED (tool_calls half): no ToolCall row is written on the qoder/MCP path'
      + ' (no writer in act-execution/.../adk/qoder; the executing record is the run progress stream'
      + ' asserted above). Design §10 item 6 "server audit records only the allowed execution with'
      + ' run correlation" is therefore evidenced through the progress stream in this spec.',
    );
  } else {
    for (const row of rows) {
      expect(
        String(row?.runId ?? ''),
        `every tool-call row must correlate to run ${run.id}; got ${JSON.stringify(row)?.slice(0, 200)}`,
      ).toBe(run.id);
    }
    console.log(`[S11] tool_calls half: ${rows.length} row(s), all correlated to run ${run.id}`);
  }

  const terminal = await pollRunTerminal(request, run.id, 240_000).catch(() => null);
  console.log(`[S11] run terminal=${terminal ? terminal.status : '<not terminal within 240s>'}`);
});

// ────────────────────────────────────────────────────────────────────────────
// S12 — UI smoke (spec half): touched routes, zero console errors, no failed backend calls
// ────────────────────────────────────────────────────────────────────────────

test('S12: UI smoke — board, Review surface, Providers and Ops render with zero console errors and no failed backend calls', async ({ page, request }) => {
  // A shorter budget than the qoder-run scenarios: no sandbox, no CLI.
  test.setTimeout(240_000);

  // Nothing is allowlisted. Console assertions use the `error` level only; benign
  // warnings (if any) are printed for the report. Backend failures are any 4xx/5xx
  // response from the backend origin — there are no intentional negative calls in
  // this scenario (a keyless stack's 503 on the credential route is a stack-config
  // failure and deliberately NOT permitted here).
  const consoleErrors: string[] = [];
  const failedBackendRequests: string[] = [];
  const consoleWarnings: string[] = [];
  // Non-vacuity guard (F1): count the requests the filter matched; a zero count after
  // the smoke steps fails loudly instead of silently passing on a broken filter.
  let backendRequestCount = 0;
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text());
    else if (msg.type() === 'warning') consoleWarnings.push(msg.text());
  });
  page.on('response', (resp) => {
    if (!isBackendUrl(resp.url())) return;
    backendRequestCount += 1;
    if (resp.status() >= 400) {
      failedBackendRequests.push(`${resp.status()} ${resp.request().method()} ${resp.url()}`);
    }
  });
  page.on('requestfailed', (req) => {
    if (!isBackendUrl(req.url())) return;
    backendRequestCount += 1;
    failedBackendRequests.push(`FAILED ${req.method()} ${req.url()} (${req.failure()?.errorText})`);
  });

  // Board with one REVIEW card so the Review surface has a real target.
  const card = await seedKanbanItem(request, { title: `s12-${uniqueName('card')}` });
  expect((await transitionKanban(request, card.id, 'REVIEW')).status).toBe(200);

  // 1. Board.
  await page.goto('/');
  await expect(page.locator('.col-k[data-col="REVIEW"]')).toBeVisible({ timeout: 30_000 });
  const reviewCard = page.locator(`[data-col="REVIEW"] [data-card="${card.id}"]`);
  await expect(reviewCard).toBeVisible({ timeout: 30_000 });

  // 2. Review surface: the drawer (this smoke card has no pending ask, so the
  //    "Decision panel" region — rendered only when pendingAsks.length > 0,
  //    TaskDrawer.tsx:264-269 — is absent by design; the drawer's REVIEW-only
  //    "Expand review" affordance, TaskDrawer.tsx:98,231-239, then the expanded
  //    ReviewWorkspace rail).
  await reviewCard.click();
  const drawer = page.getByRole('complementary', { name: 'Task details drawer' });
  await expect(drawer).toBeVisible({ timeout: 30_000 });
  const expandReview = drawer.getByRole('button', { name: 'Expand review' });
  await expect(expandReview).toBeVisible({ timeout: 30_000 });
  await expandReview.click();
  await expect(page.getByTestId('review-workspace')).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole('button', { name: 'Collapse review' })).toBeVisible();

  // 3. Providers.
  await page.goto('/providers');
  await expect(page.getByRole('heading', { name: 'Agent Providers' })).toBeVisible({ timeout: 30_000 });

  // 4. Ops. (The h1's accessible name is "◈ Operations · Command Surface" —
  //    a regex keeps the assertion on the page identity without pinning glyphs.)
  await page.goto('/ops');
  await expect(page.getByRole('heading', { name: /Operations/ })).toBeVisible({ timeout: 30_000 });

  if (consoleWarnings.length > 0) {
    console.log(
      `[S12] benign console warnings recorded (not asserted): ${JSON.stringify(consoleWarnings.slice(0, 10))}`,
    );
  }
  console.log(
    `[S12] console errors=${consoleErrors.length} failed backend requests=${failedBackendRequests.length}`,
  );
  expect(consoleErrors, `console errors on the touched routes: ${JSON.stringify(consoleErrors)}`).toHaveLength(0);
  console.log(`[S12] backend-URL requests matched by the filter: ${backendRequestCount}`);
  expect(
    backendRequestCount,
    'the S12 backend-URL filter matched ZERO requests — the "no failed backend requests"'
    + ' assertion below would be vacuous. Browser-issued calls carry the dashboard origin'
    + ` (proxied to the backend), not BACKEND_ORIGIN=${BACKEND_ORIGIN}; fix the filter.`,
  ).toBeGreaterThan(0);
  expect(
    failedBackendRequests,
    `failed backend requests on the touched routes: ${JSON.stringify(failedBackendRequests)}`,
  ).toHaveLength(0);

  // Teardown: remove the smoke card so the board is not left with residue.
  const deleted = await apiCall(request, 'DELETE', `/kanban/items/${card.id}`);
  console.log(`[S12] cleanup: DELETE card ${card.id} HTTP ${deleted.status}`);
});
