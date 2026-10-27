import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { RailNav } from '../RailNav';

/** Reads the router location so a link click can be proven, not inferred. */
function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{location.pathname}</output>;
}

function renderRail(initialEntry = '/') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <RailNav />
      <LocationProbe />
    </MemoryRouter>,
  );
}

// Task 8: rail destinations are real links (middle-click, open in new tab,
// focus order, screen-reader semantics), not buttons that call navigate().
describe('RailNav navigation semantics (Task 8)', () => {
  it('renders every route item as a link and keeps Configure as a button', () => {
    renderRail();

    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(10);
    expect(screen.getByRole('link', { name: /overview/i })).toHaveAttribute('href', '/');
    expect(screen.getByRole('link', { name: /chat/i })).toHaveAttribute('href', '/chat');
    expect(screen.getByRole('button', { name: 'Open configuration' })).toBeInTheDocument();
    // The Configure control is an action, not a destination.
    expect(screen.queryByRole('link', { name: /configure/i })).not.toBeInTheDocument();
  });

  it('marks the active route with aria-current="page"', () => {
    renderRail('/chat');

    expect(screen.getByRole('link', { name: /chat/i })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: /overview/i })).not.toHaveAttribute('aria-current');
    expect(screen.getByRole('link', { name: /runs/i })).not.toHaveAttribute('aria-current');
  });

  it('marks only the root link active at / and uses prefix matching for subroutes', () => {
    const { unmount } = renderRail('/');
    expect(screen.getByRole('link', { name: /overview/i })).toHaveAttribute('aria-current', 'page');
    expect(screen.queryAllByRole('link', { current: 'page' })).toHaveLength(1);
    unmount();

    renderRail('/crew/agent-1');
    expect(screen.getByRole('link', { name: /crew/i })).toHaveAttribute('aria-current', 'page');
  });

  it('navigates through the router when a link is clicked', async () => {
    const user = userEvent.setup();
    renderRail();
    expect(screen.getByTestId('location')).toHaveTextContent('/');

    await user.click(screen.getByRole('link', { name: /chat/i }));

    expect(screen.getByTestId('location')).toHaveTextContent('/chat');
    expect(screen.getByRole('link', { name: /chat/i })).toHaveAttribute('aria-current', 'page');
  });

  it('keeps the existing rail classes and data-view hooks on the links', () => {
    renderRail();
    const overview = screen.getByRole('link', { name: /overview/i });
    expect(overview).toHaveClass('rail-btn', 'active');
    expect(overview).toHaveAttribute('data-view', 'overview');
  });
});
