/**
 * Keep visible rows where the operator last saw them while a surface is
 * locked (drawer/review open, row focused or hovered). New rows append; a
 * vanished row disappears. When unlocked, the server order wins.
 */
export function mergeStableOrder<T extends { id: string }>(
  previous: T[] | null,
  next: T[],
  locked: boolean,
): T[] {
  if (!locked || !previous) return next;
  const rank = new Map(previous.map((item, index) => [item.id, index]));
  const known = next
    .filter((item) => rank.has(item.id))
    .sort((a, b) => (rank.get(a.id) ?? 0) - (rank.get(b.id) ?? 0));
  const added = next.filter((item) => !rank.has(item.id));
  return [...known, ...added];
}
