import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DecisionQueue } from '../DecisionQueue';
import type { DecisionRow } from '../../hooks/useDecisionQueue';

const row: DecisionRow = {
  id: 'ap-1',
  approvalId: 'ap-1',
  runId: 'run-1',
  taskId: 'k-1',
  title: 'Fix pickup retry',
  state: 'REVIEW',
  askType: 'REVIEW_REQUEST',
  source: 'LEGACY_GATE',
  reason: 'Check output',
  requestedAt: '2026-10-27T10:00:00Z',
  isPermission: false,
};

function renderQueue(over: Partial<React.ComponentProps<typeof DecisionQueue>> = {}) {
  return render(
    <DecisionQueue
      rows={[row]}
      phase="success"
      partial={false}
      count={1}
      retry={vi.fn()}
      onActivate={vi.fn()}
      onDecide={vi.fn()}
      onAnswer={vi.fn()}
      collapsed={false}
      {...over}
    />,
  );
}

describe('DecisionQueue', () => {
  it('renders the task, reason and one task-backed action', async () => {
    const onActivate = vi.fn();
    renderQueue({ onActivate });
    expect(screen.getByText('Fix pickup retry')).toBeInTheDocument();
    expect(screen.getByText('Check output')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Review' }));
    expect(onActivate).toHaveBeenCalledWith(row);
  });

  it('never claims all clear while loading or unavailable', () => {
    const { rerender } = renderQueue({ rows: [], phase: 'loading', count: null });
    expect(screen.queryByText(/no decisions waiting/i)).not.toBeInTheDocument();
    expect(screen.getByText('—')).toBeInTheDocument();
    rerender(
      <DecisionQueue
        rows={[]} phase="error" partial={false} count={null}
        retry={vi.fn()} onActivate={vi.fn()} onDecide={vi.fn()} onAnswer={vi.fn()}
        collapsed={false}
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent(/unavailable/i);
    expect(screen.queryByText(/no decisions waiting/i)).not.toBeInTheDocument();
  });

  it('keeps an unlinked approval actionable with direct decide and inline answer', async () => {
    const onDecide = vi.fn();
    const onAnswer = vi.fn();
    const decideRow: DecisionRow = {
      ...row, id: 'ap-2', approvalId: 'ap-2', taskId: null, state: 'UNKNOWN', source: 'LEGACY_GATE',
    };
    const askRow: DecisionRow = {
      ...decideRow, id: 'ap-3', approvalId: 'ap-3', askType: 'QUESTION', source: 'CLARIFICATION', reason: 'Which environment?',
    };
    renderQueue({ rows: [decideRow, askRow], count: 2, onDecide, onAnswer });

    expect(screen.getByText(/unavailable — decide here/i)).toBeInTheDocument();
    expect(screen.getByText(/unavailable — answer here/i)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }));
    expect(onDecide).toHaveBeenCalledWith(decideRow, true);
    await userEvent.click(screen.getByRole('button', { name: 'Deny' }));
    expect(onDecide).toHaveBeenCalledWith(decideRow, false);

    await userEvent.click(screen.getByRole('button', { name: 'Answer' }));
    await userEvent.type(screen.getByRole('textbox', { name: /answer/i }), 'staging');
    await userEvent.click(screen.getByRole('button', { name: /answer & continue/i }));
    expect(onAnswer).toHaveBeenCalledWith(askRow, 'staging');
  });

  it('renders a served action error on the matching row', () => {
    renderQueue({ actionError: { id: 'ap-1', presented: { text: 'Answer was rejected' } } });
    expect(screen.getByRole('alert')).toHaveTextContent('Answer was rejected');
  });

  it('collapses only to the one-line all-clear section', () => {
    renderQueue({ rows: [], count: 0, collapsed: true });
    expect(screen.getByText(/no decisions waiting/i)).toBeInTheDocument();
    expect(screen.queryByTestId('state-notice-empty')).not.toBeInTheDocument();
  });
});
