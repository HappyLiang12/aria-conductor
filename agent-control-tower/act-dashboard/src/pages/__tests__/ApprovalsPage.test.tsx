import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import ApprovalsPage from '../ApprovalsPage';

// The approvals page was retired by the kanban HITL redesign (spec D4): the
// kanban Review column is the single HITL surface. The route now redirects to
// the overview so stale deep links and bookmarks still land somewhere useful.
describe('ApprovalsPage (retired)', () => {
  it('redirects to the overview instead of rendering an approvals UI', () => {
    render(
      <MemoryRouter initialEntries={['/approvals']}>
        <Routes>
          <Route path="/approvals" element={<ApprovalsPage />} />
          <Route path="/" element={<div>overview-landing</div>} />
        </Routes>
      </MemoryRouter>,
    );
    // <Navigate replace /> swapped the route to "/" and mounted the overview.
    expect(screen.getByText('overview-landing')).toBeInTheDocument();
    expect(screen.queryByText('Approvals')).not.toBeInTheDocument();
    expect(screen.queryByText(/pending/i)).not.toBeInTheDocument();
  });
});
