# Aria Memory and Context Pipeline — Design (2026-11-09)

Status: draft for operator review
Scope: Agent OS milestone 1 (S1), platform base
Related: S2 (full product redesign) and S3 (final real-scenario retest) are separate specs.

## 1. Problem

Aria Conductor is a governed agent orchestration platform. Four gaps block the next
step toward a personal agent:

1. There is no structured long-term memory. Conversation history is a transcript;
   knowledge is document-level; nothing models people, projects, repositories,
   machines, or durable preferences. Nothing separates a durable fact from the
   current state of a run.
2. Prompt context is assembled in three places with no shared contract:
   `AgentLoopEngine.buildMessages`, `AriaStreamService.buildInitialContext`, and
   `CorePromptComposition.joined`. There is no shared budget, ordering, sensitivity
   filter, or provenance record.
3. Aria has two prompts that already diverge. The sync path reads
   `agents.config.systemPrompt`, which is seeded create-only and patched by Flyway
   string replacement (V80); the streaming path uses `AriaService.buildSystemPrompt`
   in code. Any new instruction must be added twice, plus a migration.
4. `Sensitivity` is stored on knowledge items but is not enforced when a prompt is
   built. An APPROVED `RESTRICTED` item can enter any provider prompt today.

The platform's governance posture is an asset, not a problem. Memory must inherit it:
the model may propose, but only the operator can promote an inferred preference, and a
sensitive fact may only reach a provider whose ceiling allows it.

## 2. Operator decisions (2026-11-09 dialog)

1. Product direction: platform-first "governed agent OS". Engineering is the first
   pack, not the whole product.
2. Milestone shape: full platform base (kernel + envelope + API/MCP + backfill). The
   final Memory UI belongs to S2 and is built once, in the new design language; S1
   ships no production UI.
3. Data model: normalized entities, relations, and facts plus an append-only
   assertion log.
4. Recall: deterministic first. No embedding provider or vector store in M1; the
   recall interface must allow a later reranker without a contract change.
5. Governance: tiered. Operator writes are ACTIVE. Run-derived structural facts are
   ACTIVE with provenance. Conversation-inferred preferences and relations are
   PENDING until an operator approves them.
6. Deployment: single-operator, local-first. `owner_id` is reserved but not enforced
   in M1; no multi-tenant behavior.
7. Pre-work: refactor bundle P1-P8 plus the P10 seam, followed by a full regression
   E2E on a real scenario (Gate A) before the memory feature starts.
8. Acceptance: memory acceptance plus full regression (Gate B) after the feature;
   S2 gets its own design spec and a final full retest (Gate C).

## 3. Non-goals

- Memory UI, design system, onboarding, desktop/mobile surfaces (S2).
- Email, calendar, files, research, or messaging connectors.
- Embeddings, vector search, fuzzy entity merge, model post-training.
- Multi-user or tenant isolation, cloud sync.
- Proactive out-of-app notifications.
- Reworking the run loop, host process control, SDD workflow, or sandbox runtime.

## 4. Pre-work refactors

These land before the memory feature. Every item is behavior-preserving except where
explicitly stated.

