import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfirmDialog } from '../ConfirmDialog';

/** Renders one dialog with sensible defaults; overrides win. */
function setup(over: Partial<React.ComponentProps<typeof ConfirmDialog>> = {}) {
  const onConfirm = vi.fn();
  const onCancel = vi.fn();
  const props = {
    open: true,
    title: 'Cancel task — confirmation required',
    message: 'This cancels the card and stops the work in progress on it.',
    onConfirm,
    onCancel,
    ...over,
  };
  const utils = render(<ConfirmDialog {...props} />);
  return { ...utils, onConfirm, onCancel, props };
}

describe('ConfirmDialog', () => {
  it('renders nothing when open is false', () => {
    const { container } = setup({ open: false });

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(container).toBeEmptyDOMElement();
  });

  it('renders the title, message and both controls when open', () => {
    setup({ title: 'Retire selected agents?', message: 'You are about to retire 2 agent(s).' });

    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAccessibleName('Retire selected agents?');
    expect(screen.getByText('Retire selected agents?')).toBeInTheDocument();
    expect(screen.getByText('You are about to retire 2 agent(s).')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
  });

  it('labels the dialog with a unique id per instance', () => {
    render(
      <>
        <ConfirmDialog open title="First" message="a" onConfirm={vi.fn()} onCancel={vi.fn()} />
        <ConfirmDialog open title="Second" message="b" onConfirm={vi.fn()} onCancel={vi.fn()} />
      </>,
    );

    const [first, second] = screen.getAllByRole('dialog');
    const firstId = first.getAttribute('aria-labelledby');
    const secondId = second.getAttribute('aria-labelledby');
    expect(firstId).toBeTruthy();
    expect(secondId).toBeTruthy();
    expect(firstId).not.toBe(secondId);
    expect(first.querySelector('h3')?.id).toBe(firstId);
    expect(second.querySelector('h3')?.id).toBe(secondId);
  });

  it('fires onConfirm from the Confirm control', async () => {
    const user = userEvent.setup();
    const { onConfirm, onCancel } = setup();

    await user.click(screen.getByRole('button', { name: 'Confirm' }));

    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onCancel).not.toHaveBeenCalled();
  });

  it('fires onCancel from the Cancel control', async () => {
    const user = userEvent.setup();
    const { onConfirm, onCancel } = setup();

    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('fires onCancel on backdrop click but not on a dialog-body click', () => {
    const { container, onConfirm, onCancel } = setup();

    fireEvent.click(screen.getByText('Cancel task — confirmation required'));
    expect(onCancel).not.toHaveBeenCalled();
    expect(onConfirm).not.toHaveBeenCalled();

    fireEvent.click(container.querySelector('.modal-overlay') as HTMLElement);
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('fires onCancel on Escape', () => {
    const { onConfirm, onCancel } = setup();

    fireEvent.keyDown(window, { key: 'Escape' });

    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('ignores Escape while closed', () => {
    const { onCancel } = setup({ open: false });

    fireEvent.keyDown(window, { key: 'Escape' });

    expect(onCancel).not.toHaveBeenCalled();
  });

  it('defaults the control labels to exactly Confirm and Cancel', () => {
    setup();

    expect(screen.getByRole('button', { name: 'Confirm' })).toHaveTextContent('Confirm');
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveTextContent('Cancel');
  });

  it('honours confirmLabel / cancelLabel overrides', () => {
    setup({ confirmLabel: 'Approve & execute', cancelLabel: 'Keep' });

    expect(screen.getByRole('button', { name: 'Approve & execute' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Keep' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Confirm' })).not.toBeInTheDocument();
  });

  it('autofocuses the confirm control so keyboard users land on the safe default', () => {
    setup();

    expect(screen.getByRole('button', { name: 'Confirm' })).toHaveFocus();
  });

  it('styles the confirm control as destructive only when danger is set', () => {
    const { unmount } = setup({ danger: true });
    expect(screen.getByRole('button', { name: 'Confirm' }).className).toContain('danger');
    unmount();

    setup();
    expect(screen.getByRole('button', { name: 'Confirm' }).className).not.toContain('danger');
  });

  it('never falls back to window.confirm', async () => {
    const nativeConfirm = vi.spyOn(window, 'confirm');
    const user = userEvent.setup();
    const { onConfirm } = setup();

    await user.click(screen.getByRole('button', { name: 'Confirm' }));

    expect(nativeConfirm).not.toHaveBeenCalled();
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });
});
