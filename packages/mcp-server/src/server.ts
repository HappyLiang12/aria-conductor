import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from '@modelcontextprotocol/sdk/types.js';

import { agentTools } from './tools/agents.js';
import { runTools } from './tools/runs.js';
import { workflowTools } from './tools/workflows.js';
import { executionTools } from './tools/execution.js';
import { approvalTools } from './tools/approvals.js';
import { kanbanTools } from './tools/kanban.js';
import { knowledgeTools } from './tools/knowledge.js';
import { ariaTools } from './tools/aria.js';
import { reportTools } from './tools/reports.js';
import { dashboardTools } from './tools/dashboard.js';
import { llmProviderTools } from './tools/llm-providers.js';
import { ariaNotificationTools } from './tools/aria-notifications.js';
import { ariaJobTools } from './tools/aria-jobs.js';
import { devSqlTools } from './tools/dev-sql.js';
import { promptCallTools } from './tools/prompt-calls.js';
import { trajectoryTools } from './tools/trajectory.js';
import { ActHttpError } from './http-client.js';
import { z } from 'zod';

// ── Collect all tools ───────────────────────────────────────────────────────
type ToolDef = {
  name: string;
  description: string;
  inputSchema: z.ZodObject<z.ZodRawShape>;
  handler: (args: any) => Promise<{ content: Array<{ type: 'text'; text: string }> }>;
};

const allTools: ToolDef[] = [
  ...agentTools,
  ...runTools,
  ...workflowTools,
  ...executionTools,
  ...approvalTools,
  ...kanbanTools,
  ...knowledgeTools,
  ...ariaTools,
  ...reportTools,
  ...dashboardTools,
  ...llmProviderTools,
  ...ariaNotificationTools,
  ...ariaJobTools,
  ...devSqlTools,
  ...promptCallTools,
  ...trajectoryTools,
];

const toolMap = new Map(allTools.map(t => [t.name, t]));

// ── Build MCP Server ────────────────────────────────────────────────────────
export function createServer(): Server {
  const server = new Server(
    { name: 'act-mcp-server', version: '0.1.0' },
    { capabilities: { tools: {} } },
  );

  // List tools
  server.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: allTools.map(t => ({
      name: t.name,
      description: t.description,
      inputSchema: zodToJsonSchema(t.inputSchema),
    })),
  }));

  // Call tool
  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const { name, arguments: args } = request.params;
    const tool = toolMap.get(name);

    if (!tool) {
      return {
        content: [{ type: 'text', text: `Unknown tool: ${name}` }],
        isError: true,
      };
    }

    try {
      const parsed = tool.inputSchema.parse(args ?? {});
      return await tool.handler(parsed);
    } catch (err) {
      if (err instanceof z.ZodError) {
        return {
          content: [{ type: 'text', text: `Validation error: ${err.message}` }],
          isError: true,
        };
      }
      if (err instanceof ActHttpError) {
        return {
          content: [{ type: 'text', text: `API error [${err.status}]: ${err.message}\n${err.details ? JSON.stringify(err.details) : ''}` }],
          isError: true,
        };
      }
      return {
        content: [{ type: 'text', text: `Error: ${err instanceof Error ? err.message : String(err)}` }],
        isError: true,
      };
    }
  });

  return server;
}

// ── Minimal Zod → JSON Schema converter ─────────────────────────────────────
function zodToJsonSchema(schema: z.ZodObject<z.ZodRawShape>): Record<string, unknown> {
  const shape = schema.shape;
  const properties: Record<string, unknown> = {};
  const required: string[] = [];

  for (const [key, value] of Object.entries(shape)) {
    const zodType = value as z.ZodTypeAny;
    properties[key] = zodFieldToJson(zodType);
    if (!zodType.isOptional()) {
      required.push(key);
    }
  }

  return {
    type: 'object',
    properties,
    ...(required.length > 0 ? { required } : {}),
  };
}

function zodFieldToJson(field: z.ZodTypeAny): Record<string, unknown> {
  // Unwrap optional
  if (field instanceof z.ZodOptional) {
    return zodFieldToJson(field.unwrap());
  }

  // String
  if (field instanceof z.ZodString) {
    const desc = field.description;
    return { type: 'string', ...(desc ? { description: desc } : {}) };
  }

  // Number
  if (field instanceof z.ZodNumber) {
    const desc = field.description;
    return { type: 'number', ...(desc ? { description: desc } : {}) };
  }

  // Boolean
  if (field instanceof z.ZodBoolean) {
    const desc = field.description;
    return { type: 'boolean', ...(desc ? { description: desc } : {}) };
  }

  // Enum
  if (field instanceof z.ZodEnum) {
    const desc = field.description;
    return { type: 'string', enum: field.options, ...(desc ? { description: desc } : {}) };
  }

  // Array
  if (field instanceof z.ZodArray) {
    const desc = field.description;
    return { type: 'array', items: zodFieldToJson(field.element), ...(desc ? { description: desc } : {}) };
  }

  // Record
  if (field instanceof z.ZodRecord) {
    return { type: 'object', additionalProperties: true };
  }

  // UUID (ZodString with check)
  if (field instanceof z.ZodEffects) {
    return zodFieldToJson(field.innerType());
  }

  // Fallback
  return { type: 'string' };
}
