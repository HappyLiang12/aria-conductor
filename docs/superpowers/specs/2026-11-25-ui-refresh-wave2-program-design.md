# UI Refresh Wave 2 — Full Program (2a–2d) Design

Status: draft for spec review
Date: 2026-11-25
Base: `main` @ `f987b1f2`

## 1. Outcome

The entire dashboard becomes token-driven. Every route and every shared overlay is rebuilt on the existing shadcn/ui + Tailwind tokens, while behaviour and the operator/E2E contracts stay unchanged. Wave 2 lands as one delivery: 2a Overview plus 2b, 2c and 2d, with the legacy page styling removed.

Success criteria:

| id | Criterion | Evidence |
|----|-----------|----------|
| P1 | All 12 routes use token-driven page surfaces; no page keeps its legacy chrome | Route screenshots at 390/1024/1400, light/dark |
| P2 | Behaviour parity: every existing unit test and E2E spec passes; no test loosened or skipped | Full local CI + 57 E2E specs |
| P3 | Shared overlays (drawers, dialogs, toasts, notification bell, Aria panel) match the token system and keep focus/Escape/confirm behaviour | Overlay unit tests + `configure-modal-close`, `notification-*`, `mobile-flows` |
| P4 | No overflow at 390/1024/1400; mobile flows and lane selectors preserved | Playwright |
| P5 | Dark/light AA on body text, status, risk and error surfaces | Painted-element contrast |
| P6 | Legacy page CSS removed: `index.css` keeps only globals (scrollbars, layout, theme bridge), and `chat-flow`/`main-chat`/`configure-flow`/`toast-flow`/`feedback-flow`/`state-notice` are gone or reduced to shared primitives | Selector audit + file list |
| P7 | Frontend only; no new runtime dependency; `bash scripts/local-ci.sh affected` is the pre-push gate | Empty backend diff + CI |

## 2. Scope and inventory

### 2b — operator pages

| Page | LOC | Page tests | Main E2E | CSS source | Shared components |
|------|-----|-----------|----------|------------|-------------------|
| `CrewPage` | 818 | `CrewPage.test.tsx` 430 | `crew-crud`, `crew-telemetry`, `multi-agent-lifecycle`, `agent-dev-workflow-governance`, `journey-crew-deploy-run` | `index.css` Crew section | `AgentCatalog`, `AgentDrawer` (2d) |
| `ProvidersPage` | 173 | via `Providers` tests | `adk-providers`, `config-knowledge-honesty`, provider/mode specs | `index.css` Settings/providers | `RuntimeCredentialsCard` |
| `RunsPage` | 371 | `RunsPage.live.test.tsx` 236 | `runs-page`, `observability-live`, `opencode-adk-e2e`, `journey-agent-run-report` | `index.css` Timeline/approval queue | `ErrorNotice` |
| `OpsPage` | 811 | `OpsPage.test.tsx` 310 | `ops-monitoring`, `ops-approval-surface`, `review-decision-zone`, `housekeeping-cleanup`, `core-permissions` | `index.css` Approval queue/drawer | `ReviewQueue`, `HousekeepingPanel` |

### 2c — knowledge and admin pages

| Page | LOC | Page tests | Main E2E | CSS source |
|------|-----|-----------|----------|------------|
| `KnowledgePage` | 978 | 358 | `knowledge-review`, `journey-knowledge-promotion`, `config-knowledge-honesty` | `index.css` + `TemplatesPanel` styles |
| `MemoryPage` | 773 | 753 | `memory-onboarding` | `index.css` |
| `ReportsPage` | 738 | 304 | `reports-generation` | `index.css` |
| `WorkflowsPage` | 560 | 147 | `workflow-*` (10 specs), `sdd-workflow`, `journey-workflow-yaml`, `template-api` | `index.css` |
| `ScheduledJobsPage` | 372 | 105 | `scheduled-jobs-page` | `index.css` |
| `SettingsPage` | 406 | 184 | config/settings specs | `index.css` Settings section |

### 2d — chat and shared overlays

| Surface | LOC | Tests | Main E2E | CSS source |
|---------|-----|-------|----------|------------|
| `ChatPage` + `ReviewQueue` | 633 + 196 | `ChatPage.live` 101, `ChatPage.mobile` 138 | `chat-smoke`, `mobile-flows` | `chat-flow.css`, `index.css` |
| `MainChatPage` + `components/main-chat/*` | 608 + 180 | `MainChatPage.test` | `aria-main-chat` | `main-chat.css` |
| `AriaPanel` | 1,921 | `AriaPanel.test` | `aria-attachments`, `aria-engine-unification`, `aria-inflow-decisions`, `aria-session-id`, `aria-timeout-cancel`, `aria-conversation-id-regression`, `fab-send-overlap` | `main-chat.css`, `index.css` |
| `TaskDrawer` | 580 | `TaskDrawer.test` 833 | `review-decision-zone`, `kanban-hitl` | `index.css` Drawer section |
| `AgentDrawer` | 830 | `AgentDrawer` tests | `multi-agent-lifecycle` | `index.css` Agent live drawer |
| `ConfigureModal` | 446 | `ConfigureModal` tests | `configure-modal-close`, `config-knowledge-honesty`, `core-permissions` | `configure-flow.css`, `index.css` Settings modal |
| `Toast` / `NotificationBell` | 267 / 236 | toast/bell tests | `notification-toast`, `notification-bell` | `toast-flow.css`, `index.css` |
| Shared notices/dialogs | `StateNotice`, `ErrorNotice`, `ConfirmDialog` | covered by consumers | indirect | `state-notice.css`, `feedback-flow.css`, `index.css` |

