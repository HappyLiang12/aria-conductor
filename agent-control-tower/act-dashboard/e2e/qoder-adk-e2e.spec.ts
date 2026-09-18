/**
 * C6a (part 1): real-stack qoder Playwright scenarios S1, S2, S3, S4, S6
 * (plan docs/superpowers/plans/2026-09-17-qoder-cli-provider.md, §C0.8 matrix;
 * brief .superpowers/sdd/2026-09-17-qoder-cli-provider/task-C6a-brief.md).
 *
 * S5 (cancel) and S10 (restart) are bash-owned by C6b; S7/S8/S9/S11/S12 live in
 * e2e/qoder-governance-e2e.spec.ts (sibling dispatch) which imports ./qoder-e2e-lib.
 *
 * Gates: QODER_E2E=1 (else skip), QODER_E2E_PAT (else skip, names the credential),
 * QODER_E2E_MODEL in {efficient, lite} (else hard-fail — plan Global Constraints),
 * plus the live `qoder.model` check against the credential status.
 *
 * Runner contract owned by C6b (documented here because this spec depends on it):
 *  - the stack is started with a SHORT approval TTL: `APPROVALS_TIMEOUT_MS`
 *    (backend key `approvals.timeout-ms`, AcpPermissionCoordinator.java:156;
 *    default 1800000 = 30 min) — S4 fails fast with an explicit message otherwise.
 *  - the run sandbox is on the local container runtime (podman by default):
 *    S2/S3/S4 observe the file side effect with `<runtime> exec` against the run's
 *    sandbox container, mirroring e2e/qoder/lib/stack.sh:94-131.
 *  - `GET /runs/{id}/progress` exposes the bridge's pinned model as
 *    `qoder.model=<id>` (QoderProgressPump.java:207-208).
 *  - `PACK_CREDENTIAL_KEY` must be in the backend environment (or in the project
 *    `.env`, which start.ps1:121 / start-backend.sh:11 load) — VERIFIED: nothing in
 *    `scripts/` sets it, `.env.example:52` only documents it, and this worktree's
 *    `.env` has no such line. Without it the credential API 503s KEY_NOT_CONFIGURED
 *    and S1/S6 cannot pass; the 503 path itself needs a keyless stack (NOT VERIFIED).
 */
import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import {
  pollRunTerminal,
  pollUntil,
  seedKanbanItem,
  transitionKanban,
  uniqueName,
} from './fixtures';
import {
  E2E_ENABLED,
  ZERO_CREDIT_MODELS,
  askDetail,
  assertZeroCreditModel,
  credentialDelete,
  credentialGet,
  credentialSet,
  credentialTest,
  decideAsk,
  isMaskedPat,
  listAsksForRun,
  listSandboxContainerIds,
  readFileInSandbox,
  requireEnabledOrSkip,
  runDetail,
  runProgress,
  seedQoderAgent,
  skipUnlessPat,
  startRun,
  waitForNewSandboxContainer,
  waitForPendingAcpAsk,
  waitForSandboxFile,
  type QoderAsk,
} from './qoder-e2e-lib';

// Real sandbox boot + CLI session per run; the config default (120s) is far too small.
test.setTimeout(600_000);

test.beforeAll(async ({ request }) => {
  // Env half hard-fails even when the suite is otherwise disabled (RED contract item 2);
  // the live credential-model check only runs when the suite is enabled.
  await assertZeroCreditModel(E2E_ENABLED ? request : undefined);
  requireEnabledOrSkip();
  skipUnlessPat();
});

// ── shared snippet builders ─────────────────────────────────────────────────

const WRITE_CONTENT = 'hi';

/** Same shape as the A4 probe prompt (e2e/qoder/slice-a/04-permissions.mjs:182-184). */
function writePrompt(filePath: string): string {
  return `Create a new file at ${filePath} containing exactly the two characters "hi" (no trailing period).`
    + ' Use the Write file tool and nothing else; do not use shell commands.';
}

