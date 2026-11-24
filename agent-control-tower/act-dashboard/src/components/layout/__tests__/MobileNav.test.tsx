import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MobileNav } from '../MobileNav';

const VIEWS = ['overview', 'crew', 'providers', 'runs', 'knowledge', 'memory', 'reports', 'aria', 'chat', 'workflows', 'ops', 'jobs'];

/** jsdom's setup never matches a media query, so the shell defaults to desktop. */
function matchMobile() {
  vi.spyOn(window, 'innerWidth', 'get').mockReturnValue(390);
  vi.spyOn(window, 'matchMedia').mockImplementation((query: string) => ({
    matches: query.includes('max-width'),
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }) as unknown as MediaQueryList);
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('MobileNav', () => {
  it('stays out of the desktop DOM so the grouped sidebar owns the rail hooks', () => {
    const { container } = render(
      <MemoryRouter initialEntries={['/']}>
        <MobileNav />
      </MemoryRouter>,
    );

    expect(container).toBeEmptyDOMElement();
  });

  it('renders every destination with the rail hooks below md and marks the active route', async () => {
    matchMobile();
    render(
      <MemoryRouter initialEntries={['/chat']}>
        <MobileNav />
      </MemoryRouter>,
    );

    const chat = await screen.findByRole('link', { name: /chat/i });
    await waitFor(() => expect(document.querySelectorAll('.rail-btn[data-view]')).toHaveLength(VIEWS.length));
    for (const view of VIEWS) {
      expect(document.querySelector(`.rail-btn[data-view="${view}"]`)).not.toBeNull();
    }
    expect(chat).toHaveAttribute('href', '/chat');
    expect(chat).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: /overview/i })).not.toHaveAttribute('aria-current');
  });
});
