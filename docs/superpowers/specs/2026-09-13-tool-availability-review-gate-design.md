# Tool Availability and Review-Gate Design

Date: 2026-09-13
Status: draft, awaiting user review
Origin: findings from `docs/reviews/2026-09-13-e2e-full-ui-journey.md`

## 1. Summary

Agents, skills and workflows depend on external tools (GitHub, MCP servers, CLI scripts,
handlers). Today that dependency is implicit and unresolved: the platform has no single answer to
"can this tool actually be used right now?", and no way to turn "it cannot" into a decision the
operator can make. The visible consequence is E2E-002 — a spec approval in the SDD workflow returns
HTTP 500 and the chain parks forever, because the git handoff reads a token from the process
environment that no one configured and the UI cannot configure.

This design makes tool availability first-class: one resolution point, one configuration surface,
explicit requirement declarations, and a review-gate decision whenever a requirement cannot be
satisfied.

## 2. Problem and evidence

### 2.1 Two independent GitHub credential paths

The platform already models a tool pack for git and already resolves its credential from the
encrypted credential store:

- `V33__seed_git_pack_and_approval_tool.sql` seeds `tool_packs('pack-git-0001', 'git', 'SCRIPT', enabled)`
- `GitPackHandler:160` resolves the token via `credentialService.resolve(GIT_PACK_ID, null, "GITHUB_TOKEN")`
  and injects it into the subprocess environment.

The SDD spec-approval path does not use any of this. `GitBranchConfig:22` reads
`@Value("${GH_TOKEN:}")` straight from the process environment and, when blank, wires a stub whose
every method throws `GitBranchException`.

Only three classes reference `PackCredentialService`: `ToolPackController`, `PackCredentialService`
itself, and `GitPackHandler`. The SDD flow is not one of them.

Result: an operator who correctly stores a GitHub credential in the tool-pack credential store still
gets a 500 from spec approval.

### 2.2 The credential key is inconsistent

`PackCredentialService.resolve` falls back to `System.getenv("GITHUB_TOKEN")`, `.env.example`
documents `GITHUB_TOKEN`, and `scripts/start-backend.ps1:84` warns on `GH_TOKEN`. Following the
documentation and following the startup warning produce different outcomes.

### 2.3 The decision and the routing share a transaction

`ApprovalGate.decideApproval` is `@Transactional` and publishes `ApprovalDecidedEvent` in-transaction.
`SpecReviewCoordinator.onApprovalDecided` is a synchronous `@EventListener` running in the same
transaction and thread; for an approved spec it calls `createBranchAndCommitSpec`, which reaches
`GitBranchService`. The thrown `GitBranchException` propagates out through the controller and Spring
rolls the transaction back — discarding the decision. The approval therefore stays `PENDING` while the
chain stays `WAITING_APPROVAL`, and the operator has no retry path because a resubmit is refused
while an approval is pending.

### 2.4 The runtime cannot express "unavailable, needs a decision"

- `ToolExecutionEngine:42-43` returns hard failures: `"Unknown tool: …"` / `"Tool is disabled: …"`,
  surfaced to the agent as an ordinary tool result.
- `AgentToolResolver.resolveForAgent:27` filters disabled tools out, so an agent never sees a tool it
  lacks — it cannot ask about what it cannot see.
- A third case is covered by neither: tool enabled but credential missing. That fails deep inside the
  handler (the git case above) as a generic error.

## 3. Current assets

| Asset | Location | State |
|---|---|---|
| Tool registry | `ToolDefinition` (`enabled`, `category`, `riskTier`, `packId`, `sandboxMode`) | exists |
| Tool/pack taxonomy | `PackKind { HANDLER, SCRIPT, MCP, AGENT }` | exists |
| Tool packs | `ToolPack` (`kind`, `config`, `enabled`, `sandboxMode`, `status`) | exists |
| Encrypted credentials | `PackCredential` (`packId`, `agentId`, `credKey`, `encValue`), `PackCredentialCipher`, `PackCredentialService` | exists |
| Resolution precedence | per-agent override → pack-global → host env fallback | exists |
| Pack API | `ToolPackController` | partial |
| Ask mechanism | `Approval.AskType { APPROVAL, QUESTION, REVIEW_REQUEST }`, `optionsJson`, `POST /approvals/{id}/answer` | exists |
| Agent→tool binding | `agent_tool`, `role_tool_templates` | exists |
| Skill requirements | — | **missing** |

## 4. Goals

1. One resolution point answers "is this tool usable, and if not, why".
2. One surface lets an operator configure every kind of tool (MCP, CLI script, handler, agent) and
   its credentials.
