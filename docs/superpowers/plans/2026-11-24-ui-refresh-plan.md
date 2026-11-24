# UI Refresh Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the dashboard shell, Overview, and Aria page on Tailwind 4 + shadcn/ui + AI Elements + assistant-ui, while the Spring backend and all existing routes keep working.

**Architecture:** A design-token layer maps the existing dark/light palette into shadcn variables; legacy flow CSS stays unlayered under Tailwind's cascade layers. Aria chat uses `LocalRuntime` with one `ChatModelAdapter` over the existing SSE turn stream; approvals, waiting-input, and memory proposals render as backend tool UIs that post to the existing decision endpoints. STOMP events only invalidate TanStack Query keys.

**Tech Stack:** React 19, Vite 6, TypeScript 5.6, TanStack Query 5, Tailwind CSS 4, shadcn/ui, AI Elements patterns, assistant-ui 0.15.x, Vitest 2, Playwright.

**Spec:** `docs/superpowers/specs/2026-11-24-ui-refresh-design.md`
**Decisions:** `docs/superpowers/specs/2026-11-24-ui-refresh-decisions.md`

## Global Constraints

- Base branch is `main` after `feat/main-chat` is merged. Do not start Tasks 3 and 7 while that branch is unmerged.
- Phase 1 is frontend-only: no Spring endpoint, DTO, migration, or permission change.
- Preserve every E2E hook that existing specs use: `.rail-btn` + `data-view` on sidebar links, `data-testid="decision-queue"`, `data-testid="attention-queue"`, `data-testid="kanban-board"`, and the `mc-*` class hooks in `e2e/aria-main-chat.spec.ts` or update that spec in the same task.
- Do not edit `src/components/AriaPanel.tsx` or `src/components/main-chat/*` except `ConversationSidebar` import sites. Those belong to `feat/main-chat`.
- Do not touch `#130` aria-glass styles, `--ai-glass-*`, or `opencode-sandbox`.
- Tailwind lives in cascade layers; legacy CSS stays unlayered until its page wave.
- Every task ends with its own commit. Reply to the user in Traditional Chinese; code and commits in English.

## File ownership

| Area | Files | Owner |
|------|-------|-------|
| Toolchain | `package.json`, `vite.config.ts`, `tsconfig.json`, `tsconfig.app.json`, `components.json`, `src/styles/tokens.css`, `src/styles/index.css` | Task 1 |
| Query layer | `src/api/queryKeys.ts`, `src/hooks/useAmbientInvalidation.ts` | Task 2 |
| Shell | `src/components/layout/*`, `src/components/Layout.tsx`, `src/components/RailNav.tsx` (deleted or re-exported), `src/components/TopBar.tsx` | Task 3 |
| Decision UI | `src/components/chat/*` | Task 4 |
| Overview | `src/pages/OverviewPage.tsx`, `src/components/overview/*` | Task 5 |
| Adapter | `src/api/aria.ts`, `src/api/__tests__/aria.test.ts`, `src/chat/*` | Task 6 |
| Aria page | `src/pages/MainChatPage.tsx`, `src/pages/__tests__/MainChatPage.test.tsx`, `src/styles/main-chat.css` | Task 7 |
| Tests | `e2e/ui-refresh.spec.ts`, `e2e/aria-main-chat.spec.ts` | Tasks 3, 7, 8 |

Shared files `src/App.tsx` and `src/styles/index.css` are edited only by Task 1 and Task 3; no other task may touch them.

---

### Task 0: Base and ownership gate

**Files:** none.

**Interfaces:**
- Consumes: `main`, `feat/main-chat`.
- Produces: a worktree based on `main` that already contains the main-chat work.

- [ ] **Step 1: Verify both prerequisite branches are merged**

Run:
```bash
git merge-base --is-ancestor feat/main-chat main && echo MAIN_CHAT_MERGED || echo MAIN_CHAT_NOT_MERGED
test -f docs/superpowers/specs/2026-11-24-ui-refresh-design.md && echo SPEC_PRESENT || echo SPEC_MISSING
```
Expected: `MAIN_CHAT_MERGED` and `SPEC_PRESENT`. If either line is negative, stop the plan, report which prerequisite is missing, and wait for its owner or the user. Do not copy the spec by hand into the implementation worktree.

- [ ] **Step 2: Recreate the implementation worktree from main**

Run:
```bash
git worktree add /Users/happy/.qoder/worktrees/app/b420ea/aria-conductor-ui -b feat/ui-refresh main
cd /Users/happy/.qoder/worktrees/app/b420ea/aria-conductor-ui
git status -sb
```
Expected: clean `## feat/ui-refresh`.

- [ ] **Step 3: Record the baseline bundle**

Run:
```bash
cd agent-control-tower/act-dashboard
pnpm install --frozen-lockfile
pnpm build
cp -R dist /tmp/aria-ui-baseline-dist
du -sh /tmp/aria-ui-baseline-dist
```
Expected: build passes; record the size and the largest chunk for the PR evidence.

---

### Task 1: Tailwind, shadcn, and tokens

**Files:**
- Modify: `agent-control-tower/act-dashboard/package.json`
- Modify: `agent-control-tower/act-dashboard/vite.config.ts`
- Modify: `agent-control-tower/act-dashboard/tsconfig.json`, `tsconfig.app.json`
- Create: `agent-control-tower/act-dashboard/components.json`
- Create: `agent-control-tower/act-dashboard/src/styles/tokens.css`
- Modify: `agent-control-tower/act-dashboard/src/styles/index.css`
- Test: `agent-control-tower/act-dashboard/src/styles/__tests__/tokens.test.ts`

**Interfaces:**
- Consumes: existing CSS variables in `src/styles/index.css`.
- Produces: shadcn variables `--background`, `--foreground`, `--card`, `--popover`, `--primary`, `--secondary`, `--muted`, `--muted-foreground`, `--accent`, `--destructive`, `--border`, `--input`, `--ring`, `--radius`, `--success`, `--warning`, and the `@/*` import alias.

- [ ] **Step 1: Write the failing token test**