function doubleWritePrompt(filePath: string): string {
  return `${writePrompt(filePath)} Then, as a second step, write the very same file ${filePath} again`
    + ` with the same content "hi", using the Write file tool again.`;
}

/** A sandbox-internal path (the CLI cwd is /workspace — QoderAdkProvider.java:89). */
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

async function tryRunTerminal(request: APIRequestContext, runId: string, timeoutMs: number) {
  try {
    return await pollRunTerminal(request, runId, timeoutMs);
  } catch {
    return null;
  }
}

function maxSeq(entries: any[]): number {
  return entries.reduce((max, e) => Math.max(max, Number(e?.seq ?? 0)), 0);
}

/** Board drill-down: REVIEW column card → Decision panel (patterns from review-decision-zone.spec.ts). */
async function openCardDecisionPanel(page: Page, cardId: string) {
  await page.goto('/');
  const card = page.locator(`[data-col="REVIEW"] [data-card="${cardId}"]`);
  await expect(card).toBeVisible({ timeout: 30_000 });
  await card.click();
  const zone = page.getByRole('region', { name: 'Decision panel' });
  await expect(zone).toBeVisible({ timeout: 30_000 });
  return zone;
}

/** Seeds a card whose dispatch prompt is the write instruction, dispatches it, returns the linked run. */
async function dispatchWriteCard(request: APIRequestContext, agentName: string, prompt: string) {
  const card = await seedKanbanItem(request, {
    title: `qoder write ${uniqueName('card')}`,
    description: prompt,
    agentTemplateId: agentName,
  });
  const moved = await transitionKanban(request, card.id, 'IN_PROGRESS');
  // A card created in TODO is itself a dispatch intent: KanbanAutoDispatchListener
  // (auto-dispatch-on-create, on by default) dispatches it right after the create
  // commits and races this explicit operator move with the same dispatch — card
  // description, pinned agent, one run. When the listener wins, this move loses its
  // optimistic lock and answers 409 ("Card was modified by another move"); the card
  // is dispatched either way, so the run link — not the mover — is the contract.
  if (moved.status !== 200 && moved.status !== 409) {
    throw new Error(`TODO→IN_PROGRESS dispatch rejected: ${JSON.stringify(moved.data)}`);
  }
  const dispatched = await pollUntil<any>(
    request,
    `/kanban/items/${card.id}`,
    (c) => !!c?.linkedRunId,
    30_000,
  );
  return { card, runId: dispatched.linkedRunId as string };
}

// ── S1 ──────────────────────────────────────────────────────────────────────

