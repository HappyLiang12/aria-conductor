# fix(uiux): decision-first dashboard repairs

## Summary

The dashboard made operators hunt for the thing that needs them. This branch makes the Overview **decision-first**: pending approvals and blocked/failed work sit above the board, counts are honest about what is known, and the secondary surfaces (Kanban, dialogs, toasts, Chat, Ops, Knowledge, Configure) stop claiming things the build does not enforce.

Scope is frontend-only in `agent-control-tower/act-dashboard`: no new routes, no new dependencies, no backend/API/DB changes, no replacement design system. The existing tokens and components are reused.

## What changed

**Overview is decision-first**
- New `DecisionQueue`: one row per PENDING approval, above the board. Row identity is the approval; the card join (`kanbanItemId` first, then `linkedRunId`) is display context only. A taskless approval stays actionable in place (Approve / Deny, or an inline Answer for clarifications) instead of rendering a dead control.
- New `AttentionQueue`: pickup failures (`TODO` + `lastError`), failed runs with no pending ask (including REVIEW cards without asks), and unhealthy ADK providers.
- Honest states: `loading` / `error` / `stale` / `success` from both sources; `partial` joins show "refresh failed — showing last known"; the all-clear line appears only on authoritative zero/zero. Counts are `null` (rendered `—`) unless the data is complete. A failed approvals fetch is an error even when the board succeeds; a failed provider list is an error, not "no providers".
- Row order stays stable while a drawer or the review workspace is open. Hover and focus are tracked separately, so a pointer that leaves while an inline answer still has focus cannot re-sort the rows, and each row owns its own busy state so one decision settling never re-enables another row mid-request.

**Board, dialogs and focus**
- Kanban list loading and failure no longer look like an empty board; the board exposes `+ New Item`, a mobile lane selector, served create errors (backend reasons, not a generic failure) and the real 409 `AGENT_NOT_ELIGIBLE` body.
- The card body is a named keyboard opener (`Open task <title>`) that is also the native drag source; Enter opens it. Batch presets (`Accept all (N)`, `Rework all failed (N)`, `Cancel all`) skip cards with a pending ask and name the held count in the tooltip.
- `ConfirmDialog` is cancel-first for destructive actions, traps Tab from anywhere inside it, focuses the safe exit, and returns focus to its opener. Escape from an open task drawer now closes it even though focus starts on the heading; Escape inside a draft or a stacked dialog never does.
- Batch copy is counted (`✓ Approve all (N)`, `Request changes (starts 1 fresh run)`).

**Toasts and navigation**
- One dismiss timer per toast, hover/focus pauses with per-toast remaining time, visible cap of 5 that never evicts the toast holding focus, `role="status" aria-live="polite"`.
- A `View` action is only rendered when the fine-grained notification type resolves to a route.
- The rail is real `<Link>` navigation with `aria-current="page"`, so middle-click and assistive tech work.

**Secondary surfaces**
- Chat: honest copy ("Run conversations", participants), single-pane mobile layout with `Back to conversations`; the transcript now uses the phone width (the old 320px track is gone). A failed transcript fetch says so and offers Retry instead of claiming the thread is empty.
- Ops: the two-column grid collapses at the stylesheet breakpoint instead of relying on an inline style.
- Knowledge: an item whose `agentId` no longer resolves is **Unattributed** (its own space, row and detail label) instead of silently landing under a real agent; adding an agent never migrates ownership. When the agent list itself fails, the page says ownership is unavailable and does not relabel every item as Unattributed.
- Configure: the invented skill permission model is removed; the dead "Reset to defaults" control is removed. Gates and Skills are clearly labelled reference-only, and their controls are inert — the build no longer pretends to enforce them.
- Executive summary: stale last-known values (agent runtime, runs) are labelled as last-known rather than presented as fresh.

## Design-phase findings → fixes

| Finding | Verdict | Where |
| --- | --- | --- |
| C1 Empty board indistinguishable from loading/failure | fixed | board query states, `StateNotice` |
| C2 Destructive confirm autofocus / containment / restore | fixed | cancel-first `ConfirmDialog`, opener capture |
| C3 Toast announcements + 5s action window inaccessible | fixed | per-toast timers, hover/focus pause, live region |
| C4 Unmapped notification types show a no-op View | fixed | `View` only when a route resolves |
| C5 Rail buttons lack link/current-page semantics | fixed | `<Link>` + `aria-current` |
| C6 Create-item error discards backend recovery info | fixed | served `KanbanRejection` reasons |
| F1 Task 7 tests contradicted cancel-first | fixed | rewritten focus/Enter/Tab tests |
| F2 All-clear ignored partial/freshness | fixed | `count = null` unless complete; all-clear gated |
| F3 Invented 409 shape | fixed | real `AGENT_NOT_ELIGIBLE` + `details.agentId/reasons` |
| F4 Chat pane hid but 320px track stayed | fixed | `minmax(0,1fr)`, scoped overrides, transcript 306px at 390 |
| F5 Fabricated `USE` skill permission | fixed | permission model removed; reference-only controls |
| F6 Unattributed bucket never rendered | fixed | space button, filter, row, detail |
| F7 `kanbanItemId` ignored; unlinked approvals stranded | fixed | join by item id first; taskless rows decide in place |
| F8 TopBar KPI zeros fabricated under failure | fixed | `—` / last-known / real values per phase |
| F9 Toast test used a nonexistent helper | fixed | existing `renderToast()` harness |
| F10 Wrong runs key; ask-less REVIEW failures hidden; mixed fleets suppressed | fixed | `['runs-all']` invalidation, attention rules, mixed label |

