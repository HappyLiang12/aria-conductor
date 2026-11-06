import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';

export interface DrawerSlot {
  open: boolean;
  itemId: string | null;
}

export interface AgentDrawerSlot {
  open: boolean;
  agentId: string | null;
}

export interface DrawerState {
  taskDrawer: DrawerSlot;
  agentDrawer: AgentDrawerSlot;
  // In-place review workspace target (spec 10.3): the kanban card id whose
  // ReviewWorkspace is rendered inside the Overview layout, or null.
  reviewTargetId: string | null;
}

export interface DrawerContextValue {
  state: DrawerState;
  openTaskDrawer: (itemId: string) => void;
  closeTaskDrawer: () => void;
  openReviewMode: (itemId: string) => void;
  closeReviewMode: () => void;
  openAgentDrawer: (agentId: string) => void;
  closeAgentDrawer: () => void;
}

const initialState: DrawerState = {
  taskDrawer: { open: false, itemId: null },
  agentDrawer: { open: false, agentId: null },
  reviewTargetId: null,
};

const DrawerContext = createContext<DrawerContextValue | null>(null);

export const TASK_DRAWER_EVENT = 'act:open-task-drawer';
export const AGENT_DRAWER_EVENT = 'act:open-agent-drawer';

interface OpenTaskEventDetail {
  itemId: string;
}

interface OpenAgentEventDetail {
  agentId: string;
}

export function DrawerProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<DrawerState>(initialState);

  const openTaskDrawer = useCallback((itemId: string) => {
    setState((prev) => ({
      ...prev,
      taskDrawer: { open: true, itemId },
      // Surfaces are mutually exclusive (defense-in-depth): opening a task
      // drawer while a review workspace target is set tears the workspace
      // down so it can never linger behind an open drawer.
      reviewTargetId: null,
    }));
  }, []);

  const closeTaskDrawer = useCallback(() => {
    setState((prev) => ({
      ...prev,
      taskDrawer: { open: false, itemId: prev.taskDrawer.itemId },
      // Leaving the drawer must never leave a stale review workspace behind.
      reviewTargetId: null,
    }));
  }, []);

  // Spec 10.3 in-place expand: opening the review workspace collapses the
  // drawer — the workspace replaces it inside the Overview layout (no overlay).
  const openReviewMode = useCallback((itemId: string) => {
    setState((prev) => ({
      taskDrawer: { open: false, itemId: prev.taskDrawer.itemId },
      agentDrawer: prev.agentDrawer,
      reviewTargetId: itemId,
    }));
  }, []);

  // Collapse: clear the target AND reopen the drawer on the card that was
  // under review so the operator lands back exactly where they expanded.
  const closeReviewMode = useCallback(() => {
    setState((prev) => ({
      taskDrawer: { open: true, itemId: prev.reviewTargetId ?? prev.taskDrawer.itemId },
      agentDrawer: prev.agentDrawer,
      reviewTargetId: null,
    }));
  }, []);

  const openAgentDrawer = useCallback((agentId: string) => {
    setState((prev) => ({
      ...prev,
      agentDrawer: { open: true, agentId },
    }));
  }, []);

  const closeAgentDrawer = useCallback(() => {
    setState((prev) => ({
      ...prev,
      agentDrawer: { open: false, agentId: prev.agentDrawer.agentId },
    }));
  }, []);

  useEffect(() => {
    const onTask = (e: Event) => {
      const detail = (e as CustomEvent<OpenTaskEventDetail>).detail;
      if (detail?.itemId) openTaskDrawer(detail.itemId);
    };
    const onAgent = (e: Event) => {
      const detail = (e as CustomEvent<OpenAgentEventDetail>).detail;
      if (detail?.agentId) openAgentDrawer(detail.agentId);
    };
    window.addEventListener(TASK_DRAWER_EVENT, onTask as EventListener);
    window.addEventListener(AGENT_DRAWER_EVENT, onAgent as EventListener);
    return () => {
      window.removeEventListener(TASK_DRAWER_EVENT, onTask as EventListener);
      window.removeEventListener(AGENT_DRAWER_EVENT, onAgent as EventListener);
    };
  }, [openTaskDrawer, openAgentDrawer]);

  // Close drawers on Escape for accessibility. Escape events that originate
  // INSIDE an open drawer (operator typing in the order console etc.) must not
  // slam it shut — only Escape pressed outside the drawers closes them.
  // `.modal-overlay` is the shared confirmation surface (ConfirmDialog), which
  // call sites render as a SIBLING of the drawer so its fixed overlay can
  // escape the drawer's transform. It is therefore "inside the drawer" for the
  // purpose of this guard: while a modal is on top it owns the Escape key —
  // the press dismisses the modal, and the drawer underneath must stay put.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      const target = e.target instanceof Element ? e.target : null;
      if (target?.closest('.agent-drawer.open, .drawer.open, .modal-overlay')) return;
      if (state.taskDrawer.open) closeTaskDrawer();
      if (state.agentDrawer.open) closeAgentDrawer();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [state.taskDrawer.open, state.agentDrawer.open, closeTaskDrawer, closeAgentDrawer]);

  const value = useMemo<DrawerContextValue>(
    () => ({
      state,
      openTaskDrawer,
      closeTaskDrawer,
      openReviewMode,
      closeReviewMode,
      openAgentDrawer,
      closeAgentDrawer,
    }),
    [state, openTaskDrawer, closeTaskDrawer, openReviewMode, closeReviewMode, openAgentDrawer, closeAgentDrawer]
  );

  return <DrawerContext.Provider value={value}>{children}</DrawerContext.Provider>;
}

export function useDrawerContext(): DrawerContextValue {
  const ctx = useContext(DrawerContext);
  if (!ctx) {
    throw new Error('useDrawerContext must be used inside <DrawerProvider>');
  }
  return ctx;
}

/** Convenience helpers for non-React callsites or quick imperative dispatch. */
export function dispatchOpenTaskDrawer(itemId: string) {
  window.dispatchEvent(new CustomEvent(TASK_DRAWER_EVENT, { detail: { itemId } }));
}

export function dispatchOpenAgentDrawer(agentId: string) {
  window.dispatchEvent(new CustomEvent(AGENT_DRAWER_EVENT, { detail: { agentId } }));
}
