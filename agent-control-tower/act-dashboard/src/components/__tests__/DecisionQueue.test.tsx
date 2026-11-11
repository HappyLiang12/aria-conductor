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

  // Task 37 / Item 6 (T35 audit row 12): `presentError` marks a 401/403 as
  // `operatorRejected`, but this row rendered the plain text — so a refusal on
  // the Overview read as "Decision failed." with no remedy anywhere on the page.
  it('renders the operator notice, not the server text, for a refused decision', () => {
    renderQueue({
      actionError: {
        id: 'ap-1',
        presented: { text: 'Decision failed.', operatorRejected: true },
      },
    });

    const notice = screen.getByTestId('operator-session-notice');
    expect(notice).toHaveTextContent(/deciding the ask is operator-only/i);
    expect(notice).toHaveTextContent(/loopback requests are the operator automatically/i);
    expect(screen.queryByText('Decision failed.')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  // Positive control: a 500-shaped presented error keeps the ordinary path.
  it('keeps the server text and no notice for a non-refusal action error', () => {
    renderQueue({ actionError: { id: 'ap-1', presented: { text: 'Decision failed.' } } });

    expect(screen.getByRole('alert')).toHaveTextContent('Decision failed.');
    expect(screen.queryByTestId('operator-session-notice')).not.toBeInTheDocument();
  });

  // The clarification row answers rather than decides, and the notice must name
  // the action the operator actually took.
  it('names answering for a refused answer on a clarification row', () => {
    renderQueue({
      rows: [{
        ...row,
        taskId: null,
        state: 'WAITING_INPUT',
        askType: 'CLARIFICATION',
        source: 'CLARIFICATION',
      }],
      actionError: {
        id: 'ap-1',
        presented: { text: 'Answer failed.', operatorRejected: true },
      },
    });

    expect(screen.getByTestId('operator-session-notice'))
      .toHaveTextContent(/answering the ask is operator-only/i);
  });

  it('collapses only to the one-line all-clear section', () => {
    renderQueue({ rows: [], count: 0, collapsed: true });
    expect(screen.getByText(/no decisions waiting/i)).toBeInTheDocument();
    expect(screen.queryByTestId('state-notice-empty')).not.toBeInTheDocument();
  });
});