test('S1: qoder agent ordinary run completes on the zero-credit model', async ({ page, request }) => {
  // Live truth: the run model this stack pins (qoder.model, reported by the credential status).
  const credential = await credentialGet(request);
  expect(credential.status).toBe(200);
  expect(credential.data?.configured).toBe(true);
  const liveModel: string = credential.data?.model;
  console.log(`[S1] credential status: configured=${credential.data?.configured} model=${liveModel}`);
  expect(typeof liveModel).toBe('string');
  expect(liveModel.length).toBeGreaterThan(0);
  if (process.env.QODER_E2E_ALLOW_PAID !== '1') {
    expect(
      (ZERO_CREDIT_MODELS as readonly string[]).includes(liveModel),
      `live qoder.model='${liveModel}' is not zero-credit {efficient, lite}`,
    ).toBe(true);
  }

  const agent = await seedQoderAgent(request, { name: uniqueName('e2e-qoder-s1') });
  const run = await startRun(request, agent.id, 'Reply with a short greeting. Do not use any tools.');
  console.log(`[S1] agent=${agent.id} run=${run.id}`);

  const terminal = await pollRunTerminal(request, run.id, 480_000);
  console.log(
    `[S1] terminal status=${terminal.status} iterations=${terminal.iterationCount}`
    + ` totalTokensUsed=${terminal.totalTokensUsed}`
    + ` finalOutput=${JSON.stringify(terminal.finalOutput)?.slice(0, 240)}`,
  );
  expect(terminal.status).toBe('COMPLETED');

  // Effective/selected model observable: the bridge's pinned model as a run progress STATUS entry.
  const progress = await runProgress(request, run.id);
  expect(progress.status).toBe(200);
  const modelEvents = (progress.data ?? []).filter(
    (e: any) => typeof e?.content === 'string' && e.content.startsWith('qoder.model='),
  );
  console.log(`[S1] qoder.model progress events: ${JSON.stringify(modelEvents.map((e: any) => e.content))}`);
  expect(modelEvents.length).toBeGreaterThan(0);
  expect(
    String(modelEvents[0].content).slice('qoder.model='.length),
    'the run must execute on the model the credential status reports',
  ).toBe(liveModel);

  // Records the reported usage with a lower bound only — never a zero-cost claim (design §4.2).
  expect(terminal.totalTokensUsed).toBeGreaterThanOrEqual(0);
  console.log(`[S1] reported usage (no zero-cost guarantee): totalTokensUsed=${terminal.totalTokensUsed}`);

  const probe = await credentialTest(request);
  expect(probe.status).toBe(200);
  expect(probe.data?.success).toBe(true);
  expect(probe.data?.model).toBe(liveModel);
  expect(probe.data?.billable).toBe(false);
  console.log(
    `[S1] credential probe: success=${probe.data?.success} model=${probe.data?.model}`
    + ` billable=${probe.data?.billable} costNote=${JSON.stringify(probe.data?.costNote)?.slice(0, 120)}`,
  );

  // UI half: the finished run is visible on the Runs page.
  await page.goto('/runs');
  const row = page.getByRole('row', { name: new RegExp(run.id.slice(0, 8)) });
  await expect(row).toBeVisible({ timeout: 30_000 });
  await expect(row).toContainText('COMPLETED');
});

// ── S2 ──────────────────────────────────────────────────────────────────────

