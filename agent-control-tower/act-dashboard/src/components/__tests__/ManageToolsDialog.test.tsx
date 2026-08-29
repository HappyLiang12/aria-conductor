import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ManageToolsDialog } from '../ManageToolsDialog';
import type { Agent } from '../../types';

vi.mock('../api/agents', () => ({
  listTools: vi.fn().mockResolvedValue([]),
  getAgentTools: vi.fn().mockResolvedValue([]),
  assignAgentTool: vi.fn().mockResolvedValue({}),
  unassignAgentTool: vi.fn().mockResolvedValue({}),
  getAgentSkills: vi.fn().mockResolvedValue([]),
  assignAgentSkill: vi.fn().mockResolvedValue({}),
  unassignAgentSkill: vi.fn().mockResolvedValue({}),
  getRoleDefaults: vi.fn().mockResolvedValue({ tools: [], skills: [] }),
  setAgentTools: vi.fn().mockResolvedValue({}),
  setAgentSkills: vi.fn().mockResolvedValue({}),
}));
vi.mock('../api/skills', () => ({
  listSkills: vi.fn().mockResolvedValue([]),
}));

const agent: Agent = {
  id: 'a-1',
  name: 'Test Agent',
  description: '',
  agentType: 'NATIVE',
  role: 'dev',
  model: '',
  provider: '',
  healthStatus: 'HEALTHY',
  createdAt: '2026-01-01T00:00:00Z',
};

function ui(openAgent: Agent | null) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <ManageToolsDialog agent={openAgent} onClose={vi.fn()} />
    </QueryClientProvider>,
  );
}

describe('ManageToolsDialog (F3 regression)', () => {
  it('exposes no dialog role to the a11y tree while closed', () => {
    ui(null);
    // opacity-hiding alone kept the dialog in the a11y tree on page load;
    // a closed dialog must be invisible to assistive tech and automation.
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('exposes the dialog role when open', () => {
    ui(agent);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText(/Capabilities · Test Agent/)).toBeInTheDocument();
  });
});
