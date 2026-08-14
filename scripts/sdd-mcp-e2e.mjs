#!/usr/bin/env node
/**
 * SDD (spec-driven development) real-stack MCP E2E driver.
 *
 * Drives the Aria MCP server (packages/mcp-server) over stdio against a running
 * backend (ACT_BASE_URL, default http://localhost:8080) and proves the D1-D7
 * fixes end-to-end through the public MCP tool surface:
 *
 *   1. initialize + tools/list: create_workflow / aria_chat / decide_approval present
 *   2. create_workflow SDD probe: EXPECT an error (R-F4 governance gate)
 *   3. aria_chat: instantiate development-workflow (issueRef/issueRepo/repoUrl) -> chainId
 *   4. poll get_workflow for BA completion (diagnosis endpoint hint printed)
 *   5. poll list_pending_approvals for SPEC_REVIEW -> get_approval -> assert clean spec
 *   6. decide_approval REJECT (reason) -> poll get_workflow for BA reschedule (D6)
 *   7. next SPEC_REVIEW -> decide_approval APPROVE -> poll get_workflow for DEV advance
 *
 * Exit codes:
 *   0  all steps passed
 *   1  an assertion/step failed
 *   2  SKIP — DEEPSEEK_API_KEY or GH_TOKEN unset, or backend health check failed
 *
 * Nightly/manual harness. Requires a live stack (backend :8080, OpenSandbox :8090)
 * started with `scripts/start-backend.ps1 -AdkProvider opencode`, GH_TOKEN exported,
 * and an active DB LlmProvider.
 *
 * Usage:
 *   node scripts/sdd-mcp-e2e.mjs
 *
 * Useful env:
 *   ACT_BASE_URL                  backend base URL (default http://localhost:8080)
 *   SDD_E2E_TIMEOUT_MIN           BA completion timeout in minutes (default 30)
 *   SDD_E2E_POLL_INTERVAL_SEC     poll interval in seconds (default 30)
 *   SDD_E2E_SMOKE_ONLY=1          stop after steps 1-2 (no live LLM loop)
 *   SDD_E2E_CHAIN_ID              override the chain id captured from aria_chat
 */

import process from 'node:process';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import fs from 'node:fs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '..');
const MCP_DIR = path.join(REPO_ROOT, 'packages', 'mcp-server');

// ── Config ────────────────────────────────────────────────────────────────────
const BASE_URL = (process.env.ACT_BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const DEEPSEEK_API_KEY = process.env.DEEPSEEK_API_KEY || '';
const GH_TOKEN = process.env.GH_TOKEN || '';
const TIMEOUT_MIN = Number(process.env.SDD_E2E_TIMEOUT_MIN || 30);
const POLL_INTERVAL_SEC = Number(process.env.SDD_E2E_POLL_INTERVAL_SEC || 30);
const SMOKE_ONLY = process.env.SDD_E2E_SMOKE_ONLY === '1';

const EXIT_OK = 0;
const EXIT_FAIL = 1;
const EXIT_SKIP = 2;

// Diagnosis endpoint hint printed while the BA agent runs (D7b / plan Task 5).
const DIAGNOSIS_URL = `${BASE_URL}/api/v1/adk/opencode/sandboxes/{agentId}/diagnosis`;

const log = (msg) => console.log(`[sdd-e2e] ${msg}`);
const warn = (msg) => console.warn(`[sdd-e2e][warn] ${msg}`);

// ── Small utilities ───────────────────────────────────────────────────────────
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const uuidRe = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;

function textOf(result) {
  const content = Array.isArray(result?.content) ? result.content : [];
  return content
    .filter((c) => c && c.type === 'text')
    .map((c) => c.text ?? '')
    .join('\n');
}

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/** Extract the first UUID that is not one of the excluded ids (e.g. runId/conversationId). */
function extractChainId(text, exclude = []) {
  const seen = new Set(exclude.filter(Boolean));
  for (const m of text.matchAll(uuidRe)) {
    if (!seen.has(m[0])) return m[0];
  }
  return null;
}

// ── Gates (no SDK import / process spawn before these) ────────────────────────
function gateEnv() {
  if (!DEEPSEEK_API_KEY || !GH_TOKEN) {
    warn('SKIP: DEEPSEEK_API_KEY and GH_TOKEN are both required for the live SDD loop.');
    warn('      Export them and start the backend (`scripts/start-backend.ps1 -AdkProvider opencode`) first.');
    return false;
  }
  return true;
}

async function gateHealth() {
  const url = `${BASE_URL}/actuator/health`;
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(5000) });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    log(`backend health OK: ${url}`);
    return true;
  } catch (err) {
    warn(`SKIP: backend health check failed at ${url} (${err instanceof Error ? err.message : err}).`);
    warn('      Start the backend before running the live loop.');
    return false;
  }
}

