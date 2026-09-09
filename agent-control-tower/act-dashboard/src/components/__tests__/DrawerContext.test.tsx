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

/** Probe for the in-place review-mode state machine (spec 10.3). */
function ReviewProbe() {
  const { state, openTaskDrawer, openReviewMode, closeReviewMode, closeTaskDrawer } =
    useDrawerContext();
  return (
    <div>
      <span data-testid="task-open">{String(state.taskDrawer.open)}</span>
      <span data-testid="task-item">{state.taskDrawer.itemId ?? ''}</span>
      <span data-testid="review-target">{state.reviewTargetId ?? ''}</span>
      <button data-testid="open-task" onClick={() => openTaskDrawer('k-prev')} />
      <button data-testid="open-review" onClick={() => openReviewMode('k1')} />
      <button data-testid="close-review" onClick={closeReviewMode} />
      <button data-testid="close-task" onClick={closeTaskDrawer} />
    </div>
  );
}

describe('DrawerContext review mode (in-place expand, spec 10.3)', () => {
  it('openReviewMode sets the review target AND collapses the drawer', () => {
    const { getByTestId } = render(
      <DrawerProvider>
        <ReviewProbe />
      </DrawerProvider>,
    );
    act(() => getByTestId('open-task').click());
    act(() => getByTestId('open-review').click());
    expect(getByTestId('review-target').textContent).toBe('k1');
    expect(getByTestId('task-open').textContent).toBe('false');
  });

  it('closeReviewMode clears the target AND reopens the drawer on that card', () => {
    const { getByTestId } = render(
      <DrawerProvider>
        <ReviewProbe />
      </DrawerProvider>,
    );
    act(() => getByTestId('open-review').click());
    expect(getByTestId('review-target').textContent).toBe('k1');

    act(() => getByTestId('close-review').click());
    expect(getByTestId('review-target').textContent).toBe('');
    expect(getByTestId('task-open').textContent).toBe('true');
    // Collapse reopens the drawer on the card that was under review.
    expect(getByTestId('task-item').textContent).toBe('k1');
  });

  it('closing the task drawer also clears the review target', () => {
    const { getByTestId } = render(
      <DrawerProvider>
        <ReviewProbe />
      </DrawerProvider>,
    );
    act(() => getByTestId('open-review').click());
    expect(getByTestId('review-target').textContent).toBe('k1');

    act(() => getByTestId('close-task').click());
    expect(getByTestId('task-open').textContent).toBe('false');
    expect(getByTestId('review-target').textContent).toBe('');
  });
});

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