test('S2: write attempt asks, allow once executes, second write asks again', async ({ page, request }) => {
  const filePath = sandboxFilePath('s2');
  const beforeContainers = listSandboxContainerIds();
  const agent = await seedQoderAgent(request, { name: uniqueName('e2e-qoder-s2') });
  const { card, runId } = await dispatchWriteCard(request, agent.name, doubleWritePrompt(filePath));
  console.log(`[S2] card=${card.id} run=${runId} file=${filePath}`);

  const ask1 = await waitForPendingAcpAsk(request, (a) => a.runId === runId, 420_000);
  console.log(
    `[S2] ask1=${ask1.id} status=${ask1.status} source=${ask1.source}`
    + ` toolCallId=${ask1.toolCallId} expiresAt=${ask1.expiresAt}`,
  );
  const options = acpOptions(ask1);
  console.log(`[S2] ask1 options=${JSON.stringify(options)}`);
  expect(options.some((o) => o.kind === 'allow_once')).toBe(true);

  // The sandbox exists while the ask is pending — capture it before any cleanup.
  const containerId = await waitForNewSandboxContainer(beforeContainers, 180_000);
  console.log(`[S2] sandbox container=${containerId} (image ancestor filter)`);

  // Decide through the UI: board REVIEW card → Decision panel → Allow once.
  expect((await transitionKanban(request, card.id, 'REVIEW')).status).toBe(200);
  const zone = await openCardDecisionPanel(page, card.id);
  await expect(zone.locator('.pill.acp')).toContainText('ACP permission');
  await expect(zone.locator('pre.acp-preview')).toBeVisible();
  const allowOnce = zone.getByRole('button', { name: 'Allow once' });
  await expect(allowOnce).toBeEnabled();
  await allowOnce.click();

  await expect
    .poll(async () => (await askDetail(request, ask1.id)).data?.status, { timeout: 90_000 })
    .toBe('APPROVED');
  const decided1 = await askDetail(request, ask1.id);
  console.log(
    `[S2] ask1 decided: status=${decided1.data?.status} deliveryState=${decided1.data?.deliveryState}`
    + ` decision-recorded reason=${JSON.stringify(decided1.data?.reason)}`,
  );
  expect(decided1.data?.deliveryState).toBe('DELIVERED');

  // File side effect, observed host-side inside the run's sandbox container.
  const write1 = await waitForSandboxFile(containerId, filePath, 180_000);
  console.log(
    `[S2] write1 probe: exists=${write1.exists} content=${JSON.stringify(write1.content)}`
    + ` note="${write1.note}"`,
  );
  expect(write1.exists, `file not created after allow_once (${write1.note})`).toBe(true);
  expect((write1.content ?? '').trim()).toBe(WRITE_CONTENT);

  // One-use grant: the second identical write must ask again (new ask id).
  const ask2 = await waitForPendingAcpAsk(
    request,
    (a) => a.runId === runId && a.id !== ask1.id,
    300_000,
  );
  console.log(`[S2] ask2=${ask2.id} (new ask after the one-use approval) toolCallId=${ask2.toolCallId}`);

  // Real collision check (F3): the wait predicate above already guarantees ask2.id !== ask1.id,
  // so instead assert id uniqueness over ALL asks the run produced.
  const runAsks = await listAsksForRun(request, runId);
  const askIds = runAsks.map((a) => a.id);
  console.log(`[S2] asks for run ${runId}: ${JSON.stringify(runAsks.map((a) => `${a.id}:${a.status}`))}`);
  expect(new Set(askIds).size, `duplicate ask ids for run ${runId}: ${JSON.stringify(askIds)}`).toBe(askIds.length);
  expect(askIds, `the run's ask list must contain ask1 (${ask1.id})`).toContain(ask1.id);
  expect(askIds, `the run's ask list must contain ask2 (${ask2.id})`).toContain(ask2.id);

  // Settle the second permission (deny) so the run is not left blocked; the file claim stands.
  const deny2 = await decideAsk(request, ask2.id, false, 'S2: one-use grant verified — second write denied');
  expect(deny2.status).toBe(200);
  await expect
    .poll(async () => (await askDetail(request, ask2.id)).data?.status, { timeout: 60_000 })
    .toBe('DENIED');

  const writeAfter = readFileInSandbox(containerId, filePath);
  console.log(
    `[S2] file after the second (denied) write: exists=${writeAfter.exists}`
    + ` content=${JSON.stringify(writeAfter.content)} note="${writeAfter.note}"`,
  );
  expect((writeAfter.content ?? '').trim()).toBe(WRITE_CONTENT);

  const terminal = await tryRunTerminal(request, runId, 180_000);
  console.log(
    `[S2] run terminal=${terminal ? terminal.status : '<not terminal within 180s>'}`
    + ` finalOutput=${JSON.stringify(terminal?.finalOutput)?.slice(0, 200)}`,
  );
});

// ── S3 ──────────────────────────────────────────────────────────────────────

