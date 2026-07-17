import { describe, it, expect, afterEach } from 'vitest';
import { createTestClient, mockFetch } from './helpers.js';
import type { Client } from '@modelcontextprotocol/sdk/client/index.js';

let ctx: { client: Client; cleanup: () => Promise<void> } | undefined;
let fetchMock: ReturnType<typeof mockFetch> | undefined;

afterEach(async () => {
  if (fetchMock) { fetchMock.restore(); fetchMock = undefined; }
  if (ctx) { await ctx.cleanup(); ctx = undefined; }
});

describe('MCP Server — protocol', () => {
  it('reports correct server info after handshake', async () => {
    ctx = await createTestClient();
    const ver = ctx.client.getServerVersion();
    expect(ver).toBeDefined();
    expect(ver!.name).toBe('act-mcp-server');
    expect(ver!.version).toBe('0.1.0');
  });

  it('supports ping', async () => {
    ctx = await createTestClient();
    const result = await ctx.client.ping();
    expect(result).toBeDefined();
  });

  it('lists tools with valid metadata', async () => {
    ctx = await createTestClient();
    const { tools } = await ctx.client.listTools();
    expect(tools.length).toBeGreaterThan(20);
    expect(tools.some(t => t.name === 'sql_execute')).toBe(true);
    for (const t of tools) {
      expect(t.name).toBeTruthy();
      expect(t.description).toBeTruthy();
      expect(t.inputSchema.type).toBe('object');
    }
  });

  it('create_agent has required name + agentType', async () => {
    ctx = await createTestClient();
    const { tools } = await ctx.client.listTools();
    const tool = tools.find(t => t.name === 'create_agent')!;
    expect(tool).toBeDefined();
    expect(tool.inputSchema.required).toContain('name');
    expect(tool.inputSchema.required).toContain('agentType');
  });

  it('list_runs has optional agentId + status', async () => {
    ctx = await createTestClient();
    const { tools } = await ctx.client.listTools();
    const tool = tools.find(t => t.name === 'list_runs')!;
    expect(tool).toBeDefined();
    expect(tool.inputSchema.required ?? []).not.toContain('agentId');
    expect(tool.inputSchema.required ?? []).not.toContain('status');
    expect(tool.inputSchema.properties).toHaveProperty('agentId');
    expect(tool.inputSchema.properties).toHaveProperty('status');
  });

  it('returns error for unknown tool', async () => {
    ctx = await createTestClient();
    const result = await ctx.client.callTool({ name: 'nonexistent_tool', arguments: {} });
    expect(result.isError).toBe(true);
    const text = (result.content as any[])[0].text;
    expect(text).toContain('Unknown tool');
  });

  it('returns validation error for missing required args', async () => {
    ctx = await createTestClient();
    // get_agent requires 'id'
    const result = await ctx.client.callTool({ name: 'get_agent', arguments: {} });
    expect(result.isError).toBe(true);
    const text = (result.content as any[])[0].text;
    expect(text).toContain('Validation error');
  });

  it('tool domains are all represented', async () => {
    ctx = await createTestClient();
    const { tools } = await ctx.client.listTools();
    const names = tools.map(t => t.name);
    // Spot-check one tool per domain
    expect(names).toContain('list_agents');
    expect(names).toContain('list_runs');
    expect(names).toContain('list_workflows');
    expect(names).toContain('start_execution');
    expect(names).toContain('list_pending_approvals');
    expect(names).toContain('list_kanban_items');
    expect(names).toContain('list_knowledge');
    expect(names).toContain('aria_chat');
    expect(names).toContain('generate_report');
    expect(names).toContain('get_dashboard_summary');
    expect(names).toContain('list_llm_providers');
    expect(names).toContain('sql_execute');
  });
});
