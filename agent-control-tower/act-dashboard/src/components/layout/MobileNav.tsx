import { Link, useLocation } from 'react-router-dom';
import { useIsMobile } from '@/hooks/use-mobile';
import { NAV_GROUPS } from './AppSidebar';

/**
 * Below `md` the shadcn sidebar is a closed Sheet, so its destinations are not
 * in the accessibility tree until the operator opens it. The mobile flows drive
 * routes by clicking the links directly, so the primary navigation has to be
 * visible without that extra step. This is a compact, horizontally scrollable
 * strip that keeps the same `.rail-btn` / `data-view` hooks the E2E specs and
 * the active-route contract rely on.
 *
 * It is mounted only below the `useIsMobile` breakpoint: the desktop DOM keeps
 * exactly one `.rail-btn` per destination, so selectors like
 * `.rail-btn[data-view="providers"]` stay unambiguous.
 */
const NAV_CONTAINER =
  'rail-nav flex w-full min-w-0 items-center gap-1 overflow-x-auto border-b border-border '
  + 'bg-background px-2 py-1 md:hidden';
const NAV_LINK = 'rail-btn w-auto! shrink-0 flex-row! items-center! justify-start! gap-1.5! '
  + 'whitespace-nowrap! border-transparent! px-2.5! py-1.5! text-xs! shadow-none! '
  + 'hover:bg-accent! hover:text-accent-foreground!';
const NAV_ACTIVE = 'active bg-primary/20! text-foreground! hover:bg-primary/25! hover:text-foreground!';

const ITEMS = NAV_GROUPS.flatMap((group) => group.items);

export function MobileNav() {
  const isMobile = useIsMobile();
  const { pathname } = useLocation();

  if (!isMobile) return null;

  const active = (path: string) => (path === '/' ? pathname === '/' : pathname.startsWith(path));

  return (
    <nav aria-label="Primary navigation" data-testid="mobile-nav" className={NAV_CONTAINER}>
      {ITEMS.map(({ path, view, label, icon: Icon }) => (
        <Link
          key={path}
          to={path}
          data-view={view}
          aria-current={active(path) ? 'page' : undefined}
          className={`${NAV_LINK}${active(path) ? ` ${NAV_ACTIVE}` : ''}`}
        >
          <Icon aria-hidden="true" className="size-3.5" />
          <span>{label}</span>
        </Link>
      ))}
    </nav>
  );
}

export default MobileNav;
