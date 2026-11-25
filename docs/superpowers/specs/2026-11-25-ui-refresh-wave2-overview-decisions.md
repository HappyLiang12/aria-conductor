# UI Refresh Wave 2a — Decisions

Companion to `2026-11-25-ui-refresh-wave2-overview-design.md`.

## D1 Scope is the whole Overview, not only the board and queue

Phase 1 produced an Overview with a new KPI row above legacy board/queue/summary sections. That mixed look is what prompted this wave. Restyling only the board/queue would leave the same incoherence, so Wave 2a covers every section `OverviewPage` renders and the shared decision panels.

## D2 Native HTML5 drag/drop stays

`KanbanBoard` owns the `LEGAL_DROPS` state machine, guards and native drag handlers. Replacing them with a DnD library adds risk and a dependency with no product gain. The wave changes presentation only and keeps the handlers/state.

## D3 Existing DOM hooks stay as compatibility shims during Wave 2a

The E2E suite uses `.col-k[data-col]`, `.decision-queue__count`, `data-collapsed`, `.decision-zone` and `.kanban-mobile-list`. Rewriting 13 specs in the same wave would mix presentation and test-contract churn. New markup emits these hooks alongside token classes; the cleanup wave migrates specs to semantic selectors and removes the class hooks.

## D4 Shared decision panels are in scope; drawer chrome is not

`DecisionPanel`/`ShortApprovalView`/`RunOutcomeChip` are rendered by both the Overview review workspace and the TaskDrawer. The decision zone must change wherever it appears, so the panels are restyled in this wave. The TaskDrawer/AgentDrawer/ConfigureModal chrome stays legacy until its own wave.

## D5 No data or permission changes

The wave keeps the existing hooks, APIs, queries, SSE/STOMP and approval endpoints. Any backend change would be a separate spec.

## D6 CSS removal is selector-by-selector, after each step is green

The board/agent/activity styling mostly lives in unlayered `index.css`; only part is in the small flow CSS files. Each step removes the exact selectors it replaces, after the step's unit/E2E gate is green. `index.css` is not rewritten or globally reset in this wave.

## D7 Five sequential commits

Each step (queue/attention, board, decision panels/review workspace, summary/briefing/team/activity, cleanup) is independently testable and revertible.

## D8 Other pages remain later waves

Crew/Providers/Runs/Ops (2b), Knowledge/Memory/Reports/Workflows/Jobs/Settings (2c), Chat/AriaPanel/drawers (2d). This keeps reviewable diffs and lets each surface migrate its own tests.

## Revisit conditions

- If a native DnD limitation blocks the new card design, evaluate a DnD library in a separate decision.
- If a legacy class hook cannot be emitted by the new markup, migrate that spec to a semantic selector in the same step rather than inventing a hidden element.
- If contrast on a token surface fails AA, fix the token globally rather than per-component overrides.