Two defects were found during the E2E wave and fixed on this branch:

- **A failed approvals fetch rendered an all-clear.** Approvals own row identity; the queue now enters the error phase when they fail, even if the board succeeds.
- **The Overview inherited the global three-column `.layout` template.** `index.css` is injected after the page stylesheets, so `.overview-grid` lost the cascade: the board sat in a 260px column with an empty 340px column beside it and the executive strip compressed to vertical text. Page templates are now compounded with `.layout`, and the E2E spec asserts lane width at 1440x900 and 1024x768.

## Implementation review (independent, read-only)

A read-only reviewer audited the full diff at `342d8775` plus the in-flight drawer fix. Verdict: **BLOCKED**, 12 findings (4 High, 7 Medium, 1 Low). All 12 are fixed on this branch; the reviewer's snapshot predated two of the High fixes, which are listed with the commit that landed them.

| # | Severity | Finding | Fix |
| --- | --- | --- | --- |
| H1 | High | A failed `/adk/providers` query was indistinguishable from "no providers"; the queue phase could never be `error` and still showed an all-clear | providers error → `error` phase; empty only on `success` without `partial` (`28899d7c`) |
| H2 | High | Agent-list failure relabelled every knowledge item "Unattributed" and claimed no agents exist | ownership is withheld until agents load; explicit "ownership unavailable" state with Retry (`0db73e93`) |
| H3 | High | Overview lost the media-query cascade to `index.css` (three-column layout, 260px board) | compound `.layout.overview-*` selectors + width assertions (`b6adb9cc`) |
| H4 | High | Escape could not close the drawer from its own focused heading | drawer-local Escape handler that ignores drafts and stacked dialogs (`ceb30dbb`) |
| M1 | Medium | Configure gate rows still asserted enforcement ("required before work flows on") under a reference-only panel | copy rewritten as policy targets, not enforcement (`3e9742a0`) |
| M2 | Medium | Runtime tab exposed a "Reset to defaults" button that reset nothing | removed; absence asserted (`3e9742a0`) |
| M3 | Medium | `draggable={false}` on the card-wide opener removed the pointer drag affordance | the opener is the drag source again; E2E asserts hit-tested `draggable` (`775c49ee`) |
| M4 | Medium | Tab trap only ran while focus was on Cancel/Confirm; clicking the message let Tab escape | window-level trap that pulls stray focus back to the safe exit (`f17b96c9`) |
| M5 | Medium | Queue lock dropped focus when the pointer left; one shared `busyId` let a second click duplicate a decision | separate hover/focus lock; per-row busy set with per-call callbacks (`3a8934d6`) |
| M6 | Medium | A failed transcript fetch rendered "No messages recorded yet" | error notice with Retry (`747debd8`) |
| M7 | Medium | REVIEW batch presets included cards with pending asks | presets operate on ask-free cards and name the held count (`775c49ee`) |
| L1 | Low | Executive summary showed stale runtime/runs values without saying they were last-known | stale detail prefixes on both surfaces (`8a4b80f9`) |

## Hosted CI findings

The first hosted run (`40551694153`, head `8d38cccc`) failed only `Frontend Build Check`, on `ReviewWorkspace.test.tsx:336`: expected `""` to be `"keep my draft"`. The same suite was green locally and in a clean-room on faster hardware, so this was not retried away.

- **CI-1 — the review heading focus raced the feedback draft.** The heading focus was deferred with `requestAnimationFrame`; on the slower runner that frame landed after the test (or an operator) had focused the Request-changes textarea, so keystrokes went to the heading and the controlled draft stayed empty. Fix `2027711d`: focus synchronously in the opening layout effect, plus a deterministic regression test that holds every frame callback unrun and asserts (a) the heading already has focus in the opening commit and (b) a late frame cannot steal the caret from the draft. RED evidence: against the rAF version the new test fails with focus still on the opener button.
- The E2E shards never produced signal in that run: they require `frontend-build`'s artifact, so a frontend failure skips them. They run on the fixed head.

The second hosted run (`40552479882`, head `2027711d`) fixed the frontend job and finally exercised every shard: Java lanes, build, smoke, Docker and shards 1, 3, 4 were green; shard 2 failed `kanban-board.spec.ts`.