// ── MCP client bootstrap (lazy — only after the gates pass) ───────────────────
async function loadSdk() {
  // @modelcontextprotocol/sdk is a dependency of packages/mcp-server (pnpm layout),
  // not of the repo root, so resolve its ESM entry by absolute path from that package.
  const sdkDir = path.join(MCP_DIR, 'node_modules', '@modelcontextprotocol', 'sdk');
  const [clientMod, stdioMod] = await Promise.all([
    import(pathToFileURL(path.join(sdkDir, 'dist', 'esm', 'client', 'index.js')).href),
    import(pathToFileURL(path.join(sdkDir, 'dist', 'esm', 'client', 'stdio.js')).href),
  ]);
  return { Client: clientMod.Client, StdioClientTransport: stdioMod.StdioClientTransport };
}

async function connectMcp({ Client, StdioClientTransport }) {
  const tsxCli = path.join(MCP_DIR, 'node_modules', 'tsx', 'dist', 'cli.mjs');
  if (!fs.existsSync(tsxCli)) {
    throw new Error(`tsx CLI not found at ${tsxCli} — run \`pnpm install\` in packages/mcp-server first.`);
  }
  // The stdio entry is src/index.ts (connects StdioServerTransport); env is passed
  // explicitly because the SDK's default env is a curated subset.
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [tsxCli, 'src/index.ts'],
    cwd: MCP_DIR,
    env: { ...process.env },
    stderr: 'inherit',
  });
  const client = new Client({ name: 'sdd-mcp-e2e', version: '1.0.0' });
  await client.connect(transport);
  return { client, transport };
}

async function callTool(client, name, args) {
  const result = await client.callTool({ name, arguments: args ?? {} });
  return { isError: !!result.isError, text: textOf(result), raw: result };
}

async function listToolNames(client) {
  const { tools } = await client.listTools();
  return tools.map((t) => t.name);
}

// ── Steps ─────────────────────────────────────────────────────────────────────
async function step1ToolsPresent(client) {
  const names = await listToolNames(client);
  log(`tools/list: ${names.length} tools.`);
  for (const required of ['create_workflow', 'aria_chat', 'decide_approval']) {
    if (!names.includes(required)) {
      throw new Error(`required tool missing from MCP server: ${required}`);
    }
  }
  log('step 1 OK: create_workflow / aria_chat / decide_approval present.');
}

async function step2CreateWorkflowProbe(client) {
  log('step 2: probing create_workflow for the SDD governance rejection (R-F4)...');
  // Note: the MCP create_workflow inputSchema only exposes name/agentIds/initialPrompt;
  // it cannot express step kinds (BA/DEV/QA), so the R-F4 kind rejection itself is
  // proven at the REST layer (WorkflowServiceSddGuardTest). Here we assert the MCP
  // layer surfaces the backend rejection as an error instead of swallowing it.
  const probeName = `sdd-probe-${Date.now()}`;
  const res = await callTool(client, 'create_workflow', {
    name: probeName,
    agentIds: ['123e4567-e89b-12d3-a456-426614174000'],
  });
  if (!res.isError) {
    // The backend should reject an ungoverned create (missing steps / SDD guard).
    throw new Error(`create_workflow probe unexpectedly succeeded: ${res.text}`);
  }
  if (res.text.includes('instantiate_template')) {
    log('step 2 OK: backend SDD rejection surfaced through MCP, contains instantiate_template.');
  } else {
    warn('step 2: backend rejected the create, but the message did not mention instantiate_template.');
    warn('        (Expected: the MCP tool cannot express step kinds, so the kind-specific');
    warn('         governance message is proven at the REST layer — WorkflowServiceSddGuardTest.)');
    log(`step 2 (surfaced): ${res.text.slice(0, 300)}`);
  }
}

