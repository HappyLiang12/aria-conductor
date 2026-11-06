import { z } from 'zod';
import { http, toJsonResult } from '../http-client.js';

export const ariaTools = [
  {
    name: 'aria_chat',
    description: 'Send a message to Aria (the ACT AI orchestrator) and get a response. Aria can use tools to manage agents, runs, knowledge, etc.',
    inputSchema: z.object({
      message: z.string().describe('Message to send to Aria'),
      conversationId: z.string().optional().describe('Conversation UUID for tracing across multiple runs. Omit to start a new conversation.'),
    }),
    handler: async ({ message, conversationId }: { message: string; conversationId?: string }) =>
      toJsonResult(await http.post('/api/v1/aria/chat', { message, conversationId })),
  },
];
