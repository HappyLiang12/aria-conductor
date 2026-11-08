import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';

vi.mock('../client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  },
}));

import client from '../client';
import { buildSyncChatContent, sendMessage } from '../aria';
import type { AriaChatResponse } from '../aria';

const post = client.post as Mock;

/**
 * F2 (SDD hardening): the sync chat answered "I've completed your request…"
 * while the underlying run was still RUNNING, and reported nothing when the run
 * had failed. The api lane adds the run's status/error to the response; the
 * dashboard must never repeat a completion claim the status does not support.
 */
const baseResponse = (over: Partial<AriaChatResponse> = {}): AriaChatResponse => ({
  message: "I've completed your request. The workflow was instantiated.",
  runId: 'e823008f-1111-2222-3333-444455556666',
  conversationId: 'conv-1',
  intent: 'chat',
  actionsTaken: [],
  timestamp: '2026-11-06T12:00:00Z',
  ...over,
});

describe('sync chat copy (F2)', () => {
  it('does not repeat a completion claim for a failed run, and names the failure', () => {
    const content = buildSyncChatContent(
      baseResponse({ status: 'FAILED', errorMessage: 'RUNTIME_CREDENTIAL_NOT_CONFIGURED' }),
    );

    expect(content).toContain('FAILED');
    expect(content).toContain('e823008f');
    expect(content).toContain('RUNTIME_CREDENTIAL_NOT_CONFIGURED');
    expect(content).not.toMatch(/completed your request/i);
    expect(content).not.toMatch(/I've completed/i);
  });

  it('reports a still-running chat as in progress instead of completed', () => {
    const content = buildSyncChatContent(baseResponse({ status: 'RUNNING' }));

    expect(content).toContain('still RUNNING');
    expect(content).toContain('e823008f');
    expect(content).not.toMatch(/completed/i);
  });

  it('keeps the backend answer only for a terminal COMPLETED run, and cites the status', () => {
    const content = buildSyncChatContent(baseResponse({ status: 'COMPLETED' }));

    expect(content).toContain('The workflow was instantiated');
    expect(content).toContain('COMPLETED');
    expect(content).toContain('e823008f');
  });

  it('flags an unreported status instead of trusting the backend wording', () => {
    const content = buildSyncChatContent(baseResponse());

    expect(content).toContain('status not reported');
    expect(content).toContain('e823008f');
    // The legacy prose is quoted after the caveat, never presented as verified.
    expect(content.startsWith('Run e823008f: status not reported')).toBe(true);
    expect(content).toContain('Backend reply (unverified):');
  });
});

describe('sendMessage (F2)', () => {
  beforeEach(() => vi.clearAllMocks());

  it('POSTs the sync chat body and returns the status-aligned copy', async () => {
    post.mockResolvedValue({
      data: baseResponse({ status: 'FAILED', errorMessage: 'bridge exited before the first tool call' }),
    });

    const message = await sendMessage('conv-1', 'instantiate the workflow', []);

    expect(post).toHaveBeenCalledTimes(1);
    expect(post).toHaveBeenCalledWith('/api/v1/aria/chat', {
      message: 'instantiate the workflow',
      history: [],
      conversationId: 'conv-1',
    });
    expect(message.role).toBe('assistant');
    expect(message.timestamp).toBe('2026-11-06T12:00:00Z');
    expect(message.content).toContain('FAILED');
    expect(message.content).toContain('bridge exited before the first tool call');
    expect(message.content).not.toMatch(/completed your request/i);
  });
});
