import { Badge } from './ui/badge';

/**
 * The frozen runtime a run (or an agent's next run) is admitted against, as a
 * compact `core - mode` badge (fleet runtime governance): the operator reads
 * mixed-usage straight off the crew and the runs list. Null-safe for records
 * predating the fields.
 *
 * Wave 2 F3: the shadcn `Badge` token surface. The text, the placeholders and
 * the `type-badge` hook are unchanged.
 */
export function RuntimeBadge({ core, mode }: { core?: string | null; mode?: string | null }) {
  if (!core && !mode) {
    return <Badge variant="outline" className="type-badge">-</Badge>;
  }
  return (
    <Badge variant="outline" className="type-badge">
      {core ?? '?'} &middot; {mode ?? '?'}
    </Badge>
  );
}
