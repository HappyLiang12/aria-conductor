import type { ReactNode } from 'react';

/**
 * The one page heading treatment: title, optional description and the page's
 * primary actions on the right. Pages compose it instead of repeating a
 * `.page-header` block.
 *
 * Wave 2 F3: token surface for the 2b/2c page workstreams. The title is the
 * route's level-one heading (the Overview page already leads with one).
 */
export function PageHeader({ title, description, actions }: {
  title: string;
  description?: string;
  actions?: ReactNode;
}) {
  return (
    <header
      data-slot="page-header"
      className="mb-5 flex flex-wrap items-start justify-between gap-3"
    >
      <div className="min-w-0 space-y-1">
        <h1 className="text-2xl leading-8 font-semibold tracking-tight text-foreground">
          {title}
        </h1>
        {description && (
          <p className="max-w-2xl text-sm text-muted-foreground">{description}</p>
        )}
      </div>
      {actions && (
        <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>
      )}
    </header>
  );
}

export default PageHeader;
