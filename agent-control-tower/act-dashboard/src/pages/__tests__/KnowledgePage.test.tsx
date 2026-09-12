import { describe, it, expect, vi, beforeEach } from 'vitest';
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

  it('surfaces a failure message instead of failing silently', async () => {
    mockApprovedLibrary();
    vi.mocked(updateKnowledge).mockRejectedValueOnce(new Error('boom'));
    ui();
    await selectApprovedItem();

    fireEvent.click(screen.getByRole('button', { name: /Promote/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    expect(await screen.findByText('Promote failed. Please retry.')).toBeInTheDocument();
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