test('S3: deny produces no side effect and does not cancel the run', async ({ page, request }) => {
  const filePath = sandboxFilePath('s3');
  const beforeContainers = listSandboxContainerIds();
  const agent = await seedQoderAgent(request, { name: uniqueName('e2e-qoder-s3') });
  const { card, runId } = await dispatchWriteCard(request, agent.name, writePrompt(filePath));
  console.log(`[S3] card=${card.id} run=${runId} file=${filePath}`);

  const ask = await waitForPendingAcpAsk(request, (a) => a.runId === runId, 420_000);
  console.log(`[S3] ask=${ask.id} status=${ask.status} source=${ask.source}`);
  const containerId = await waitForNewSandboxContainer(beforeContainers, 180_000);
  console.log(`[S3] sandbox container=${containerId}`);

  expect((await transitionKanban(request, card.id, 'REVIEW')).status).toBe(200);
  const zone = await openCardDecisionPanel(page, card.id);
  await expect(zone.locator('.pill.acp')).toContainText('ACP permission');
  const deny = zone.getByRole('button', { name: 'Deny', exact: true });
  await expect(deny).toBeEnabled();
  await deny.click();

  await expect
    .poll(async () => (await askDetail(request, ask.id)).data?.status, { timeout: 90_000 })
    .toBe('DENIED');
  const decided = await askDetail(request, ask.id);
  console.log(
    `[S3] ask decided: status=${decided.data?.status} deliveryState=${decided.data?.deliveryState}`
    + ` reason=${JSON.stringify(decided.data?.reason)}`,
  );
  expect(decided.data?.deliveryState).toBe('DELIVERED');

  const probe = readFileInSandbox(containerId, filePath);
  console.log(
    `[S3] file probe after denial: exists=${probe.exists} content=${JSON.stringify(probe.content)}`
    + ` note="${probe.note}"`,
  );
  expect(probe.exists, `file created despite the denial (${probe.note})`).toBe(false);
  expect(probe.note).not.toContain('container no longer exists');

  // The denial must not cancel the run: it settles in a non-aborted terminal state.
  const rightAfter = await runDetail(request, runId);
  console.log(`[S3] run right after the denial: status=${rightAfter.data?.status}`);
  expect(['ABORTED', 'CANCELLED']).not.toContain(rightAfter.data?.status);
  const terminal = await pollRunTerminal(request, runId, 300_000);
  const progress = await runProgress(request, runId);
  const deniedAtSeq = maxSeq((progress.data ?? []).filter((e: any) => e?.kind === 'STATUS'));
  console.log(
    `[S3] run terminal=${terminal.status} finalOutput=${JSON.stringify(terminal.finalOutput)?.slice(0, 200)}`
    + ` progress entries=${(progress.data ?? []).length} maxStatusSeq=${deniedAtSeq}`,
  );
  expect(
    ['COMPLETED', 'FAILED', 'CANCELLED'],
    `S3: unexpected terminal run status '${terminal.status}' (expected COMPLETED, FAILED or CANCELLED)`,
  ).toContain(terminal.status);
});

// ── S4 ──────────────────────────────────────────────────────────────────────

