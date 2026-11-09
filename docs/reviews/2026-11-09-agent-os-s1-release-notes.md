# Release note — S1 memory and context base (feat/agent-os-memory)

Status: in delivery. This note is filled alongside the gates; every claim below is
either shipped code at the recorded commit or a gate entry in
`docs/reviews/2026-11-09-agent-os-s1-ledger.md`. Unfilled evidence stays visibly
unfilled until the gate that produces it runs.

## What this release is

S1 is the platform base for a governed agent OS: a typed context pipeline that every
LLM entry point shares, a deterministic long-term memory kernel with tiered governance,
and one tool catalog. It is not a product redesign and it ships no production UI; the
Memory page is the first S2 vertical slice.

## What ships

1. Context SPI in `act-common` (`io.aria.conductor.common.context`): `ContextAdapter`,
   `ContextMessage`, `ContextSection`, `ContextRequest`, `ContextContributor`.
   Fail-closed defaults: missing sensitivity is `INTERNAL`; missing budget is the global
   24,000-character cap.
2. A context assembler and ordered contributors in `act-execution`, replacing the three
   previous prompt-assembly points. Sync and streaming runs assemble the same envelope
   for the same state; the client no longer supplies history on streaming turns.
3. Sensitivity enforcement: each section carries a sensitivity, and a provider's
   new `sensitivityCeiling` (default `INTERNAL`) drops sections above it. Dropped and
   degraded sections are recorded, not silently lost.
4. Memory kernel in a new `act-memory` module: normalized `memory_entities`,
   `memory_relations`, `memory_facts`, append-only `memory_assertions`, and
   `memory_knowledge_links`. Every write leaves an assertion row.
5. Tiered governance: operator writes and run-derived structural facts are `ACTIVE`;
   conversation-inferred preferences/relations are `PENDING` until an operator
   approves them. Pending rows never enter a prompt.
6. Deterministic recall: canonical keys, entity kinds, and fact keys; no embeddings or
   vector store in S1.
7. One tool catalog: `ToolPolicy`, `ToolDescriptor`, and `ToolCatalogProvider` in
   `act-common`; the MCP policy registry, inventory, auto-approve validation, and the
   generated tool prompt section read the same classification.
8. `context_manifests`: one row per run turn recording the sections, budget, dropped
   and degraded reasons, so a prompt can be audited after the fact.
9. REST surface under `/api/v1/memory` and MCP tools for entity, fact, relation, and
   proposal work, including `remember_fact` and `decide_memory_proposal`.

## Schema and configuration changes

- Flyway `V81__memory_kernel.sql`: six tables and
  `llm_providers.sensitivity_ceiling VARCHAR(16) NOT NULL DEFAULT 'INTERNAL'`.
- Flyway `V82__aria_managed_prompt.sql`: the Aria managed prompt becomes code-owned
  instead of a database template.
- Existing rows keep `INTERNAL` after V81; raising a ceiling is an explicit operator
  action. No provider-ceiling REST surface ships in S1 (see the ledger for the accepted
  operating procedure).

## Upgrade notes

- Apply V81/V82 before starting the app; `ddl-auto: validate` fails fast on a stale
  schema. Back up the H2 file or the MariaDB database first.
- A pre-V81 database has no memory rows; the backfill links existing knowledge items to
  memory entities idempotently and can be re-run.
- The Aria system prompt now comes from code; an operator-edited prompt row is no longer
  read. Review any local prompt customisation before upgrading.

## Behaviour changes to watch

- Prompt text is byte-different from the previous release even where behaviour is meant
  to be identical: sections are now ordered contributors with titles.
- Streaming turns no longer trust client-supplied history; the server reconstructs it
  from the run and its conversation.
- Knowledge and run-history queries are bounded (top 200 trajectory rows, top 20 runs,
  count-only approval queries); very long conversations see a truncated history section
  with a degraded reason instead of unbounded growth.

## Verification evidence (filled at each gate)

- Wave 0 contract gate: pending.
- Wave 1 wave gate: pending.
- Gate A (full local gate, live SDD scenario, hosted CI at HEAD): pending.
- Wave 2 wave gate: pending.
- Gate B (fresh-database memory acceptance, full regression, live SDD, hosted CI):
  pending.
- Residual risks and follow-ups: pending.
