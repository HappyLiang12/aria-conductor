import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import TemplatesPanel from '../TemplatesPanel';

vi.mock('../../api/knowledge', () => ({
  listKnowledge: vi.fn(),
  retireKnowledge: vi.fn(),
  approveKnowledge: vi.fn(),
  createKnowledge: vi.fn(),
  getKnowledgeYaml: vi.fn(),
  instantiateWorkflowTemplate: vi.fn(),
}));

import { listKnowledge, approveKnowledge, retireKnowledge, createKnowledge, getKnowledgeYaml } from '../../api/knowledge';

const mockList = listKnowledge as Mock;
const mockApprove = approveKnowledge as Mock;
const mockRetire = retireKnowledge as Mock;
const mockCreate = createKnowledge as Mock;
const mockGetYaml = getKnowledgeYaml as Mock;

const TEMPLATES = [
  { id: 't1', name: 'development-workflow', type: 'WORKFLOW', status: 'APPROVED', description: 'SDD loop', currentVersion: 'v1.0.0', createdAt: '2026-01-01T00:00:00Z' },
  { id: 't2', name: 'draft-flow', type: 'WORKFLOW', status: 'PENDING', description: 'A draft', currentVersion: 'v0.1.0', createdAt: '2026-01-02T00:00:00Z' },
  { id: 't3', name: 'old-flow', type: 'WORKFLOW', status: 'RETIRED', description: 'Retired', currentVersion: 'v1.0.0', createdAt: '2026-01-03T00:00:00Z' },
];

function ui() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <TemplatesPanel />
    </QueryClientProvider>,
  );
}

describe('TemplatesPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockList.mockResolvedValue(TEMPLATES);
    mockApprove.mockResolvedValue({ ...TEMPLATES[1], status: 'APPROVED' });
    mockRetire.mockResolvedValue(undefined);
  });

  it('renders non-retired templates', async () => {
    ui();
    await waitFor(() => {
      expect(screen.getByText('development-workflow')).toBeInTheDocument();
      expect(screen.getByText('draft-flow')).toBeInTheDocument();
    });
    expect(screen.queryByText('old-flow')).not.toBeInTheDocument();
  });

  it('shows template count excluding retired', async () => {
    ui();
    await waitFor(() => {
      expect(screen.getByText('2 templates')).toBeInTheDocument();
    });
  });

  it('Run button is enabled for APPROVED templates', async () => {
    ui();
    await waitFor(() => expect(screen.getByText('development-workflow')).toBeInTheDocument());
    const runButtons = screen.getAllByText('Run');
    // First Run button (for approved template) should be enabled
    expect(runButtons[0].closest('button')).not.toBeDisabled();
  });

  it('Run button is disabled for PENDING templates', async () => {
    ui();
    await waitFor(() => expect(screen.getByText('draft-flow')).toBeInTheDocument());
    const runButtons = screen.getAllByText('Run');
    // Second Run button (for pending template) should be disabled
    expect(runButtons[1].closest('button')).toBeDisabled();
  });

  it('Approve button shown only for PENDING templates', async () => {
    ui();
    await waitFor(() => expect(screen.getByText('draft-flow')).toBeInTheDocument());
    const approveButtons = screen.getAllByText('Approve');
    expect(approveButtons).toHaveLength(1);
  });

  it('clicking Approve calls approveKnowledge', async () => {
    ui();
    await waitFor(() => expect(screen.getByText('Approve')).toBeInTheDocument());
    await userEvent.click(screen.getByText('Approve'));
    expect(mockApprove).toHaveBeenCalledWith('t2');
  });

  it('shows empty state when no templates', async () => {
    mockList.mockResolvedValue([]);
    ui();
    await waitFor(() => {
      expect(screen.getByText('No workflow templates yet.')).toBeInTheDocument();
    });
  });

  it('surfaces an approve failure (409) as visible error feedback', async () => {
    mockApprove.mockRejectedValue({ response: { status: 409, data: { message: 'Invalid review transition' } } });
    ui();
    await waitFor(() => expect(screen.getByText('Approve')).toBeInTheDocument());
    await userEvent.click(screen.getByText('Approve'));
    expect(await screen.findByRole('alert')).toHaveTextContent('Approve failed: Invalid review transition');
  });

  it('surfaces a duplicate failure with a fallback message for non-axios errors', async () => {
    mockGetYaml.mockResolvedValue('steps: []');
    mockCreate.mockRejectedValue(new Error('boom'));
    ui();
    await waitFor(() => expect(screen.getAllByText('Duplicate')).toHaveLength(2));
    await userEvent.click(screen.getAllByText('Duplicate')[0]);
    expect(await screen.findByRole('alert')).toHaveTextContent('Duplicate failed: boom');
  });
});