- **CI-2 — the create-form error lost the `.kanban-form-error` contract.** The structured create error (Task 6) renders through `ErrorNotice` inside a `.kanban-create-error` wrapper, which dropped the class that carried both the red form-error styling and the hosted spec's hook; the board-level slot with that class is suppressed while the modal is open. Fix `0950fd0c`: the create `ErrorNotice` keeps `className="kanban-form-error"`, and the empty-title unit test now asserts the class in addition to the exact text and the no-API-call guarantee. The other failure in that shard (`housekeeping-cleanup`) passed on retry and is not treated as green-by-retry for CI-2.

## Verification

Verification point: `0950fd0c` (last code commit; the documentation commit that follows is comments only). Commands and results:

| Gate | Command | Result |
| --- | --- | --- |
| Unit + coverage | `pnpm --dir agent-control-tower/act-dashboard test -- --coverage` | 72 files / 621 tests passed, exit 0; 74.75% lines / 82.27% branches / 61.16% functions |
| Build | `pnpm --dir agent-control-tower/act-dashboard build` | `tsc -b` + `vite build`, exit 0 |
| E2E (new features, artifact-first) | clean-room worktree at `0950fd0c`, `pnpm build`, `pnpm preview --port 4176`, then `BASE_URL=http://localhost:4176 CI=1 pnpm exec playwright test e2e/decision-first-overview.spec.ts e2e/kanban-keyboard.spec.ts e2e/mobile-flows.spec.ts e2e/config-knowledge-honesty.spec.ts --project=chromium --workers=1 --retries=0` | 20 tests passed (42.4s) at 1440x900 / 1024x768 / 390x844 against the built artifact |
| E2E (regression subset, artifact-first) | same preview, `overview-dashboard` (backend-independent cases), `notification-toast`, `configure-modal-close`, `unknown-route` | 6 passed (12.6s) |
| Clean-room | fresh worktree at `0950fd0c`: `pnpm install --frozen-lockfile`, `pnpm test -- --coverage`, `pnpm build` | install exit 0; 621 tests passed; coverage identical to local; build exit 0; no `dist/` at checkout |
| Independent review | read-only full-diff review, then re-gate after the fix wave | 12 findings fixed; local and clean-room gates re-run |
| Hosted CI run 1 | run 40551694153 at `8d38cccc` | red: `Frontend Build Check` only; E2E shards were skipped because they need that job's artifact (CI-1) |
| Hosted CI run 2 | run 40552479882 at `2027711d` | red: shard 2 only, `kanban-board.spec.ts` create-error hook (CI-2); everything else green |

The new specs use `page.route` / `page.routeWebSocket` fixtures only: no backend, no LLM, no credentials. Backend-dependent specs (`overview-dashboard` seeded case, `kanban-hitl`, `chat-smoke`, `observability-live`, all journey specs) run in the hosted Playwright shards, not locally.

Artifact checks (screenshots kept locally under `docs/reviews/evidence/2026-10-27-uiux-release/`, not committed): Overview at 1440x900, 1024x768 and 390x844; mobile chat thread; board keyboard opener and dialog focus are covered by the specs above.

Hosted CI: run [40554394382](https://github.com/HappyLiang12/aria-conductor/actions/runs/40554394382) at `0950fd0cf275ae20cc2a50a747c9c7554fb01352` — **green**. Jobs: Frontend Build Check; Detect Changed Trees; Build Backend + Harness Distribution (once); Java Unit Tests (api-mcp-app); Java Unit Tests (common-agent); Java Unit Tests (execution); Java Unit Tests (knowledge-aria); Java Integration Tests (Failsafe); E2E Smoke Test (deterministic harness); Docker Build Verification; E2E Playwright (shard 2/4); E2E Playwright (shard 1/4); E2E Playwright (shard 3/4); E2E Playwright (shard 4/4).

## Deferred (documented, not implemented)

Finalize-run destructive classification, run deep links (`/chat?run=`), transcript-to-task opening, toast dismiss button, dialog failure retry lifecycle, Ops section anchors/retry, non-drag keyboard card movement, route/IA consolidation, backend permission enforcement, native app work, performance/contrast certification.

## Residual risks

- The Overview layout is still two frames of CSS cascade; a future page stylesheet that relies on equal specificity will lose to `index.css` the same way. The compound `.layout.*` rule is the documented mitigation.
- `mergeStableOrder` keeps row order only while locked; an operator who reads slowly with nothing open sees server order re-sort on each refetch.
- The Attention queue treats `provider.healthy === false` as actionable; a provider flapping between healthy probes will add and remove its row.
- `overview-dashboard`'s API-seeded case is CI-only (needs the harness backend); it is not verified locally.

## Out of scope

No backend, API, schema, Flyway, auth or runtime changes. Nothing is pushed to `main`; this PR is not merged by the agent.
