import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useSlashCommands } from '../useSlashCommands';
import { listSkills, type Skill } from '../../api/skills';

vi.mock('../../api/skills', () => ({ listSkills: vi.fn() }));
const mockListSkills = listSkills as Mock;

// s2 (no template field) and s3 (explicit null) must never appear in the menu:
// the server (SkillContextProviderImpl) can't inject skills without a template.
const SKILLS = [
  { id: 's1', name: 'Dev Workflow', description: 'Dev loop', enabled: true, stage: 'SKILL', template: 'steps: []' },
  { id: 's2', name: 'No Template', description: 'authored without a template', enabled: true, stage: 'SKILL' },
  { id: 's3', name: 'Null Template', description: 'template explicitly null', enabled: true, stage: 'SKILL', template: null },
] as unknown as Skill[];

function renderSlashCommands(panelOpen = true) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return renderHook(() => useSlashCommands(panelOpen), {
    wrapper: ({ children }) => <QueryClientProvider client={qc}>{children}</QueryClientProvider>,
  });
}

describe('useSlashCommands', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockListSkills.mockResolvedValue(SKILLS);
  });

  it('filters template-less SKILL rows out of the menu (server injection gate parity)', async () => {
    const { result } = renderSlashCommands();
    await waitFor(() => expect(result.current.items).toHaveLength(1));
    expect(result.current.items[0].id).toBe('s1');
    expect(result.current.items[0].command).toBe('/dev-workflow');
  });

  it('offers only templated skills for selection as pendingSkill', async () => {
    const { result } = renderSlashCommands();
    await waitFor(() => expect(result.current.items).toHaveLength(1));
    act(() => result.current.choose(result.current.items[0]));
    expect(result.current.pendingSkill?.id).toBe('s1');
    act(() => result.current.clearSkill());
    expect(result.current.pendingSkill).toBeNull();
  });

  it('does not fetch skills until the panel is open', async () => {
    const { result } = renderSlashCommands(false);
    expect(mockListSkills).not.toHaveBeenCalled();
    expect(result.current.items).toEqual([]);
  });
});
