import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AttentionQueue } from '../AttentionQueue';
import type { AttentionRow } from '../../hooks/useAttentionQueue';

const pickup: AttentionRow = {
  id: 'pickup-k-1', kind: 'pickup', title: 'Fix pickup retry',
  reason: 'port busy', taskId: 'k-1', cardStatus: 'TODO',
};
const run: AttentionRow = {
  id: 'run-run-1', kind: 'run', title: 'Run run-1', reason: 'boom',
  taskId: 'k-2', runId: 'run-1', cardStatus: 'REVIEW',
};
const provider: AttentionRow = {
  id: 'provider-p-2', kind: 'provider', title: 'Broken core',
  reason: 'Provider health check reported unhealthy',
};

function renderQueue(over: Partial<React.ComponentProps<typeof AttentionQueue>> = {}) {
  return render(
    <AttentionQueue
      rows={[pickup, run, provider]}
      phase="success"
      partial={false}
      count={3}
      retry={vi.fn()}
      onOpenTask={vi.fn()}
      onRetryPickup={vi.fn()}
      onOpenConfigure={vi.fn()}
      collapsed={false}
      {...over}
    />,
  );
}

describe('AttentionQueue', () => {
  it('exposes one real action per row without a batch action', async () => {
    const onOpenTask = vi.fn();
    const onRetryPickup = vi.fn();
    const onOpenConfigure = vi.fn();
    renderQueue({ onOpenTask, onRetryPickup, onOpenConfigure });

    expect(screen.getByText('port busy')).toBeInTheDocument();
    expect(screen.getByText('boom')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /retry all/i })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Retry pickup (starts 1 run)' }));
    expect(onRetryPickup).toHaveBeenCalledWith(pickup);
    await userEvent.click(screen.getByRole('button', { name: 'Open Configure' }));
    expect(onOpenConfigure).toHaveBeenCalledTimes(1);
  });

  it('never claims nothing needs attention while loading or unavailable', () => {
    const { rerender } = renderQueue({ rows: [], phase: 'loading', count: null });
    expect(screen.queryByText(/nothing needs attention/i)).not.toBeInTheDocument();
    expect(screen.getByText('—')).toBeInTheDocument();
    rerender(
      <AttentionQueue
        rows={[]} phase="error" partial={false} count={null}
        retry={vi.fn()} onOpenTask={vi.fn()} onRetryPickup={vi.fn()} onOpenConfigure={vi.fn()}
        collapsed={false}
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent(/unavailable/i);
  });

  it('renders a served action error on the matching row', () => {
    renderQueue({ actionError: { id: pickup.id, presented: { text: 'Pickup was rejected' } } });
    expect(screen.getByRole('alert')).toHaveTextContent('Pickup was rejected');
  });

  it('collapses to a one-line all-clear section', () => {
    renderQueue({ rows: [], count: 0, collapsed: true });
    expect(screen.getByText(/nothing needs attention/i)).toBeInTheDocument();
    expect(screen.queryByTestId('state-notice-empty')).not.toBeInTheDocument();
  });
});
