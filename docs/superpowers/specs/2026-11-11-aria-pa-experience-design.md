# Aria PA Experience — Design (2026-11-11)

Status: draft for operator review
Scope: make Aria good to use as a daily personal assistant, not merely usable. Fixes
findings F1-F11 from the live PA acceptance run on 2026-11-11 against merged main
`ec995a98`.
Related: amends `2026-10-03-run-admission-limit-design.md` (D2 and its accepted Aria
queueing UX change) and the 2026-11 memory-read governance decision pinned by
`ToolCatalogParityTest.theMemoryReadToolsAreWorkerReadButNeverAutoApproved`. Builds on
`2026-11-09-aria-memory-context-design.md` (S1, merged). S2's full product redesign stays
a separate spec; the Memory page here is deliberately minimal.

## 1. Problem

The 2026-11-11 live PA acceptance exercised Aria as a personal assistant: a five-turn
conversation (briefing, teach preferences, request self-approval, recall, plan the
afternoon), a three-turn memory governance probe, and one turn typed into the dashboard
panel. Nine runs COMPLETED and one sync attempt was cancelled. The assistant's judgement
and the governance kernel held: answers were tool-grounded, self-approval was refused
with `FORBIDDEN: Operator authority required`, and a PENDING fact was provably absent from
the prompt until an operator promoted it.

The experience around that judgement did not hold. Eleven findings, all measured on the
live stack:

- A turn that needs an operator decision stalls silently in the chat panel for up to ten
  minutes, because the panel has no in-flow approval surface and only checks the review
  queue after its client timeout.
- One parked Aria run occupies the only Aria admission slot, so every later turn queues
  behind a question nobody can see.
- The synchronous chat endpoint answers a parked run with a generic text stub after
  exhausting its bounded wait, instead of returning the question.
- Restored panel history renders raw `tool_call` / `tool_result` JSON as assistant chat
  bubbles.
- Approval cards label mutating and operator-only platform MCP tools as `risk READ`.
- Every qoder run reports `totalTokensUsed = 0`, so the dashboard and the briefing assert
  a zero cost that was never measured.
- The panel loads its timeline once on mount and never refreshes, so turns created by
  another client are invisible until a reload.