3. Consumers (agent, skill, workflow step) can declare required tools.
4. An unsatisfied requirement becomes a review-gate decision, raised at the moment the need is hit,
   with options proposed from a platform-defined vocabulary and chosen by the operator.
5. A decision is durable and is actually applied.

## 5. Non-goals

- Per-agent credential overrides in the UI. `PackCredential.agentId` exists; the UI stays pack-level
  until a concrete need appears.
- Changing `agent_tool` bindings or role defaults.
- A general RBAC/approver-identity model. Anyone who can reach the API can still decide an ask.
- Rewriting the SDD workflow itself.

## 6. Design overview

Four incremental pieces. TP1 is a bounded fix and ships first; TP2–TP4 are the platform work.

```
TP1  unify credential resolution        -> SDD git handoff uses the credential store
TP2  universal tool config surface      -> operators configure packs + credentials in the UI
TP3  requirement declaration + resolver -> "what is needed" meets "what is available"
TP4  review-gate decisions              -> unsatisfied requirement becomes an ask, with options
```

Three enforcement points carry TP3/TP4 (see §9):

- **P1 pre-flight** — before a run or chain starts, deterministic resolution.
- **P2 exposure** — the agent is told which required tools exist but are unavailable.
- **P3 invocation-time** — a handler that fails because its dependency is unavailable reports
  `UNAVAILABLE`, not a generic error.

## 7. TP1 — Unify credential resolution

Bounded. Ships independently and unblocks the SDD flow for anyone who configures GitHub.

1. `GitBranchConfig` resolves the token via
   `PackCredentialService.resolve("pack-git-0001", null, "GITHUB_TOKEN")` instead of
   `@Value("${GH_TOKEN:}")`. The pack-global scope matches `GitPackHandler:160`, which already calls
   `resolve(GIT_PACK_ID, null, "GITHUB_TOKEN")`. Precedence and env fallback come from the service, so
   existing `GITHUB_TOKEN` environments and CI keep working unchanged. A blank result keeps today's
   disabled stub.
2. `GitBranchService` exposes `isAvailable()`; the disabled stub returns `false`. Callers ask instead
   of catching.
3. `WorkflowTemplateService.instantiateTemplate` gains a capability check beside the existing R8-F1
   `repoUrl` check: a template declaring `{repoUrl}` while git is unavailable fails fast with a
   message that names the missing credential and points at Configure.
4. The token key is normalised on `GITHUB_TOKEN`; `scripts/start-backend.ps1:84` changes its warning
   from `GH_TOKEN` to `GITHUB_TOKEN`, matching `.env.example` and the resolver.
5. Tests: resolution precedence (store over env, per-agent over pack), and instantiation failing with
   an actionable error rather than proceeding.

Known limitation, deliberate: TP1 only makes the flow work for operators who *have* a credential.
The no-credential case becomes a clear pre-flight error instead of a 500, and is superseded by TP4's
review-gate decision.

## 8. TP2 — Universal tool configuration surface

### 8.1 Backend

