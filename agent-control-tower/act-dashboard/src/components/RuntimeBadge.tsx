/**
 * The frozen runtime a run (or an agent's next run) is admitted against, as a
 * compact `core - mode` badge (fleet runtime governance): the operator reads
 * mixed-usage straight off the crew and the runs list. Null-safe for records
 * predating the fields.
 */
export function RuntimeBadge({ core, mode }: { core?: string | null; mode?: string | null }) {
  if (!core && !mode) {
    return <span className="type-badge">-</span>;
  }
  return (
    <span className="type-badge">
      {core ?? '?'} &middot; {mode ?? '?'}
    </span>
  );
}
