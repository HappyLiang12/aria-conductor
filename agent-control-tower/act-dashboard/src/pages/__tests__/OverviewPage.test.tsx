import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import OverviewPage from '../OverviewPage';
import type { DrawerState } from '../../components/DrawerContext';

// Light render test: the expanded bottom widget strip is layout/DOM structure;
// stacking vs the .review-fullpage overlay is CSS (verified visually on the
// dev server) — here we only pin the DOM contract of the expanded state.

vi.mock('../../components/ExecutiveSummary', () => ({ default: () => <div data-testid="widget" /> }));
vi.mock('../../components/KanbanBoard', () => ({ default: () => <div data-testid="widget" /> }));
vi.mock('../../components/AgentTeam', () => ({ default: () => <div data-testid="widget" /> }));
vi.mock('../../components/ReviewQueue', () => ({ default: () => <div data-testid="widget" /> }));
vi.mock('../../components/ActivityTimeline', () => ({ default: () => <div data-testid="widget" /> }));
vi.mock('../../components/MorningBriefing', () => ({ default: () => <div data-testid="widget" /> }));

let mockState: DrawerState = {
  taskDrawer: { open: false, itemId: null },
  agentDrawer: { open: false, agentId: null },
  reviewExpanded: false,
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

describe('OverviewPage expanded review layout (D5)', () => {
  beforeEach(() => {
    mockState = {
      taskDrawer: { open: false, itemId: null },
      agentDrawer: { open: false, agentId: null },
      reviewExpanded: false,
    };
  });

  it('renders the bottom widget strip in .expanded-strip when review is expanded', () => {
    mockState = {
      taskDrawer: { open: true, itemId: 'k-1' },
      agentDrawer: { open: false, agentId: null },
      reviewExpanded: true,
    };
    const { container } = ui();

    const strip = container.querySelector('.expanded-strip');
    expect(strip).not.toBeNull();
    // The four dashboard widgets reflow into the bottom strip while expanded.
    expect(strip!.querySelectorAll('[data-testid="widget"]')).toHaveLength(4);
  });

  it('does not render .expanded-strip while collapsed', () => {
    mockState = {
      taskDrawer: { open: true, itemId: 'k-1' },
      agentDrawer: { open: false, agentId: null },
      reviewExpanded: false,
    };
    const { container } = ui();

    expect(container.querySelector('.expanded-strip')).toBeNull();
  });
});
