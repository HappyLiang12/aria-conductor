import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { WebSocketProvider, Layout } from './components/Layout';
import OverviewPage from './pages/OverviewPage';
import CrewPage from './pages/CrewPage';
import ChatPage from './pages/ChatPage';
import OpsPage from './pages/OpsPage';
import { KnowledgePage } from './pages/KnowledgePage';
import { ReportsPage } from './pages/ReportsPage';
import { WorkflowsPage } from './pages/WorkflowsPage';
import { ScheduledJobsPage } from './pages/ScheduledJobsPage';
import { RunsPage } from './pages/RunsPage';
import { ApprovalsPage } from './pages/ApprovalsPage';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
      staleTime: 5000,
    },
  },
});

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <WebSocketProvider>
        <BrowserRouter>
          <Routes>
            <Route element={<Layout />}>
              <Route path="/" element={<OverviewPage />} />
              <Route path="/crew" element={<CrewPage />} />
              <Route path="/knowledge" element={<KnowledgePage />} />
              <Route path="/reports" element={<ReportsPage />} />
              <Route path="/chat" element={<ChatPage />} />
              <Route path="/workflows" element={<WorkflowsPage />} />
              <Route path="/ops" element={<OpsPage />} />
              <Route path="/scheduled-jobs" element={<ScheduledJobsPage />} />
              <Route path="/runs" element={<RunsPage />} />
              <Route path="/approvals" element={<ApprovalsPage />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </WebSocketProvider>
    </QueryClientProvider>
  );
}
