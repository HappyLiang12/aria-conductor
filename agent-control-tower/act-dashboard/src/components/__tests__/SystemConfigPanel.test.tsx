import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SystemConfigPanel } from '../SystemConfigPanel';

vi.mock('../../api/systemConfig', () => ({
  listConfig: vi.fn(),
  updateConfig: vi.fn(),
  resetConfig: vi.fn(),
}));

import { listConfig, updateConfig } from '../../api/systemConfig';

const mockList = listConfig as Mock;
const mockUpdate = updateConfig as Mock;

function renderPanel() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <SystemConfigPanel />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mockList.mockResolvedValue([
    {
      configKey: 'aria.tasks.deadline-minutes',
      configValue: '45',
      description: 'Hard run deadline in minutes (range: 1-1440)',
    },
  ]);
  mockUpdate.mockResolvedValue({
    configKey: 'aria.tasks.deadline-minutes',
    configValue: '60',
    description: 'Hard run deadline in minutes (range: 1-1440)',
  });
});

describe('SystemConfigPanel run deadline validation', () => {
  it('blocks saving a value outside the documented range', async () => {
    renderPanel();
    const input = await screen.findByLabelText('Run Deadline');

    fireEvent.change(input, { target: { value: '0' } });

    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByTitle('Save Run Deadline')).toBeDisabled();
  });

  it('saves a value inside the range', async () => {
    renderPanel();
    const input = await screen.findByLabelText('Run Deadline');

    fireEvent.change(input, { target: { value: '60' } });
    const save = screen.getByTitle('Save Run Deadline');
    expect(save).toBeEnabled();
    fireEvent.click(save);

    await waitFor(() =>
      expect(mockUpdate).toHaveBeenCalledWith('aria.tasks.deadline-minutes', '60'),
    );
  });
});
