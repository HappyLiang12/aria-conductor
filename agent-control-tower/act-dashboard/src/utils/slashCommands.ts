/**
 * Pure-function utilities for the `/` slash-command autocomplete menu.
 * No React, no IO — easily unit-testable.
 */

export interface SlashCommandItem {
  id: string;
  command: string; // e.g. "/workflow"
  name: string;
  description: string;
}

/**
 * Convert a skill name to a slash-command slug.
 * Lowercase, non-alphanumeric collapsed to `-`, trimmed.
 */
export function skillToCommand(name: string): string {
  if (!name) return '';
  return name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

/**
 * Detect whether the current input should trigger the slash menu.
 * Returns the query string (text after `/`) or null if not triggered.
 * Only triggers when `/` is at position 0 and there's no space after it yet.
 */
export function parseSlashTrigger(input: string, caretPos: number): { query: string } | null {
  if (!input || input.length === 0) return null;
  if (caretPos < 1) return null; // caret must be past the '/'
  if (input[0] !== '/') return null;
  // Only trigger while caret is within the first "word" (no space yet)
  const textBeforeCaret = input.slice(0, caretPos);
  if (textBeforeCaret.includes(' ')) return null;
  // Query is everything after the leading `/`
  return { query: textBeforeCaret.slice(1).toLowerCase() };
}

/**
 * Filter and rank slash command items by query.
 * Prefix matches first, then substring matches on name/description.
 * Returns at most `limit` items.
 */
export function filterSkillCommands(
  items: SlashCommandItem[],
  query: string,
  limit = 8,
): SlashCommandItem[] {
  if (!query) return items.slice(0, limit);

  const prefix: SlashCommandItem[] = [];
  const substring: SlashCommandItem[] = [];

  for (const item of items) {
    const cmd = item.command.slice(1); // remove leading `/`
    if (cmd.startsWith(query)) {
      prefix.push(item);
    } else if (
      cmd.includes(query) ||
      item.name.toLowerCase().includes(query) ||
      item.description.toLowerCase().includes(query)
    ) {
      substring.push(item);
    }
  }

  return [...prefix, ...substring].slice(0, limit);
}
