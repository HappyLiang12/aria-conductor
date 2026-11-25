import type { ReactNode } from 'react';
import { Badge } from '../ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';

/**
 * The flat token section both Overview queues share: a title, an optional
 * accent, a count badge and a collapsed one-liner. The legacy
 * `decision-queue`/`attention-queue` class hooks and the
 * `decision-queue__count`/`attention-queue__count` count hooks stay on the
 * markup because the E2E suite selects them (Wave 2a compatibility shim).
 */
export function QueueSection({
  className,
  countClassName,
  countAriaLabel,
  title,
  accent,
  collapsedLabel,
  count,
  collapsed,
  testId,
  children,
}: {
  className: string;
  countClassName: string;
  countAriaLabel?: string;
  title: string;
  accent?: string;
  collapsedLabel: string;
  count: number | null;
  collapsed: boolean;
  testId: string;
  children: ReactNode;
}) {
  if (collapsed) {
    return (
      <section
        className={className}
        data-testid={testId}
        data-collapsed="true"
        aria-label={title}
      >
        <h2 className="text-sm font-medium text-muted-foreground">{collapsedLabel}</h2>
      </section>
    );
  }
  return (
    <section
      className={className}
      data-testid={testId}
      data-collapsed="false"
      aria-label={title}
    >
      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2">
          <CardTitle>
            <h2 className="text-base font-semibold text-card-foreground">
              {title}
              {accent && <span className="font-normal text-muted-foreground"> · {accent}</span>}
            </h2>
          </CardTitle>
          <Badge
            variant="secondary"
            className={countClassName}
            aria-label={countAriaLabel}
          >
            {count ?? '—'}
          </Badge>
        </CardHeader>
        <CardContent className="space-y-3">{children}</CardContent>
      </Card>
    </section>
  );
}

export default QueueSection;