Create `src/styles/__tests__/tokens.test.ts`:
```ts
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const tokens = readFileSync('src/styles/tokens.css', 'utf8');
const index = readFileSync('src/styles/index.css', 'utf8');

describe('design tokens', () => {
  it('defines the shadcn variables in both themes', () => {
    for (const name of [
      '--background', '--foreground', '--card', '--popover',
      '--primary', '--secondary', '--muted', '--muted-foreground',
      '--accent', '--destructive', '--border', '--input', '--ring', '--radius',
    ]) {
      expect(tokens).toContain(`${name}:`);
    }
    expect(tokens).toMatch(/body\.light\s*\{/);
  });

  it('puts Tailwind in layers before legacy rules', () => {
    expect(index).toMatch(/@layer theme, base, components, utilities;/);
    expect(index.indexOf('@import "tailwindcss"')).toBeGreaterThan(0);
    expect(index.indexOf('@import "./tokens.css"')).toBeGreaterThan(
      index.indexOf('@import "tailwindcss"'),
    );
  });
});
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `pnpm vitest run src/styles/__tests__/tokens.test.ts`
Expected: FAIL, `tokens.css` not found or variables missing.

- [ ] **Step 3: Install and configure**

Run:
```bash
pnpm add tailwindcss @tailwindcss/vite
pnpm add -D @types/node
pnpm dlx shadcn@latest init
```
Accept the defaults that write `components.json`, `src/lib/utils.ts`, and CSS variables. If `init` rewrites `src/styles/index.css`, restore the legacy rules from `git show HEAD:agent-control-tower/act-dashboard/src/styles/index.css`, then apply the imports from Step 4; do not lose the existing design-system rules. Then apply the Vite config:
```ts
import path from 'path';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
});
```
Add `baseUrl: "."` and `paths: { "@/*": ["./src/*"] }` to both `tsconfig.json` and `tsconfig.app.json`.

- [ ] **Step 4: Write the token file**

Create `src/styles/tokens.css`:
```css
@layer base {
  :root {
    --background: 225 55% 6%;
    --foreground: 225 100% 95%;
    --card: 222 43% 10%;
    --card-foreground: 225 100% 95%;
    --popover: 222 43% 10%;
    --popover-foreground: 225 100% 95%;
    --primary: 206 100% 35%;
    --primary-foreground: 0 0% 100%;
    --secondary: 222 36% 14%;
    --secondary-foreground: 225 60% 82%;
    --muted: 222 36% 14%;
    --muted-foreground: 224 40% 70%;
    --accent: 206 100% 20%;
    --accent-foreground: 225 100% 95%;
    --destructive: 353 100% 71%;
    --border: 225 41% 21%;
    --input: 225 41% 21%;
    --ring: 206 100% 35%;
    --radius: 8px;
    --success: 160 65% 52%;
    --warning: 43 90% 65%;
  }

  body.light {
    --background: 223 47% 97%;
    --foreground: 226 55% 13%;
    --card: 0 0% 100%;
    --card-foreground: 226 55% 13%;
    --popover: 0 0% 100%;
    --popover-foreground: 226 55% 13%;
    --primary: 226 76% 56%;
    --primary-foreground: 0 0% 100%;
    --secondary: 219 42% 94%;
    --secondary-foreground: 226 45% 25%;
    --muted: 219 42% 94%;
    --muted-foreground: 224 30% 38%;
    --accent: 219 60% 92%;
    --accent-foreground: 226 55% 13%;
    --destructive: 353 100% 71%;
    --border: 219 42% 92%;
    --input: 219 42% 92%;
    --ring: 226 76% 56%;
  }
}
```
Prepend to `src/styles/index.css`, before the existing rules:
```css
@layer theme, base, components, utilities;
@import "tailwindcss";
@import "./tokens.css";
```
Keep every existing rule unlayered below the imports.

- [ ] **Step 5: Add the components used by later tasks**

Run:
```bash
pnpm dlx shadcn@latest add button card badge separator scroll-area skeleton tooltip collapsible dropdown-menu sheet sidebar input textarea tabs
```
Expected: files under `src/components/ui/`.

- [ ] **Step 6: Run the tests and the build**

Run:
```bash
pnpm vitest run src/styles/__tests__/tokens.test.ts
pnpm test
pnpm build
```
Expected: token test PASS; existing suite PASS; build PASS.

- [ ] **Step 7: Commit**

```bash
git add agent-control-tower/act-dashboard/package.json agent-control-tower/act-dashboard/pnpm-lock.yaml \
  agent-control-tower/act-dashboard/vite.config.ts agent-control-tower/act-dashboard/tsconfig.json \
  agent-control-tower/act-dashboard/tsconfig.app.json agent-control-tower/act-dashboard/components.json \
  agent-control-tower/act-dashboard/src/styles agent-control-tower/act-dashboard/src/components/ui \
  agent-control-tower/act-dashboard/src/lib agent-control-tower/act-dashboard/src/styles/__tests__
git commit -m "feat(dashboard): add Tailwind 4 and shadcn token foundation"
```

---

### Task 2: Query keys and ambient invalidation

**Files:**
- Create: `agent-control-tower/act-dashboard/src/api/queryKeys.ts`
- Create: `agent-control-tower/act-dashboard/src/hooks/useAmbientInvalidation.ts`
- Create: `agent-control-tower/act-dashboard/src/hooks/__tests__/useAmbientInvalidation.test.tsx`

**Interfaces:**
- Consumes: `useWebSocketContext()` from `src/components/Layout.tsx`; `QueryClient`.
- Produces: `queryKeys` constants and `invalidateForAmbientEvent(client, type)`.

- [ ] **Step 1: Write the failing invalidation test**

Create `src/hooks/__tests__/useAmbientInvalidation.test.tsx`:
```tsx
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { useAmbientInvalidation } from '../useAmbientInvalidation';
import { WebSocketContext } from '../../components/Layout';

function wrapper(client: QueryClient, type: string) {
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <WebSocketContext.Provider
        value={{
          lastMessage: null,
          isConnected: true,
          subscribe: (handler) => {
            handler({ type, payload: { runId: 'r1' }, timestamp: '' });
            return { unsubscribe: () => {} };
          },
        }}
      >
        {children}
      </WebSocketContext.Provider>
    </QueryClientProvider>
  );
}

describe('useAmbientInvalidation', () => {
  it('invalidates the run keys for a run event', () => {
    const client = new QueryClient();
    const spy = vi.spyOn(client, 'invalidateQueries');
    renderHook(() => useAmbientInvalidation(), { wrapper: wrapper(client, 'run.completed') });
    expect(spy).toHaveBeenCalledWith({ queryKey: ['runs'] });
    expect(spy).toHaveBeenCalledWith({ queryKey: ['dashboard-summary'] });
  });

  it('invalidates approvals for an approval event', () => {
    const client = new QueryClient();
    const spy = vi.spyOn(client, 'invalidateQueries');
    renderHook(() => useAmbientInvalidation(), { wrapper: wrapper(client, 'approval.decided') });
    expect(spy).toHaveBeenCalledWith({ queryKey: ['approvals'] });
  });
});
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `pnpm vitest run src/hooks/__tests__/useAmbientInvalidation.test.tsx`
Expected: FAIL, module not found.