Weight: 11 pages = 6,633 lines; shared overlays/panels measured = 4,791 lines (some, like `ReviewWorkspace`, belong to 2a); page test files = 3,066 lines plus the overlay suites; `index.css` = 2,824 lines (legacy sections from Executive summary through Crew, roughly lines 164–2745); 57 E2E specs, many depending on existing class hooks.

## 3. Delivery model

"All together" means one Wave 2 release, not one unreviewable PR.

- One integration branch `feat/ui-refresh-wave2`, with four file-disjoint workstreams: 2a Overview, 2b operator pages, 2c knowledge/admin pages, 2d chat/overlays.
- Each workstream lands as its own PR into the integration branch (or as its own commit series), reviewed with its own gates.
- The integration branch is merged/released as one bundle after the full cross-page E2E, viewport and contrast gates.
- A shared foundation step precedes page work so overlays and notices have one owner; `index.css` is edited only by the cleanup owner, never by parallel page workstreams.
- If a workstream cannot stay file-disjoint, it waits or the files move to the foundation owner.

This keeps "all together" for the release while keeping reviewable diffs. A single 15k-line PR is rejected as unverifiable.

## 4. Design

### 4.1 Shared foundation (before parallel page work)

- `ConfirmDialog` → shadcn `AlertDialog` (registry component; no new runtime package).
- `TaskDrawer`/`AgentDrawer`/`ConfigureModal` → shadcn `Sheet`/`Dialog` shells with token styling; keep their focus trap, Escape and opener-restore contracts.
- `Toast`/`NotificationBell` → token-styled existing components (no Sonner dependency); keep the browser-only notification rule and manual dismiss.
- `StateNotice`/`ErrorNotice` → token Card/Alert surfaces; keep the existing semantics and copy.
- Form controls (inputs/selects/textareas) → shadcn primitives; keep validation and disabled/busy semantics.

### 4.2 Page surfaces

- 2b/2c pages use token Cards, Tables, Tabs, Sheets and Buttons; no page imports a legacy flow CSS file.
- Page-specific components (`AgentCatalog`, `RuntimeCredentialsCard`, `ReviewQueue`, `HousekeepingPanel`, `TemplatesPanel`, etc.) are owned by their page workstream; shared ones move to the foundation owner.
- Existing test ids, accessible names and class hooks stay as compatibility shims during the program. The legacy class names may remain in markup; their CSS is removed only at cleanup.

### 4.3 Chat and overlays (2d)

- `AriaPanel` is split into presentational subcomponents (thread, composer, decision cards, activity) while keeping its reducer/stream logic. This is the highest-risk surface; it gets its own review and E2E gate.
- `ChatPage` keeps its pane/list/thread state machine; `chat-flow.css` is removed after its tokens land.
- `TaskDrawer` decision zone is already handled by Wave 2a; 2d restyles the drawer chrome and the run/evidence sections.

### 4.4 CSS cleanup (final)

After every page/overlay no longer uses a legacy selector:

1. Audit each selector with grep across `src` (excluding tests).
2. Remove the matching block from `index.css`/flow CSS.
3. Keep globals: scrollbars, layout shell, theme/token bridge, shadcn base.
4. Delete `chat-flow.css`, `main-chat.css`, `configure-flow.css`, `toast-flow.css`, `feedback-flow.css`, `state-notice.css`, `decision-queue.css`, `attention-queue.css`, `kanban-flow.css`, `overview-flow.css` once their consumers use tokens.
5. `index.css` target: no page-specific sections; only globals + theme bridge.

Hooks (`col-k`, `decision-queue`, etc.) may remain in markup as test hooks; removing them and migrating E2E selectors is a separate cleanup decision after the program.

## 5. Testing and acceptance

- Unit: all page/section/overlay suites unchanged; new display tests only for new branches.
- E2E: all 57 specs, sharded 1–4, plus the keyboard/mobile/contrast cases; no skipped new spec.
- Viewports: 390, 1024, 1400; both themes.
- Contrast: body, status/risk, error, disabled and link states on painted elements.
- Full local gate: `bash scripts/local-ci.sh all --with-e2e` (stack running) before the integration merge; per-PR `affected` + its own E2E subset.
- P1–P7 acceptance table; unrun criteria are NOT VERIFIED in the PR.

## 6. Risks

| Risk | Mitigation |
|------|------------|
| 15k-line program | Four PRs into one integration branch; shared foundation first; no giant PR |
| `index.css` conflicts | Only the cleanup owner edits it; page PRs never touch it |
| `AriaPanel` 1,921 lines | Split presentational components; dedicated review + aria E2E |
| 57 E2E hooks | Keep class hooks as shims; cleanup removes CSS, not hooks |
| Parallel worktrees | File ownership map; shared components assigned to foundation owner |
| Missing states in screenshots | State matrix in the wave-2 E2E spec (empty/loading/error/stale/busy) |
| Review fatigue | Each PR carries its own acceptance subset; integration gate is the union |

## 7. Out of scope

- AG-UI, token streaming, IA/route changes, new product features.
- Backend/API/permission changes.
- Native/mobile app work.
