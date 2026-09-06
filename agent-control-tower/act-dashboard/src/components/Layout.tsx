import { Outlet } from 'react-router-dom';
import { createContext, useContext } from 'react';
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

export function Layout() {
  return (
    <DrawerProvider>
      <TopBar />
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