- Aria made two false capability claims ("no memory-review tool is available", "no
  workflow template is approved").
- OPERATOR_ONLY tools are hidden from the generated prompt section but still advertised
  to Aria's core, which attempted one.
- There is no way to create the first memory entity from any assistant or UI surface.
- There is no memory UI at all. Aria repeatedly directed the operator to a
  "dashboard memory-review queue" that does not exist; the only working promotion path
  was a raw REST call.

The last two mean the PENDING-to-ACTIVE governance loop that S1 shipped is not closed for
a normal operator. That is the core of this design: the governance story is only a
feature if the operator can actually act on it.

## 2. Operator decisions (2026-11-11 dialog)

1. **One spec, three waves.** Wave A conversation flow and approvals, Wave B truth
   surface, Wave C memory operation surface and authority honesty. Each wave has its own
   gate and live acceptance.
2. **Per-conversation serialization with a global Aria cap.** A parked run blocks only
   its own conversation; other conversations proceed. Releasing the slot of a parked run
   was rejected: the run still owns a live core process, so releasing it would make the
   resource accounting lie.
3. **Amend the 2026-11 memory-read decision.** The five memory read tools become
   auto-approved for the Aria assistant's coordinated runs. Writes and operator-only
   tools keep a per-call operator ask. The INTERNAL sensitivity ceiling is unchanged and
   manifests still record which facts entered a prompt. This reverses a decision pinned
   by a test, so the pin, the catalog comment and the delivery ledger are amended in the
   same change.
4. **Collapsed activity strip.** Tool activity renders as one collapsible strip per turn;
   the final answer stays a clean Markdown bubble. Approval and clarification cards live
   in the same position.
5. **In-panel proposal cards plus a minimal Memory page.** Memory proposals surface as
   operator decision cards in the chat; a small operator-only page provides durable
   browse/create/decide. `remember_fact` gains the ability to propose a PENDING entity so
   Aria can onboard a new operator inside the conversation.
6. **Keep OPERATOR_ONLY tools exposed; fix the prompt and the refusal.** Splitting the
   MCP surface or filtering `tools/list` per actor was rejected for this program: the
   tool list is built once from all `McpTool` beans and only call-time identity is
   guarded, so per-actor listing needs a second server instance or an SDK change. The
   refusal becomes actionable and the prompt stops claiming the tools are absent.
7. **SSE-first, WS for backup, REST for reconciliation.** The engine emits the ask and
   clarification signals on the run's own ordered stream; the existing WebSocket events
   cover a dead stream; REST reconciles on mount, focus and reconnect.

Two refinements made after the section approvals, both explicit:

- **B1 moves into Wave A.** The approval card consumes the risk tier and tool policy, so
  shipping the card before the label fix would display a wrong tier on day one.
- **Reuse `aria.runs.aria-reserved` instead of adding a new property.** Section 1 named a
  new `aria.runs.max-active-conversations`. The 2026-10-03 admission spec already defines
  `aria-reserved` as the concurrent-Aria-run cap, so a second knob would duplicate it.
  This design raises its default from 1 to 3 and adds a per-conversation mutex. The
  operator may reject this refinement at spec review.

## 3. Non-goals

- No S2 product redesign, design-system overhaul, desktop or mobile surface.
- No memory merge/redirect UI, relation graph, link management, or sensitivity-ceiling
  editing (S1 ships no ceiling REST surface).
- No embedding recall, reranker, or vector store.
- No new MCP transport, no SDK upgrade, no per-actor `tools/list` filtering.
- No multi-operator or multi-tenant behavior; `owner_id` stays reserved and unenforced.
- No token-cost estimation. If a provider does not report usage, the product says so.
- No change to the worker admission pool, the SDD workflow, or the execution engine's
  terminal settlement and cancel semantics.

## 4. Findings inventory

Severity is the impact on daily PA use, not on security. Every row was observed on the
live stack at `ec995a98`; the evidence appendix carries the commands and identifiers.

| ID | Severity | Finding | Primary seam |
|----|----------|---------|--------------|
| F1 | High | No in-flow approval; a parked turn stalls silently up to the 600 s client timeout | `AriaPanel.tsx:18`, `:313-400` |
| F2 | High | One Aria slot; a parked run blocks every later turn | `RunAdmissionQueue.java:163-165` |
| F3 | Medium | Sync chat returns a text stub after the bounded wait instead of the question | `AriaService.java:154-156` |
| F4 | Medium | Restored tool rows render as assistant JSON bubbles | `AriaPanel.tsx:233-243` |
| F5 | Medium | Mutating and operator-only platform MCP tools stamped `risk READ` | `ToolRiskResolver.java:66-89` |
| F6 | Medium | `totalTokensUsed = 0` means "not reported", displayed as zero cost | `AgentLoopEngine.java:450-459` |
| F7 | Low | Panel timeline loads once on mount; later turns invisible until reload | `AriaPanel.tsx:223-261` |
| F8 | Low | Two false capability claims (tool absence, template absence) | `WorkflowTools.java:31-40`, `AriaPromptCatalog` |
| F9 | Low | OPERATOR_ONLY tools advertised to Aria's core; refusal not actionable | `McpServerConfig.java:86-92`, `MemoryTools` |
| F10 | Medium | No assistant or UI path to create the first memory entity | `MemoryCommandService.createEntity` |
| F11 | High | No memory UI; Aria advertises a review queue that does not exist | `act-dashboard/src/pages`, `src/api` |

F11 was found during this design dialog and was not in the original acceptance report.

## 5. Architecture

### 5.1 Three-plane signal model

The panel becomes a small state machine fed by three planes, each with one job:

- **SSE (ordered, per turn).** The run's own stream carries `thinking`, `tool_call`,
  `tool_result`, `message`, `done`, `error` today, plus three new events:
  `approval_required`, `waiting_input`, `memory_proposal`. Because the ask signal arrives
  on the same stream as the tool that triggered it, the card appears in order, next to
  its cause. The frontend dispatcher ignores unknown events (`aria.ts:267`), so adding
  events is backward compatible.
- **WebSocket (background, cross-tab).** The existing `approval.requested`,
  `approval.decided`, `approval.expired`, `run.waiting_input`, `run.completed` broadcasts
  (`EventBroadcastListener.java:64-127`) keep the panel truthful when the stream is gone:
  panel reopened, client timeout reached, or another tab decided the ask.
- **REST (reconciliation).** On mount, on window focus, and on WS reconnect the panel
  fetches the conversation timeline plus the pending approvals and memory proposals for
  that conversation's runs, and merges idempotently.

Every card is keyed by its `approvalId` or proposal id, so the same decision arriving
from two or three planes renders once. No plane is authoritative on its own; the merge
is.

The engine's emitter lives only in its call stack today (no `SseEmitter` appears anywhere
else in act-execution). Wave A adds a `runId -> SseEmitter` registry inside
`AgentLoopEngine`, registered when a stream starts and removed on every terminal path.
One listener consumes the existing domain events and emits the new SSE events to a
registered emitter. This is the only new backend structure in Wave A.