- [ ] **Step 3: Implement**

Create `src/api/queryKeys.ts`:
```ts
export const queryKeys = {
  summary: ['dashboard-summary'] as const,
  providers: ['adk-providers'] as const,
  providerHealth: (id: string) => ['adk-provider-health', id] as const,
  approvals: ['approvals'] as const,
  kanban: ['kanban-items'] as const,
  runs: ['runs'] as const,
  run: (id: string) => ['run', id] as const,
  agents: ['agents'] as const,
  knowledge: ['knowledge'] as const,
  reports: ['reports'] as const,
  workflows: ['workflows'] as const,
  notifications: ['notifications'] as const,
};
```
Create `src/hooks/useAmbientInvalidation.ts`:
```ts
import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useWebSocketContext } from '../components/Layout';
import { queryKeys } from '../api/queryKeys';

const RUN_EVENTS = ['run.started', 'run.completed', 'run.waiting_input', 'run.progress', 'run.iteration'];
const APPROVAL_EVENTS = ['approval.requested', 'approval.decided', 'approval.expired'];

export function keysForAmbientEvent(type: string): readonly unknown[][] {
  if (RUN_EVENTS.includes(type)) return [queryKeys.runs, queryKeys.summary];
  if (APPROVAL_EVENTS.includes(type)) return [queryKeys.approvals, queryKeys.summary];
  if (type.startsWith('kanban.')) return [queryKeys.kanban, queryKeys.summary];
  if (type.startsWith('knowledge.')) return [queryKeys.knowledge];
  if (type.startsWith('report.')) return [queryKeys.reports];
  if (type === 'workflow.advanced') return [queryKeys.workflows];
  if (type === 'agent.created') return [queryKeys.agents];
  return [];
}

export function useAmbientInvalidation(): void {
  const client = useQueryClient();
  const { subscribe } = useWebSocketContext();
  useEffect(() => subscribe((event) => {
    for (const key of keysForAmbientEvent(event.type)) {
      void client.invalidateQueries({ queryKey: key });
    }
  }).unsubscribe, [client, subscribe]);
}
```
Use the same `subscribe` instance returned by `useWebSocketContext`; do not create a second socket.

- [ ] **Step 4: Run the tests**

Run: `pnpm vitest run src/hooks/__tests__/useAmbientInvalidation.test.tsx`
Expected: PASS for both event families.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard/src/api/queryKeys.ts agent-control-tower/act-dashboard/src/hooks/useAmbientInvalidation.ts agent-control-tower/act-dashboard/src/hooks/__tests__/useAmbientInvalidation.test.tsx
git commit -m "feat(dashboard): invalidate query keys from ambient events"
```

---

### Task 3: New shell

**Files:**
- Create: `agent-control-tower/act-dashboard/src/components/layout/AppSidebar.tsx`
- Create: `agent-control-tower/act-dashboard/src/components/layout/ShellTopBar.tsx`
- Create: `agent-control-tower/act-dashboard/src/components/layout/AmbientInvalidation.tsx`
- Modify: `agent-control-tower/act-dashboard/src/components/Layout.tsx`
- Delete: `agent-control-tower/act-dashboard/src/components/RailNav.tsx`, `src/components/TopBar.tsx`, and `src/components/__tests__/RailNav.test.tsx` once the new shell ships.
- Modify: `agent-control-tower/act-dashboard/src/components/__tests__/Layout.test.tsx`, `src/components/__tests__/TopBar.test.tsx` to the new shell shape.
- Test: `agent-control-tower/act-dashboard/src/components/layout/__tests__/AppSidebar.test.tsx`

**Interfaces:**
- Consumes: `lucide-react` icons, shadcn `SidebarProvider`, `Sidebar`, `SidebarInset`, `SidebarTrigger`, `Button`, existing `ConnectionBanner`.
- Produces: `AppSidebar` with `.rail-btn` + `data-view` on every link; `ShellTopBar` preserving the four KPI `data-testid`s and the theme toggle.

- [ ] **Step 1: Write the failing sidebar test**

Create `src/components/layout/__tests__/AppSidebar.test.tsx`:
```tsx
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { SidebarProvider } from '@/components/ui/sidebar';
import { AppSidebar } from '../AppSidebar';

describe('AppSidebar', () => {
  it('keeps the e2e rail hooks and groups every route', () => {
    render(
      <MemoryRouter initialEntries={['/runs']}>
        <SidebarProvider>
          <AppSidebar />
        </SidebarProvider>
      </MemoryRouter>,
    );
    for (const view of ['overview', 'crew', 'providers', 'runs', 'knowledge', 'memory', 'reports', 'aria', 'chat', 'workflows', 'ops', 'jobs']) {
      expect(document.querySelector(`.rail-btn[data-view="${view}"]`)).not.toBeNull();
    }
    expect(document.querySelector('.rail-btn[data-view="runs"]')?.getAttribute('aria-current')).toBe('page');
  });
});
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `pnpm vitest run src/components/layout/__tests__/AppSidebar.test.tsx`
Expected: FAIL, module not found.

- [ ] **Step 3: Implement the sidebar**

