import { Link, useLocation } from 'react-router-dom';
import {
  BookOpen,
  Brain,
  CalendarClock,
  ClipboardList,
  Gauge,
  Layers,
  MessageSquare,
  Plug,
  Shield,
  Sparkles,
  Users,
  Workflow,
} from 'lucide-react';
import {
  Sidebar,
  SidebarContent,
  SidebarGroup,
  SidebarGroupLabel,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarRail,
} from '@/components/ui/sidebar';

export const NAV_GROUPS = [
  {
    label: 'Operate',
    items: [
      { path: '/', view: 'overview', label: 'Overview', icon: Gauge },
      { path: '/runs', view: 'runs', label: 'Runs', icon: Layers },
      { path: '/ops', view: 'ops', label: 'Ops', icon: Shield },
      { path: '/crew', view: 'crew', label: 'Crew', icon: Users },
    ],
  },
  {
    label: 'Build',
    items: [
      { path: '/workflows', view: 'workflows', label: 'Workflows', icon: Workflow },
      { path: '/knowledge', view: 'knowledge', label: 'Knowledge', icon: BookOpen },
      { path: '/memory', view: 'memory', label: 'Memory', icon: Brain },
      { path: '/reports', view: 'reports', label: 'Reports', icon: ClipboardList },
    ],
  },
  {
    label: 'Chat',
    items: [
      { path: '/aria', view: 'aria', label: 'Aria', icon: Sparkles },
      { path: '/chat', view: 'chat', label: 'Chat', icon: MessageSquare },
    ],
  },
  {
    label: 'System',
    items: [
      { path: '/providers', view: 'providers', label: 'Providers', icon: Plug },
      { path: '/scheduled-jobs', view: 'jobs', label: 'Jobs', icon: CalendarClock },
    ],
  },
];

function openConfigure() {
  window.dispatchEvent(new CustomEvent('act:open-configure'));
  document.body.classList.add('configure-open');
}

/**
 * `.rail` / `.rail-btn` are kept as E2E hooks, but their legacy rules live
 * unlayered in `styles/index.css`, so they outrank every Tailwind utility:
 * `.rail` pinned the container sticky-below-the-header and `.rail-btn` renders a
 * centred icon-over-label column at 10.5px, which clips the label inside the
 * shadcn row (`h-8` + `overflow-hidden`). The `!` utilities below win the
 * cascade back so the new shell keeps the shadcn geometry while the selectors
 * the specs click on stay in the DOM.
 */
const RAIL_CONTAINER = 'rail fixed! top-0! h-svh! z-10! gap-0! p-0! bg-sidebar!';
/**
 * The link's colours are token `!` utilities too: `.rail-btn:hover` and
 * `.rail-btn.active` paint an accent gradient, glow and hard-coded rgba text
 * unlayered, so without `!` the active destination keeps the legacy chrome
 * regardless of the theme tokens.
 */
const RAIL_LINK = 'rail-btn flex-row! items-center! justify-start! gap-2! p-2! text-sm! '
  + 'border-transparent! shadow-none! hover:bg-accent! hover:text-accent-foreground!';
const RAIL_ACTIVE = 'active bg-primary/20! text-foreground! hover:bg-primary/25! hover:text-foreground!';

export function AppSidebar() {
  const { pathname } = useLocation();
  const active = (path: string) => (path === '/' ? pathname === '/' : pathname.startsWith(path));

  return (
    // `rail` keeps the legacy E2E container hook (specs wait on `.rail` being
    // visible before driving the SPA). The link-level `rail-btn` hook carries
    // the same contract for destinations.
    <Sidebar collapsible="icon" className={RAIL_CONTAINER}>
      <SidebarContent>
        {NAV_GROUPS.map((group) => (
          <SidebarGroup key={group.label}>
            <SidebarGroupLabel>{group.label}</SidebarGroupLabel>
            <SidebarMenu>
              {group.items.map(({ path, view, label, icon: Icon }) => (
                <SidebarMenuItem key={path}>
                  <SidebarMenuButton
                    isActive={active(path)}
                    render={
                      <Link
                        to={path}
                        data-view={view}
                        aria-current={active(path) ? 'page' : undefined}
                        className={`${RAIL_LINK}${active(path) ? ` ${RAIL_ACTIVE}` : ''}`}
                      />
                    }
                  >
                    <Icon aria-hidden="true" />
                    <span>{label}</span>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              ))}
            </SidebarMenu>
          </SidebarGroup>
        ))}
        <SidebarGroup>
          <SidebarMenu>
            <SidebarMenuItem>
              <SidebarMenuButton className={RAIL_LINK} onClick={openConfigure} aria-label="Open configuration">
                <Plug aria-hidden="true" />
                <span>Configure</span>
              </SidebarMenuButton>
            </SidebarMenuItem>
          </SidebarMenu>
        </SidebarGroup>
      </SidebarContent>
      <SidebarRail />
    </Sidebar>
  );
}

export default AppSidebar;