Module boundaries are preserved: act-memory depends on act-common only, so it publishes
a `MemoryProposalCreatedEvent` in act-common rather than reaching for an emitter. The
same listener that handles approval events emits `memory_proposal`.

### 5.2 Admission: per-conversation serialization

`Run.conversationId` already exists and is indexed (`Run.java:44-45`), so no schema change
is needed. `RunAdmissionQueue.Waiter` gains the conversation id; the Aria pool grants a
waiter only when both conditions hold: the global Aria count is below
`aria.runs.aria-reserved` (default raised 1 -> 3), and no active Aria run shares that
conversation. Granting scans the FIFO deque and **skips** a waiter whose conversation is
busy instead of stopping at the head, so one parked conversation cannot block the others.
The worker pool, its cap, and its FIFO order are untouched.

A queued Aria run is visible in the panel as a queued state, never as silence. Cancelling
a queued run keeps the existing ABORTED refusal path.

### 5.3 Governance labels come from the catalog

`ToolPolicy`, `ToolDescriptor` and `ToolCatalogProvider` live in act-common, and
act-execution already consumes the provider in `ToolCatalogContextContributor`. So
`ToolRiskResolver` can consult the catalog without a new module dependency.

Resolution order becomes: a configured `tool_definitions` row wins (including a legacy
null tier resolving to READ); otherwise strip the `mcp__aria-conductor__` prefix and look
the bare name up in the catalog; otherwise the native ACP map; otherwise READ for a
genuinely unknown name.

Policy-to-tier mapping: `WORKER_READ -> READ`, `WORKER_WRITE -> WRITE_LOCAL`,
`OPERATOR_ONLY -> PUSH`, `GATED -> PUSH`. Authority and blast radius are two axes, so the
approval DTO also carries `toolPolicy`, resolved at read time from the catalog. No new
column and no migration. `requiresApproval()` is referenced only by tests today, so this
mapping changes no runtime behavior.

### 5.4 Usage truth

`Run.inputTokens` and `Run.outputTokens` are nullable `Long` and are COALESCE-updated by
`RunRepository.updateUsageIfNotTerminal`. `AgentLoopEngine.recordUsage` returns early when
the core reports no usage, and `RunTelemetryService.recordUsage` no-ops when nothing was
reported. Both are correct; the defect is that a primitive `totalTokensUsed = 0` cannot
express "unknown", and the UI and the briefing read it as zero cost.

The fix derives `usageState` (`NOT_REPORTED` when both nullable counters are null,
`REPORTED` otherwise) and exposes it on the run DTO and the dashboard summary. The UI
shows "not reported" instead of 0, and the summary distinguishes "N runs, M of which
reported no usage" so Aria stops asserting a zero burn. No migration, no invented number.