Create `src/components/layout/AppSidebar.tsx`:
```tsx
import { Link, useLocation } from 'react-router-dom';
import { BookOpen, Brain, CalendarClock, ClipboardList, Gauge, Layers, MessageSquare, Plug, Shield, Sparkles, Users, Workflow } from 'lucide-react';
import { Sidebar, SidebarContent, SidebarGroup, SidebarGroupLabel, SidebarMenu, SidebarMenuButton, SidebarMenuItem, SidebarRail } from '@/components/ui/sidebar';

const GROUPS = [
  { label: 'Operate', items: [
    { path: '/', view: 'overview', label: 'Overview', icon: Gauge },
    { path: '/runs', view: 'runs', label: 'Runs', icon: Layers },
    { path: '/ops', view: 'ops', label: 'Ops', icon: Shield },
    { path: '/crew', view: 'crew', label: 'Crew', icon: Users },
  ]},
  { label: 'Build', items: [
    { path: '/workflows', view: 'workflows', label: 'Workflows', icon: Workflow },
    { path: '/knowledge', view: 'knowledge', label: 'Knowledge', icon: BookOpen },
    { path: '/memory', view: 'memory', label: 'Memory', icon: Brain },
    { path: '/reports', view: 'reports', label: 'Reports', icon: ClipboardList },
  ]},
  { label: 'Chat', items: [
    { path: '/aria', view: 'aria', label: 'Aria', icon: Sparkles },
    { path: '/chat', view: 'chat', label: 'Chat', icon: MessageSquare },
  ]},
  { label: 'System', items: [
    { path: '/providers', view: 'providers', label: 'Providers', icon: Plug },
    { path: '/scheduled-jobs', view: 'jobs', label: 'Jobs', icon: CalendarClock },
  ]},
];

export function AppSidebar() {
  const { pathname } = useLocation();
  const active = (path: string) => (path === '/' ? pathname === '/' : pathname.startsWith(path));
  return (
    <Sidebar collapsible="icon">
      <SidebarContent>
        {GROUPS.map((group) => (
          <SidebarGroup key={group.label}>
            <SidebarGroupLabel>{group.label}</SidebarGroupLabel>
            <SidebarMenu>
              {group.items.map(({ path, view, label, icon: Icon }) => (
                <SidebarMenuItem key={path}>
                  <SidebarMenuButton asChild isActive={active(path)}>
                    <Link
                      to={path}
                      data-view={view}
                      aria-current={active(path) ? 'page' : undefined}
                      className={`rail-btn${active(path) ? ' active' : ''}`}
                    >
                      <Icon aria-hidden="true" />
                      <span>{label}</span>
                    </Link>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              ))}
            </SidebarMenu>
          </SidebarGroup>
        ))}
        <SidebarGroup>
          <SidebarMenu>
            <SidebarMenuItem>
              <SidebarMenuButton
                className="rail-btn"
                onClick={() => {
                  window.dispatchEvent(new CustomEvent('act:open-configure'));
                  document.body.classList.add('configure-open');
                }}
                aria-label="Open configuration"
              >
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
```
Install `lucide-react` if shadcn did not already add it. Keep the `.rail-btn` class in addition to shadcn's classes so existing E2E specs keep matching.

- [ ] **Step 4: Implement the top bar and layout**

Move the existing TopBar query logic into `ShellTopBar.tsx` with the same four `data-testid`s and honesty copy; replace the clock/theme/notification markup with shadcn `Button`, `Badge`, and `DropdownMenu`. Keep the theme storage key `aria-theme` and `body.classList.toggle('light')`. Update `TopBar.test.tsx` to the new markup; the KPI test ids and the honesty assertions stay.

Create `AmbientInvalidation.tsx`:
```tsx
import { useAmbientInvalidation } from '../../hooks/useAmbientInvalidation';

export function AmbientInvalidation() {
  useAmbientInvalidation();
  return null;
}
```

`Layout.tsx` becomes:
```tsx
<DrawerProvider>
  <SidebarProvider>
    <AppSidebar />
    <SidebarInset>
      <ShellTopBar />
      <ConnectionBanner />
      <main className="content"><Outlet /></main>
    </SidebarInset>
  </SidebarProvider>
  <AriaPanel />
  <ConfigureModal />
  <TaskDrawer />
  <AgentDrawer />
  <Toast />
  <AmbientInvalidation />
</DrawerProvider>
```
where `AmbientInvalidation` is a null-rendering component calling `useAmbientInvalidation()`. Keep `useTopStackHeight` only if the sticky rail still needs it; otherwise remove it in the same commit.

- [ ] **Step 5: Run the tests and the route smoke**

Run:
```bash
pnpm vitest run src/components/layout/__tests__/AppSidebar.test.tsx
pnpm test
pnpm build
npx playwright test e2e/overview-dashboard.spec.ts e2e/configure-modal-close.spec.ts --project=chromium
```
Expected: sidebar test PASS, full unit suite PASS, build PASS, both E2E specs PASS against the local stack.

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-dashboard/src/components/layout agent-control-tower/act-dashboard/src/components/Layout.tsx agent-control-tower/act-dashboard/src/components/TopBar.tsx
git commit -m "feat(dashboard): rebuild the app shell on shadcn sidebar"
```

---

### Task 4: Decision tool UIs

**Files:**
- Create: `agent-control-tower/act-dashboard/src/components/chat/ApprovalToolUI.tsx`
- Create: `agent-control-tower/act-dashboard/src/components/chat/ClarificationToolUI.tsx`
- Create: `agent-control-tower/act-dashboard/src/components/chat/MemoryProposalToolUI.tsx`
- Create: `agent-control-tower/act-dashboard/src/components/chat/decisionToolkit.tsx`
- Test: `agent-control-tower/act-dashboard/src/components/chat/__tests__/ApprovalToolUI.test.tsx`

**Interfaces:**
- Consumes: `decideApproval`, `answerAsk`, `decideMemoryProposal`.
- Produces: `decisionToolkit` with backend entries `__approval`, `__question`, `__memory`, each with a `render`.

- [ ] **Step 1: Write the failing approval-card test**

```tsx
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ApprovalToolUI } from '../ApprovalToolUI';

vi.mock('../../../api/approvals', () => ({ decideApproval: vi.fn().mockResolvedValue({}) }));

describe('ApprovalToolUI', () => {
  const props = {
    args: { approvalId: 'a1', toolName: 'bash', riskTier: 'DESTRUCTIVE', expiresAt: new Date(Date.now() + 60_000).toISOString() },
    toolName: '__approval', toolCallId: 'approval-a1', argsText: '', status: { type: 'complete' as const },
    addResult: vi.fn(), resume: vi.fn(), respondToApproval: vi.fn(),
  } as never;

  it('posts an approval decision and shows the receipt', async () => {
    const { decideApproval } = await import('../../../api/approvals');
    render(<ApprovalToolUI {...props} />);
    await userEvent.click(screen.getByRole('button', { name: /allow/i }));
    await waitFor(() => expect(decideApproval).toHaveBeenCalledWith('a1', { approved: true }));
    expect(screen.getByText(/allowed/i)).toBeInTheDocument();
  });

  it('posts a rejection', async () => {
    const { decideApproval } = await import('../../../api/approvals');
    render(<ApprovalToolUI {...props} />);
    await userEvent.click(screen.getByRole('button', { name: /deny/i }));
    await waitFor(() => expect(decideApproval).toHaveBeenCalledWith('a1', { approved: false }));
  });
});
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `pnpm vitest run src/components/chat/__tests__/ApprovalToolUI.test.tsx`
Expected: FAIL, module not found.

- [ ] **Step 3: Implement the approval card**

