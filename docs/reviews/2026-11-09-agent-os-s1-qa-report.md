# QA report — S1 memory and context base (feat/agent-os-memory)

This report is the release-QA view of S1. Every row is filled only from a command that
was actually run, at the commit named in the row. Rows that are not yet executed say so;
they are never inferred from a neighbouring green lane.

## Acceptance checklist

| # | Requirement (spec) | Evidence required | Status |
|---|---|---|---|
| 1 | Typed context SPI exists in act-common and fails closed (spec 8.1) | Contract tests green at the merged HEAD | VERIFIED — act-common 145/0/0/0 in the Gate B full gate at `7f6647ab` |
| 2 | One assembler; sync, stream and core share it (spec 8.5) | Assembler tests plus a live sync and stream turn with identical section ids for the same state | NOT VERIFIED — no live sync/stream pair was captured at Gate B |
| 3 | No client-supplied history on streaming turns (spec 8.5) | Test that a streamed turn ignores client history; live check | NOT VERIFIED — not evaluated at Gate B |
| 4 | One manifest per turn, no prompt content stored (spec 6.6) | Manifest rows counted against run turns; section JSON contains ids and estimates only | VERIFIED live — 6 runs, 6 rows, whitelisted section metadata, zero prompt/fact text (Gate B evidence §3.7) |
| 5 | Sensitivity ceiling drops sections above it (spec 7) | Unit test per level; live RESTRICTED-vs-INTERNAL check at Gate B | VERIFIED live — the RESTRICTED fact is absent at INTERNAL and present after raising the active provider ceiling (Gate B evidence §3.6) |
| 6 | Memory kernel schema and assertion log (spec 6) | V81 applied on a fresh H2; six tables; one assertion row per write; MariaDB compatibility checked by either a container run or a documented SQL review if no database container is used | VERIFIED on fresh H2 — V82 schema, six tables, one CREATE assertion per write; MariaDB NOT VERIFIED |
| 7 | Tiered governance: PENDING never reaches a prompt (spec 7) | Worker `remember_fact` writes PENDING; next turn omits it; approve; following turn includes it | VERIFIED live — the real MCP worker actor wrote `75d9fa1c…` PENDING (EXTRACTION); absent until approved, present after (Gate B evidence §3.5) |
| 8 | Deterministic recall with provenance (spec 5) | `GET /recall` returns the taught facts with source ids; no embedding dependency | VERIFIED live — entity and fact ids, origin and sensitivity returned (Gate B evidence §3.2); the literal `q=timezone` is empty by spec §8.4 entity-mention recall (deviation D-B2) |
| 9 | One tool catalog; inventory parity; operator-only absent from Aria's toolset (spec 9) | Parity test green; generated prompt excludes `decide_memory_proposal` and `forget_fact` | VERIFIED — act-mcp 209/0/0/0; the live Aria manifest lists the six worker memory tools and neither operator-only tool (Gate B evidence §3.7) |
| 10 | Backfill is idempotent (spec 10) | Run twice; row counts identical; no duplicate links | NOT VERIFIED — backfill not exercised at Gate B |
| 11 | Aria managed prompt is code-owned (P1) | V82 applied; prompt section rendered by the contributor; no DB template read | PARTIAL — V82 applied and the manifest renders `policy.aria` with source `aria.prompt`; the "no DB template read" arm was not independently observed |
| 12 | Bounded history and knowledge queries (P6) | Trajectory top 200, runs top 20, count-only approvals; long history degrades with a reason | NOT VERIFIED — no long-history or degradation run at Gate B |
| 13 | Module boundaries (ArchUnit) | `act-memory` depends only on `act-common`; no knowledge/execution dependency | VERIFIED by tests — act-memory 162/0/0/0 in the Gate B full gate includes the boundary guard |
| 14 | No secret or `.env` read; no credential in logs or commit | Grep of the diff and the run logs | VERIFIED for this evidence — the env file is only sourced in-process and never printed; backend logs contain no `sk-` prefix, Bearer header or env assignment; the Gate B commit adds documentation only |

## Gate A evidence

Sourced from the committed Gate A evidence and ledger; not re-run in the Gate B task.

- Integration HEAD: `62020f64` (Gate A close; the feature branch advanced to `7f6647ab`
  for Gate B).
- Full local gate command and counts: green in the clean-room at `62020f64`
  (`mvn -B -pl act-execution,act-agent,act-aria,act-app -am verify` failsafe 123/0/0/0 in
  act-app; dashboard 662/662 plus build; MCP server 157/157; bridge 62/62 with a rebuilt
  dist).