Whether the pinned qoder CLI can report usage at all is a separate spike, off the critical
path. The bridge already supports both the ACP `usage` block and `_meta.quota`
(`packages/qoder-acp-bridge/src/usage.ts`); neither appeared in this acceptance.

### 5.5 Memory operation surface

The memory REST surface is already complete: entities list/create/get/patch, facts
add/supersede/retire, proposals list/decide, recall preview, and per-run context manifest
(`MemoryController.java:113-259`). Mutations resolve operator authority through
`OperatorAuthorityResolver.resolveOperator(request, true)`, which throws 403 for a
non-operator (`MemoryController.java:273-276`). The minimal Memory page is therefore
mostly frontend work, and an in-panel card calling the same endpoints is not a privilege
escalation.

`EntityStatus` has ACTIVE, MERGED and RETIRED but no PENDING, while V81 declares
`status VARCHAR(16) NOT NULL` with no CHECK constraint, so adding PENDING needs no
migration. `MemorySubjectType.ENTITY` already exists. An EXTRACTION actor may create an
entity only as PENDING; `remember_fact` against an unknown canonical key creates a
PENDING entity and a PENDING fact atomically; one operator decision promotes both.

The risk is read-path leakage, not the write. Every entity read path must exclude PENDING
by default: recall, the memory context contributor, `list_memory_entities`,
`get_memory_entity`, and the REST list/detail views. The audit is part of the wave, not a
follow-up.

Exclusion is about prompts and agent-facing reads, not about hiding the proposal from the
operator. An explicit `status=PENDING` filter on the operator-facing list and detail
surfaces returns PENDING entities, because the operator cannot approve what they cannot
see. The default (no status filter) stays ACTIVE-only, and no agent-facing read path may
request PENDING.

Two semantics are fixed here so implementation does not invent them:

- **Identity.** A proposal is keyed by canonical key. A second `remember_fact` against a
  key that already has a PENDING entity attaches a new PENDING fact to that entity; it
  never creates a duplicate entity.
- **Rejection.** Rejecting an entity proposal retires the PENDING entity and every PENDING
  fact proposed with it in the same batch, and records the retire assertions. Rejecting a
  single fact proposal leaves its entity untouched.

## 6. Wave A — conversation flow and approvals

Covers F1, F2, F3, F4, F7, plus B1 (F5) moved in from Wave B.

**A1 Admission.** As described in 5.2. `aria-reserved` default 1 -> 3, per-conversation
mutex, skip-and-continue granting, queued state surfaced. Amends the 2026-10-03 spec's D2
and its accepted "Aria replies become slower" UX note; that spec is updated in the same
change.

**A2 Ordered signals.** The emitter registry and listener from 5.1. New SSE payloads:

- `approval_required`: `runId`, `approvalId`, `toolName`, `riskTier`, `toolPolicy`,
  `options`, `expiresAt`.
- `waiting_input`: `runId`, `approvalId`, `question`.

The WebSocket payloads are unchanged; the panel fetches approval detail by id when a WS
event arrives, because `approval.requested` carries no tool name or tier.

**A3 Panel state machine.** `PanelMessage` becomes a discriminated union: `user`,
`assistant`, `activity`, `approval`, `clarification`, `queued`, `error`.

- The activity strip groups a turn's tool calls with name, status and duration, collapsed
  by default, expandable to arguments and result. Timeline tool rows map to the same
  structure, which removes the raw JSON bubbles.
- The approval card shows tool name, risk tier, tool policy and an argument summary, with
  Allow once and Deny calling the existing operator endpoints.
- The clarification card shows the question and an answer field calling
  `/api/v1/approvals/{id}/answer`.
- Reconciliation on mount, focus and WS reconnect merges by approval id and message
  identity.

**A4 Sync contract.** `AriaSyncRunWaiter` treats WAITING_INPUT as a returnable outcome and
returns immediately instead of polling to exhaustion. `AriaChatResponse` gains `question`
and `approvalId`; `messageFor()` returns the real question for WAITING_INPUT. Existing
fields are untouched, so the change is additive.