Create `ApprovalToolUI.tsx` with `ToolCallMessagePartComponent<ApprovalArgs>`:
```tsx
import { useState } from 'react';
import type { ToolCallMessagePartComponent } from '@assistant-ui/react';
import { decideApproval } from '@/api/approvals';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';

export interface ApprovalArgs {
  approvalId: string;
  toolName: string;
  riskTier?: string;
  toolPolicy?: string;
  expiresAt?: string;
}

export const ApprovalToolUI: ToolCallMessagePartComponent<ApprovalArgs> = ({ args }) => {
  const [state, setState] = useState<'pending' | 'allowed' | 'denied' | 'error'>('pending');
  const [error, setError] = useState<string | null>(null);

  async function decide(approved: boolean) {
    try {
      await decideApproval(args.approvalId, { approved });
      setState(approved ? 'allowed' : 'denied');
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
      setState('error');
    }
  }

  if (state === 'allowed' || state === 'denied') {
    return <p className="text-sm text-muted-foreground">{state === 'allowed' ? 'Allowed' : 'Denied'} · {args.toolName}</p>;
  }
  return (
    <div className="my-2 rounded-lg border border-border bg-card p-4">
      <div className="flex items-center gap-2">
        <Badge variant={args.riskTier === 'DESTRUCTIVE' ? 'destructive' : 'secondary'}>{args.riskTier ?? 'REVIEW'}</Badge>
        <span className="font-medium">Approve {args.toolName}?</span>
      </div>
      {args.expiresAt && <p className="mt-1 text-xs text-muted-foreground">Expires {new Date(args.expiresAt).toLocaleTimeString()}</p>}
      {error && <p role="alert" className="mt-2 text-sm text-destructive">{error}</p>}
      <div className="mt-3 flex gap-2">
        <Button size="sm" onClick={() => void decide(true)}>Allow</Button>
        <Button size="sm" variant="outline" onClick={() => void decide(false)}>Deny</Button>
      </div>
    </div>
  );
};
```
The `args.toolName` collision with the component's `toolName` prop is intentional: the reserved tool is `__approval`; the displayed tool is the governed tool inside args.

Implement `ClarificationToolUI` (textarea + Send → `answerAsk(id, { approved: true, answer })`) and `MemoryProposalToolUI` (Approve → `decideMemoryProposal(id, 'APPROVE')`, Reject → `decideMemoryProposal(id, 'REJECT')`; `MemoryProposalDecision` is exactly `'APPROVE' | 'REJECT'`).

Create `decisionToolkit.tsx`:
```ts
import { defineToolkit } from '@assistant-ui/react';
import { ApprovalToolUI } from './ApprovalToolUI';
import { ClarificationToolUI } from './ClarificationToolUI';
import { MemoryProposalToolUI } from './MemoryProposalToolUI';

export const decisionToolkit = defineToolkit({
  __approval: { type: 'backend', display: 'standalone', render: ApprovalToolUI },
  __question: { type: 'backend', display: 'standalone', render: ClarificationToolUI },
  __memory: { type: 'backend', display: 'standalone', render: MemoryProposalToolUI },
});
```
Note: `display: 'standalone'` keeps decision cards out of the collapsed tool group, so an ask is never hidden behind a disclosure.

- [ ] **Step 4: Run the tests**

Run: `pnpm vitest run src/components/chat/__tests__`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard/src/components/chat
git commit -m "feat(dashboard): add approval and clarification tool UIs"
```

---

### Task 5: Overview rebuild

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/pages/OverviewPage.tsx`
- Create: `agent-control-tower/act-dashboard/src/components/overview/KpiRow.tsx`
- Create: `agent-control-tower/act-dashboard/src/components/overview/DecisionPanel.tsx`
- Create: `agent-control-tower/act-dashboard/src/components/overview/RunTracePanel.tsx`
- Create: `agent-control-tower/act-dashboard/src/components/overview/BoardSummary.tsx`
- Create: `agent-control-tower/act-dashboard/src/components/overview/ActivityFeed.tsx`
- Test: `agent-control-tower/act-dashboard/src/pages/__tests__/OverviewPage.test.tsx` (update in place)

**Interfaces:**
- Consumes: `getSummary`, `listRuns`, `listKanbanItems`, `getRecentActivity`, `useDecisionQueue`, `useAttentionQueue`, `useDrawerContext`.
- Produces: the same page test ids (`decision-queue`, `attention-queue`, `kanban-board`) and blocking-only decision rows.

- [ ] **Step 1: Update the page test first**

Extend `OverviewPage.test.tsx` with a case that mocks `useDecisionQueue` returning one blocking row and asserts:
```tsx
expect(screen.getByTestId('decision-queue')).toHaveTextContent('Approve bash');
expect(screen.getByRole('button', { name: /allow/i })).toBeInTheDocument();
expect(screen.queryByText(/review requested/i)).not.toBeInTheDocument();
```
Keep the existing partial/stale/all-clear assertions; they must still pass.

- [ ] **Step 2: Run it and confirm it fails**

Run: `pnpm vitest run src/pages/__tests__/OverviewPage.test.tsx`
Expected: FAIL on the new assertions.

- [ ] **Step 3: Implement the sections**

`KpiRow` renders four shadcn `Card`s from `getSummary`. Keep the exact honesty copy: a zero token total with silent runs renders `not reported`, and the card states how many runs reported no usage.

`DecisionPanel` keeps `data-testid="decision-queue"`, uses `useDecisionQueue({ locked })`, renders blocking rows only, and moves `approveApproval`/`rejectApproval`/`answerAsk` into the same mutation logic as today. Mount `ApprovalToolUI`-style visual language (Card + Badge + Allow/Deny) but drive it from `DecisionRow`, not from assistant-ui parts.

`RunTracePanel` reads `listRuns()` and renders the five newest runs as steps with tool counts; link each row to `/runs`.

`BoardSummary` reads `listKanbanItems()` and renders counts per lane in shadcn Cards with `data-testid="kanban-board"` on the container. No drag behaviour.

`ActivityFeed` reads `getRecentActivity()` and renders a shadcn `Table` of the latest events.

`OverviewPage` composes them in this order: KPI row, DecisionPanel, RunTracePanel, BoardSummary, ActivityFeed. Keep the review workspace, attention queue, and drawer wiring that existing tests assert; delete only the old section components that no longer have a caller.

- [ ] **Step 4: Run the tests and E2E**

