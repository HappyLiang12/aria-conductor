import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ErrorNotice } from '../ErrorNotice';

describe('ErrorNotice', () => {
  it('renders the backend text and opens Configure on the action tab', async () => {
    const listener = vi.fn();
    window.addEventListener('act:open-configure', listener);

    render(<ErrorNotice presented={{
      text: 'Qoder runtime credential is not configured (qoder:operator).',
      action: { tab: 'llm', label: 'Open Configure → LLM Providers' },
    }} />);

    expect(screen.getByRole('alert')).toHaveTextContent('Qoder runtime credential is not configured');
    await userEvent.click(screen.getByRole('button', { name: /LLM Providers/ }));

    expect(listener).toHaveBeenCalledTimes(1);
    expect((listener.mock.calls[0][0] as CustomEvent).detail).toEqual({ tab: 'llm' });
    window.removeEventListener('act:open-configure', listener);
  });

  it('renders no action button when the failure names no fixable surface', () => {
    render(<ErrorNotice presented={{ text: 'The bridge stream ended.' }} />);

    expect(screen.getByRole('alert')).toHaveTextContent('The bridge stream ended.');
    expect(screen.queryByRole('button')).toBeNull();
  });
});