**B1 Governance labels.** As described in 5.3, so the card is truthful from its first
release.

## 7. Wave B — truth surface

Covers F6, F8, and the governance amendment from decision 3.

**B2 Usage truth.** As described in 5.4: `usageState` on the run DTO and dashboard
summary, "not reported" in the UI, and a summary that separates unreported runs from zero
cost. The qoder usage spike is scheduled but does not gate the wave.

**B3 Capability honesty.** Two measured false claims are fixed at three places:

- `list_workflow_templates` returns an additive `totalApproved` count so an empty match
  is distinguishable from an empty catalog, and its description states that an empty
  result may be a filter artifact and requires a blank retry before concluding absence.
- `AriaPromptCatalog` gains a capability-claims rule: distinguish "exists but refuses"
  from "absent", and retry an empty filter unfiltered before concluding.
- Operator-only refusals become actionable (shared with C4).

**B4 Memory-read auto-approval amendment.** The five memory read tools
(`recall_memory`, `list_memory_entities`, `get_memory_entity`, `list_memory_proposals`,
`get_context_manifest`) flip to `autoApproveRead = true` in `ToolCatalog`, join the
`McpProperties.autoApproveReadTools` default list, and the two parity assertions that pin
the old decision are rewritten to pin the new one. The catalog comment records the
2026-11-11 operator amendment and its reasoning, and the delivery ledger carries the same
entry. Writes and operator-only tools are unchanged. The INTERNAL ceiling and manifest
recording are unchanged.

The policy applies to ACP asks from run-owned cores, and only Aria's runs have
platform-MCP wiring today, so the practical scope is the Aria assistant. Planning must
re-verify that premise before the flip; if a worker run can reach platform MCP, the
amendment needs an explicit actor gate instead.

## 8. Wave C — memory operation surface and authority honesty

Covers F9, F10, F11.

**C1 Minimal Memory page.** A new `MemoryPage.tsx`, `src/api/memory.ts`, route and nav
entry. In scope: entity list and create dialog, entity detail with facts
(add/retire/supersede), proposal queue with Approve/Reject, sensitivity display. Out of
scope: merge/redirect UI, relation graph, link management, ceiling editing. 403 handling
follows the existing approvals UI posture.

**C2 In-panel proposal cards.** A `MemoryProposalCreatedEvent` in act-common, emitted by
the memory command service when a PENDING proposal is created during a run; the Wave A
listener turns it into a `memory_proposal` SSE event. The card shows subject, key, value,
sensitivity and origin, with Approve/Reject calling
`POST /api/v1/memory/proposals/{id}/decide`. It renders both `subjectType` FACT and ENTITY,
so an onboarding proposal is approvable on the same card. Mount-time reconciliation lists
proposals for the conversation's entities. After approval the card flips to ACTIVE and the
next turn's manifest includes the fact.

**C3 PENDING entity proposal.** As described in 5.5: PENDING added to `EntityStatus`,
EXTRACTION entity creation allowed only as PENDING, atomic entity-plus-fact proposal from
`remember_fact` against an unknown key, canonical-key identity so a repeated proposal
attaches to the existing PENDING entity, one decision promoting both, and rejection
retiring the entity with its batch. Assertion rows are written for each step. The
read-path audit is a deliverable of this wave: every entity read path is enumerated and
tested for PENDING exclusion, and the operator-facing explicit PENDING filter is tested
separately so approval stays possible.

**C4 Authority honesty.** OPERATOR_ONLY tools stay advertised. `AriaPromptCatalog` states
that these tools exist but refuse, must never be called, and that promoting a proposal
means surfacing an operator decision card. Every operator-only refusal message becomes
actionable with a stable error code naming the operator action and surface. The whole
class of operator-only refusals is audited for consistent wording, not just the one that
was exercised.

## 9. Error handling and fail-closed rules

- Duplicate signals across SSE, WS and REST merge by id; a card never renders twice.
- An expired ask flips its card to expired with the recorded reason; a decision made in
  another tab flips it to decided. Neither leaves a live button.