| ID | Change | Evidence | Acceptance |
|---|---|---|---|
| P1 | One managed prompt per agent, assembled at runtime. Aria's policy text becomes a contributor in `act-aria`; the initializer's stored prompt is no longer the source for Aria. `managedPromptVersion` marks the row. | `AriaDefaultAgentInitializer.java:35,168`; create-only `:190-193`; `AriaService.java:187`; V80 uses SQL `REPLACE` | Sync and streaming share the same managed sections for the same agent/state; prompt-parity test; one-time migration for the Aria row |
| P2 | `ContextAssembler` in `act-execution` replaces all three assembly points. | `AgentLoopEngine.java:1015`; `AriaStreamService.java:124`; `CorePromptComposition.joined` | All three entry points write a manifest; no direct knowledge/skills injection remains in the engine |
| P3 | Typed `ContextSection` instead of a preformatted string. The knowledge contributor emits one section per approved item with ids, sensitivity, and estimates. | `KnowledgeContextProvider.java:17`; `KnowledgeService.java:381` | Budget, sensitivity, and provenance are testable per item; the old formatted-string path is deleted |
| P4 | Enforce sensitivity in the pipeline. Add `llm_providers.sensitivity_ceiling` (default `INTERNAL`); contributors filter by it and the assembler double-checks. | `Sensitivity` is only used by `ReviewSLAEnforcer` and setters | A `RESTRICTED` item never reaches a provider below that ceiling; the manifest records the filter; test at section and manifest level |
| P5 | One server-side history loader for sync and streaming. Client history is a fallback only when no server history exists, and is deduplicated. | sync `AriaService.java:299`; streaming `AriaStreamService.java:124-160` | Same conversation/state yields the same history on both transports; a test proves the client cannot replace server history |
| P6 | Bounded queries: last 20 runs per conversation, last 200 turns per run, top 5 approved knowledge. Add the supporting indexes. | `RunRepository.java:24`; `SessionTrajectoryRepository.java:15,20`; `KnowledgeService.java:374-380` | Tests with more rows than the limits prove the bound; no full-table load followed by subList |
| P7 | One tool catalog. `ToolCatalog` holds name, description, policy, and auto-approve flag; the policy registry, inventory test, auto-approve validation, and the generated tool prompt section read it. | `ToolPolicyRegistry.java:40`; `McpToolInventory.java:14`; `McpProperties.java:63-70`; Aria prompt copies | Adding a tool requires one implementation bean plus one catalog entry; policy, inventory, auto-approve, and prompt derive from it; parity tests cover all four |
| P8 | Delete the dead extraction constants in `AriaService` and route all inference through one proposal pipeline in `act-memory`. | `AriaService.java:44,49,50` have no call site; `SelfImprovementService` is the real pipeline | No dead constants; extraction has one entry point with an idempotency key |
| P10 | Extract prompt/history assembly from `AgentLoopEngine` (1440 lines) into the assembler. Keep the loop, streaming, core dispatch, and tool rounds in place. | file size; `buildMessages` and history loading | The engine no longer builds knowledge/skills/tool prompts; existing loop behavior tests stay green |

Gate A runs after P1-P8 and P10: full local gate plus the live SDD scenario on a clean
workspace. Gate A must show no behavior change in approval, stop-proof, token
accounting, and prompt output other than the intended unification.

## 5. Module layout and dependency rules

- `act-common`: SPI types only — `ContextContributor`, `ContextSection`,
  `ContextRequest`, `ToolCatalogProvider`, `ToolDescriptor`.
- `act-memory` (new): entities, repositories, services, recall, proposal review, and
  `MemoryContextContributor`. It depends on `act-common` only. It must not import
  `act-execution`, `act-knowledge`, or `act-aria`.
- `act-execution`: `ContextAssembler`, `ConversationHistoryLoader`, manifest
  persistence, and integration into sync, streaming, and core tasks. It has no compile
  dependency on `act-memory`; contributors are injected as Spring beans.
- `act-aria`: `AriaPromptContributor` (static policy) and thin chat adapters.
- `act-dashboard-api`: memory REST controllers.
- `act-mcp`: `ToolCatalog` and the memory tools.
- `act-app`: Flyway migrations, wiring, and the backfill runner.

An architecture test (ArchUnit or an equivalent Maven test) enforces the
`act-memory` dependency rule.

## 6. Data model

All tables carry `created_at`, `updated_at`, and a `version` column used for optimistic
locking.

### 6.1 `memory_entities`

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| owner_id | UUID NULL | reserved for a later multi-op scope |
| kind | VARCHAR(32) | open vocabulary: PERSON, PROJECT, REPO, MACHINE, AGENT, TASK, ORG |
| canonical_key | VARCHAR(255) | normalized key, unique with kind |
| display_name | VARCHAR(255) | |
| attributes | TEXT | JSON for non-time-validated display metadata |
| sensitivity | VARCHAR(16) | PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED |
| status | VARCHAR(16) | ACTIVE, MERGED, RETIRED |
| origin | VARCHAR(16) | OPERATOR, RUN, EXTRACTION |
| merged_into | UUID NULL | redirect target after a merge |

Unique: `(kind, canonical_key)`. Index: `(kind, status)`.

