import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PageHeader } from '../PageHeader';

describe('PageHeader', () => {
  it('renders the page title as the level-one heading', () => {
    render(<PageHeader title="Runs" />);

    expect(screen.getByRole('heading', { level: 1, name: 'Runs' })).toBeInTheDocument();
  });

  it('renders the description and the actions slot', () => {
    render(
      <PageHeader
        title="Crew"
        description="Every agent, its health and its frozen runtime."
        actions={<button type="button">Refresh</button>}
      />,
    );

    expect(
      screen.getByText('Every agent, its health and its frozen runtime.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Refresh' })).toBeInTheDocument();
  });

  it('renders on the token page-header surface', () => {
    const { container } = render(<PageHeader title="Runs" />);

    expect(container.querySelector('[data-slot="page-header"]')).not.toBeNull();
  });
});
