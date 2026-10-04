import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, act, fireEvent } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import type { WsEvent } from '../../types';
import { routeForNotificationType } from '../../utils/notificationRoutes';
import { Toast } from '../Toast';

// The Toast reads the shared WebSocket context from Layout. Swap it for a
// test context fed by a stateful stub that mirrors useWebSocket exactly:
// every emitted frame updates `lastMessage` AND fans out to `subscribe`
// handlers in the same tick, so React batching/coalescing behaves like the
// real provider (see the burst regression test below).
interface TestWsCtx {
  lastMessage: WsEvent | null;
  isConnected: boolean;
  send: (data: string) => void;
  subscribe: (handler: (e: WsEvent) => void) => { unsubscribe: () => void };
}

const TestWsContext = createContext<TestWsCtx>({
  lastMessage: null,
  isConnected: false,
  send: () => {},
  subscribe: () => ({ unsubscribe: () => {} }),
});

vi.mock('../Layout', () => ({
  useWebSocketContext: () => useContext(TestWsContext),
}));

// Set by the mounted stub; emitting mirrors one ws.onmessage frame.
let pushEvent: (e: WsEvent) => void = () => {};

function WsStubProvider({ children }: { children: React.ReactNode }) {
  const [lastMessage, setLastMessage] = useState<WsEvent | null>(null);
  const handlersRef = useRef(new Set<(e: WsEvent) => void>());
  const subscribe = useCallback((handler: (e: WsEvent) => void) => {
    handlersRef.current.add(handler);
    return {
      unsubscribe: () => {
        handlersRef.current.delete(handler);
      },
    };
  }, []);
  useEffect(() => {
    pushEvent = (event: WsEvent) => {
      setLastMessage(event);
      handlersRef.current.forEach((h) => h(event));
    };
    return () => {
      pushEvent = () => {};
    };
  }, []);
  return (
    <TestWsContext.Provider
      value={{ lastMessage, isConnected: true, send: () => {}, subscribe }}
    >
      {children}
    </TestWsContext.Provider>
  );
}

/** Emit frames like the socket does: all frames share one React tick. */
function emit(...events: WsEvent[]) {
  act(() => {
    for (const e of events) pushEvent(e);
  });
}

// Toast calls useNavigate (it mounts inside the app's BrowserRouter via
// Layout), so every render must supply a Router context.
function inRouter(ui: React.ReactElement) {
  return <MemoryRouter initialEntries={['/']}>{ui}</MemoryRouter>;
}

function renderToast() {
  return render(
    inRouter(
      <WsStubProvider>
        <Toast />
      </WsStubProvider>,
    ),
  );
}

function LocationTracker({ paths }: { paths: string[] }) {
  const location = useLocation();
  paths.push(location.pathname);
  return null;
}