- A dead stream does not remove a card: WS keeps it alive and REST reconciles on
  reconnect.
- A queued Aria run shows a queued state; a cancel while queued keeps the ABORTED refusal.
- An unclassified tool name still resolves to READ for backward compatibility, but the
  card labels it unclassified rather than implying it was reviewed.
- A PENDING entity or fact never enters recall, a prompt, or a default list. Any read path
  that cannot prove exclusion fails closed.
- Usage is never invented: no reported counter means `NOT_REPORTED`, not zero.
- A non-operator call to a memory mutation keeps the existing 403; the card surfaces the
  refusal instead of retrying.

## 10. Testing and acceptance gates

Each wave runs the local ladder (focused tests, affected-module gate, full gate,
clean-room) and hosted CI green at its head before the next wave starts. Shared-payload
changes run every consumer tier at the final head.

**Wave A**
- Unit: admission per-conversation grant, skip-and-continue, global cap, cancel-while-
  queued, worker pool unchanged; emitter registry lifecycle on every terminal path; the
  two new SSE payloads; waiter early return; resolver mapping table including prefixed
  names, configured-row-wins, cache TTL and unchanged native behavior.
- Frontend: panel reducer tests for activity grouping, card idempotency across three
  planes, reconciliation merge, expired and decided transitions, queued state.
- Playwright with real STOMP and real SSE: a turn parks on an ask, the card appears
  in-flow, approving resumes it, answering a clarification resumes it, and a second
  conversation proceeds while the first is parked.
- Live acceptance: rerun the PA scenario; a normal turn requires zero page switches.

**Wave B**
- Unit: `usageState` derivation, summary wording, additive template count, prompt rule
  pin, rewritten parity assertions.
- Live acceptance: the briefing no longer asserts a zero token burn; the plan turn no
  longer claims no template is approved; the memory-read ask count drops to zero for
  read-only turns while a write still asks.

**Wave C**
- Unit: PENDING exclusion on every enumerated entity read path, the operator-facing
  explicit PENDING filter returning rows while the default excludes them, canonical-key
  identity (a repeated proposal attaches instead of duplicating), atomic entity-plus-fact
  proposal, one decision promoting both, rejection retiring the entity with its batch,
  assertion rows, and operator-only refusal codes and messages for every such tool.
- Frontend: Memory page vitest and Playwright with a real operator session, including the
  403 path; proposal card idempotency and post-approval state.
- Live acceptance, the program's bar: a brand-new operator with no PERSON entity completes
  onboarding entirely in chat (Aria proposes entity and fact, the operator approves on the
  card), and the whole PA scenario runs with zero curl and zero page switches.

## 11. Risks and open verification steps

These are pinned during planning, not assumed here.

1. **Ask emission seam.** The registry-plus-listener design is chosen, but the exact point
   where an ACP ask becomes visible to the engine must be confirmed so the SSE event
   carries the registered approval's id, tool name and tier rather than a guess.
2. **Platform-MCP actor scope.** B4's amendment assumes only Aria's runs reach platform
   MCP. Re-verify against the shipped wiring; if a worker can reach it, add an actor gate.
3. **Entity read-path enumeration.** C3's audit list must be produced from the code at the
   implementation head, not from this document, and every path needs a test.
4. **`totalApproved` producer.** The additive count needs a service method that exists or
   is added in the same change; the plan must name it.
5. **Admission semantics drift.** Changing the Aria cap default alters an accepted UX
   decision recorded in the 2026-10-03 spec. That spec and any test pinning the old
   default are updated together.
6. **Governance reversal.** B4 flips a decision a test was written to protect. The test,
   the catalog comment and the ledger change in one commit, with the operator decision
   cited, so a later tidy-up cannot silently flip it back.
7. **qoder usage capability.** The spike may conclude the CLI cannot report usage. That
   outcome is acceptable and must be documented, not papered over with an estimate.

## 12. Evidence appendix