Run:
```bash
pnpm vitest run src/pages/__tests__/OverviewPage.test.tsx
pnpm test
npx playwright test e2e/overview-dashboard.spec.ts --project=chromium
```
Expected: all PASS; `decision-queue` and `attention-queue` hooks still resolve.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-dashboard/src/pages/OverviewPage.tsx agent-control-tower/act-dashboard/src/components/overview agent-control-tower/act-dashboard/src/pages/__tests__/OverviewPage.test.tsx
git commit -m "feat(dashboard): rebuild Overview on shadcn cards"
```

---

### Task 6: SSE events and the assistant-ui adapter

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/api/aria.ts`
- Modify: `agent-control-tower/act-dashboard/src/api/__tests__/aria.test.ts`
- Create: `agent-control-tower/act-dashboard/src/chat/ariaAdapter.ts`
- Create: `agent-control-tower/act-dashboard/src/chat/__tests__/ariaAdapter.test.ts`

**Interfaces:**
- Consumes: `streamMessage` from `src/api/aria.ts`.
- Produces: `createAriaAdapter({ conversationId, getHistory, stream? }): ChatModelAdapter`; `streamMessage` callbacks gain an optional `toolCallId` (fourth argument on `onToolCall`, fifth on `onToolResult`).

- [ ] **Step 1: Write the failing parser test**

Add to `src/api/__tests__/aria.test.ts`:
```ts
function sseResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
      controller.close();
    },
  });
  return new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

it('passes the toolCallId through to callers', async () => {
  const onToolCall = vi.fn();
  const onToolResult = vi.fn();
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
    'event: tool_call\ndata: {"runId":"r1","name":"bash","toolCallId":"tc1","args":"{}"}\n\n',
    'event: tool_result\ndata: {"runId":"r1","name":"bash","toolCallId":"tc1","result":"ok","ok":true}\n\n',
    'event: done\ndata: {"runId":"r1","conversationId":"c1","intent":""}\n\n',
  ])));
  await streamMessage('c1', 'hi', [], { onToolCall, onToolResult });
  expect(onToolCall).toHaveBeenCalledWith('bash', '{}', 'tc1');
  expect(onToolResult).toHaveBeenCalledWith('bash', 'ok', true, 'tc1');
});
```
Call `vi.unstubAllGlobals()` in the file's existing `afterEach` if it is not already there.

- [ ] **Step 2: Run it and confirm it fails**

Run: `pnpm vitest run src/api/__tests__/aria.test.ts`
Expected: FAIL, the callbacks receive only two/three arguments.

- [ ] **Step 3: Implement the parser change and the adapter**

In `src/api/aria.ts`, extend the interfaces:
```ts
onToolCall?: (name: string, args?: string, toolCallId?: string) => void;
onToolResult?: (name: string, result: string, ok: boolean, toolCallId?: string) => void;
```
and pass `optionalText(p.toolCallId)` in the `tool_call` and `tool_result` dispatch cases. Update any existing exact-argument assertions in `aria.test.ts` and `AriaPanel.test.tsx` that now receive the extra argument.

Create `src/chat/ariaAdapter.ts`:
```ts
import type { ChatModelAdapter } from '@assistant-ui/react';
import { streamMessage, type StreamError } from '@/api/aria';

type History = Array<{ role: string; content: string }>;

interface Part {
  type: 'text' | 'tool-call';
  text?: string;
  toolCallId?: string;
  toolName?: string;
  args?: unknown;
  argsText?: string;
  result?: unknown;
  isError?: boolean;
}

export interface AriaAdapter extends ChatModelAdapter {
  /** Abort the in-flight SSE request (the page's stop control). */
  cancel: () => void;
}

export function createAriaAdapter(options: {
  conversationId: string;
  getHistory: () => History;
  stream?: typeof streamMessage;
}): AriaAdapter {
  const stream = options.stream ?? streamMessage;
  let current: AbortController | null = null;
  return {
    cancel() { current?.abort(); },
    async *run({ messages, abortSignal }) {
      const controller = new AbortController();
      current = controller;
      const forwardAbort = () => controller.abort();
      abortSignal.addEventListener('abort', forwardAbort);

      const lastUser = [...messages].reverse().find((m) => m.role === 'user');
      const text = lastUser?.content.filter((p) => p.type === 'text').map((p) => p.text).join('\n') ?? '';
      let output = '';
      const tools = new Map<string, Part>();
      let finished = false;
      let failure: StreamError | null = null;
      let wake: (() => void) | null = null;
      const snapshot = (): Part[] => [
        ...[...tools.values()].map((p) => ({ ...p })),
        ...(output ? [{ type: 'text' as const, text: output }] : []),
      ];
      const notify = () => { const w = wake; wake = null; w?.(); };

      void stream(options.conversationId, text, options.getHistory(), {
        onToolCall: (name, args, toolCallId) => {
          const id = toolCallId ?? `call-${tools.size}`;
          tools.set(id, { type: 'tool-call', toolCallId: id, toolName: name, argsText: args ?? '', args: safeJson(args) });
          notify();
        },
        onToolResult: (name, result, ok, toolCallId) => {
          const id = toolCallId ?? [...tools].find(([, p]) => p.toolName === name)?.[0];
          if (!id) return;
          tools.set(id, { ...(tools.get(id) ?? { type: 'tool-call', toolCallId: id, toolName: name }), result: safeJson(result), isError: !ok });
          notify();
        },
        onApprovalRequired: (ask) => {
          tools.set(`approval-${ask.approvalId}`, { type: 'tool-call', toolCallId: `approval-${ask.approvalId}`, toolName: '__approval', args: ask, argsText: '' });
          notify();
        },
        onWaitingInput: (ask) => {
          tools.set(`question-${ask.approvalId}`, { type: 'tool-call', toolCallId: `question-${ask.approvalId}`, toolName: '__question', args: ask, argsText: '' });
          notify();
        },
        onMemoryProposal: (proposal) => {
          tools.set(`memory-${proposal.id}`, { type: 'tool-call', toolCallId: `memory-${proposal.id}`, toolName: '__memory', args: proposal, argsText: '' });
          notify();
        },
        onMessage: (content) => { output = content; notify(); },
        onDone: () => { finished = true; notify(); },
        onError: (error) => { failure = error; finished = true; notify(); },
      }, controller.signal).catch((error: unknown) => {
        if ((error as DOMException)?.name !== 'AbortError') {
          failure = error instanceof Error ? error.message : String(error);
        }
        finished = true;
        notify();
      });

      try {
        while (true) {
          const parts = snapshot();
          if (parts.length > 0) yield { content: parts };
          if (finished) break;
          await new Promise<void>((resolve) => { wake = resolve; });
        }
      } finally {
        abortSignal.removeEventListener('abort', forwardAbort);
        if (current === controller) current = null;
      }
      if (failure) throw failure;
    },
  };
}

function safeJson(value: string | undefined): unknown {
  if (value === undefined) return undefined;
  try { return JSON.parse(value); } catch { return value; }
}
```

