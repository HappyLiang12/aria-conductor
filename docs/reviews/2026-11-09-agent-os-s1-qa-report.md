# QA report — S1 memory and context base (feat/agent-os-memory)

This report is the release-QA view of S1. Every row is filled only from a command that
was actually run, at the commit named in the row. Rows that are not yet executed say so;
they are never inferred from a neighbouring green lane.

## Acceptance checklist

| # | Requirement (spec) | Evidence required | Status |
|---|---|---|---|
| 1 | Typed context SPI exists in act-common and fails closed (spec 8.1) | Contract tests green at the merged HEAD | pending |
| 2 | One assembler; sync, stream and core share it (spec 8.5) | Assembler tests plus a live sync and stream turn with identical section ids for the same state | pending |
| 3 | No client-supplied history on streaming turns (spec 8.5) | Test that a streamed turn ignores client history; live check | pending |
| 4 | One manifest per turn, no prompt content stored (spec 6.6) | Manifest rows counted against run turns; section JSON contains ids and estimates only | pending |
| 5 | Sensitivity ceiling drops sections above it (spec 7) | Unit test per level; live RESTRICTED-vs-INTERNAL check at Gate B | pending |
| 6 | Memory kernel schema and assertion log (spec 6) | V81 applied on a fresh H2; six tables; one assertion row per write; MariaDB compatibility checked by either a container run or a documented SQL review if no database container is used | pending |
| 7 | Tiered governance: PENDING never reaches a prompt (spec 7) | Worker `remember_fact` writes PENDING; next turn omits it; approve; following turn includes it | pending |
| 8 | Deterministic recall with provenance (spec 5) | `GET /recall` returns the taught facts with source ids; no embedding dependency | pending |
| 9 | One tool catalog; inventory parity; operator-only absent from Aria's toolset (spec 9) | Parity test green; generated prompt excludes `decide_memory_proposal` and `forget_fact` | pending |
| 10 | Backfill is idempotent (spec 10) | Run twice; row counts identical; no duplicate links | pending |
| 11 | Aria managed prompt is code-owned (P1) | V82 applied; prompt section rendered by the contributor; no DB template read | pending |
| 12 | Bounded history and knowledge queries (P6) | Trajectory top 200, runs top 20, count-only approvals; long history degrades with a reason | pending |
| 13 | Module boundaries (ArchUnit) | `act-memory` depends only on `act-common`; no knowledge/execution dependency | pending |
| 14 | No secret or `.env` read; no credential in logs or commit | Grep of the diff and the run logs | pending |

## Gate A evidence

- Integration HEAD: pending
- Full local gate command and counts: pending
- Live SDD scenario: chain id, run count, manifest count, `StopProof` check: pending
- Hosted CI run id and job counts: pending
- Classified failures and fixes: pending

## Gate B evidence

- Fresh-database memory scenario transcript (teach, restart, recall, PENDING approval,
  RESTRICTED ceiling): pending
- Full regression and live SDD rerun: pending
- Hosted CI run id and job counts: pending

## Residual risks and follow-ups

- Pending: list every residual the gates surface, with an owner (S2 or a named follow-up).

## Verdict

Pending. No verdict is issued before Gate B is green at HEAD.
