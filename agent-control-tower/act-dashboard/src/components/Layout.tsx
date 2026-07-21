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
import { useWebSocket } from '../hooks/useWebSocket';

interface WebSocketContextType {
  lastMessage: { type: string; payload: Record<string, unknown>; timestamp: string } | null;
  isConnected: boolean;
}

const WebSocketContext = createContext<WebSocketContextType>({
  lastMessage: null,
  isConnected: false,
});

export function WebSocketProvider({ children }: { children: React.ReactNode }) {
  const { lastMessage, isConnected } = useWebSocket();

  return (
    <WebSocketContext.Provider value={{ lastMessage, isConnected }}>
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
