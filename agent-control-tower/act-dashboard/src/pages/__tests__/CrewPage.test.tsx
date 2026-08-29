import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import CrewPage from '../CrewPage';

vi.mock('../api/agents', () => ({
  listAgents: vi.fn().mockResolvedValue([]),
  createAgent: vi.fn().mockResolvedValue({ id: 'x' }),
  getTemplates: vi.fn().mockResolvedValue([]),
  getRoleDefaults: vi.fn().mockResolvedValue({ tools: [], skills: [] }),
  setAgentTools: vi.fn().mockResolvedValue({}),
  setAgentSkills: vi.fn().mockResolvedValue({}),
}));
vi.mock('../api/dashboard', () => ({
  getAgentTelemetry: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/adk', () => ({
  listAdkProviders: vi.fn().mockResolvedValue([]),
}));
vi.mock('../components/AgentCard', () => ({
  AgentCard: () => <div data-testid="agent-card" />,
}));
vi.mock('../components/AgentCatalog', () => ({
  AgentCatalog: () => <div data-testid="agent-catalog" />,
}));
vi.mock('../components/ManageToolsDialog', () => ({
  ManageToolsDialog: () => null,
}));

function ui() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <CrewPage />
    </QueryClientProvider>,
  );
}

describe('CrewPage Add Agent dialog (F3 regression)', () => {
  it('exposes no Add Agent dialog to the a11y tree before interaction', () => {
    ui();
    // Previously the closed dialog stayed mounted with role="dialog" and was
    // visible to assistive tech / automation on page load.
    expect(screen.queryByRole('dialog', { name: /Add Agent/ })).not.toBeInTheDocument();
  });

  it('exposes the dialog after clicking + Add Agent', () => {
    ui();
    fireEvent.click(screen.getByRole('button', { name: '+ Add Agent' }));
    expect(screen.getByRole('dialog', { name: /Add Agent/ })).toBeInTheDocument();
  });
});