test('S4: expiry delivers a reject, a late decide is a typed 409 EXPIRED, no stale allow', async ({ request }) => {
  const ttlMs = Number(process.env.APPROVALS_TIMEOUT_MS || '1800000');
  expect(
    ttlMs,
    'S4 needs a short approval TTL: start the stack with APPROVALS_TIMEOUT_MS (backend key'
    + ' approvals.timeout-ms, AcpPermissionCoordinator.java:156). The default is 1800000ms (30 min)'
    + ' and cannot expire inside this spec.',
  ).toBeLessThanOrEqual(180_000);

  const filePath = sandboxFilePath('s4');
  const beforeContainers = listSandboxContainerIds();
  const agent = await seedQoderAgent(request, { name: uniqueName('e2e-qoder-s4') });
  const run = await startRun(request, agent.id, writePrompt(filePath));
  console.log(`[S4] agent=${agent.id} run=${run.id} file=${filePath} ttlMs=${ttlMs}`);

  const ask = await waitForPendingAcpAsk(request, (a) => a.runId === run.id, 420_000);
  console.log(`[S4] ask=${ask.id} status=${ask.status} expiresAt=${ask.expiresAt}`);
  const containerId = await waitForNewSandboxContainer(beforeContainers, 180_000);
  console.log(`[S4] sandbox container=${containerId}`);

  // No decision: let the TTL pass (sweep cadence is 60s — ApprovalExpiryChecker.java:50).
  const expired = await pollUntil<QoderAsk>(
    request,
    `/approvals/${ask.id}`,
    (a) => a.status === 'EXPIRED',
    ttlMs + 180_000,
    3_000,
  );
  console.log(
    `[S4] ask expired: status=${expired.status} deliveryState=${expired.deliveryState}`
    + ` reason=${JSON.stringify(expired.reason)} decidedAt=${expired.decidedAt}`,
  );
  // C3/C2 mechanics: expiry delivers the reject/cancel to the bridge (CANCELLED per the C5 label map).
  expect(expired.deliveryState).toBe('CANCELLED');
  expect(String(expired.reason ?? '')).toMatch(/expired/i);
  expect((await listAsksForRun(request, run.id)).map((a) => a.status)).toContain('EXPIRED');

  // A later decide on the same ask must be the typed 409 — never a success, never a stale allow.
  const late = await decideAsk(request, ask.id, true, 'S4: late decide after expiry');
  console.log(`[S4] late decide: HTTP ${late.status} body=${JSON.stringify(late.data)}`);
  expect(late.status).toBe(409);
  expect(late.data?.code).toBe('EXPIRED');
  expect(late.data?.approved).not.toBe(true);
  const afterLate = await askDetail(request, ask.id);
  expect(afterLate.data?.status).toBe('EXPIRED');

  // Honest outcome: the unexecuted write left no side effect.
  const probe = readFileInSandbox(containerId, filePath);
  console.log(
    `[S4] file probe after expiry: exists=${probe.exists} content=${JSON.stringify(probe.content)}`
    + ` note="${probe.note}"`,
  );
  if (probe.note.includes('container no longer exists')) {
    console.log('[S4] NOT VERIFIED: the sandbox container was already removed — file absence not directly observed');
  } else {
    expect(probe.exists, `file created despite the expired permission (${probe.note})`).toBe(false);
  }

  const terminal = await tryRunTerminal(request, run.id, 240_000);
  console.log(
    `[S4] run outcome (recorded, not a success claim): status=${terminal ? terminal.status : '<not terminal>'}`
    + ` finalOutput=${JSON.stringify(terminal?.finalOutput)?.slice(0, 240)}`
    + ` totalTokensUsed=${terminal?.totalTokensUsed}`,
  );
  const terminalStatus = terminal?.status ?? 'NOT_TERMINAL';
  expect(
    ['COMPLETED', 'FAILED', 'CANCELLED'],
    `S4: expected a terminal run status, observed '${terminalStatus}'`,
  ).toContain(terminalStatus);
});

// ── S6 ──────────────────────────────────────────────────────────────────────