### 6.2 `memory_relations`

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| from_entity_id | UUID | logical reference, no cross-module FK |
| predicate | VARCHAR(64) | open vocabulary: WORKS_ON, OWNS, DEPENDS_ON, LOCATED_AT |
| to_entity_id | UUID | |
| status | VARCHAR(16) | PENDING, ACTIVE, RETIRED |
| origin | VARCHAR(16) | |
| sensitivity | VARCHAR(16) | |
| confidence | DECIMAL(3,2) | 0.00-1.00 |
| valid_from / valid_to | TIMESTAMP | null `valid_to` means current |

Indexes: `(from_entity_id, predicate)`, `(to_entity_id, predicate)`.

### 6.3 `memory_facts`

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| entity_id | UUID | |
| fact_key | VARCHAR(128) | e.g. `timezone`, `preferred_editor`, `default_branch` |
| value_text | TEXT | |
| value_type | VARCHAR(16) | STRING, NUMBER, BOOLEAN, ENUM |
| status | VARCHAR(16) | PENDING, ACTIVE, RETIRED |
| origin | VARCHAR(16) | |
| sensitivity | VARCHAR(16) | |
| confidence | DECIMAL(3,2) | |
| valid_from / valid_to | TIMESTAMP | |

Index: `(entity_id, fact_key, valid_to)`. One ACTIVE fact per `(entity_id, fact_key)` at
`now` is enforced in the service with an optimistic version check; no database partial
unique index because H2 and MariaDB differ.

### 6.4 `memory_assertions` (append-only)

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| subject_type | VARCHAR(16) | ENTITY, RELATION, FACT, LINK |
| subject_id | UUID | |
| action | VARCHAR(16) | CREATE, UPDATE, SUPERSEDE, RETIRE, MERGE |
| actor_type | VARCHAR(16) | OPERATOR, SYSTEM, RUN, EXTRACTION |
| actor_ref | VARCHAR(255) | run id, conversation id, or operator |
| before_json / after_json | TEXT | audit payload; no secrets |
| source_ref | VARCHAR(255) | provenance pointer |
| created_at | TIMESTAMP | |

Index: `(subject_type, subject_id, created_at)`. Rows are never updated or deleted
except by an audited purge.

### 6.5 `memory_knowledge_links`

Links a knowledge item to an entity without moving the document out of `act-knowledge`.

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| entity_id | UUID | |
| knowledge_item_id | UUID | logical reference |
| status | VARCHAR(16) | PROPOSED, ACTIVE, RETIRED |
| decided_at / decided_by | TIMESTAMP / VARCHAR | |

Unique: `(entity_id, knowledge_item_id)`.

### 6.6 `context_manifests`

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| run_id | UUID | |
| turn_number | INT | one row per assembly within the run |
| conversation_id | VARCHAR NULL | |
| agent_id | UUID | |
| provider_id | UUID | |
| adapter | VARCHAR(16) | SYNC, STREAM, CORE |
| sections_json | TEXT | section ids, source ids, counts, estimates |
| dropped_json | TEXT | section ids and reasons |
| degraded_json | TEXT | unavailable sections and reasons |
| max_chars | INT | |
| estimated_tokens | INT | |
| created_at | TIMESTAMP | |

Unique: `(run_id, turn_number)`. Full prompt content is never stored.

## 7. Governance matrix

| Origin | Allowed writes | Status | Review |
|---|---|---|---|
| OPERATOR | entity, fact, relation, link | ACTIVE | no |
| RUN | structural facts only: repo, branch, issue, stack, workspace | ACTIVE, provenance required | no |
| EXTRACTION | preferences, identity facts, relations, new links | PENDING | operator |
| backfill | entities from agents and repository URLs | ACTIVE entities | links PROPOSED |

Rules:

- An agent run cannot approve its own proposal. `decide_memory_proposal` is
  OPERATOR_ONLY and absent from Aria's toolset.
- `remember_fact` called by a worker creates a PENDING proposal; the same action from
  the operator REST surface writes ACTIVE.
- Supersede is one transaction: the old ACTIVE fact gets `valid_to=now`, the new fact
  becomes ACTIVE, the version check fails with 409 on a stale read, and both
  assertions are written.
- No silent overwrite. Two overlapping active values require an explicit supersede.
- Forget retires a fact (`valid_to=now`) and writes an assertion. Hard purge is
  operator-only and audited.
