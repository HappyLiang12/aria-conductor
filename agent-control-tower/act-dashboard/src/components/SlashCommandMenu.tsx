import type { SlashCommandItem } from '../utils/slashCommands';

interface Props {
  items: SlashCommandItem[];
  activeIndex: number;
  onHover: (index: number) => void;
  onSelect: (item: SlashCommandItem) => void;
}

/**
 * Pure presentational slash-command autocomplete popup.
 * Rendered inside `.ai-compose` (position:relative parent).
 */
export default function SlashCommandMenu({ items, activeIndex, onHover, onSelect }: Props) {
  if (items.length === 0) return null;

  return (
    <div className="ai-slash" role="listbox" id="slash-command-listbox" aria-label="Available skills">
      {items.map((item, i) => (
        <div
          key={item.id}
          className="ai-slash-item"
          role="option"
          id={`slash-opt-${item.id}`}
          aria-selected={i === activeIndex}
          onMouseEnter={() => onHover(i)}
          onMouseDown={(e) => { e.preventDefault(); onSelect(item); }}
        >
          <span className="ai-slash-cmd">{item.command}</span>
          <span className="ai-slash-desc">{item.description}</span>
        </div>
      ))}
    </div>
  );
}
