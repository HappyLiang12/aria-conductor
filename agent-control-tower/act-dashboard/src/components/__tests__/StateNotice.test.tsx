import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StateNotice } from '../StateNotice';

describe('StateNotice', () => {
  it('announces an error and exposes a real retry action', async () => {
    const onAction = vi.fn();
    render(
      <StateNotice
        kind="error"
        title="Decision queue unavailable"
        message="Network request failed"
        actionLabel="Retry"
        onAction={onAction}
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Decision queue unavailable');
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onAction).toHaveBeenCalledTimes(1);
  });

  it('uses status semantics for loading and empty states', () => {
    const { rerender } = render(<StateNotice kind="loading" title="Loading decisions" />);
    expect(screen.getByRole('status')).toHaveTextContent('Loading decisions');
    rerender(<StateNotice kind="empty" title="No decisions waiting" />);
    expect(screen.getByRole('status')).toHaveTextContent('No decisions waiting');
  });
});
