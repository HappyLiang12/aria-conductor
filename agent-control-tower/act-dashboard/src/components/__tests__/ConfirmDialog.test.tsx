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

  it('labels the controls exactly Confirm and Cancel', () => {
    setup();

    expect(screen.getByRole('button', { name: 'Confirm' })).toHaveTextContent('Confirm');
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveTextContent('Cancel');
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

  // Replaces a vacuous "never falls back to window.confirm" case: ConfirmDialog
  // has no code path that could reach window.confirm, so that assertion passed
  // whatever the component did. The contract that CAN regress — and that is the
  // one that matters for a destructive gate — is that opening the dialog never
  // performs the action by itself; only a deliberate click on the rendered
  // Confirm control may.
  it('never fires onConfirm implicitly — opening the gate is not a confirmation', async () => {
    const user = userEvent.setup();
    const { onConfirm, onCancel, props, rerender } = setup({ open: false });

    rerender(<ConfirmDialog {...props} open />);
    expect(onConfirm).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: 'Confirm' }));

    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onCancel).not.toHaveBeenCalled();
  });
});

// ---------------------------------------------------------------------------
// Task 7: a destructive dialog is cancel-first. The safe exit receives initial
// focus, Enter on it dismisses exactly once, Tab cycles inside the dialog, and
// the opener is restored even when the dialog mounts already open.
// ---------------------------------------------------------------------------

describe('ConfirmDialog destroy-safe keyboard contract (Task 7)', () => {
  it('focuses Cancel in a danger dialog and only Cancel fires on Enter', async () => {
    const user = userEvent.setup();
    const { onConfirm, onCancel } = setup({ danger: true });

    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
    await user.keyboard('{Enter}');

    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('keeps Confirm first for a non-danger dialog', () => {
    setup();
    expect(screen.getByRole('button', { name: 'Confirm' })).toHaveFocus();
  });

  it('orders danger actions Cancel then Confirm in the DOM', () => {
    setup({ danger: true });
    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual(['Cancel', 'Confirm']);
  });

  it('traps Tab when focus was lost to the page body by an inside click', () => {
    // Clicking the dialog title/message moves focus to <body> in real browsers;
    // the keydown then targets body, not the dialog. The trap must still fire.
    setup({ danger: true });
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    expect(cancel).toHaveFocus();
    (document.activeElement as HTMLElement | null)?.blur();
    expect(document.activeElement).toBe(document.body);

    fireEvent.keyDown(document.body, { key: 'Tab' });

    expect(cancel).toHaveFocus();
  });

  it('traps Tab/Shift+Tab inside a danger dialog and never reaches the background', () => {
    const outside = document.createElement('button');
    outside.textContent = 'Outside';
    document.body.append(outside);
    try {
      outside.focus();

      setup({ danger: true });
      const cancel = screen.getByRole('button', { name: 'Cancel' });
      const confirm = screen.getByRole('button', { name: 'Confirm' });
      expect(screen.getByRole('dialog')).toHaveAttribute('aria-modal', 'true');
      expect(cancel).toHaveFocus();

      fireEvent.keyDown(cancel, { key: 'Tab' });
      expect(confirm).toHaveFocus();
      fireEvent.keyDown(confirm, { key: 'Tab' });
      expect(cancel).toHaveFocus();
      fireEvent.keyDown(cancel, { key: 'Tab', shiftKey: true });
      expect(confirm).toHaveFocus();
      fireEvent.keyDown(confirm, { key: 'Tab', shiftKey: true });
      expect(cancel).toHaveFocus();
      expect(outside).not.toHaveFocus();
    } finally {
      outside.remove();
    }
  });

  it('restores the invoker after close even though the dialog mounted open', () => {
    const invoker = document.createElement('button');
    invoker.textContent = 'Open';
    document.body.append(invoker);
    try {
      invoker.focus();

      const { unmount } = setup({ danger: true });
      expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();

      unmount();
      expect(invoker).toHaveFocus();
    } finally {
      invoker.remove();
    }
  });

  it('restores the opener when the dialog is closed by rerender', () => {
    const invoker = document.createElement('button');
    invoker.textContent = 'Open';
    document.body.append(invoker);
    try {
      const { props, rerender } = setup({ open: false });
      invoker.focus();

      rerender(<ConfirmDialog {...props} open danger />);
      expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();

      rerender(<ConfirmDialog {...props} open={false} danger />);
      expect(invoker).toHaveFocus();
    } finally {
      invoker.remove();
    }
  });

  it('dismisses once on Escape without confirming and restores the opener', () => {
    const invoker = document.createElement('button');
    invoker.textContent = 'Open';
    document.body.append(invoker);
    try {
      invoker.focus();
      const { onConfirm, onCancel, props, rerender } = setup({ danger: true });

      fireEvent.keyDown(window, { key: 'Escape' });
      expect(onCancel).toHaveBeenCalledTimes(1);
      expect(onConfirm).not.toHaveBeenCalled();

      rerender(<ConfirmDialog {...props} open={false} danger />);
      expect(invoker).toHaveFocus();
    } finally {
      invoker.remove();
    }
  });
});
