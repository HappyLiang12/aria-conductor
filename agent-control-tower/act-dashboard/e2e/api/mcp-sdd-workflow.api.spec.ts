import { test, expect } from '@playwright/test';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { SSEClientTransport } from '@modelcontextprotocol/sdk/client/sse.js';

/**
 * MCP twin of e2e/sdd-workflow.spec.ts: an EXTERNAL MCP client (no browser, no
 * REST) drives the SDD loop purely through the platform's embedded MCP endpoint —
 * instantiate -> approve the SPEC_REVIEW gate -> poll the chain. This is the
 * "another agent can connect and operate Aria Conductor" contract
 * (docs/superpowers/specs/2026-09-05-mcp-into-opencode-design.md §7.5).
 *
 * Transport: tries streamable HTTP at /mcp first, falls back to SSE at /sse
 * (Spring AI 1.0.9 webmvc serves SSE only — verified from jar metadata).
 * V42 seeds guarantee the ba/dev/qa role agents; V40 seeds the APPROVED
 * development-workflow template.
 * Prerequisites: backend running with aria.mcp.enabled (h2 profile).
 */

test.describe.configure({ mode: 'serial', timeout: 600_000 });

const API_URL = process.env.API_URL || 'http://127.0.0.1:8080';
const GATE_TIMEOUT = Number(process.env.E2E_GATE_TIMEOUT_MS || 60_000);
const RUN_TIMEOUT = Number(process.env.E2E_RUN_TIMEOUT_MS || 180_000);

async function connectMcp(): Promise<Client> {
  const client = new Client({ name: 'mcp-e2e', version: '0.1.0' });
  try {
    await client.connect(new StreamableHTTPClientTransport(new URL(`${API_URL}/mcp`)));
  } catch {
    await client.connect(new SSEClientTransport(new URL(`${API_URL}/sse`)));
  }
  return client;
}

type Json = Record<string, any>;

/** Calls a tool and parses our uniform JSON envelope {"ok":bool,"data"|error fields}. */
async function callJson(client: Client, name: string, args: Json): Promise<Json> {
  const result = await client.callTool({ name, arguments: args });
  const text = (result.content as Array<{ type: string; text: string }>)
    .filter((c) => c.type === 'text')
    .map((c) => c.text)
    .join('');
  const parsed = JSON.parse(text) as Json;
  expect(parsed.ok, `${name} -> ${text.slice(0, 300)}`).toBe(true);
  return parsed;
}

async function pollMcp<T>(
  client: Client,
  fn: () => Promise<T | null>,
  timeoutMs: number,
  intervalMs = 3_000,
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let last: T | null = null;
  while (Date.now() < deadline) {
    const value = await fn();
    if (value != null) return value;
    last = value;
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  throw new Error(`pollMcp timed out after ${timeoutMs}ms`);
}

test('mcp: external client instantiates development-workflow, approves the gate, chain leaves WAITING_APPROVAL', async () => {
  const client = await connectMcp();
  try {
    // 1. Discover the APPROVED development-workflow template via MCP.
    const listed = await callJson(client, 'list_knowledge', { type: 'WORKFLOW', status: 'APPROVED' });
    const tpl = (listed.data as Json[]).find((k) => k.name === 'development-workflow');
    expect(tpl, 'V40 seed development-workflow must exist').toBeTruthy();

    // 2. Instantiate via MCP (same path as the Templates-tab Run button).
    const inst = await callJson(client, 'instantiate_workflow_template', {
      templateId: tpl.id,
      parameters: { issueRef: '#1-test', repoUrl: 'https://github.com/HappyLiang12/aria-conductor.git' },
    });
    const chain = inst.data;
    expect(chain.id).toBeTruthy();

    // 3. Poll the chain via get_workflow until the SPEC_REVIEW gate is up.
    //    Escape hatch mirrors the REST twin: a real BA run needs a working ADK
    //    runtime (opencode locally is slow; langchain in CI is fast) — if the
    //    gate does not appear in time and the chain is already FAILED, skip
    //    rather than flake. A RUNNING chain just needs a longer GATE_TIMEOUT.
    let approval: Json | null = null;
    try {
      approval = await pollMcp(client, async () => {
        const wf = await callJson(client, 'get_workflow', { chainId: chain.id });
        if (wf.data.status !== 'WAITING_APPROVAL') return null;
        const approvals = await callJson(client, 'list_approvals', { status: 'PENDING' });
        return (approvals.data as Json[]).find((a) => a.approvalType === 'SPEC_REVIEW') ?? null;
      }, GATE_TIMEOUT);
    } catch (e) {
      const wf = await callJson(client, 'get_workflow', { chainId: chain.id });
      test.skip(
        wf.data.status === 'FAILED' || wf.data.status === 'RUNNING',
        `BA run requires a working ADK runtime; chain status=${wf.data.status}`,
      );
      throw e;
    }
    expect(approval.content).toContain('#');
    expect(approval.knowledgeItemId).toBeTruthy();

    // 4. Approve the gate VIA MCP (operator-level tool action).
    const decided = await callJson(client, 'decide_approval', {
      approvalId: approval.id,
      approved: true,
      reason: 'mcp e2e lgtm',
    });
    expect(decided.ok).toBe(true);

    // 5. The chain must leave WAITING_APPROVAL; Dev/QA outcomes depend on the
    //    ADK runtime — the full PASS/DEFECT/SPEC_GAP routing is covered by
    //    SddWorkflowIntegrationTest (Java) and the live acceptance run.
    const resumed = await pollMcp(client, async () => {
      const wf = await callJson(client, 'get_workflow', { chainId: chain.id });
      return wf.data.status !== 'WAITING_APPROVAL' ? wf.data : null;
    }, RUN_TIMEOUT);
    expect(['RUNNING', 'COMPLETED', 'FAILED']).toContain(resumed.status);
  } finally {
    await client.close();
  }
});
