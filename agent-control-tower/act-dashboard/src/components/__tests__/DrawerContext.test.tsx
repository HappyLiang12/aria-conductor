import { describe, it, expect } from 'vitest';
import { render, act } from '@testing-library/react';
import { DrawerProvider, useDrawerContext, TASK_DRAWER_EVENT } from '../DrawerContext';

/** Probe that exposes drawer state + open/close actions for assertions. */
function Probe() {
  const { state, openTaskDrawer, openAgentDrawer } = useDrawerContext();
  return (
    <div>
      <span data-testid="task-open">{String(state.taskDrawer.open)}</span>
      <span data-testid="agent-open">{String(state.agentDrawer.open)}</span>
      <button data-testid="open-task" onClick={() => openTaskDrawer('k-1')} />
      <button data-testid="open-agent" onClick={() => openAgentDrawer('a-1')} />
      {/* A focusable control inside an open drawer (real DOM class contract):
          Escape typed here must not slam the drawer shut. */}
      {state.agentDrawer.open && (
        <aside className="agent-drawer open">
          <input data-testid="drawer-input" />
        </aside>
      )}
    </div>
  );
}

function esc(target: EventTarget = window) {
  act(() => {
    target.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
  });
}

describe('DrawerContext Escape handling (regression)', () => {
  it('Escape closes drawers when pressed outside them', () => {
    const { getByTestId } = render(
      <DrawerProvider>
        <Probe />
      </DrawerProvider>,
    );
    act(() => getByTestId('open-agent').click());
    expect(getByTestId('agent-open').textContent).toBe('true');

    esc(window);
    expect(getByTestId('agent-open').textContent).toBe('false');
  });

  it('Escape from INSIDE an open drawer does not close it', () => {
    const { getByTestId } = render(
      <DrawerProvider>
        <Probe />
      </DrawerProvider>,
    );
    act(() => getByTestId('open-agent').click());
    const input = getByTestId('drawer-input');

    esc(input);
    // The drawer stays open — an Escape typed while working inside the drawer
    // (e.g. clearing the order console) must not destroy the operator's context.
    expect(getByTestId('agent-open').textContent).toBe('true');
  });

  it('re-opening via the canonical event still works after Escape', () => {
    const { getByTestId } = render(
      <DrawerProvider>
        <Probe />
      </DrawerProvider>,
    );
    act(() => {
      window.dispatchEvent(new CustomEvent(TASK_DRAWER_EVENT, { detail: { itemId: 'k-1' } }));
    });
    expect(getByTestId('task-open').textContent).toBe('true');

    esc(window);
    expect(getByTestId('task-open').textContent).toBe('false');

    act(() => {
      window.dispatchEvent(new CustomEvent(TASK_DRAWER_EVENT, { detail: { itemId: 'k-1' } }));
    });
    expect(getByTestId('task-open').textContent).toBe('true');
  });
});
