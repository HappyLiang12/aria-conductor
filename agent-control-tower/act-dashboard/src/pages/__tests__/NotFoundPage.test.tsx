import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import NotFoundPage from '../NotFoundPage';

describe('NotFoundPage', () => {
  it('states the route is missing and links back to Overview', () => {
    render(
      <MemoryRouter>
        <NotFoundPage />
      </MemoryRouter>,
    );
    expect(screen.getByTestId('not-found')).toBeInTheDocument();
    expect(screen.getByText(/page not found/i)).toBeInTheDocument();
    const link = screen.getByRole('link', { name: /back to overview/i });
    expect(link).toHaveAttribute('href', '/');
  });
});
