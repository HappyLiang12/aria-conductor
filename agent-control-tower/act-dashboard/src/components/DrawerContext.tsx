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
}

export interface DrawerContextValue {
  state: DrawerState;
  openTaskDrawer: (itemId: string) => void;
  closeTaskDrawer: () => void;
  openAgentDrawer: (agentId: string) => void;
  closeAgentDrawer: () => void;
}

const initialState: DrawerState = {
  taskDrawer: { open: false, itemId: null },
  agentDrawer: { open: false, agentId: null },
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
    }));
  }, []);

  const closeTaskDrawer = useCallback(() => {
    setState((prev) => ({
      ...prev,
      taskDrawer: { open: false, itemId: prev.taskDrawer.itemId },
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

  // Close drawers on Escape for accessibility.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
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
      openAgentDrawer,
      closeAgentDrawer,
    }),
    [state, openTaskDrawer, closeTaskDrawer, openAgentDrawer, closeAgentDrawer]
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