async function step3AriaChatInstantiate(client) {
  const msg =
    'instantiate development-workflow with issueRef=#38 issueRepo=owner/repo repoUrl=https://github.com/owner/repo.git';
  log(`step 3: aria_chat -> "${msg}"`);
  const res = await callTool(client, 'aria_chat', { message: msg });
  if (res.isError) {
    throw new Error(`aria_chat failed: ${res.text}`);
  }
  const chainId = process.env.SDD_E2E_CHAIN_ID || extractChainId(res.text);
  if (!chainId) {
    log(`aria_chat response (no chain id detected): ${res.text.slice(0, 500)}`);
    throw new Error('could not capture a chain id from the aria_chat response text.');
  }
  log(`step 3 OK: captured chainId=${chainId}`);
  return chainId;
}

async function getWorkflow(client, chainId) {
  const res = await callTool(client, 'get_workflow', { id: chainId });
  if (res.isError) return null;
  return parseJson(res.text);
}

async function listPendingApprovals(client) {
  const res = await callTool(client, 'list_pending_approvals', {});
  if (res.isError) return [];
  const parsed = parseJson(res.text);
  return Array.isArray(parsed) ? parsed : [];
}

/** Poll `fn` every POLL_INTERVAL_SEC until `predicate` returns a truthy value or timeout. */
async function pollUntil(label, fn, predicate, timeoutMin) {
  const deadline = Date.now() + timeoutMin * 60_000;
  let last;
  while (Date.now() < deadline) {
    last = await fn();
    const ok = predicate(last);
    if (ok) return ok;
    await sleep(POLL_INTERVAL_SEC * 1000);
  }
  throw new Error(`timed out after ${timeoutMin}m waiting for ${label} (last: ${JSON.stringify(last)?.slice(0, 300)})`);
}

async function step4PollBaCompletion(client, chainId) {
  log(`step 4: polling get_workflow for BA completion (timeout ${TIMEOUT_MIN}m).`);
  log(`        live diagnosis hint: ${DIAGNOSIS_URL}`);
  let printedAgent = false;
  await pollUntil(
    'BA completion',
    async () => {
      const wf = await getWorkflow(client, chainId);
      if (wf && !printedAgent && Array.isArray(wf.steps) && wf.steps.length > 0) {
        const ba = wf.steps[0];
        if (ba && ba.agentId) {
          log(`        BA agentId=${ba.agentId} -> diagnosis: ${DIAGNOSIS_URL.replace('{agentId}', ba.agentId)}`);
          printedAgent = true;
        }
      }
      return wf;
    },
    (wf) => wf && Array.isArray(wf.steps) && wf.steps[0] && wf.steps[0].status === 'COMPLETED',
    TIMEOUT_MIN,
  );
  log('step 4 OK: BA step completed.');
}

async function step5SpecReview(client) {
  log(`step 5: polling list_pending_approvals for a SPEC_REVIEW approval (timeout ${TIMEOUT_MIN}m).`);
  const approval = await pollUntil(
    'SPEC_REVIEW approval',
    () => listPendingApprovals(client),
    (list) => list.find((a) => a.approvalType === 'SPEC_REVIEW'),
    TIMEOUT_MIN,
  );
  log(`step 5: found SPEC_REVIEW approval id=${approval.id}.`);
  const detailRes = await callTool(client, 'get_approval', { id: approval.id });
  if (detailRes.isError) {
    throw new Error(`get_approval failed: ${detailRes.text}`);
  }
  const spec = parseJson(detailRes.text) || {};
  const content = spec.content ?? '';
  if (content.includes('<tool_call>')) {
    throw new Error('spec content contains <tool_call> chatter (R4-F2 regression).');
  }
  log(`step 5 OK: spec content is clean (no <tool_call> chatter), length=${content.length}.`);
  log(`-------- SPEC CONTENT --------\n${content}\n------------------------------`);
  return { id: approval.id };
}

