import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { WsEvent } from '../../types';
import HousekeepingPanel from '../HousekeepingPanel';

vi.mock('../../api/housekeeping', () => ({
  scanHousekeeping: vi.fn(),
  executeHousekeeping: vi.fn(),
}));

let mockCtx: { lastMessage: WsEvent | null; isConnected: boolean } = {
  lastMessage: null,
  isConnected: false,
};
vi.mock('../Layout', () => ({
  useWebSocketContext: () => mockCtx,
}));

import { scanHousekeeping, executeHousekeeping } from '../../api/housekeeping';

const scan = scanHousekeeping as Mock;
const exec = executeHousekeeping as Mock;

const SCAN_RESULT = {
  categories: [
    { key: 'runs', count: 2, preview: [{ id: 'r-1', title: 'old run', status: 'FAILED', age: '30h' }] },
    { key: 'stuck', count: 1, preview: [] },
    { key: 'kanban', count: 3, preview: [] },
    { key: 'agents', count: 1, preview: [] },
    { key: 'approvals', count: 0, preview: [] },
  ],
  scannedAt: 't',
};

function ui() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <HousekeepingPanel />
    </QueryClientProvider>,
  );
}

describe('HousekeepingPanel (H1)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockCtx = { lastMessage: null, isConnected: false };
    scan.mockResolvedValue(SCAN_RESULT);
    exec.mockResolvedValue({ categories: [], executedAt: 't' });
  });

  it('scans on demand and defaults stuck/approvals to unchecked', async () => {
    ui();
    fireEvent.click(screen.getByRole('button', { name: /scan leftovers/i }));

    await waitFor(() => expect(screen.getByText('2')).toBeInTheDocument());
    // default checkbox states: runs/kanban/agents checked, stuck/approvals not
    const stuck = screen.getByLabelText(/stuck/i) as HTMLInputElement;
    const approvals = screen.getByLabelText(/approvals/i) as HTMLInputElement;
    const runs = screen.getByLabelText(/terminal runs/i) as HTMLInputElement;
    expect(stuck.checked).toBe(false);
    expect(approvals.checked).toBe(false);
    expect(runs.checked).toBe(true);
  });

  it('dry-run shows the plan without executing', async () => {
    ui();
    fireEvent.click(screen.getByRole('button', { name: /scan leftovers/i }));
    await waitFor(() => expect(screen.getByText('2')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /dry-run preview/i }));
    expect(await screen.findByRole('heading', { name: /dry-run preview/i })).toBeInTheDocument();
    expect(exec).not.toHaveBeenCalled();
  });

  it('execute requires the confirm modal', async () => {
    ui();
    fireEvent.click(screen.getByRole('button', { name: /scan leftovers/i }));
    await waitFor(() => expect(screen.getByText('2')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /execute cleanup/i }));
    expect(exec).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: /approve|confirm/i }));
    await waitFor(() =>
      expect(exec).toHaveBeenCalledWith(
        expect.objectContaining({ confirm: true, categories: expect.arrayContaining(['runs']) }),
      ),
    );
  });

  it('invalidates lists on housekeeping completion event', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const spy = vi.spyOn(qc, 'invalidateQueries');
    render(
      <QueryClientProvider client={qc}>
        <HousekeepingPanel />
      </QueryClientProvider>,
    );

    act(() => {
      mockCtx = {
        lastMessage: { type: 'audit.HOUSEKEEPING_EXECUTED', payload: {}, timestamp: 't2' },
        isConnected: true,
      };
    });
    // rerender to deliver the WS message
    render(
      <QueryClientProvider client={qc}>
        <HousekeepingPanel />
      </QueryClientProvider>,
    );

    await waitFor(() => {
      const keys = spy.mock.calls.map((c) => JSON.stringify(c[0]?.queryKey));
      expect(keys).toEqual(expect.arrayContaining([JSON.stringify(['runs'])]));
    });
  });
});