Extend `ToolPackController` into a complete CRUD surface:

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/packs` | list packs with availability summary |
| GET | `/api/v1/packs/{id}` | detail incl. config |
| PUT | `/api/v1/packs/{id}` | edit config / sandbox mode |
| POST | `/api/v1/packs/{id}/enable`, `/disable` | toggle, recorded |
| PUT | `/api/v1/packs/{id}/credentials/{key}` | write-only upsert via `PackCredentialCipher` |
| DELETE | `/api/v1/packs/{id}/credentials/{key}` | remove |

Credential responses never return a value. They return `{ key, configured: boolean, hint: "…c230" }`
where `hint` is a masked tail, and `updatedAt`.

### 8.2 Frontend

Configure → Skills & Tools becomes a management surface rather than a list:

- packs grouped by `PackKind` (MCP / CLI script / handler / agent)
- enable/disable toggle, config editor, credential rows with masked state
- per-pack availability and reason (from TP3)
- an "add tool" flow: choose kind → supply config (MCP endpoint/command, script body, …) → supply
  credentials → enable

Scope decision: pack-level credentials only (§5).

## 9. TP3 — Requirement declaration and availability resolution

### 9.1 Availability model

A single `ToolAvailabilityService` is the only place that answers availability. Input: a tool name
optionally scoped to an agent. Output: a status plus a human reason.

| Status | Meaning |
|---|---|
| `AVAILABLE` | tool enabled, pack enabled and approved, declared credential keys resolve |
| `TOOL_DISABLED` | `ToolDefinition.enabled = false` |
| `PACK_DISABLED` | `ToolPack.enabled = false` or pack `status != APPROVED` |
| `MISSING` | no tool definition with that name |
| `CREDENTIAL_MISSING` | tool/pack declares a credential key that does not resolve |
| `NOT_BOUND` | agent-scoped query and the agent does not have the tool assigned |

`CREDENTIAL_MISSING` is what the git case needs and what nothing detects today.

### 9.2 Requirement declaration

| Consumer | Source of truth |
|---|---|
| Agent | derived from existing `agent_tool` bindings; no new storage |
| Skill | new `requires:` block in the skill's `yaml_content` (KnowledgeVersion already stores YAML) |
| Workflow step | new `requires:` per step in the template YAML, persisted in `WorkflowChain.steps_json` |

Each requirement entry supports an optional `alternatives:` list of tool names and
`bypassable: <boolean>`. Storing requirements in the existing YAML avoids a second source of truth
and reuses `TemplateConverter`; the tradeoff is that requirements are not directly SQL-queryable, so
any future fleet-wide reporting would need an index built from the YAML.

### 9.3 API

`GET /api/v1/tools/availability?names=a,b,c&agentId=…` returns the per-name status. Read-only, no side
effects. This is the single endpoint both the tool panel and every enforcement point consume.

## 10. TP4 — Review-gate decisions

### 10.1 Trigger and shape

An unsatisfied requirement becomes an ask — an `Approval` with `askType = QUESTION` and a structured
`optionsJson`. The ask is raised where the need is actually hit, not only at pre-flight.

| Point | Trigger | Option set |
|---|---|---|
| P1 pre-flight | run/chain start; a required tool is unusable | deterministic; built from the requirement (an alternative only appears if declared) |
| P2 exposure | required-but-unavailable tool omitted from the agent's toolset | platform marks it so the agent knows it is missing |
| P3 invocation-time | handler reports `UNAVAILABLE` (e.g. no GitHub credential when pushing) | agent-proposed from the vocabulary, platform-validated |

### 10.2 Option vocabulary

The platform defines the vocabulary; the agent chooses which options apply in context.

| Kind | Meaning | Application |
|---|---|---|
| `CONFIGURE` | operator configures it themselves | open Configure on the relevant pack. The ask stays open; once the requirement resolves the operator retries from the same ask, which re-resolves and resumes the blocked work |
| `PROVIDE_CREDENTIAL` | the credential is handed over here, Aria helps store it | write via TP2 credential API, then retry the blocked work in the same request |
| `BYPASS` | the blocked operation is skipped; downstream agents continue in the same sandbox / working context | record degraded, keep the shared working context so later steps can continue |

Options are validated against this vocabulary. The agent may not invent option kinds.

### 10.3 Safety guard on BYPASS

`BYPASS` is only offered when the requirement is marked `bypassable: true`. The default is `false`,
and it is forced `false` for any tool whose `riskTier` is `DESTRUCTIVE` (the `RiskTier` enum is
`READ | WRITE_LOCAL | PUSH | DESTRUCTIVE`). Silently bypassing a destructive operation is not an
acceptable outcome, so it must be an explicit declaration in the requirement, not an agent's runtime
judgement.

### 10.4 Decision durability and application

Independent of TP4, the decision must survive a routing failure:

- `decideApproval` commits the decision in its own transaction.
- Routing runs in a separate transaction and catches failures, recording the reason on the chain
  rather than propagating. A routing exception must never roll the decision back.
- The chain keeps a retryable state with a recorded error, and the existing
  `recoverPendingDecisions` startup path already re-routes `APPROVED` SPEC_REVIEW approvals whose
  chain is still `WAITING_APPROVAL` — that becomes a backstop rather than the only recovery.
- A retry entry point is added for the already-decided-but-unrouted case. The existing
  `POST /workflows/{id}/resubmit-approval` cannot serve: it requires a still-`PENDING` approval.

## 11. Data model changes

| Migration | Change |
|---|---|
| `V56` | `workflow_chains.last_error TEXT` (nullable). Existing precedent: `kanban_items.last_error`, `knowledge_submission_intents.last_error` |
| `V57` | `approvals.workflow_chain_id` (nullable UUID) so an ask can be scoped to a chain, not just a run or card |
| `V58` | seed requirement declarations for the shipped `development-workflow` template (`requires:` on the git-dependent steps) |

No change is needed for `ToolDefinition`, `ToolPack` or `PackCredential`: `enabled`, `packId`,
`credKey` and `encValue` already carry the model. `ToolDefinition` gains no credential field —
credential keys belong to the pack/definition config, resolved by TP3, so that consumers declare
tool names only and never credential keys.

## 12. Failure taxonomy

Unavailable-tool failures become a distinct, typed outcome rather than a string:

`ToolExecutionResult` is currently a bare `record(boolean success, String output, String error)` with
only `success(..)` / `failed(..)` factories and no outcome kind, so this section does require a type
change: the record gains an outcome kind with `FAILED` as the default, which keeps every existing
`failed(..)` call site compiling while allowing the engine to report
`UNAVAILABLE` (dependency missing) and `UNKNOWN_TOOL` (no such tool) distinctly.

- `ToolExecutionEngine:42-43` reports `UNKNOWN_TOOL` and `TOOL_DISABLED` instead of the current
  free-text failures.
- A handler that cannot proceed because a credential does not resolve returns `UNAVAILABLE` with the
  missing key, instead of letting a provider exception escape.

This is what makes P3 possible at all.

## 13. Testing strategy

- TP1: unit tests for resolution precedence; an instantiation test asserting the actionable error.
- TP2: API tests for credential write-only semantics (a value never appears in a response); a
  component test that a masked credential renders as configured.
- TP3: a table-driven test over every status in §9.1; requirement parsing from template YAML and from
  skill YAML.
- TP4: an integration test that an unavailable tool produces an ask rather than a failed run; that
  each option kind is applied (credential written then retried; bypass records degraded); and that a
  routing failure leaves the decision durable with a retry path.
- A browser-driven E2E for the operator path: hit the unavailable tool, choose
  `PROVIDE_CREDENTIAL`, confirm the blocked work resumes.
- Regression guard for the original defect: the SDD spec approval must resolve rather than 500 when
  the credential is configured in the store.

## 14. Compatibility

- TP1 keeps the `GITHUB_TOKEN` environment fallback, so CI and existing `.env` files keep working.
- `steps_json` gains an optional field; older rows parse with an empty requirement list.
- `Approval` gains a nullable column; existing rows are unaffected.
- The `/approvals/{id}/decide` contract is unchanged; the Ops-surface 404 is already fixed.

## 15. Risks and open questions

- **Requirements in YAML are not queryable.** Accepted for now (§9.2); revisit if fleet-wide reporting
  is needed.
- **Agent-proposed options** depend on the agent knowing what it lacks (P2). If P2 is deferred, P3
  degrades to platform-proposed options for that release.
- **`BYPASS` and shared working context**: the "downstream agents continue in the same sandbox"
  behaviour assumes the sandbox survives the blocked step. Whether that holds for every provider
  needs verification during implementation.
- **Approver identity** is out of scope (§5). Every ask in this design is decidable by anyone who can
  reach the API, including `PROVIDE_CREDENTIAL`, which writes a secret. This is the largest
  unaddressed security question and should be scheduled explicitly rather than inherited.
- **Credential changes need a backend restart.** `GitBranchConfig.gitBranchService` is a singleton
  `@Bean`, so the token is resolved once at context startup; a credential stored through the pack API
  at runtime does not affect the running process. TP1 accepts this and the operator-facing Configure
  copy already says changes apply "on the next request or restart". TP2 should either resolve
  credentials per request or state the restart requirement explicitly in the tool panel — otherwise
  `PROVIDE_CREDENTIAL` in TP4 would appear to succeed while the running process still reports the
  credential as missing.
- **The sandbox environment is a second consumer of the credential, and TP1 does not feed it.**
  `application.yml`'s `sandbox-env.GH_TOKEN` is sourced only from the process environment; it is
  never read from the credential store. So an operator who stores the credential in the git tool
  pack opens the TP1 instantiate gate while the in-sandbox `gh`/`git` steps still start with no
  token. Deliberately deferred: TP1 is scoped to the fail-fast gate and to `GitBranchService`'s
  credential, and feeding sandbox creation from the store is a design change (which env keys,
  per-agent override semantics, which injection points) rather than a wiring fix. TP2's "one
  resolution point" work must cover this consumer explicitly, otherwise the store stays a partial
  substitute for the environment variable.
- **`ApprovalGate.decideApproval:247-250` silently ignores a decision on an already-decided approval
  (log warn, then return). A second decision therefore appears to succeed while doing nothing. This
  design does not change it, but the retry path in §10.4 must not depend on re-deciding.

## 16. Delivery order

1. TP1 — bounded, unblocks the SDD flow, independent of everything else.
2. TP2 — configuration surface; needed before TP4 can offer `PROVIDE_CREDENTIAL` usefully.
3. TP3 — resolution and requirements; depends on TP2 only for the UI half.
4. TP4 — review-gate decisions; depends on TP1's availability probe and TP3's resolver.

Each of TP1–TP4 gets its own implementation plan. TP1 can proceed immediately; the remainder are
sequenced because TP4's option application depends on TP2's credential API and TP3's status model.
