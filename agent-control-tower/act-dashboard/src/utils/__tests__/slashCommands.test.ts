import { describe, it, expect } from 'vitest';
import { skillToCommand, parseSlashTrigger, filterSkillCommands, type SlashCommandItem } from '../slashCommands';

describe('skillToCommand', () => {
  it('lowercases and slugifies a simple name', () => {
    expect(skillToCommand('workflow')).toBe('workflow');
  });

  it('replaces spaces and special chars with hyphens', () => {
    expect(skillToCommand('Code Review')).toBe('code-review');
    expect(skillToCommand('Release Checklist-skill')).toBe('release-checklist-skill');
  });

  it('collapses multiple non-alphanumeric chars', () => {
    expect(skillToCommand('my__cool  skill!!')).toBe('my-cool-skill');
  });

  it('trims leading/trailing hyphens', () => {
    expect(skillToCommand('--hello--')).toBe('hello');
  });

  it('returns empty string for empty/null input', () => {
    expect(skillToCommand('')).toBe('');
  });
});

describe('parseSlashTrigger', () => {
  it('triggers when input starts with /', () => {
    expect(parseSlashTrigger('/work', 5)).toEqual({ query: 'work' });
  });

  it('triggers with empty query for bare /', () => {
    expect(parseSlashTrigger('/', 1)).toEqual({ query: '' });
  });

  it('does not trigger when / is not at position 0', () => {
    expect(parseSlashTrigger('hello /world', 12)).toBeNull();
  });

  it('does not trigger when there is a space after /command', () => {
    expect(parseSlashTrigger('/workflow start', 15)).toBeNull();
  });

  it('does not trigger for empty input', () => {
    expect(parseSlashTrigger('', 0)).toBeNull();
  });

  it('does not trigger when caret is before the slash', () => {
    expect(parseSlashTrigger('/hello', 0)).toBeNull();
  });

  it('uses text before caret as query', () => {
    expect(parseSlashTrigger('/workflow extra', 5)).toEqual({ query: 'work' });
  });
});

describe('filterSkillCommands', () => {
  const items: SlashCommandItem[] = [
    { id: '1', command: '/workflow', name: 'workflow', description: 'Run SDD loop' },
    { id: '2', command: '/code-review', name: 'code-review', description: 'Review code changes' },
    { id: '3', command: '/deploy', name: 'deploy', description: 'Deploy to production' },
    { id: '4', command: '/workout', name: 'workout', description: 'Fitness tracker' },
  ];

  it('returns all items (up to limit) for empty query', () => {
    expect(filterSkillCommands(items, '')).toHaveLength(4);
  });

  it('prefix matches come first', () => {
    const result = filterSkillCommands(items, 'work');
    expect(result[0].command).toBe('/workflow');
    expect(result[1].command).toBe('/workout');
  });

  it('matches on description substring', () => {
    const result = filterSkillCommands(items, 'production');
    expect(result).toHaveLength(1);
    expect(result[0].command).toBe('/deploy');
  });

  it('respects limit', () => {
    const result = filterSkillCommands(items, '', 2);
    expect(result).toHaveLength(2);
  });

  it('returns empty for no matches', () => {
    expect(filterSkillCommands(items, 'zzz')).toHaveLength(0);
  });
});
