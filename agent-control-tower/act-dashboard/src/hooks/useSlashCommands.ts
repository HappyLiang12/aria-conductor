import { useState, useMemo, useCallback, useRef } from 'react';
import { useQuery } from '@tanstack/react-query';
import { listSkills, type Skill } from '../api/skills';
import {
  skillToCommand,
  parseSlashTrigger,
  filterSkillCommands,
  type SlashCommandItem,
} from '../utils/slashCommands';

export interface SlashCommandState {
  open: boolean;
  items: SlashCommandItem[];
  activeIndex: number;
  pendingSkill: Skill | null;
  move: (delta: number) => void;
  selectCurrent: () => void;
  dismiss: () => void;
  choose: (item: SlashCommandItem) => void;
  clearSkill: () => void;
  onInputChange: (value: string, caretPos: number) => void;
}

/**
 * Hook managing the `/` slash-command autocomplete state for AriaPanel.
 * Fetches enabled SKILL-stage skills (lazy: only when panel is open).
 * Filter conditions align with SkillContextProviderImpl (enabled + stage=SKILL + template!=null).
 */
export function useSlashCommands(panelOpen: boolean): SlashCommandState {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [activeIndex, setActiveIndex] = useState(0);
  const [pendingSkill, setPendingSkill] = useState<Skill | null>(null);
  const skillsRef = useRef<Skill[]>([]);

  const { data: skills } = useQuery({
    queryKey: ['skills', 'slash'],
    queryFn: () => listSkills({ enabled: true, stage: 'SKILL' }),
    enabled: panelOpen,
    staleTime: 300_000, // 5 min — skills change rarely
  });

  // Keep a ref for synchronous access in callbacks
  skillsRef.current = skills ?? [];

  // Map skills to slash command items
  const allItems: SlashCommandItem[] = useMemo(() => {
    if (!skills) return [];
    return skills.map((s) => ({
      id: s.id,
      command: '/' + skillToCommand(s.name),
      name: s.name,
      description: s.description ?? '',
    }));
  }, [skills]);

  // Filtered items based on current query
  const items = useMemo(() => filterSkillCommands(allItems, query), [allItems, query]);

  const move = useCallback((delta: number) => {
    setActiveIndex((prev) => {
      const len = items.length;
      if (len === 0) return 0;
      return (prev + delta + len) % len;
    });
  }, [items.length]);

  const choose = useCallback((item: SlashCommandItem) => {
    const skill = skillsRef.current.find((s) => s.id === item.id) ?? null;
    setPendingSkill(skill);
    setOpen(false);
    setQuery('');
    setActiveIndex(0);
  }, []);

  const selectCurrent = useCallback(() => {
    if (items.length > 0 && activeIndex < items.length) {
      choose(items[activeIndex]);
    }
  }, [items, activeIndex, choose]);

  const dismiss = useCallback(() => {
    setOpen(false);
    setQuery('');
    setActiveIndex(0);
  }, []);

  const clearSkill = useCallback(() => {
    setPendingSkill(null);
  }, []);

  const onInputChange = useCallback((value: string, caretPos: number) => {
    // If a skill is already selected and user clears input, deselect
    if (!value) {
      setPendingSkill(null);
      setOpen(false);
      return;
    }

    const trigger = parseSlashTrigger(value, caretPos);
    if (trigger) {
      setOpen(true);
      setQuery(trigger.query);
      setActiveIndex(0);
    } else {
      setOpen(false);
      setQuery('');
    }
  }, []);

  return {
    open,
    items,
    activeIndex,
    pendingSkill,
    move,
    selectCurrent,
    dismiss,
    choose,
    clearSkill,
    onInputChange,
  };
}
