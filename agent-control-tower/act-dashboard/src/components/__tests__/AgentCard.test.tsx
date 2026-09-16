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
});
