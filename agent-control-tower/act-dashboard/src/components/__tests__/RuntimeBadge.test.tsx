import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { RuntimeBadge } from '../RuntimeBadge';

describe('RuntimeBadge', () => {
  it('renders the frozen core and mode as one compact badge', () => {
    render(<RuntimeBadge core="opencode" mode="HOST" />);

    const badge = screen.getByText('opencode · HOST');
    expect(badge).toBeInTheDocument();
    expect(badge).toHaveClass('type-badge');
  });

  it('keeps the existing placeholders for a partially known runtime', () => {
    const { rerender } = render(<RuntimeBadge core="qoder" />);
    expect(screen.getByText('qoder · ?')).toBeInTheDocument();

    rerender(<RuntimeBadge mode="SANDBOX" />);
    expect(screen.getByText('? · SANDBOX')).toBeInTheDocument();
  });

  it('renders a dash when neither field is known', () => {
    render(<RuntimeBadge />);

    expect(screen.getByText('-')).toHaveClass('type-badge');
  });
});
