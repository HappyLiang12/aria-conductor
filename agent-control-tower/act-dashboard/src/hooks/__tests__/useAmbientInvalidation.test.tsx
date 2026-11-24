import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { useAmbientInvalidation } from '../useAmbientInvalidation';
import { WebSocketContext } from '../../components/Layout';

function wrapper(client: QueryClient, type: string) {
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <WebSocketContext.Provider
        value={{
          lastMessage: null,
          isConnected: true,
          subscribe: (handler) => {
            handler({ type, payload: { runId: 'r1' }, timestamp: '' });
            return { unsubscribe: () => {} };
          },
        }}
      >
        {children}
      </WebSocketContext.Provider>
    </QueryClientProvider>
  );
}

describe('useAmbientInvalidation', () => {
  it('invalidates the run keys for a run event', () => {
    const client = new QueryClient();
    const spy = vi.spyOn(client, 'invalidateQueries');
    renderHook(() => useAmbientInvalidation(), { wrapper: wrapper(client, 'run.completed') });
    expect(spy).toHaveBeenCalledWith({ queryKey: ['runs'] });
    expect(spy).toHaveBeenCalledWith({ queryKey: ['dashboard-summary'] });
  });

  it('invalidates approvals for an approval event', () => {
    const client = new QueryClient();
    const spy = vi.spyOn(client, 'invalidateQueries');
    renderHook(() => useAmbientInvalidation(), { wrapper: wrapper(client, 'approval.decided') });
    expect(spy).toHaveBeenCalledWith({ queryKey: ['approvals'] });
  });
});
