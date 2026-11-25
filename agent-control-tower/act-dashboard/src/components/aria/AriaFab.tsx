/**
 * The floating launcher for the Aria panel. Presentational only: it reports the
 * toggle to the panel, which owns the open state (and its localStorage toggle).
 *
 * Wave 2 D1: split out of `AriaPanel` so the panel file keeps the conversation
 * wiring. The `.ai-fab*` class hooks and the accessible names stay — the aria
 * E2E specs select `.ai-fab` and drive the button by name.
 */
export function AriaFab({ open, onToggle }: { open: boolean; onToggle: () => void }) {
  return (
    <button
      type="button"
      className={`ai-fab ${open ? 'ai-fab-active' : ''} fixed right-6 bottom-6 z-[1100] flex size-14 items-center justify-center rounded-full border border-border bg-card text-card-foreground shadow-xl transition-transform hover:-translate-y-0.5`}
      onClick={onToggle}
      aria-label={open ? 'Close Aria panel' : 'Open Aria panel'}
      aria-expanded={open}
    >
      <span className="ai-fab-halo pointer-events-none absolute -inset-1.5 rounded-full border border-border" aria-hidden="true" />
      <span className="ai-fab-glyph text-2xl leading-none text-primary" aria-hidden="true">✦</span>
      <span className="ai-fab-dot absolute top-1.5 right-1.5 size-2.5 rounded-full bg-[var(--success)] ring-2 ring-card" aria-hidden="true" />
    </button>
  );
}

export default AriaFab;