- Entity merge is operator-only. A merged entity keeps a redirect (`merged_into`) and
  its facts are re-pointed in the same transaction.
- Values that look like credentials are rejected with a validation error that names
  the field, not the value. Memory never stores secrets.
- Fact types that are current state (run status, CI status) are not mirrored. The
  envelope reads live state from the domain tables through the state contributor.

## 8. Context pipeline

### 8.1 Contract

`ContextSection`: `id`, `title`, `content`, `sensitivity`, `sourceIds`,
`estimatedChars`, `estimatedTokens`, `truncated`, `degradedReason`.

`ContextRequest`: `runId`, `conversationId`, `agentId`, `adapter` (SYNC, STREAM, CORE),
`goal`, `providerId`, `maxSensitivity`, `maxChars`, `workspaceRef`, `priorHistory`.

`ContextContributor`: `id()`, `order()`, `required()`, `contribute(request)`.

Canonical section order: identity, policy, goal, memory, knowledge, skills, state,
workspace, tools, history. The goal section carries the current request once; history
is rendered last.

- Identity: the operator's display identity and locale (name, timezone, language).
  Durable preferences live in memory, not here.
- Policy: the managed agent prompt (Aria policy text or another agent's configured
  policy), including governance rules.
- Goal: the current request, rendered once.
- Memory: the ranked recall slice.
- Knowledge: approved knowledge items, one section per item.
- Skills: enabled SKILL-stage skill templates assigned to the agent.
- State: live domain state read from the domain tables (running runs, pending
  approvals, kanban counts); never mirrored into memory.
- Workspace: repository, branch, and workspace reference for the run.
- Tools: the generated tool catalog section (names, policies, one-line descriptions).
- History: the bounded conversation transcript.

Contributors may return no sections when they do not apply (for example, the Aria
policy contributor on a non-Aria agent). Identity is read from the `system_config`
keys `memory.operator.name`, `memory.operator.timezone`, and
`memory.operator.language`; empty values render nothing.

Required sections are identity for every agent, and policy for an agent with a managed
or configured prompt. A missing required section fails the run with a named error.
Optional sections degrade.

### 8.2 Assembly

1. Resolve the provider's `sensitivity_ceiling`; default `INTERNAL`.
2. Collect sections. Each contributor filters its own items by `maxSensitivity` and
   returns typed sections with source ids.
3. Double-check the declared section sensitivity in the assembler; drop and record any
   section over the ceiling.
4. Order by the canonical order.
5. Apply per-section caps and a global `maxChars` (default 24,000 characters):
   identity 2,000; policy 6,000; goal 4,000; memory 4,000; knowledge 4,000; skills
   3,000; state 2,000; workspace 2,000; tools 3,000; history 8,000. If the total still
   exceeds the global cap, scale the optional sections down proportionally.
6. Drop order when still over budget: history (oldest first), workspace, skills,
   knowledge, memory. Never drop identity, policy, or goal. Record every drop.
7. Render each section as a `## <title>` block separated by one blank line.
8. Persist the manifest with ids, counts, estimates, drops, and degraded reasons.
9. Return the envelope and the rendered prompt.

Token estimates use `ceil(chars / 3.5)` and are compared with the provider's reported
usage after the run; the manifest records both the estimate and the actual usage when
available.

### 8.3 History

`ConversationHistoryLoader` loads the last 20 runs of a conversation, the last 200
turns per run, and keeps the most recent 40 user/assistant messages. Failed runs keep
their existing synthetic note. Both transports call it. If the server has no rows for
the conversation, streaming falls back to the client-provided history, capped at 40
messages, and records `degraded:client-history`. The current request is never repeated:
it enters as the run's prompt seed.

### 8.4 Deterministic recall

1. Extract candidate entity mentions from the goal and the last three user turns:
   canonical-key exact, alias exact, and case-insensitive token-boundary match. Add
   focus entities from the run context (repository, agent, task).
2. Load matching ACTIVE entities. Expand one hop of ACTIVE relations.
3. Load ACTIVE facts whose validity contains `now`, filtered by the provider ceiling.
4. Rank by mention source, relation distance, source authority
   (OPERATOR > RUN > EXTRACTION), recency, then confidence; break ties by id.
5. Cap at 12 entities and 40 facts. Render each fact as
   `kind/name: key = value (source, valid since)`.