- Live SDD scenario: chain `588bcde6-6c48-4713-8c7c-0c7d248df72b`, 9 runs (BA, four DEV,
  four QA), 10 `context_manifests` rows for the ten executed turns, `StopProof` absent,
  approval decision 200; the chain ended FAILED because the governed HOST toolset denies
  write/shell for non-Aria agents (recorded, not simulated).
- Hosted CI run id and job counts: run `42656087082` at `62020f64`, 17/17 success. The
  earlier run `42652224806` at `039d3d10` was 16/17 because of F-A1/F-A2, fixed test-only at
  `3b352d96` (merged `62020f64`).
- Classified failures and fixes: F-A1/F-A2 product test debt, fixed test-only; the
  opencode native-executable blocker resolved; the SANDBOX arm blocked by the podman
  machine's `memory.max` limitation (environment).

## Gate B evidence

Full transcript: `docs/reviews/evidence/2026-11-09-gate-b/gate-b-evidence.md`.

- Fresh-database memory scenario transcript: VERIFIED live at `7f6647ab` on a fresh H2
  (`gate-b-db`, V82) with the fat jar and the native opencode HOST core.
  PERSON `a3255334-3895-4db7-93b8-c622731cb461` (timezone `4a8e66a2…`, preferred_editor
  `8c66b2e9…`) and REPO `12a8bfbe-a98b-4c85-953d-6158d64a5097`; recall returns both fact
  ids with origin and sensitivity; a restart at 17:49:51Z keeps them; six Aria runs each
  produced one manifest row (6/6, unique key intact) whose memory section carries the fact
  ids and no prompt text. PENDING `75d9fa1c-9349-454d-af4a-70bbd35a8a6a` was written by the
  real MCP `remember_fact` worker actor (EXTRACTION, actor_ref = run
  `ba61eced-544f-48a5-9554-e1a41087f1ea`; no H2 fallback), absent from the next turn and
  manifest, approved over REST at 17:51:05Z and present on the following turn. The
  RESTRICTED fact was absent at the INTERNAL ceiling and present after
  `llm_providers.sensitivity_ceiling='RESTRICTED'` plus a restart.
- Full regression: RED. `mvn -B -pl act-execution,act-agent,act-aria,act-app -am verify`
  fails in act-app failsafe on F-B1 (`cleanup-all.sql:11` truncates `context_manifests` in
  the only V65-pinned IT). Surefire 2919/0/0/8, failsafe 263/0/1/6. Non-Maven surfaces are
  green (dashboard 662/662 and build, MCP server 157/157, bridge 62/62).
- Live SDD rerun: NOT VERIFIED — not run in this task.
- Hosted CI run id and job counts: NOT VERIFIED — no dispatch in this task.

## Residual risks and follow-ups

- F-B1 (blocker, owner: a lane that owns the test script): `cleanup-all.sql:11` truncates
  V81's `context_manifests` in `ReviewAskRepairMigrationIntegrationTest`, which pins Flyway
  to V65. Fix by splitting the pinned-target cleanup or making the truncation
  schema-aware, then re-run the full gate. No product code, test or migration was changed
  by Release QA.
- Hosted CI at `7f6647ab` and the live SDD rerun are NOT VERIFIED; Gate B cannot be called
  green on local evidence alone.
- MariaDB execution of V81/V82 remains unverified (H2 only).
- The Gate A live SDD deliverable remains blocked by the pre-existing governed HOST
  toolset (non-Aria agents get read-only tools; the platform MCP is Aria-only) and the
  SANDBOX arm by the podman machine's `memory.max` limitation; qoder still lacks a PAT.
- Plan-text drift: the Gate B examples use `key`/`value` fact fields and a `q=timezone`
  recall query; the live contract is `factKey`/`valueText`, and recall is entity-mention
  based (spec §8.4). Both were applied correctly in the live run and recorded as
  deviations D-B1/D-B2.
- The tool-catalog prompt section lists tool names only (deliberate A1 review decision);
  a reader expecting per-tool descriptions will not find them.

## Verdict

BLOCKED at Gate B (2026-11-10). The live memory acceptance arm is VERIFIED at `7f6647ab`
(teach, restart, recall, manifest, PENDING governance, RESTRICTED ceiling), but the full
local gate is RED on F-B1 and the hosted CI and live SDD rerun are NOT VERIFIED. No release
verdict is issued until F-B1 is fixed and the gate is re-run green at the resulting HEAD.
