import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { KnowledgePage, formatVersion } from '../KnowledgePage';
import type { KnowledgeItem } from '../../types';

vi.mock('../../api/knowledge', () => ({
  listKnowledge: vi.fn(),
  createKnowledge: vi.fn(),
  batchReviewKnowledge: vi.fn(),
  updateKnowledge: vi.fn(),
  getKnowledgeYaml: vi.fn(),
}));
vi.mock('../../api/agents', () => ({
  listAgents: vi.fn().mockResolvedValue([]),
}));
vi.mock('../../components/Layout', () => ({
  useWebSocketContext: () => ({
    lastMessage: null,
    isConnected: true,
    subscribe: () => ({ unsubscribe: () => {} }),
  }),
}));

import { listKnowledge, updateKnowledge, batchReviewKnowledge } from '../../api/knowledge';
import { listAgents } from '../../api/agents';

function mkItem(over: Partial<KnowledgeItem> = {}): KnowledgeItem {
  return {
    id: 'k-1',
    name: 'Review Skill',
    type: 'SKILL',
    description: 'A skill awaiting review',
    currentVersion: 1,
    status: 'PENDING',
    sensitivity: 'INTERNAL',
    createdAt: new Date().toISOString(),
    ...over,
  };
}

function ui() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <KnowledgePage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(listKnowledge).mockResolvedValue([mkItem()]);
  vi.mocked(updateKnowledge).mockResolvedValue(mkItem());
  vi.mocked(batchReviewKnowledge).mockResolvedValue(
    [mkItem()].map((r) => ({ status: 'fulfilled' as const, value: r })),
  );
});

describe('KnowledgePage review confirmation (#UI audit)', () => {
  it('review_requiresConfirmation: clicking ✓ opens a dialog and the mutation fires only on confirm', async () => {
    ui();
    fireEvent.click(await screen.findByTitle('Approve'));

    expect(screen.getByText('Confirm Review')).toBeInTheDocument();
    expect(updateKnowledge).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByText('Confirm Review')).not.toBeInTheDocument();
    expect(updateKnowledge).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTitle('Approve'));
    fireEvent.click(screen.getByRole('button', { name: /Confirm Approve/i }));
    await waitFor(() =>
      expect(updateKnowledge).toHaveBeenCalledWith('k-1', { status: 'APPROVED', reason: '' }),
    );
  });

  it('reject via ✗ fires only after dialog confirm', async () => {
    ui();
    fireEvent.click(await screen.findByTitle('Reject'));

    expect(screen.getByText('Confirm Review')).toBeInTheDocument();
    expect(updateKnowledge).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: /Confirm Reject/i }));
    await waitFor(() =>
      expect(updateKnowledge).toHaveBeenCalledWith('k-1', { status: 'REJECTED', reason: 'Rejected' }),
    );
  });

  it('batch approve goes through the confirm dialog', async () => {
    ui();
    await screen.findByTitle('Approve');
    // "Select all visible" is the first checkbox in the review queue toolbar
    const checkboxes = document.querySelectorAll('input[type="checkbox"]');
    fireEvent.click(checkboxes[0]);
    fireEvent.click(screen.getByRole('button', { name: /Batch Approve/ }));

    expect(screen.getByText('Confirm Review')).toBeInTheDocument();
    expect(batchReviewKnowledge).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: /Confirm Approve/i }));
    await waitFor(() =>
      expect(batchReviewKnowledge).toHaveBeenCalledWith(['k-1'], true, 'Batch approved'),
    );
  });
});