6. Same inputs must produce the same output.
7. The recall budget is 200 ms; a timeout returns an empty memory section with a
   degraded reason and never blocks the run.

### 8.5 Consumers

- `AgentLoopEngine.buildMessages` calls the assembler once per run/turn. System
  sections come first, then history, then the current request.
- `AriaStreamService` has no prompt of its own; it delegates to the same path.
- `CorePromptComposition` receives the rendered system block and the history from the
  assembler; it no longer performs knowledge or skills injection.
- The assembler writes one manifest per assembly (one row per turn) with
  `turn_number`; the run's prompt content is never stored.

## 9. API and MCP

REST under `/api/v1/memory`:

- `GET /entities?kind=&q=&status=&limit=`
- `POST /entities`
- `GET /entities/{id}` — entity, facts, relations, links, assertion timeline
- `PATCH /entities/{id}` — rename, alias, merge, retire
- `POST /entities/{id}/facts`
- `POST /facts/{id}/supersede`
- `POST /facts/{id}/retire`
- `GET /proposals?status=PENDING`
- `POST /proposals/{id}/decide`
- `GET /recall?q=&limit=` — read-only preview with provenance
- `GET /runs/{id}/context-manifest` — every assembly for the run, ordered by turn,
  with section ids, drops, and degraded reasons

Responses carry ids, provenance, sensitivity, and validity. Errors use the existing
`ok`/`errorType`/`message` envelope with explicit codes: `VALIDATION`, `NOT_FOUND`,
`CONFLICT`, `FORBIDDEN`.

MCP tools and policies (all registered through `ToolCatalog`):

| Tool | Policy | Behavior |
|---|---|---|
| `remember_fact` | WORKER_WRITE | writes a PENDING proposal unless the actor is the operator |
| `recall_memory` | WORKER_READ | returns ranked facts/entities with provenance |
| `list_memory_entities` | WORKER_READ | list/filter |
| `get_memory_entity` | WORKER_READ | entity detail |
| `forget_fact` | OPERATOR_ONLY | retire a fact |
| `list_memory_proposals` | WORKER_READ | review inbox |
| `decide_memory_proposal` | OPERATOR_ONLY | approve/reject; absent from Aria's toolset |
| `get_context_manifest` | WORKER_READ | section ids and drops for a run |

The Aria prompt's tool section is generated from the catalog and excludes OPERATOR_ONLY
entries. The parity test asserts that every registered MCP tool has a policy, that the
inventory equals the catalog, that the auto-approve list is a subset of WORKER_READ
tools, and that the generated prompt covers exactly the allowed set.

## 10. Backfill and migrations

- V81 creates the six tables, their indexes, and
  `llm_providers.sensitivity_ceiling` with default `INTERNAL`.
- V82 is a data-only migration for the Aria managed-prompt marker:
  `agents.config.managedPromptVersion=1`.
- A one-time idempotent `MemoryBackfillRunner` (default enabled, completion recorded in
  `system_config`) creates:
  - one AGENT entity per non-retired agent, canonical key `agent:<uuid>`;
  - one REPO entity per distinct repository URL observed in runs, canonical key
    normalized URL;
  - knowledge links only where the knowledge item name exactly matches an entity
    display name; links are PROPOSED.
- The runner never derives facts from free text and never guesses a relation.
- The knowledge base is not moved: documents stay in `act-knowledge`; only links are
  added.

## 11. Failure, concurrency, and security

- Memory writes run in their own transaction; no LLM call is made inside a database
  transaction.
- Recall timeout is 200 ms; a timeout degrades the memory section.
- Manifest persistence failure is logged and never fails the run.
- PENDING rows are never selected by the prompt path (queries filter ACTIVE only).
- Optimistic version checks return 409 on stale supersede or merge.
- Proposal idempotency uses a unique hash over origin, subject, predicate, value hash,
  and source reference; a repeated extraction is a no-op.
- Sensitivity ceiling defaults to INTERNAL. A provider must be explicitly raised to
  receive CONFIDENTIAL or RESTRICTED sections. This closes the current gap where
  APPROVED RESTRICTED knowledge could enter any prompt; it is an intended behavior
  change and is called out in the release notes.