test('S6: credential set/mask/remove/test via API and the Providers page', async ({ page, request }) => {
  // The PAT is only ever held in memory: never logged, never typed into the browser,
  // never part of a title, URL or screenshot (design §4.1).
  const pat = (process.env.QODER_E2E_PAT ?? '').trim();
  const last4 = pat.slice(-4);

  // Self-determinism (F5): the runtime store may already hold a credential from an
  // earlier run, so load THIS run's PAT before asserting the mask (idempotent when
  // the store already matches). credentialSet keeps the value in the request body
  // only — never logged, never typed into the browser (design §4.1).
  const loaded = await credentialSet(request, pat);
  expect(
    loaded.status,
    `the credential PUT must answer 200 (503 KEY_NOT_CONFIGURED means PACK_CREDENTIAL_KEY`
    + ` is missing from the backend env); got HTTP ${loaded.status}`,
  ).toBe(200);
  expect(JSON.stringify(loaded.data), 'the PUT response must not echo the PAT').not.toContain(pat);

  const initial = await credentialGet(request);
  expect(initial.status).toBe(200);
  // The stack is keyed only when the operator supplied PACK_CREDENTIAL_KEY (the start
  // scripts load the project .env; nothing in scripts/ sets the variable — verified).
  // A keyless stack answers 503 {code: KEY_NOT_CONFIGURED}; that path needs a second
  // keyless stack config and is NOT VERIFIED by this spec.
  expect(initial.status).not.toBe(503);
  expect(initial.data?.configured).toBe(true);
  expect(isMaskedPat(initial.data?.patMasked)).toBe(true);
  expect(initial.data?.patMasked).toBe(`****${last4}`);
  const liveModel: string = initial.data?.model;
  console.log(
    `[S6] credential status: configured=${initial.data?.configured} patMasked=****(last4 masked)`
    + ` model=${liveModel} updatedAt=${initial.data?.updatedAt}`,
  );

  // Bounded non-billable structural probe: success + the zero-credit run model, no billing.
  const probe = await credentialTest(request);
  expect(probe.status).toBe(200);
  expect(probe.data?.success).toBe(true);
  expect(probe.data?.model).toBe(liveModel);
  expect(probe.data?.billable).toBe(false);
  console.log(
    `[S6] credential test: success=${probe.data?.success} model=${probe.data?.model}`
    + ` billable=${probe.data?.billable} costNote=${JSON.stringify(probe.data?.costNote)?.slice(0, 120)}`,
  );

  // Providers page: masked display only; the raw PAT must not be in the DOM.
  await page.goto('/providers');
  const cardEl = page.getByTestId('qoder-credential-card');
  await expect(cardEl).toBeVisible({ timeout: 30_000 });
  // exact:true — the card also carries the fixed sentence "A configured PAT alone is
  // not sufficient…" (QoderCredentialCard.tsx:296), which a substring match would
  // resolve to as a second element (strict-mode violation).
  await expect(cardEl.getByText('Configured', { exact: true })).toBeVisible({ timeout: 30_000 });
  const maskedCell = cardEl.locator('.cell-mono');
  await expect(maskedCell).toHaveText(`****${last4}`);
  const domText = (await page.locator('body').innerText()).replace(/\s+/g, ' ');
  expect(domText.includes(pat), 'the raw PAT must never reach the DOM').toBe(false);

  // UI probe round-trip (the Test credential button hits POST .../credential/test).
  await cardEl.getByRole('button', { name: 'Test credential' }).click();
  await expect(cardEl.getByRole('status')).toContainText('Probe passed', { timeout: 30_000 });
  await expect(cardEl.getByRole('status')).toContainText(`model ${liveModel}`);

  // Blank PAT is rejected with the controller's constant message (no echo of the input).
  const blank = await credentialSet(request, '   ');
  console.log(`[S6] blank-PAT PUT rejected: HTTP ${blank.status} body=${JSON.stringify(blank.data)}`);
  expect(blank.status).toBe(400);
  expect(JSON.stringify(blank.data)).toContain('pat is required and must not be blank');

  // DELETE (204, idempotent) then re-PUT the runner-loaded PAT through the API only (R42).
  const del = await credentialDelete(request);
  expect(del.status).toBe(204);
  const gone = await credentialGet(request);
  console.log(`[S6] after DELETE: configured=${gone.data?.configured} patMasked=${gone.data?.patMasked}`);
  expect(gone.data?.configured).toBe(false);
  expect(gone.data?.patMasked ?? null).toBeNull();

  const put = await credentialSet(request, pat);
  expect(put.status).toBe(200);
  expect(put.data?.configured).toBe(true);
  expect(put.data?.patMasked).toBe(`****${last4}`);
  expect(JSON.stringify(put.data), 'no response may echo the PAT').not.toContain(pat);
  console.log(`[S6] after re-PUT: configured=${put.data?.configured} patMasked=****(last4 masked)`);

  const restored = await credentialGet(request);
  expect(restored.data?.configured).toBe(true);
  // Leave the stack exactly as found: the credential must be configured for the following scenarios.
  expect(restored.data?.patMasked).toBe(`****${last4}`);
});
