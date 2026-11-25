import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AgentCatalog } from '../AgentCatalog';
import type { AgentTemplate } from '../../types';

vi.mock('../../api/agents', () => ({
  getTemplates: vi.fn(),
  createFromTemplate: vi.fn(),
  createAgent: vi.fn(),
  listAgents: vi.fn(),
}));

const TEMPLATES: AgentTemplate[] = [
  {
    id: 't-dev',
    label: 'Dev Agent',
    agentType: 'ADK',
    role: 'dev',
    model: 'gpt-4o-mini',
    provider: 'openai',
    description: 'Implements changes.',
  },
];

function ui() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <AgentCatalog />
    </QueryClientProvider>,
  );
}

describe('AgentCatalog (Wave 2 B1)', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    const { getTemplates, createFromTemplate, createAgent, listAgents } = await import('../../api/agents');
    (getTemplates as ReturnType<typeof vi.fn>).mockResolvedValue(TEMPLATES);
    (createFromTemplate as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'a-new' });
    (createAgent as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'a-new' });
    (listAgents as ReturnType<typeof vi.fn>).mockResolvedValue([]);
  });

  it('renders each template tile on a token card surface and keeps the catalog hook', async () => {
    const { container } = ui();

    expect(await screen.findByText('Dev Agent')).toBeInTheDocument();
    expect(container.querySelector('[data-testid="agent-catalog"]')).not.toBeNull();
    const tile = container.querySelector('[data-template="t-dev"]') as HTMLElement;
    expect(tile).not.toBeNull();
    expect(tile.className).toContain('tmpl');
    expect(tile.className).toContain('bg-card');
  });

  it('deploys the pressed template through createFromTemplate', async () => {
    const { createFromTemplate } = await import('../../api/agents');
    ui();
    await screen.findByText('Dev Agent');

    await userEvent.click(screen.getByRole('button', { name: '+ Deploy' }));

    await waitFor(() => expect(createFromTemplate).toHaveBeenCalledWith('t-dev'));
  });

  it('deploys on the fleet runtime pair when the roster already runs one', async () => {
    const { createFromTemplate, createAgent, listAgents } = await import('../../api/agents');
    (listAgents as ReturnType<typeof vi.fn>).mockResolvedValue([
      { id: 'a-1', name: 'Existing', adkProvider: 'opencode', executionMode: 'HOST' },
    ]);
    ui();
    await screen.findByText('Dev Agent');

    await userEvent.click(screen.getByRole('button', { name: '+ Deploy' }));

    await waitFor(() =>
      expect(createAgent).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Dev Agent',
          role: 'dev',
          adkProvider: 'opencode',
          executionMode: 'HOST',
        }),
      ),
    );
    expect(createFromTemplate).not.toHaveBeenCalled();
  });
});