describe('KnowledgePage promote confirmation (#UI audit Task 10)', () => {
  function mockApprovedLibrary() {
    vi.mocked(listKnowledge).mockResolvedValue([
      mkItem({ id: 'k-live', name: 'Live Playbook', status: 'APPROVED', currentVersion: 2 }),
    ]);
  }

  async function selectApprovedItem() {
    const title = await screen.findByText('Live Playbook');
    fireEvent.click(title.closest('.kitem') as HTMLElement);
    await screen.findByRole('button', { name: /Promote/ });
  }

  it('does not promote until the operator confirms', async () => {
    mockApprovedLibrary();
    ui();
    await selectApprovedItem();

    fireEvent.click(screen.getByRole('button', { name: /Promote/ }));

    // Nothing may fire from the first click — the dialog only opens.
    expect(updateKnowledge).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(updateKnowledge).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: /Promote/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() => expect(updateKnowledge).toHaveBeenCalledTimes(1));
    expect(updateKnowledge).toHaveBeenCalledWith('k-live', { status: 'PROMOTED' });
    // Completion clears the pending state.
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('backdrop click clears the pending promote without mutating', async () => {
    mockApprovedLibrary();
    ui();
    await selectApprovedItem();

    fireEvent.click(screen.getByRole('button', { name: /Promote/ }));
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    fireEvent.click(document.querySelector('.modal-overlay') as HTMLElement);

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(updateKnowledge).not.toHaveBeenCalled();
  });

  it('surfaces the backend rejection instead of failing silently', async () => {
    mockApprovedLibrary();
    vi.mocked(updateKnowledge).mockRejectedValueOnce({
      response: { data: { message: 'Promote denied: the item is already in review.' } },
    });
    ui();
    await selectApprovedItem();

    fireEvent.click(screen.getByRole('button', { name: /Promote/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    expect(
      await screen.findByText('Promote denied: the item is already in review.'),
    ).toBeInTheDocument();
    expect(updateKnowledge).toHaveBeenCalledTimes(1);
  });
});

describe('KnowledgePage version rendering (approved list render site)', () => {
  /** Reads the meta line ("<version> · <owner> · <date>") of a rendered item card. */
  function metaLineOf(name: string): string {
    const title = screen.getByText(name);
    const card = title.closest('.kitem');
    return (card?.querySelector('.desc')?.textContent ?? '').trim();
  }

  it('renders an em dash for a null version and exactly "v1.0.0" for an already-prefixed version', async () => {
    vi.mocked(listKnowledge).mockResolvedValue([
      mkItem({ id: 'k-null', name: 'Unversioned Playbook', status: 'APPROVED', currentVersion: null }),
      mkItem({ id: 'k-prefixed', name: 'Prefixed Playbook', status: 'APPROVED', currentVersion: 'v1.0.0' }),
    ]);
    ui();
    await screen.findByText('Unversioned Playbook');

    const nullMeta = metaLineOf('Unversioned Playbook');
    const prefixedMeta = metaLineOf('Prefixed Playbook');

    // A null version must render the em dash placeholder — never a bare "v".
    expect(nullMeta).toMatch(/^—\s·/);
    expect(nullMeta).not.toMatch(/^v\s*·/);

    // An already-prefixed version must not be prefixed twice.
    expect(prefixedMeta).toMatch(/^v1\.0\.0\s·/);
    expect(prefixedMeta).not.toContain('vv1.0.0');
  });
});

describe('formatVersion', () => {
  it('returns an em dash when the backend sends null', () => {
    expect(formatVersion(null)).toBe('—');
  });
  it('does not double the v prefix when the backend value already has one', () => {
    expect(formatVersion('v1.0.0')).toBe('v1.0.0');
  });
  it('adds the v prefix to a bare number', () => {
    expect(formatVersion(3)).toBe('v3');
  });
  it('returns an em dash when the backend sends a whitespace-only value', () => {
    expect(formatVersion('   ')).toBe('—');
  });
});

// ---------------------------------------------------------------------------
// Task 11: ownership is a fact, not an inference. An item without a matching
// agentId belongs to the Unattributed bucket - never a hash-picked agent and
// never a "Shared" permission claim.
// ---------------------------------------------------------------------------

const agentA = {
  id: 'agent-a', name: 'Agent A', role: 'developer', agentType: 'ADK',
  healthStatus: 'HEALTHY', description: '', model: '', provider: 'opencode',
  createdAt: '2026-01-01T00:00:00Z',
} as never;
const agentB = {
  id: 'agent-b', name: 'Agent B', role: 'qa', agentType: 'ADK',
  healthStatus: 'HEALTHY', description: '', model: '', provider: 'opencode',
  createdAt: '2026-01-01T00:00:00Z',
} as never;
const agentC = {
  id: 'agent-c', name: 'Agent C', role: 'reviewer', agentType: 'ADK',
  healthStatus: 'HEALTHY', description: '', model: '', provider: 'opencode',
  createdAt: '2026-01-01T00:00:00Z',
} as never;

function totalOf(button: HTMLElement): number {
  return Number(button.querySelector('.metrics b')?.textContent ?? Number.NaN);
}

function spaceButtons(): HTMLElement[] {
  return Array.from(document.querySelectorAll('.kspace')) as HTMLElement[];
}

describe('KnowledgePage truthful ownership (Task 11)', () => {
  beforeEach(() => {
    vi.mocked(listAgents).mockResolvedValue([agentA, agentB] as never);
  });

  afterEach(() => {
    vi.mocked(listAgents).mockResolvedValue([]);
  });

  it('shows Unattributed for a missing owner and never invents one', async () => {
    vi.mocked(listKnowledge).mockResolvedValue([
      mkItem({ id: 'item-1', name: 'Legacy import', status: 'APPROVED', agentId: 'missing-agent' } as never),
    ]);
    ui();

    expect(await screen.findByRole('button', { name: /unattributed/i })).toBeInTheDocument();
    expect((await screen.findAllByText('Unattributed')).length).toBeGreaterThan(0);
    expect(screen.queryByText('Shared')).not.toBeInTheDocument();
  });

  it('does not migrate an unowned item when another agent is added', async () => {
    vi.mocked(listKnowledge).mockResolvedValue([
      mkItem({ id: 'item-1', agentId: 'missing-agent' } as never),
    ]);

    const first = ui();
    expect(totalOf(await screen.findByRole('button', { name: /unattributed/i }))).toBe(1);
    first.unmount();

    // A third unrelated agent appears: ownership cannot change, because none
    // was ever derived from the agent list.
    vi.mocked(listAgents).mockResolvedValue([agentA, agentB, agentC] as never);
    ui();
    expect(totalOf(await screen.findByRole('button', { name: /unattributed/i }))).toBe(1);
    expect(totalOf(screen.getByRole('button', { name: /Agent C/ }))).toBe(0);
  });

  it('filters the review queue to unowned items from the Unattributed space', async () => {
    vi.mocked(listKnowledge).mockResolvedValue([
      mkItem({ id: 'owned', name: 'Owned pending', status: 'PENDING', agentId: 'agent-a' } as never),
      mkItem({ id: 'lost', name: 'Unowned pending', status: 'PENDING', agentId: 'gone' } as never),
    ]);
    ui();

    expect(await screen.findByText('Owned pending')).toBeInTheDocument();
    expect(screen.getByText('Unowned pending')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /unattributed/i }));
    await waitFor(() => expect(screen.queryByText('Owned pending')).not.toBeInTheDocument());
    expect(screen.getByText('Unowned pending')).toBeInTheDocument();

    // Toggling the same space again clears the filter.
    fireEvent.click(screen.getByRole('button', { name: /unattributed/i }));
    await waitFor(() => expect(screen.getByText('Owned pending')).toBeInTheDocument());
  });

  it('accounts for every loaded item across real spaces and the Unattributed bucket', async () => {
    vi.mocked(listKnowledge).mockResolvedValue([
      mkItem({ id: 'k-1', status: 'PENDING', agentId: 'agent-a' } as never),
      mkItem({ id: 'k-2', status: 'APPROVED', agentId: 'agent-b' } as never),
      mkItem({ id: 'k-3', status: 'PENDING', agentId: 'gone' } as never),
      mkItem({ id: 'k-4', status: 'APPROVED' } as never),
    ]);
    ui();

    await screen.findByRole('button', { name: /unattributed/i });
    const spaces = spaceButtons();
    const total = spaces.reduce((sum, el) => sum + totalOf(el), 0);
    expect(total).toBe(4);
  });

  it('labels an unowned approved item Unattributed in the row and the detail', async () => {
    vi.mocked(listKnowledge).mockResolvedValue([
      mkItem({ id: 'k-live', name: 'Legacy playbook', status: 'APPROVED', agentId: 'gone' } as never),
    ]);
    ui();

    const title = await screen.findByText('Legacy playbook');
    const row = title.closest('.kitem') as HTMLElement;
    expect(row.textContent).toContain('Unattributed');
    expect(screen.queryByText('Shared')).not.toBeInTheDocument();

    fireEvent.click(row);
    const detail = document.querySelector('.kb-detail .meta') as HTMLElement;
    expect(detail.textContent).toContain('Unattributed');
    expect(detail.textContent).not.toContain('Shared');
  });

  it('never claims an item is Unattributed when the agent list failed to load', async () => {
    // A failed ownership source is not evidence of missing ownership: the item
    // must still be listed, but no bucket and no ownership label may be invented.
    vi.mocked(listAgents).mockRejectedValue(new Error('agents unavailable'));
    vi.mocked(listKnowledge).mockResolvedValue([
      mkItem({ id: 'k-live', name: 'Legacy playbook', status: 'APPROVED', agentId: 'agent-a' } as never),
    ]);
    ui();

    expect(await screen.findByText('Legacy playbook')).toBeInTheDocument();
    expect(await screen.findByText(/agent ownership unavailable/i)).toBeInTheDocument();
    expect(screen.queryByText('Unattributed')).not.toBeInTheDocument();
    expect(screen.queryByText(/No agents registered yet/i)).not.toBeInTheDocument();
    expect(spaceButtons()).toHaveLength(0);
  });
});
