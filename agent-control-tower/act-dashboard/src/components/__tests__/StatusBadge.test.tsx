import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StatusBadge } from '../StatusBadge';

describe('StatusBadge', () => {
  it('renders the status text', () => {
    render(<StatusBadge status="RUNNING" />);
    expect(screen.getByText('RUNNING')).toBeInTheDocument();
  });

  it('uses the mapped color for known statuses', () => {
    render(<StatusBadge status="FAILED" />);
    const badge = screen.getByText('FAILED');
    expect(badge).toHaveStyle({ borderColor: '#f44336' });
  });

  it('distinguishes run and approval statuses by color', () => {
    render(<StatusBadge status="COMPLETED" />);
    render(<StatusBadge status="EXPIRED" />);
    expect(screen.getByText('COMPLETED')).toHaveStyle({ borderColor: '#66bb6a' });
    expect(screen.getByText('EXPIRED')).toHaveStyle({ borderColor: '#9e9e9e' });
  });

  it('falls back to the neutral color for unknown statuses', () => {
    render(<StatusBadge status="SOMETHING_NEW" />);
    expect(screen.getByText('SOMETHING_NEW')).toHaveStyle({ borderColor: '#90a4ae' });
  });

  it('defaults to the md size class', () => {
    render(<StatusBadge status="PENDING" />);
    expect(screen.getByText('PENDING')).toHaveClass('status-badge', 'status-badge-md');
  });

  it('applies the sm size class when requested', () => {
    render(<StatusBadge status="PAUSED" size="sm" />);
    const badge = screen.getByText('PAUSED');
    expect(badge).toHaveClass('status-badge-sm');
    expect(badge).not.toHaveClass('status-badge-md');
  });
});