- [ ] **Step 4: Write the adapter test**

Create `src/chat/__tests__/ariaAdapter.test.ts`:
```ts
import { describe, expect, it } from 'vitest';
import { createAriaAdapter } from '../ariaAdapter';

describe('createAriaAdapter', () => {
  it('maps tool, approval, and final message events to cumulative parts', async () => {
    const stream = async (_c: string, _m: string, _h: unknown, cb: any) => {
      cb.onToolCall('bash', '{}', 'tc1');
      cb.onToolResult('bash', 'ok', true, 'tc1');
      cb.onApprovalRequired({ runId: 'r1', approvalId: 'a1', toolName: 'bash' });
      cb.onMessage('All good.');
      cb.onDone?.({ runId: 'r1', conversationId: 'c1', intent: '' });
    };
    const adapter = createAriaAdapter({ conversationId: 'c1', getHistory: () => [], stream: stream as never });
    const chunks: any[] = [];
    const run = adapter.run({
      messages: [{ role: 'user', content: [{ type: 'text', text: 'hi' }] }],
      abortSignal: new AbortController().signal,
    } as never);
    for await (const chunk of run as AsyncIterable<any>) chunks.push(chunk);

    const last = chunks.at(-1)!.content;
    expect(last).toContainEqual(expect.objectContaining({ type: 'tool-call', toolCallId: 'tc1', result: 'ok' }));
    expect(last).toContainEqual(expect.objectContaining({ toolName: '__approval', toolCallId: 'approval-a1' }));
    expect(last).toContainEqual({ type: 'text', text: 'All good.' });
  });
});
```

- [ ] **Step 5: Run the tests**

Run: `pnpm vitest run src/api/__tests__/aria.test.ts src/chat/__tests__/ariaAdapter.test.ts src/components/__tests__/AriaPanel.test.tsx`
Expected: PASS; AriaPanel's ignored extra arguments keep compiling.

- [ ] **Step 6: Commit**

```bash
git add agent-control-tower/act-dashboard/src/api/aria.ts agent-control-tower/act-dashboard/src/api/__tests__/aria.test.ts agent-control-tower/act-dashboard/src/chat
git commit -m "feat(dashboard): add an assistant-ui adapter over the Aria SSE turn"
```

---

### Task 7: Aria page rebuild

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/pages/MainChatPage.tsx`
- Create: `agent-control-tower/act-dashboard/src/chat/AriaRuntimeProvider.tsx`
- Create: `agent-control-tower/act-dashboard/src/chat/AriaThread.tsx`
- Modify: `agent-control-tower/act-dashboard/src/pages/__tests__/MainChatPage.test.tsx`
- Modify: `agent-control-tower/act-dashboard/e2e/aria-main-chat.spec.ts` (page-level `.mc-*` hooks stay; composer selectors move to roles)
- Delete after migration: `agent-control-tower/act-dashboard/src/styles/main-chat.css` (only if `grep -R "main-chat.css" src` shows MainChatPage alone)

**Interfaces:**
- Consumes: `createAriaAdapter`, `decisionToolkit`, `ConversationSidebar`, `ProgressTimeline`, `useLiveProgress`, `cancelRun`, `streamMessage` history already loaded by the page.
- Produces: `<AriaRuntimeProvider conversationId getHistory>` and `<AriaThread />`; page keeps `.mc-header-title`, `.mc-conversation-list`, `.mc-stack`, `.mc-compose`, `.mc-empty` hooks used by E2E.

- [ ] **Step 1: Write the failing page test**

Update `MainChatPage.test.tsx` to assert the new provider boundary:
```tsx
vi.mock('../../chat/AriaRuntimeProvider', () => ({
  AriaRuntimeProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('../../chat/AriaThread', () => ({ AriaThread: () => <div className="mc-stack">thread</div> }));
```
Assert the conversation list still renders, the empty state text is `Ask Aria anything.`, and stop still calls `cancelRun`.

- [ ] **Step 2: Run it and confirm it fails**

Run: `pnpm vitest run src/pages/__tests__/MainChatPage.test.tsx`
Expected: FAIL, new modules not found.

- [ ] **Step 3: Implement the provider**

Run `pnpm add @assistant-ui/react` if not already added, then create:
```tsx
import type { ReactNode } from 'react';
import type { ChatModelAdapter } from '@assistant-ui/react';
import { AssistantRuntimeProvider, AuiConfig, Tools, useLocalRuntime } from '@assistant-ui/react';
import { decisionToolkit } from './decisionToolkit';

export function AriaRuntimeProvider({ adapter, children }: {
  adapter: ChatModelAdapter;
  children: ReactNode;
}) {
  const runtime = useLocalRuntime(adapter);
  const config = AuiConfig({ tools: Tools({ toolkit: decisionToolkit }) });
  return <AssistantRuntimeProvider runtime={runtime} config={config}>{children}</AssistantRuntimeProvider>;
}
```
The page owns the adapter so its stop control can abort the stream:
```tsx
const adapter = useMemo(
  () => createAriaAdapter({ conversationId, getHistory: () => historyRef.current }),
  [conversationId],
);
```
Mount `decisionToolkit` renderers by rendering `<ApprovalToolUI />` etc. once inside the provider if the toolkit contract requires it; assistant-ui registers them through `Tools`, so the runtime config is sufficient.

- [ ] **Step 4: Implement the thread**

Run `npx assistant-ui@latest add thread` and wrap the generated component:
```tsx
export function AriaThread() {
  return (
    <div className="mc-stack">
      <Thread />
    </div>
  );
}
```
Restyle the generated thread with token classes (`bg-background`, `text-foreground`, `border-border`, `rounded-lg`) and remove any Next-specific directive. Update `e2e/aria-main-chat.spec.ts` so page-level hooks keep working (`.mc-header-title`, `.mc-conversation-list`, `.mc-stack`, `.mc-empty`) while composer interactions use roles/labels (`getByRole('textbox')`, send button, stop button, delete conversation) instead of the generated CLI markup. Record the selector changes in the PR.

- [ ] **Step 5: Rewire the page**

Keep the existing conversation loading, `useSearchParams` conversation id, delete-confirm dialog, slash commands, live progress polling, and stop logic. Replace the message list, composer, and per-message rendering with `AriaRuntimeProvider` + `AriaThread`. Keep the `mc-*` class hooks on the surrounding containers. Stop remains:
```ts
const stop = () => {
  cancelledRef.current = true;
  adapter.cancel();
  if (runId) {
    void cancelRun(runId).catch((err) => {
      setCancelError(err instanceof Error ? err.message : String(err));
    });
  }
};
```
Render `cancelError` through the existing state notice. `adapter.cancel()` aborts the same SSE request the page opened; do not open a second stream.

- [ ] **Step 6: Run the tests and the chat E2E**

Run:
```bash
pnpm vitest run src/pages/__tests__/MainChatPage.test.tsx src/chat/__tests__
pnpm test
pnpm build
npx playwright test e2e/aria-main-chat.spec.ts --project=chromium
```
Expected: PASS, including conversation list, delete, send, and the empty state.

- [ ] **Step 7: Commit**

```bash
git add agent-control-tower/act-dashboard/src/pages/MainChatPage.tsx agent-control-tower/act-dashboard/src/pages/__tests__/MainChatPage.test.tsx agent-control-tower/act-dashboard/src/chat agent-control-tower/act-dashboard/e2e/aria-main-chat.spec.ts
git commit -m "feat(dashboard): rebuild the Aria page on assistant-ui"
```

---

### Task 8: Acceptance, real-stack QA, and evidence

**Files:**
- Create: `agent-control-tower/act-dashboard/e2e/ui-refresh.spec.ts`
- Modify: none in the backend.

**Interfaces:**
- Consumes: the running local stack (`bash scripts/start-backend.sh --skip-sandbox --provider=opencode`, `bash scripts/start-frontend.sh`).
- Produces: the S1–S7 evidence for the PR.

- [ ] **Step 1: Write the route and shell E2E**

```ts
import { expect, test } from '@playwright/test';

const ROUTES = ['/', '/crew', '/providers', '/knowledge', '/memory', '/reports', '/aria', '/chat', '/workflows', '/ops', '/scheduled-jobs', '/runs'];

test('every route renders in the new shell', async ({ page }) => {
  const errors: string[] = [];
  page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()); });
  for (const route of ROUTES) {
    await page.goto(route);
    await expect(page.locator('.rail-btn[data-view]')).not.toHaveCount(0);
  }
  expect(errors).toEqual([]);
});

