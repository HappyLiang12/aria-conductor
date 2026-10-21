import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { ErrorNotice } from '../ErrorNotice';

function LocationTracker({ paths }: { paths: string[] }) {
  const location = useLocation();
  paths.push(location.pathname);
  return null;
}

function renderNotice(presented: {
  text: string;
  action?: { tab: 'llm' | 'runtime' | 'config'; label: string } | { path: string; label: string };
}) {
  const paths: string[] = [];
  render(
    <MemoryRouter initialEntries={['/runs']}>
      <LocationTracker paths={paths} />
      <ErrorNotice presented={presented} />
    </MemoryRouter>,
  );
  return paths;
}

describe('ErrorNotice', () => {
  it('renders the backend text and opens Configure on the action tab', async () => {
    const listener = vi.fn();
    window.addEventListener('act:open-configure', listener);

    renderNotice({
      text: 'Qoder runtime credential is not configured (qoder:operator).',
      action: { tab: 'llm', label: 'Open Configure → LLM Providers' },
    });

    expect(screen.getByRole('alert')).toHaveTextContent('Qoder runtime credential is not configured');
    await userEvent.click(screen.getByRole('button', { name: /LLM Providers/ }));

    expect(listener).toHaveBeenCalledTimes(1);
    expect((listener.mock.calls[0][0] as CustomEvent).detail).toEqual({ tab: 'llm' });
    window.removeEventListener('act:open-configure', listener);
  });

  it('navigates to Crew for a workspace action', async () => {
    const paths = renderNotice({
      text: 'Edit the agent\'s workspace settings in Crew.',
      action: { path: '/crew', label: 'Open Crew' },
    });

    await userEvent.click(screen.getByRole('button', { name: 'Open Crew' }));

    expect(paths[paths.length - 1]).toBe('/crew');
  });

  it('renders no action button when the failure names no fixable surface', () => {
    renderNotice({ text: 'The bridge stream ended.' });

    expect(screen.getByRole('alert')).toHaveTextContent('The bridge stream ended.');
    expect(screen.queryByRole('button')).toBeNull();
  });
});
