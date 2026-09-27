import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { AgentCard } from '../AgentCard';
import type { Agent } from '../../types';

const baseAgent: Agent = {
  id: 'a-1',
  name: 'Test Agent',
  description: '',
  agentType: 'NATIVE',
  role: 'dev',
  model: '',
  provider: '',
  healthStatus: 'HEALTHY',
  createdAt: '2026-01-01T00:00:00Z',
};

/** Props other than the agent: the cases below only vary the agent itself. */
const cardProps = {};

describe('AgentCard pickup eligibility', () => {
  it('flags an agent that cannot receive cards, with the reason in the title', () => {
    render(
      <AgentCard
        agent={{
          ...baseAgent,
          pickupEligible: false,
          pickupIneligibleReasons: ['PICKUP_DISABLED'],
          lastProbedAt: null,
        }}
        {...cardProps}
      />,
    );

    const badge = screen.getByText('No pickup');
    expect(badge).toBeVisible();
    expect(badge.getAttribute('title')).toContain('PICKUP_DISABLED');
  });

  it('renders no pickup badge for an eligible agent', () => {
    render(<AgentCard agent={{ ...baseAgent, pickupEligible: true }} {...cardProps} />);

    expect(screen.queryByText('No pickup')).toBeNull();
  });

  it('names the last-probed stamp in the title alongside the reason', () => {
    render(
      <AgentCard
        agent={{
          ...baseAgent,
          pickupEligible: false,
          pickupIneligibleReasons: ['PICKUP_DISABLED'],
          lastProbedAt: '2026-09-16T00:00:00Z',
        }}
        {...cardProps}
      />,
    );

    const title = screen.getByText('No pickup').getAttribute('title') ?? '';
    expect(title).toContain('PICKUP_DISABLED');
    expect(title).toContain('last probed');
    expect(title).toContain('2026-09-16T00:00:00Z');
  });

  it('falls back to a placeholder when the backend reports no reasons', () => {
    render(
      <AgentCard
        agent={{
          ...baseAgent,
          pickupEligible: false,
          pickupIneligibleReasons: [],
          lastProbedAt: null,
        }}
        {...cardProps}
      />,
    );

    expect(screen.getByText('No pickup').getAttribute('title')).toContain('no reason reported');
  });

  it('keeps the full role reachable via the title when the long role is truncated visually', () => {
    const longRole =
      'AI operator assistant for the Aria Conductor. Helps manage AI agents, execute commands, and answer system questions.';
    render(<AgentCard agent={{ ...baseAgent, role: longRole }} {...cardProps} />);

    const roleTag = screen.getByText(longRole);
    expect(roleTag.className).toContain('role-tag');
    expect(roleTag.getAttribute('title')).toBe(longRole);
  });
});

/**
 * UX-3: the status pill renders the agent lifecycle flag (set at creation /
 * retire, not a live probe), so the labels must not claim online/offline
 * connectivity. Active / Degraded / Retired describe the flag honestly; the
 * underlying LiveStatus values stay as CSS class hooks.
 */
describe('AgentCard lifecycle status labels (UX-3)', () => {
  it('labels a HEALTHY agent "Active" instead of a probed-sounding "Online"', () => {
    render(<AgentCard agent={baseAgent} {...cardProps} />);

    expect(screen.getByText('Active')).toBeInTheDocument();
    expect(screen.queryByText('Online')).not.toBeInTheDocument();
  });

  it('labels a DEGRADED agent "Degraded" instead of "Idle"', () => {
    render(<AgentCard agent={{ ...baseAgent, healthStatus: 'DEGRADED' }} {...cardProps} />);

    expect(screen.getByText('Degraded')).toBeInTheDocument();
    expect(screen.queryByText('Idle')).not.toBeInTheDocument();
  });

  it('labels a RETIRED agent "Retired" instead of "Offline"', () => {
    render(<AgentCard agent={{ ...baseAgent, healthStatus: 'RETIRED' }} {...cardProps} />);

    expect(screen.getByText('Retired')).toBeInTheDocument();
    expect(screen.queryByText('Offline')).not.toBeInTheDocument();
  });
});