- Secret-like values are rejected at the write boundary; error messages name the
  field, not the value. Redaction rules are shared with the existing log redaction.
- Hard purge is operator-only, audited, and removes the subject plus its assertions.
- `owner_id` is reserved; M1 is single-operator.

## 12. Testing and gates

Unit:

- canonical keys, aliases, and merge redirects;
- validity windows and supersede conflict;
- the governance matrix (origin x type -> status);
- recall ranking and determinism;
- budget scaling, drop order, and never-dropped sections;
- sensitivity filtering and the manifest record.

Repository slice (H2 MySQL mode plus Flyway):

- unique constraints and indexes;
- bounded queries with more rows than the limits;
- backfill idempotency;
- upgrade from the prior schema.

Integration:

- sync, stream, and core each persist a manifest;
- PENDING is never in a prompt; approval makes it visible on the next turn;
- a provider ceiling filters at both contributor and assembler levels;
- sync and stream produce the same history and shared sections;
- the Aria prompt and the tool catalog are in parity;
- the architecture test proves `act-memory` has no domain-module import.

Contract:

- MCP catalog, policy, auto-approve, and prompt parity, plus the live registered tool
  set equal to the catalog;
- REST error codes and response shapes.

Gate A, after the pre-work refactors:

- full local gate (unit, failsafe, frontend, MCP, bridge, container runtime as
  applicable);
- live SDD real scenario on a clean workspace;
- hosted CI green at the Gate A HEAD;
- evidence: command, counts, run id, and the exact HEAD, recorded in the delivery
  ledger. Expected counts are measured at the HEAD of the run, not hardcoded here.

Gate B, after the memory feature:

- a fresh database: teach facts, restart, recall in a new conversation;
- approve a PENDING proposal and observe it on the next turn;
- a RESTRICTED fact is absent from a provider with ceiling INTERNAL and present after
  the ceiling is raised;
- the full regression suite plus the SDD real scenario;
- hosted CI green at HEAD.

Gate C, after S2: the redesigned UI at desktop and mobile plus the same real scenario.

## 13. Acceptance criteria (S1)

1. A new conversation after a restart recalls an ACTIVE operator fact without
   re-teaching; the manifest lists its id and source.
2. A PENDING extracted preference never enters a prompt; after approval it appears on
   the next turn.
3. A RESTRICTED fact is absent from a provider with ceiling INTERNAL; it is present
   with ceiling RESTRICTED; the manifest records the filter.
4. Sync, stream, and core each persist a manifest; the same request and state yield
   the same shared sections and history.
5. Over-budget input drops sections in the documented order and records every drop.
6. `remember_fact` from a worker creates PENDING; operator REST writes ACTIVE;
   `decide_memory_proposal` refuses a worker actor.
7. The backfill is idempotent: a second run changes nothing.
8. Gate A and Gate B pass; hosted CI is green at HEAD; the SDD chain has no
   regression.
9. `act-memory` has no compile dependency on another domain module.

## 14. Residual risks

- Deterministic recall misses paraphrases and synonyms. A small fixture set measures
  it in S1; a reranker can be added behind the recall interface later.
- Exact canonicalization creates duplicate entities. Merging is manual in S1 and gets
  a UI in S2.
- Moving Aria's managed prompt to code stops the stored prompt from taking effect.
  Operator edits remain in the row but are ignored; this is called out in the release
  notes.
- The tool-catalog refactor touches the MCP governance surface. It requires an
  independent review and the full parity suite before merge.
- The sensitivity ceiling default changes behavior for existing RESTRICTED knowledge;
  intended, but must be announced.
- S1 is only observable through the API and MCP until S2 ships the Memory UI.
- Multi-user isolation is not implemented; `owner_id` is a placeholder.

## 15. S2 and S3 boundaries

S2 is a separate design spec. It runs its own brainstorming and prototype round:
three HMI directions, selection, design language, IA, onboarding, desktop and mobile,
and all six state classes. Its first vertical slice is the Memory page, consuming the
S1 API; approvals and alerts move into the new cockpit. S2 may not change the S1
schema without a new spec.

S3 is the final acceptance after S2: the full regression ladder, the live SDD
scenario, the memory scenario, and an artifact-first UI check at desktop and mobile
viewports, with hosted CI green at HEAD.