describe('Toast', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders nothing when no event has arrived', () => {
    const { container } = renderToast();
    expect(container).toBeEmptyDOMElement();
  });

  it('shows a human-readable label without the raw event type for noteworthy events', () => {
    renderToast();
    emit({ type: 'run.completed', payload: { status: 'FAILED' }, timestamp: 't1' });

    expect(screen.getByText('Run Failed')).toBeInTheDocument();
    // The machine-oriented event type must never be surfaced to users.
    expect(screen.queryByText('run.completed')).not.toBeInTheDocument();
  });

  // Regression (observability-live, CI shard 2): a run's lifecycle frames
  // (run.started, kanban.*, run.completed, aria.notification) arrive in one
  // burst within ~30ms. The Toast used to read only `lastMessage`; when React
  // coalesced the burst into one render, the toast-worthy frame was never
  // observed and no toast appeared. Consuming `subscribe` delivers every
  // frame regardless of render batching.
  it('shows the Run Failed toast when the run lifecycle arrives as one burst', () => {
    renderToast();
    emit(
      { type: 'run.started', payload: {}, timestamp: 't0' },
      { type: 'kanban.created', payload: {}, timestamp: 't1' },
      { type: 'run.iteration', payload: {}, timestamp: 't2' },
      { type: 'kanban.transitioned', payload: {}, timestamp: 't3' },
      { type: 'aria.notification', payload: { id: 'n-1', title: 'Run failed' }, timestamp: 't4' },
      { type: 'run.completed', payload: { status: 'FAILED' }, timestamp: 't5' },
      { type: 'kanban.transitioned', payload: {}, timestamp: 't6' },
    );

    expect(screen.getByText('Run Failed')).toBeInTheDocument();
    expect(screen.getByText('Run failed')).toBeInTheDocument();
  });

  // UX-6: approval expiry used to be completely silent to the operator. The
  // backend now broadcasts approval.expired, and it must surface as a toast
  // with an operator-readable label — never the raw event type.
  it('toasts approval.expired with a human-readable label', () => {
    renderToast();
    emit({ type: 'approval.expired', payload: { approvalId: 'a-1' }, timestamp: 't1' });

    expect(screen.getByText('Approval Expired')).toBeInTheDocument();
    expect(screen.queryByText('approval.expired')).not.toBeInTheDocument();
  });

  it.each(['run.started', 'kanban.created', 'kanban.transitioned', 'run.iteration'])(
    'does not toast internal lifecycle event %s',
    (type) => {
      const { container } = renderToast();
      emit({ type, payload: {}, timestamp: 't1' });
      expect(container).toBeEmptyDOMElement();
    },
  );

  it('does not toast unknown event types', () => {
    const { container } = renderToast();
    emit({ type: 'custom.event', payload: {}, timestamp: 't1' });
    expect(container).toBeEmptyDOMElement();
  });

  it('renders aria.notification events with their title and a View action', () => {
    renderToast();
    emit({
      type: 'aria.notification',
      payload: { id: 'n-1', title: 'Build finished' },
      timestamp: 't1',
    });

    expect(screen.getByText('Build finished')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'View' })).toBeInTheDocument();
  });

  // Non-aria toasts (approval.requested, run.completed, ...) were appended with
  // no action at all, so an approval toast could not be clicked through even
  // though its event type maps to a route. It must offer the same View action
  // as the aria.notification branch.
  it('offers a View action on an approval.requested toast', () => {
    renderToast();
    emit({ type: 'approval.requested', payload: { runId: 'r1' }, timestamp: 't1' });

    expect(screen.getByRole('button', { name: 'View' })).toBeInTheDocument();
  });

  // Rendering the button proves nothing about where it goes: a wrong-route
  // regression (e.g. a resurrected '/approvals' page) would still pass the
  // assertion above. Start away from the target route and click for real, so
  // only an actual navigate() to the mapped route satisfies the assertion.
  it('View on an approval.requested toast navigates to the route the map resolves', () => {
    const paths: string[] = [];
    render(
      <MemoryRouter initialEntries={['/runs']}>
        <WsStubProvider>
          <Toast />
          <LocationTracker paths={paths} />
        </WsStubProvider>
      </MemoryRouter>,
    );
    emit({ type: 'approval.requested', payload: { runId: 'r1' }, timestamp: 't1' });

    fireEvent.click(screen.getByRole('button', { name: 'View' }));

    // approval.requested => the overview, where the Kanban Review column is the
    // single HITL surface (there is no approvals page anymore).
    expect(routeForNotificationType('approval.requested')).toBe('/');
    expect(paths[paths.length - 1]).toBe(routeForNotificationType('approval.requested'));
  });

  it('uses a default title when the notification payload has none', () => {
    renderToast();
    emit({ type: 'aria.notification', payload: { id: 'n-2' }, timestamp: 't1' });

    expect(screen.getByText('Notification')).toBeInTheDocument();
  });

  it('deduplicates aria.notification events by notification id', () => {
    renderToast();
    emit({
      type: 'aria.notification',
      payload: { id: 'dup-1', title: 'Once only' },
      timestamp: 't1',
    });
    expect(screen.getAllByText('Once only')).toHaveLength(1);

    // same notification id arrives again as a new event object
    emit({
      type: 'aria.notification',
      payload: { id: 'dup-1', title: 'Once only' },
      timestamp: 't2',
    });
    expect(screen.getAllByText('Once only')).toHaveLength(1);
  });

  it('dismisses a toast automatically after 5 seconds', () => {
    renderToast();
    emit({ type: 'run.completed', payload: {}, timestamp: 't1' });
    expect(screen.getByText('Run Completed')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(4999);
    });
    expect(screen.getByText('Run Completed')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(screen.queryByText('Run Completed')).not.toBeInTheDocument();
  });

  // Regression: the dismiss timer of an older toast must survive the arrival
  // of newer events (previously the effect cleanup cancelled it, freezing the
  // whole toast stack on screen).
  it('dismisses each toast on its own schedule even when newer events arrive', () => {
    renderToast();

    emit({ type: 'run.completed', payload: { status: 'FAILED', n: 1 }, timestamp: 't1' });
    expect(screen.getByText('Run Failed')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(3000);
    });

    // A second noteworthy event arrives at t+3s; the first toast must still
    // expire at t+5s.
    emit({ type: 'approval.requested', payload: { n: 2 }, timestamp: 't2' });
    expect(screen.getByText('Approval Needed')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(2000); // t+5s
    });
    expect(screen.queryByText('Run Failed')).not.toBeInTheDocument();
    expect(screen.getByText('Approval Needed')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(3000); // t+8s
    });
    expect(screen.queryByText('Approval Needed')).not.toBeInTheDocument();
  });

  // Review P2-1: failures arrive as run.completed with payload.status, so the
  // label must reflect the terminal status instead of always saying Completed.
  it('labels run.completed with FAILED status as Run Failed', () => {
    renderToast();
    emit({ type: 'run.completed', payload: { status: 'FAILED' }, timestamp: 't1' });

    expect(screen.getByText('Run Failed')).toBeInTheDocument();
    expect(screen.queryByText('Run Completed')).not.toBeInTheDocument();
  });

  it('toasts the housekeeping completion audit event with a human label', () => {
    renderToast();
    emit({ type: 'audit.HOUSEKEEPING_EXECUTED', payload: {}, timestamp: 't1' });

    expect(screen.getByText('Housekeeping Executed')).toBeInTheDocument();
    expect(screen.queryByText('audit.HOUSEKEEPING_EXECUTED')).not.toBeInTheDocument();
  });

  it('keeps at most 5 toasts on screen', () => {
    renderToast();
    for (let i = 0; i < 7; i++) {
      emit({ type: 'run.completed', payload: { i }, timestamp: `t${i}` });
    }
    expect(screen.getAllByText('Run Completed')).toHaveLength(5);
  });

  // The View button on aria.notification toasts must actually navigate to the
  // resource route (previously it only console.logged). Payload shape mirrors
  // the backend: fine-grained `type` + coarse `resourceType` (NotificationDto).
  it('navigates to the resource route when View is clicked on an aria.notification', () => {
    const paths: string[] = [];
    render(
      inRouter(
        <WsStubProvider>
          <Toast />
          <LocationTracker paths={paths} />
        </WsStubProvider>,
      ),
    );
    emit({
      type: 'aria.notification',
      payload: {
        id: 'n-nav-1',
        title: 'Report ready',
        type: 'report.generated',
        resourceType: 'REPORT',
      },
      timestamp: 't1',
    });

    fireEvent.click(screen.getByRole('button', { name: 'View' }));
    expect(paths).toContain('/reports');
  });

  it('does not navigate when the notification type has no mapped route', () => {
    const paths: string[] = [];
    render(
      inRouter(
        <WsStubProvider>
          <Toast />
          <LocationTracker paths={paths} />
        </WsStubProvider>,
      ),
    );
    emit({
      type: 'aria.notification',
      payload: { id: 'n-nav-2', title: 'Daily brief', type: 'brief', resourceType: '' },
      timestamp: 't1',
    });

    fireEvent.click(screen.getByRole('button', { name: 'View' }));
    expect(paths).toEqual(['/']);
  });

  // The coarse resourceType (RUN/APPROVAL/KNOWLEDGE/REPORT) carried by every
  // notification is deliberately NOT a routing key — only the fine-grained
  // `type` field maps to routes. Keying on resourceType was the original bug:
  // the fine-grained keys never matched, so View was a silent no-op.
  it('does not navigate when only the coarse resourceType is present', () => {
    const paths: string[] = [];
    render(
      inRouter(
        <WsStubProvider>
          <Toast />
          <LocationTracker paths={paths} />
        </WsStubProvider>,
      ),
    );
    emit({
      type: 'aria.notification',
      payload: { id: 'n-nav-3', title: 'Build finished', resourceType: 'REPORT' },
      timestamp: 't1',
    });

    fireEvent.click(screen.getByRole('button', { name: 'View' }));
    expect(paths).toEqual(['/']);
  });
});
