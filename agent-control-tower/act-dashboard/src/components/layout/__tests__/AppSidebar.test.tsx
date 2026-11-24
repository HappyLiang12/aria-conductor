import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { SidebarProvider } from '@/components/ui/sidebar';
import { AppSidebar } from '../AppSidebar';

const VIEWS = ['overview', 'crew', 'providers', 'runs', 'knowledge', 'memory', 'reports', 'aria', 'chat', 'workflows', 'ops', 'jobs'];

/** Reads the router location so a link click can be proven, not inferred. */
function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{location.pathname}</output>;
}

function renderSidebar(initialEntry = '/') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <SidebarProvider>
        <AppSidebar />
      </SidebarProvider>
      <LocationProbe />
    </MemoryRouter>,
  );
}

describe('AppSidebar', () => {
  it('keeps the e2e rail hooks and groups every route', () => {
    renderSidebar('/runs');
    for (const view of VIEWS) {
      expect(document.querySelector(`.rail-btn[data-view="${view}"]`)).not.toBeNull();
    }
    expect(document.querySelector('.rail-btn[data-view="runs"]')?.getAttribute('aria-current')).toBe('page');
  });

  // Task 8 of the legacy shell: rail destinations are real links (middle-click,
  // open in new tab, focus order, screen-reader semantics), not buttons that
  // call navigate().
  it('renders every route item as a link and keeps Configure as a button', () => {
    renderSidebar();

    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(VIEWS.length);
    expect(screen.getByRole('link', { name: /overview/i })).toHaveAttribute('href', '/');
    expect(screen.getByRole('link', { name: /aria/i })).toHaveAttribute('href', '/aria');
    expect(screen.getByRole('link', { name: /chat/i })).toHaveAttribute('href', '/chat');
    expect(screen.getByRole('link', { name: /memory/i })).toHaveAttribute('href', '/memory');
    expect(screen.getByRole('button', { name: 'Open configuration' })).toBeInTheDocument();
    // The Configure control is an action, not a destination.
    expect(screen.queryByRole('link', { name: /configure/i })).not.toBeInTheDocument();
  });

  it('marks the active route with aria-current="page"', () => {
    renderSidebar('/chat');

    expect(screen.getByRole('link', { name: /chat/i })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: /overview/i })).not.toHaveAttribute('aria-current');
    expect(screen.getByRole('link', { name: /runs/i })).not.toHaveAttribute('aria-current');
  });

  it('marks only the root link active at / and uses prefix matching for subroutes', () => {
    const { unmount } = renderSidebar('/');
    expect(screen.getByRole('link', { name: /overview/i })).toHaveAttribute('aria-current', 'page');
    expect(screen.queryAllByRole('link', { current: 'page' })).toHaveLength(1);
    unmount();

    renderSidebar('/crew/agent-1');
    expect(screen.getByRole('link', { name: /crew/i })).toHaveAttribute('aria-current', 'page');
  });

  it('navigates through the router when a link is clicked', async () => {
    const user = userEvent.setup();
    renderSidebar();
    expect(screen.getByTestId('location')).toHaveTextContent('/');

    await user.click(screen.getByRole('link', { name: /chat/i }));

    expect(screen.getByTestId('location')).toHaveTextContent('/chat');
    expect(screen.getByRole('link', { name: /chat/i })).toHaveAttribute('aria-current', 'page');
  });

  it('keeps the existing rail classes and data-view hooks on the links', () => {
    renderSidebar();
    const overview = screen.getByRole('link', { name: /overview/i });
    expect(overview).toHaveClass('rail-btn', 'active');
    expect(overview).toHaveAttribute('data-view', 'overview');
    // The shadcn wrapper must not swallow the legacy class: E2E selects the anchor.
    expect(overview.tagName).toBe('A');
  });
});
