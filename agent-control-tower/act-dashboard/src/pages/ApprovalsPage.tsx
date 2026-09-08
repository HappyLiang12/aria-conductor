import { Navigate } from 'react-router-dom';

/**
 * Retired: the kanban Review column is the single HITL surface (spec D4).
 * The /approvals route redirects to the overview so stale deep links and
 * bookmarks still land next to the Review column.
 */
export function ApprovalsPage() {
  return <Navigate to="/" replace />;
}

// Default export kept for parity with the other page modules.
export default ApprovalsPage;
