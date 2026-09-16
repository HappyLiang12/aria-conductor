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
});
