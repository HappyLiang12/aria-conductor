import { describe, it, expect } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { AriaActivityStrip } from '../AriaActivityStrip';
import type { PanelItem } from '../ariaPanelState';

type ActivityItem = Extract<PanelItem, { kind: 'activity' }>;

function strip(overrides: Partial<ActivityItem> = {}): ActivityItem {
  return {
    kind: 'activity',
    id: 'activity:r1',
    turnId: 'r1',
    collapsed: true,
    tools: [
      { name: 'mcp__aria-conductor__list_runs', status: 'ok', startedAt: 0, durationMs: 240, args: '{"status":"RUNNING"}', result: '3 runs' },
      { name: 'mcp__aria-conductor__remember_fact', status: 'error', startedAt: 0, durationMs: 1500, result: 'tool refused' },
    ],
    ...overrides,
  };
}

describe('AriaActivityStrip', () => {
  it('collapses a turn of tool calls into one row that names them', () => {
    render(<AriaActivityStrip item={strip()} />);

    expect(screen.getByText('mcp__aria-conductor__list_runs')).toBeInTheDocument();
    expect(screen.getByText('mcp__aria-conductor__remember_fact')).toBeInTheDocument();
    expect(screen.getByText(/2 tool calls/)).toBeInTheDocument();
    expect(screen.getByText(/1 failed/i)).toBeInTheDocument();
    // Collapsed: arguments and results stay out of the transcript.
    expect(screen.queryByText('{"status":"RUNNING"}')).not.toBeInTheDocument();
    expect(screen.queryByText('3 runs')).not.toBeInTheDocument();
  });

  it('expands to the arguments and result of each call and collapses again', () => {
    render(<AriaActivityStrip item={strip()} />);
    const toggle = screen.getByRole('button', { name: /tool calls/ });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(toggle);

    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('{"status":"RUNNING"}')).toBeInTheDocument();
    expect(screen.getByText('3 runs')).toBeInTheDocument();
    expect(screen.getByText('tool refused')).toBeInTheDocument();

    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('3 runs')).not.toBeInTheDocument();
  });

  it('marks a running call and reports a failed one', () => {
    render(
      <AriaActivityStrip
        item={strip({
          tools: [{ name: 'mcp__aria-conductor__remember_fact', status: 'running', startedAt: Date.now() }],
        })}
      />,
    );

    expect(screen.getByText(/1 tool call/)).toBeInTheDocument();
    expect(screen.getByText(/running/i)).toBeInTheDocument();
    expect(screen.queryByText(/failed/i)).not.toBeInTheDocument();
  });
});
