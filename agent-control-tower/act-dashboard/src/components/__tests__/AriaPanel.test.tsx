import { describe, it, expect, beforeEach, beforeAll, vi, type Mock } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AriaPanel from '../AriaPanel';
import type { StreamCallbacks } from '../../api/aria';

// jsdom does not implement element scrolling — the auto-scroll effect needs it.
beforeAll(() => {
  Element.prototype.scrollTo = () => {};
});

vi.mock('../../api/aria', () => ({
  streamMessage: vi.fn(),
  sendMessage: vi.fn(),
}));
vi.mock('../../api/ariaConversations', () => ({
  getLatestConversation: vi.fn(),
  getConversationTimeline: vi.fn(),
  deleteConversation: vi.fn(),
}));
vi.mock('../../api/runs', () => ({ cancelRun: vi.fn() }));
vi.mock('../../api/skills', () => ({ listSkills: vi.fn() }));

import { streamMessage } from '../../api/aria';
import { getLatestConversation, deleteConversation } from '../../api/ariaConversations';
import { listSkills } from '../../api/skills';

const mockStream = streamMessage as Mock;
const mockGetLatest = getLatestConversation as Mock;
const mockDeleteConversation = deleteConversation as Mock;
const mockListSkills = listSkills as Mock;

type StreamArgs = [
  string, // conversationId
  string, // message
  Array<{ role: string; content: string }>, // history
  StreamCallbacks,
  AbortSignal?,
  { isCancelled?: () => boolean; skillId?: string }?, // options
];

const SKILLS = [
  {
    id: 's1',
    name: 'Dev Workflow',
    description: 'Dev loop',
    enabled: true,
    stage: 'SKILL',
    template: 'steps: []',
  },
];

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <AriaPanel />
    </QueryClientProvider>,
  );
}

function textarea() {
  return screen.getByPlaceholderText(/Ask Aria anything/);
}

async function openPanel() {
  await userEvent.click(screen.getByRole('button', { name: 'Open Aria panel' }));
  return textarea();
}

function lastOptions(call: number) {
  return mockStream.mock.calls[call][5] as { skillId?: string } | undefined;
}

describe('AriaPanel slash-command skill handling', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
    mockGetLatest.mockResolvedValue(null);
    mockDeleteConversation.mockResolvedValue(undefined);
    mockListSkills.mockResolvedValue(SKILLS);
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onDone?.({ runId: 'r1', conversationId: 'conv-x', intent: 'chat' });
    });
  });

  it('keyboard selection clears the input and the sent message carries the skillId', async () => {
    renderPanel();
    const ta = await openPanel();
    await userEvent.type(ta, '/dev');
    expect(await screen.findByRole('option', { name: /dev-workflow/ })).toBeInTheDocument();
    await userEvent.type(ta, '{Enter}'); // keyboard select
    expect(ta).toHaveValue(''); // no /dev residue
    await userEvent.type(ta, 'do the thing');
    await userEvent.type(ta, '{Enter}'); // send
    expect(mockStream).toHaveBeenCalledTimes(1);
    expect(mockStream.mock.calls[0][1]).toBe('do the thing');
    expect(lastOptions(0)?.skillId).toBe('s1');
  });

  it('mouse selection clears the input and the sent message carries the skillId', async () => {
    renderPanel();
    const ta = await openPanel();
    await userEvent.type(ta, '/dev');
    const option = await screen.findByRole('option', { name: /dev-workflow/ });
    await userEvent.click(option);
    expect(ta).toHaveValue('');
    await userEvent.type(ta, 'do the thing');
    await userEvent.type(ta, '{Enter}');
    expect(mockStream).toHaveBeenCalledTimes(1);
    expect(mockStream.mock.calls[0][1]).toBe('do the thing');
    expect(lastOptions(0)?.skillId).toBe('s1');
  });

  it('does not include skillId when no skill is pending', async () => {
    renderPanel();
    const ta = await openPanel();
    await userEvent.type(ta, 'plain hello');
    await userEvent.type(ta, '{Enter}');
    expect(mockStream).toHaveBeenCalledTimes(1);
    expect(mockStream.mock.calls[0][1]).toBe('plain hello');
    expect(lastOptions(0)?.skillId).toBeUndefined();
  });

  it('Enter with zero slash matches sends the raw text as a normal message', async () => {
    renderPanel();
    const ta = await openPanel();
    await userEvent.type(ta, '/zzz');
    await userEvent.type(ta, '{Enter}');
    expect(mockStream).toHaveBeenCalledTimes(1);
    expect(mockStream.mock.calls[0][1]).toBe('/zzz');
    expect(lastOptions(0)?.skillId).toBeUndefined();
  });

  it('retry re-sends the original message with the same skillId', async () => {
    mockStream.mockImplementation(async (...args: StreamArgs) => {
      args[3]?.onError?.('boom');
    });
    renderPanel();
    const ta = await openPanel();
    await userEvent.type(ta, '/dev');
    await userEvent.click(await screen.findByRole('option', { name: /dev-workflow/ }));
    await userEvent.type(ta, 'do the thing');
    await userEvent.type(ta, '{Enter}');
    expect(await screen.findByText(/Streaming failed|boom/)).toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(mockStream).toHaveBeenCalledTimes(2));
    expect(mockStream.mock.calls[1][1]).toBe('do the thing');
    expect(lastOptions(1)?.skillId).toBe('s1');
  });

  it('starting a new conversation disarms the pending skill', async () => {
    renderPanel();
    const ta = await openPanel();
    await userEvent.type(ta, '/dev');
    await userEvent.click(await screen.findByRole('option', { name: /dev-workflow/ }));
    expect(screen.getByText('/Dev Workflow')).toBeInTheDocument(); // chip visible
    await userEvent.click(screen.getByRole('button', { name: 'Clear and start new conversation' }));
    expect(screen.queryByText('/Dev Workflow')).not.toBeInTheDocument(); // chip gone
    await userEvent.type(ta, 'hello again');
    await userEvent.type(ta, '{Enter}');
    expect(mockStream).toHaveBeenCalledTimes(1);
    expect(lastOptions(0)?.skillId).toBeUndefined();
  });
});
