import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { SectionCard } from '../SectionCard';

describe('SectionCard', () => {
  it('renders the title as an accessible heading inside a token card', () => {
    render(
      <SectionCard title="Fleet">
        <p>Agent list</p>
      </SectionCard>,
    );

    expect(screen.getByRole('heading', { name: 'Fleet' })).toBeInTheDocument();
    expect(screen.getByText('Agent list').closest('[data-slot="card"]')).not.toBeNull();
  });

  it('renders the count, the actions slot and the children', () => {
    render(
      <SectionCard title="Approvals" count={3} actions={<button type="button">Review</button>}>
        <p>Waiting on the operator</p>
      </SectionCard>,
    );

    expect(screen.getByText('3')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Review' })).toBeInTheDocument();
    expect(screen.getByText('Waiting on the operator')).toBeInTheDocument();
  });

  it('accepts a string count', () => {
    render(
      <SectionCard title="Runs" count="12+">
        <p>Recent activity</p>
      </SectionCard>,
    );

    expect(screen.getByText('12+')).toBeInTheDocument();
  });
});
