import type { ReactNode } from 'react';
import { Badge } from '../ui/badge';
import { Card, CardAction, CardContent, CardHeader } from '../ui/card';

/**
 * A titled section on the shadcn `Card` token surface: an accessible heading,
 * an optional count badge and an actions slot above its content.
 *
 * Wave 2 F3: the 2b/2c page workstreams compose this instead of repeating
 * `.panel`/section chrome. The heading level is `h2` so it nests under the
 * page's `PageHeader` `h1`.
 */
export function SectionCard({ title, count, actions, children }: {
  title: string;
  count?: number | string;
  actions?: ReactNode;
  children: ReactNode;
}) {
  return (
    <Card className="gap-3">
      <CardHeader className="flex-row items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-2">
          <h2 className="truncate text-sm font-semibold text-card-foreground">{title}</h2>
          {count !== undefined && count !== null && (
            <Badge variant="secondary" className="tabular-nums">{count}</Badge>
          )}
        </div>
        {actions && <CardAction>{actions}</CardAction>}
      </CardHeader>
      <CardContent className="text-sm">{children}</CardContent>
    </Card>
  );
}

export default SectionCard;
