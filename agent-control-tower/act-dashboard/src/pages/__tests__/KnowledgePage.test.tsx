import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { KnowledgePage } from '../KnowledgePage';
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
