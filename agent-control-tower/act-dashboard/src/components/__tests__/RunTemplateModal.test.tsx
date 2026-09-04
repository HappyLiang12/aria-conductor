import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RunTemplateModal from '../RunTemplateModal';
import { getKnowledgeYaml, instantiateWorkflowTemplate } from '../../api/knowledge';
import type { KnowledgeItem } from '../../types';

vi.mock('../../api/knowledge', () => ({
  getKnowledgeYaml: vi.fn(),
  instantiateWorkflowTemplate: vi.fn(),
}));

const mockGetYaml = getKnowledgeYaml as Mock;
const mockInstantiate = instantiateWorkflowTemplate as Mock;

const ITEM: KnowledgeItem = {
  id: 't1',
  name: 'dev-flow',
  type: 'WORKFLOW',
  description: 'A dev loop',
  currentVersion: 1,
  status: 'APPROVED',
  sensitivity: 'INTERNAL',
  createdAt: '2026-01-01T00:00:00Z',
};

function renderModal() {
  return render(<RunTemplateModal item={ITEM} onClose={vi.fn()} onSuccess={vi.fn()} />);
}

describe('RunTemplateModal required-parameter validation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockInstantiate.mockResolvedValue({});
  });

  it('blocks submit and lists missing required params while inputs are empty', async () => {
    mockGetYaml.mockResolvedValue('steps:\n  - prompt_template: "Fix {issueRef} in {issueRepo}"');
    renderModal();
    expect(await screen.findByLabelText('issueRef')).toBeInTheDocument();
    const run = screen.getByRole('button', { name: 'Run' });
    expect(run).toBeDisabled();
    expect(screen.getByRole('alert')).toHaveTextContent('Required parameters missing: issueRef, issueRepo');
    await userEvent.click(run);
    expect(mockInstantiate).not.toHaveBeenCalled();
  });

  it('submits when all required params are filled', async () => {
    mockGetYaml.mockResolvedValue('steps:\n  - prompt_template: "Fix {issueRef} in {issueRepo}"');
    const onSuccess = vi.fn();
    render(<RunTemplateModal item={ITEM} onClose={vi.fn()} onSuccess={onSuccess} />);
    await userEvent.type(await screen.findByLabelText('issueRef'), '#42');
    await userEvent.type(screen.getByLabelText('issueRepo'), 'owner/repo');
    await userEvent.click(screen.getByRole('button', { name: 'Run' }));
    await waitFor(() =>
      expect(mockInstantiate).toHaveBeenCalledWith('t1', { issueRef: '#42', issueRepo: 'owner/repo' }),
    );
    await waitFor(() => expect(onSuccess).toHaveBeenCalled());
  });

  it('allows repoUrl to stay empty (system default fallback) and sends no empty strings', async () => {
    mockGetYaml.mockResolvedValue('steps:\n  - prompt_template: "Clone {repoUrl}"');
    renderModal();
    await screen.findByLabelText(/repoUrl/);
    const run = screen.getByRole('button', { name: 'Run' });
    expect(run).toBeEnabled();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    await userEvent.click(run);
    await waitFor(() => expect(mockInstantiate).toHaveBeenCalledWith('t1', {}));
  });
});