async function decideApproval(client, id, approved, reason) {
  const res = await callTool(client, 'decide_approval', { id, approved, reason });
  if (res.isError) {
    throw new Error(`decide_approval(${id}) failed: ${res.text}`);
  }
  return res;
}

async function step6RejectAndReschedule(client, chainId, approvalId) {
  log(`step 6: REJECT approval ${approvalId} with reason -> expect BA reschedule (D6).`);
  await decideApproval(client, approvalId, false, 'Use postgres instead');
  await pollUntil(
    'BA reschedule (step 0 back to RUNNING/PENDING)',
    () => getWorkflow(client, chainId),
    (wf) => wf && Array.isArray(wf.steps) && wf.steps[0] && ['RUNNING', 'PENDING'].includes(wf.steps[0].status),
    TIMEOUT_MIN,
  );
  log('step 6 OK: BA step rescheduled after REJECT.');
}

async function step7ApproveAndAdvance(client, chainId) {
  log(`step 7: poll for the next SPEC_REVIEW, then APPROVE -> expect chain advance to DEV.`);
  const approval = await pollUntil(
    'next SPEC_REVIEW approval',
    () => listPendingApprovals(client),
    (list) => list.find((a) => a.approvalType === 'SPEC_REVIEW'),
    TIMEOUT_MIN,
  );
  log(`step 7: approving SPEC_REVIEW id=${approval.id}.`);
  await decideApproval(client, approval.id, true, 'Approved — proceed to implementation.');
  await pollUntil(
    'chain advance (DEV step RUNNING/currentStepIndex >= 1)',
    () => getWorkflow(client, chainId),
    (wf) => wf && (wf.currentStepIndex >= 1 || (Array.isArray(wf.steps) && wf.steps[1] && ['RUNNING', 'COMPLETED'].includes(wf.steps[1].status))),
    TIMEOUT_MIN,
  );
  log('step 7 OK: chain advanced to DEV.');
}

// ── Main ──────────────────────────────────────────────────────────────────────
async function main() {
  if (!gateEnv()) process.exit(EXIT_SKIP);
  if (!(await gateHealth())) process.exit(EXIT_SKIP);

  const { Client, StdioClientTransport } = await loadSdk();
  const { client, transport } = await connectMcp({ Client, StdioClientTransport });
  try {
    await step1ToolsPresent(client);
    await step2CreateWorkflowProbe(client);

    if (SMOKE_ONLY) {
      log('SDD_E2E_SMOKE_ONLY=1 — stopping after steps 1-2.');
      return EXIT_OK;
    }

    const chainId = await step3AriaChatInstantiate(client);
    await step4PollBaCompletion(client, chainId);
    const firstApproval = await step5SpecReview(client);
    await step6RejectAndReschedule(client, chainId, firstApproval.id);
    await step7ApproveAndAdvance(client, chainId);
    log('ALL SDD E2E STEPS PASSED.');
    return EXIT_OK;
  } finally {
    try {
      await client.close();
    } catch {
      /* ignore */
    }
    try {
      await transport.close();
    } catch {
      /* ignore */
    }
  }
}

main()
  .then((code) => process.exit(code ?? EXIT_OK))
  .catch((err) => {
    warn(`FAILED: ${err instanceof Error ? err.message : String(err)}`);
    if (err instanceof Error && err.stack) {
      warn(err.stack.split('\n').slice(0, 6).join('\n'));
    }
    process.exit(EXIT_FAIL);
  });
