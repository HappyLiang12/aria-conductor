import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SlashCommandMenu from '../SlashCommandMenu';
import type { SlashCommandItem } from '../../utils/slashCommands';

const items: SlashCommandItem[] = [
  { id: '1', command: '/workflow', name: 'workflow', description: 'Run SDD loop' },
  { id: '2', command: '/code-review', name: 'code-review', description: 'Review code' },
  { id: '3', command: '/deploy', name: 'deploy', description: 'Deploy to prod' },
];

describe('SlashCommandMenu', () => {
  it('renders nothing when items is empty', () => {
    const { container } = render(
      <SlashCommandMenu items={[]} activeIndex={0} onHover={vi.fn()} onSelect={vi.fn()} />,
    );
    expect(container.innerHTML).toBe('');
  });

  it('renders all items with command and description', () => {
    render(<SlashCommandMenu items={items} activeIndex={0} onHover={vi.fn()} onSelect={vi.fn()} />);
    expect(screen.getByText('/workflow')).toBeInTheDocument();
    expect(screen.getByText('Run SDD loop')).toBeInTheDocument();
    expect(screen.getByText('/code-review')).toBeInTheDocument();
    expect(screen.getByText('/deploy')).toBeInTheDocument();
  });

  it('marks the active item with aria-selected', () => {
    render(<SlashCommandMenu items={items} activeIndex={1} onHover={vi.fn()} onSelect={vi.fn()} />);
    const options = screen.getAllByRole('option');
    expect(options[0]).toHaveAttribute('aria-selected', 'false');
    expect(options[1]).toHaveAttribute('aria-selected', 'true');
    expect(options[2]).toHaveAttribute('aria-selected', 'false');
  });

  it('calls onSelect when an item is clicked', async () => {
    const onSelect = vi.fn();
    render(<SlashCommandMenu items={items} activeIndex={0} onHover={vi.fn()} onSelect={onSelect} />);
    // mouseDown is used instead of click to prevent textarea blur
    const option = screen.getAllByRole('option')[2];
    await userEvent.click(option);
    expect(onSelect).toHaveBeenCalledWith(items[2]);
  });

  it('calls onHover when mouse enters an item', async () => {
    const onHover = vi.fn();
    render(<SlashCommandMenu items={items} activeIndex={0} onHover={onHover} onSelect={vi.fn()} />);
    const option = screen.getAllByRole('option')[1];
    await userEvent.hover(option);
    expect(onHover).toHaveBeenCalledWith(1);
  });

  it('has role=listbox on the container', () => {
    render(<SlashCommandMenu items={items} activeIndex={0} onHover={vi.fn()} onSelect={vi.fn()} />);
    expect(screen.getByRole('listbox')).toBeInTheDocument();
  });
});
