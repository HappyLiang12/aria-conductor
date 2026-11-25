# UI Refresh Wave 2 Program — Decisions

Companion to `2026-11-25-ui-refresh-wave2-program-design.md`. The approved 2a spec/plan stays as workstream A.

## D1 "All together" means one delivery, four reviewable PRs

One integration branch `feat/ui-refresh-wave2` receives four file-disjoint workstreams (2a Overview, 2b operator pages, 2c knowledge/admin pages, 2d chat/overlays). They merge/release as one bundle. A single 15k-line PR is rejected: it cannot be reviewed, bisected or reverted safely.

## D2 Shared foundation before page work

Overlay and notice primitives (`ConfirmDialog`, `StateNotice`, `ErrorNotice`, `Toast`, `NotificationBell`) and the drawer/dialog shells are consumed everywhere. They get one owner and land before page streams, so pages only compose them.

## D3 `index.css` has one owner

Parallel page workstreams never edit `index.css` or shared flow CSS. New page surfaces use token classes only. The cleanup owner removes legacy blocks after all consumers stop using them. This prevents the one guaranteed conflict file from being edited by four branches.

## D4 Class hooks stay as compatibility shims

The 57 E2E specs use `.col-k`, `.decision-queue`, `.decision-zone`, `mc-*`, etc. New markup keeps emitting them during the program. The cleanup removes CSS, not hooks; migrating specs to semantic selectors is a separate, later decision.

## D5 No new runtime dependency

shadcn registry components (`alert-dialog`, `table`, etc.) are copied code and allowed. No Sonner, DnD library or UI framework is added. Native DnD and the existing toast behaviour stay.

## D6 AriaPanel is split before restyling

`AriaPanel` is 1,921 lines. It is decomposed into presentational subcomponents while keeping its reducer/stream logic, then restyled. It gets its own PR review and the aria-* E2E gate.

## D7 All 57 E2E specs gate the release

The integration gate runs the full E2E suite, not only the touched specs. Per-workstream PRs run their subset; the bundle runs the union. No skipped new case and no loosened assertion.

## D8 2a remains the first workstream

The approved 2a spec/plan is unchanged; it becomes workstream A of the program. 2b/2c/2d designs are added before implementation starts, as requested.

## Revisit conditions

- If a page cannot be made file-disjoint (shared component used by two streams), move that component to the foundation owner.
- If the shared foundation cannot preserve a focus/Escape contract with shadcn shells, keep the legacy shell for that component and disclose it.
- If the full E2E suite cannot run in one integration pass, shard it exactly as CI does and report per-shard results.