All measurements were taken on the live local stack at merged main `ec995a98` on
2026-11-11. Run and approval identifiers are queryable through the local API.

Scenario runs, all `core=qoder`, placement HOST:

| Turn | Run | Status | Latency |
|------|-----|--------|---------|
| sync attempt | `00605a29-b779-421e-a344-4280154884fd` | CANCELLED | 63.7 s to stub |
| briefing | `872beb3b-d0d6-4525-9580-fab29f87f6fb` | COMPLETED | 36.0 s |
| teach | `cc6c74b2-8115-41c1-a7f2-b984faf2afd6` | COMPLETED | 40.0 s |
| self-approval request | `471ed5d7-786b-4081-aca1-cba45907a194` | COMPLETED | 30.4 s |
| recall | `ff01ee2b-1236-4c88-80ce-521b6f4b9a67` | COMPLETED | 21.6 s |
| plan | `8346f106-1462-4e42-bdb7-39bbc5532509` | COMPLETED | 57.5 s |
| teach editor | `1381912e-dc46-478d-9820-3a16398a854a` | COMPLETED | 18.7 s |
| recall while PENDING | `63b626bd-ee71-4db8-8496-ef1d6345821b` | COMPLETED | 14.8 s |
| recall after approval | `394c1b6a-e63a-40e7-a385-65700c8c44ae` | COMPLETED | 17.1 s |
| panel summary | `27d2af95-fcdb-4506-9d7e-97d2941ddd2b` | COMPLETED | not measured |

Reproduce: `curl -s localhost:8080/api/v1/runs/<id>`.

Governance proof. The memory section of the context manifest for the PENDING recall turn
listed sources `7faef29b`, `1e6b885a`, `8c61ae96` at 384 characters and 110 tokens. After
the operator promoted `e4d741fb`, the next turn's memory section listed `7faef29b`,
`e4d741fb`, `1e6b885a`, `8c61ae96` at 533 characters and 153 tokens. One manifest row per
run, no prompt text stored. Reproduce:
`curl -s localhost:8080/api/v1/memory/runs/<runId>/context-manifest`.

Self-approval refusal. Aria called `decide_memory_proposal` twice in run `471ed5d7`; both
returned `FORBIDDEN: Operator authority required` and both proposals stayed PENDING until
an operator REST decision.

Risk-tier mislabel. `curl -s 'localhost:8080/api/v1/approvals?status=APPROVED&size=60'`
shows `mcp__aria-conductor__remember_fact` (approval `e3d98ff2`) and
`mcp__aria-conductor__decide_memory_proposal` (approval `651ee371`) both carrying
`riskTier = READ`, while `ToolCatalog.java:66` classifies `remember_fact` as WORKER_WRITE
and `:82` classifies `decide_memory_proposal` as OPERATOR_ONLY.

Usage. All nine completed runs report `totalTokensUsed = 0`. The run-owned core logs for
`27d2af95`, `1381912e` and `872beb3b` are each 197 bytes and one line (`bridge.ready`
only), so the event stream is not in the core log; the code path was inspected instead
(`AgentLoopEngine.java:450-459`, `RunTelemetryService.recordUsage`,
`RunRepository.updateUsageIfNotTerminal`).

Missing memory UI. `act-dashboard/src/pages` contains no Memory page and `src/api`
contains no memory client. The only promotion path used in this acceptance was
`POST /api/v1/memory/proposals/<id>/decide`. The first PERSON entity `7faef29b` had to be
created with `POST /api/v1/memory/entities`.

False template claim. `curl -s 'localhost:8080/api/v1/knowledge?status=APPROVED'` returns
three items including WORKFLOW `d0000001-0000-0000-0000-000000000001`, while the plan
turn's `list_workflow_templates` call with intent keywords returned `{"ok":true,"data":[]}`
and Aria concluded no template was approved.

Panel staleness. Before a reload the open panel showed only the three rows that existed
when it mounted; after a reload the same conversation rendered all 22 persisted messages,
including every assistant final answer. Persistence was complete; the panel was stale.