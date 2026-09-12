import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import OverviewPage from '../OverviewPage';
import type { DrawerState } from '../../components/DrawerContext';

// Light render test: the in-place review layout is DOM structure; the grid
// transition and reviewIn animation are CSS (verified visually on the dev
// server) — here we pin the DOM contract of the expanded state (spec 10.3).

vi.mock('../../components/ExecutiveSummary', () => ({ default: () => <div data-testid="exec-summary" /> }));
vi.mock('../../components/KanbanBoard', () => ({ default: () => <div data-testid="kanban-board" /> }));
vi.mock('../../components/AgentTeam', () => ({ default: () => <div data-testid="agent-team" /> }));
vi.mock('../../components/ReviewQueue', () => ({ default: () => <div data-testid="review-queue" /> }));
vi.mock('../../components/ActivityTimeline', () => ({ default: () => <div data-testid="activity-timeline" /> }));
vi.mock('../../components/MorningBriefing', () => ({ default: () => <div data-testid="morning-briefing" /> }));
vi.mock('../../components/ReviewWorkspace', () => ({
  ReviewWorkspace: ({ itemId }: { itemId: string }) => (
    <section className="review-workspace" data-testid="review-workspace">
      {itemId}
    </section>
  ),
}));

let mockState: DrawerState = {
  taskDrawer: { open: false, itemId: null },
  agentDrawer: { open: false, agentId: null },
  reviewTargetId: null,
};
vi.mock('../../components/DrawerContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../components/DrawerContext')>();
  return {
    ...actual,
    // OverviewPage only reads state; the action callbacks are unused in render.
    useDrawerContext: () => ({ state: mockState }) as ReturnType<typeof actual.useDrawerContext>,
  };
});

function ui() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <OverviewPage />
    </QueryClientProvider>,
  );
}

describe('OverviewPage in-place review layout (spec 10.3)', () => {
  beforeEach(() => {
    mockState = {
      taskDrawer: { open: false, itemId: null },
      agentDrawer: { open: false, agentId: null },
      reviewTargetId: null,
    };
  });

  it('renders the ReviewWorkspace in the main column with the bottom strip when a review target is set', () => {
    mockState = {
      taskDrawer: { open: false, itemId: 'k-1' },
      agentDrawer: { open: false, agentId: null },
      reviewTargetId: 'k-1',
    };
    const { container } = ui();

    const ws = container.querySelector('.review-workspace');
    expect(ws).not.toBeNull();
    expect(ws!.textContent).toBe('k-1');
    // The four dashboard widgets reflow into the bottom strip while expanded.
    const strip = container.querySelector('.expanded-strip');
    expect(strip).not.toBeNull();
    expect(strip!.querySelectorAll('[data-testid]')).toHaveLength(4);
    // Board + summary give way to the workspace; the right column is gone.
    expect(container.querySelector('[data-testid="kanban-board"]')).toBeNull();
    expect(container.querySelector('[data-testid="exec-summary"]')).toBeNull();
    expect(container.querySelectorAll('.col')).toHaveLength(1);
    // Single-column grid while the review workspace is in place.
    const layout = container.querySelector('.layout') as HTMLElement;
    expect(layout.style.gridTemplateColumns).toBe('minmax(0, 1fr)');
  });

  it('renders the normal two-column board layout without a review target', () => {
    const { container } = ui();

    expect(container.querySelector('.review-workspace')).toBeNull();
    expect(container.querySelector('.expanded-strip')).toBeNull();
    expect(container.querySelector('[data-testid="kanban-board"]')).not.toBeNull();
    expect(container.querySelector('[data-testid="exec-summary"]')).not.toBeNull();
    expect(container.querySelector('[data-testid="agent-team"]')).not.toBeNull();
    const layout = container.querySelector('.layout') as HTMLElement;
    expect(layout.style.gridTemplateColumns).toBe('minmax(0, 1fr) minmax(320px, 380px)');
  });
});
