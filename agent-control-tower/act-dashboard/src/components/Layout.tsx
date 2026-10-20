import { Outlet } from 'react-router-dom';
import { createContext, useContext, useEffect, useState } from 'react';
import { TopBar } from './TopBar';
import { RailNav } from './RailNav';
import { AriaPanel } from './AriaPanel';
import { ConfigureModal } from './ConfigureModal';
import { Toast } from './Toast';
import { DrawerProvider } from './DrawerContext';
import { TaskDrawer } from './TaskDrawer';
import { AgentDrawer } from './AgentDrawer';
import { useWebSocket, type WsSubscription } from '../hooks/useWebSocket';
import type { WsEvent } from '../types';

interface WebSocketContextType {
  lastMessage: WsEvent | null;
  isConnected: boolean;
  subscribe: (handler: (e: WsEvent) => void) => WsSubscription;
}

const WebSocketContext = createContext<WebSocketContextType>({
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
function ConnectionBanner() {
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

export function Layout() {
  return (
    <DrawerProvider>
      <TopBar />
      <ConnectionBanner />
      <div className="app-shell">
        <RailNav />
        <main className="content">
          <Outlet />
        </main>
      </div>
      <AriaPanel />
      <ConfigureModal />
      <TaskDrawer />
      <AgentDrawer />
      <Toast />
    </DrawerProvider>
  );
}

export default Layout;
