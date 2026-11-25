import { Outlet } from 'react-router-dom';
import { createContext, useContext, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { SidebarInset, SidebarProvider } from './ui/sidebar';
import { AppSidebar } from './layout/AppSidebar';
import { ShellTopBar } from './layout/ShellTopBar';
import { MobileNav } from './layout/MobileNav';
import { AmbientInvalidation } from './layout/AmbientInvalidation';
import { AriaPanel } from './AriaPanel';
import { ConfigureModal } from './ConfigureModal';
import { Toast } from './Toast';
import { DrawerProvider } from './DrawerContext';
import { TaskDrawer } from './TaskDrawer';
import { AgentDrawer } from './AgentDrawer';
import { useWebSocket, type WsSubscription } from '../hooks/useWebSocket';
import { useMediaQuery } from '../hooks/useMediaQuery';
import type { WsEvent } from '../types';

interface WebSocketContextType {
  lastMessage: WsEvent | null;
  isConnected: boolean;
  subscribe: (handler: (e: WsEvent) => void) => WsSubscription;
}

export const WebSocketContext = createContext<WebSocketContextType>({
  lastMessage: null,
  isConnected: false,
  subscribe: () => ({ unsubscribe: () => {} }),
});

export function WebSocketProvider({ children }: { children: React.ReactNode }) {
  const value = useWebSocket();

  return (
    <WebSocketContext.Provider value={value}>
      {children}
    </WebSocketContext.Provider>
  );
}

export function useWebSocketContext() {
  return useContext(WebSocketContext);
}

/**
 * A dropped live feed is otherwise invisible: timers keep polling but nothing
 * streams. Show it after a short grace period so a connect blip never flashes.
 */
export function ConnectionBanner() {
  const { isConnected } = useWebSocketContext();
  const [paused, setPaused] = useState(false);

  useEffect(() => {
    if (isConnected) {
      setPaused(false);
      return;
    }
    const id = setTimeout(() => setPaused(true), 3000);
    return () => clearTimeout(id);
  }, [isConnected]);

  if (!paused) return null;
  return (
    <div
      role="status"
      style={{
        background: 'rgba(245,158,11,.12)',
        borderBottom: '1px solid rgba(245,158,11,.4)',
        color: '#ffd884',
        padding: '6px 14px',
        fontSize: 12,
      }}
    >
      Live updates paused — reconnecting…
    </div>
  );
}

/**
 * Publish the header stack height. The shadcn sidebar is fixed to the viewport
 * and no longer needs it, but the main-chat page sizes its surface with
 * `calc(100vh - var(--top-stack-h, 67px))`, so the value must stay accurate
 * while the connection banner can appear and disappear.
 */
function useTopStackHeight(ref: React.RefObject<HTMLDivElement | null>) {
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const root = document.documentElement;
    const apply = () => root.style.setProperty('--top-stack-h', `${el.offsetHeight}px`);
    apply();
    if (typeof ResizeObserver === 'undefined') {
      return () => root.style.removeProperty('--top-stack-h');
    }
    const observer = new ResizeObserver(apply);
    observer.observe(el);
    return () => {
      observer.disconnect();
      root.style.removeProperty('--top-stack-h');
    };
  }, [ref]);
}

/**
 * The grouped nav costs 16rem of the main lane. Below this width the sidebar
 * starts on its icon rail so the decision-first template keeps its own column;
 * at 1100px and wider the full grouped nav starts open.
 */
const WIDE_DESKTOP_QUERY = '(min-width: 1100px)';

/**
 * The shadcn sidebar's default follows the viewport, not the persisted cookie:
 * collapsed below `WIDE_DESKTOP_QUERY`, open at or above it. `useMediaQuery`
 * reads `matchMedia` during the first render, so the initial paint is already
 * the right state (no expanded flash at 1024). An operator's explicit toggle
 * survives until the viewport crosses the breakpoint again.
 */
function useSidebarOpen() {
  const isWide = useMediaQuery(WIDE_DESKTOP_QUERY);
  const [open, setOpen] = useState(isWide);
  const wasWide = useRef(isWide);

  useEffect(() => {
    if (wasWide.current === isWide) return;
    wasWide.current = isWide;
    setOpen(isWide);
  }, [isWide]);

  return [open, setOpen] as const;
}

export function Layout() {
  const stackRef = useRef<HTMLDivElement>(null);
  useTopStackHeight(stackRef);
  const [sidebarOpen, setSidebarOpen] = useSidebarOpen();

  return (
    <DrawerProvider>
      <SidebarProvider open={sidebarOpen} onOpenChange={setSidebarOpen}>
        <AppSidebar />
        <SidebarInset>
          <div className="top-stack" ref={stackRef}>
            <ShellTopBar />
            <MobileNav />
            <ConnectionBanner />
          </div>
          <div className="content">
            <Outlet />
          </div>
        </SidebarInset>
      </SidebarProvider>
      <AriaPanel />
      <ConfigureModal />
      <TaskDrawer />
      <AgentDrawer />
      <Toast />
      <AmbientInvalidation />
    </DrawerProvider>
  );
}

export default Layout;