test('sidebar groups and theme toggle work', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('link', { name: 'Runs' })).toBeVisible();
  await page.getByRole('button', { name: 'Toggle theme' }).click();
  await expect(page.locator('body')).toHaveClass(/light/);
});
```

- [ ] **Step 2: Exercise an approval end to end**

Start the stack with GATED access and run the existing decision producers against it:
```bash
cd agent-control-tower/act-dashboard
npx playwright test e2e/kanban-hitl.spec.ts e2e/aria-inflow-decisions.spec.ts e2e/core-permissions.spec.ts --project=chromium
```
Expected: PASS. Then open `/aria`, trigger a GATED file-write ask through the existing run flow, and assert:
- the approval card appears in the Aria thread and in the decision queue;
- Allow lets the run continue; Deny leaves the file absent;
- after the decision, the queue no longer lists the ask.

Record the run ids, approval ids, and command output; capture no secrets in the report.

- [ ] **Step 3: Check theme contrast**

Add to `e2e/ui-refresh.spec.ts` a check on painted elements in both themes:
```ts
async function contrast(page: import('@playwright/test').Page, selector: string) {
  return page.locator(selector).first().evaluate((el) => {
    const parse = (value: string) => value.match(/\d+(\.\d+)?/g)!.slice(0, 3).map(Number);
    const lum = (rgb: number[]) => {
      const c = rgb.map((v) => { const s = v / 255; return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4; });
      return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    };
    const style = getComputedStyle(el);
    const fg = lum(parse(style.color));
    const bg = lum(parse(style.backgroundColor === 'rgba(0, 0, 0, 0)' ? getComputedStyle(document.body).backgroundColor : style.backgroundColor));
    return (Math.max(fg, bg) + 0.05) / (Math.min(fg, bg) + 0.05);
  });
}

test('body text meets AA in both themes', async ({ page }) => {
  await page.goto('/');
  expect(await contrast(page, 'main p')).toBeGreaterThanOrEqual(4.5);
  await page.getByRole('button', { name: 'Toggle theme' }).click();
  expect(await contrast(page, 'main p')).toBeGreaterThanOrEqual(4.5);
});
```

- [ ] **Step 4: Measure the bundle**

```bash
pnpm build
du -sh dist
ls -S dist/assets | head -5
```
Compare with `/tmp/aria-ui-baseline-dist` from Task 0 and put both numbers in the PR.

- [ ] **Step 5: Prove the backend is untouched**

```bash
git diff --name-only main...HEAD -- agent-control-tower | grep -v 'act-dashboard' || true
```
Expected: no output. Any backend path means Task 0 or a task broke the constraint.

- [ ] **Step 6: Run the full local gates**

```bash
pnpm test
pnpm build
npx playwright test e2e/ui-refresh.spec.ts e2e/aria-main-chat.spec.ts e2e/overview-dashboard.spec.ts --project=chromium
```
Expected: all PASS.

- [ ] **Step 7: Remove the migrated legacy CSS**

```bash
grep -R "main-chat.css" agent-control-tower/act-dashboard/src
```
Expected: only `MainChatPage` (now no longer importing). Delete the file and its import, then rerun `pnpm build` and the chat E2E. Leave `chat-flow.css`, `overview-flow.css`, and every other flow CSS in place for their waves.

- [ ] **Step 8: Commit and report**

```bash
git add agent-control-tower/act-dashboard/e2e/ui-refresh.spec.ts agent-control-tower/act-dashboard/src/styles/main-chat.css
git commit -m "test(dashboard): cover the refreshed shell, Overview, and Aria"
```
Report S1–S7 as PASS / FAIL / NOT VERIFIED with the command output and run ids; do not claim a PASS for a criterion that was not executed.

## Self-review

| Spec section | Task |
|--------------|------|
| S1 shell/Overview/Aria render | 3, 5, 7, 8 |
| S2 Aria parity + decisions | 4, 6, 7, 8 |
| S3 stop | 6, 7 |
| S4 12 routes | 3, 8 |
| S5 themes and contrast | 1, 3, 8 |
| S6 no backend change | Global constraints; `git diff` check in Task 8 |
| S7 bundle delta | Task 0, Task 8 |
| SSE mapping | 6 |
| STOMP invalidation | 2 |
| Tokens and style rules | 1, 3 |
| Migration/rollback | per-task commits; Task 8 cleanup |
| Known limitations | documented in the spec; no task claims them as features |

Placeholder scan: no TBD/TODO; the only external scaffolds are the shadcn and assistant-ui CLI commands and the copied AI Elements patterns, each with an exact command or URL.
