# Qoder CLI agent core: ACP bridge and governed execution

Date: 2026-09-17
Baseline: `c6d37f8` (`main`, PR #89).
Status: **Draft for user review after partial Phase 0 spike. Not approved for implementation.**
Evidence: `docs/reviews/2026-09-17-qoder-cli-acp-spike.md`.

All components, endpoints, fields and behavior described as target design below are proposed,
not claims that they already exist. Existing-code anchors are relative to the repository root.

## 1. Objective and approved direction

Add `qoder` as a selectable task-execution ADK provider, initially alongside opencode and
langchain. Use Qoder account models and its plugin/skill ecosystem. The long-term goal is to
replace opencode as the recommended runtime, but this change does not flip any default.

Decisions confirmed in conversation:

| Topic | Decision |
|---|---|
| Runtime | Qoder CLI inside OpenSandbox, driven through an in-sandbox ACP bridge |
| Shared infrastructure | Extract shared sandbox lifecycle; do not copy the opencode manager |
| Governance | Route ACP permission requests to platform HITL for ordinary and kanban runs |
| Platform MCP | Auto-allow explicitly read-only operations; gate writes |
| Credentials | Configure/store the PAT in Aria, with an experience like configuring an LLM key |
| Delivery | First a working local vertical slice; parity and default replacement follow |
| Verification | Real local LLM + sandbox E2E; credential-dependent cases skip in CI with reasons |
| Plugins/hooks | Support a controlled Qoder plugin bundle; verify hooks before relying on them |

Non-goals: the unrelated Overview/IA revamp, migrating existing agents, changing opencode's
permission policy, a generic arbitrary-CLI framework, persistent permission grants, generic MCQ
rendering, new tenant/role management, and changing existing LLM key storage.

### 1.1 Corrections requiring review with this draft

- Phase 0 established Windows ACP feasibility, not Linux/sandbox readiness or complete parity.
- MCP audit is not authorization. A worker must not possess operator decision authority.
- Existing LLM keys are masked in responses but saved directly; the new PAT path requires
  encrypted-at-rest storage instead of copying that persistence behavior.
- Ordinary runs already have an approval-to-Review-card linkage; no new Review route is needed.
- Prefer host-side permission creation from bridge events over the earlier proposed sandbox
  callback `POST /api/v1/approvals/permission`. It avoids exposing approval creation to workers.
- Existing boolean `/decide` can express allow-once/deny; `/answer` and a generic MCQ UI are not
  the permission continuation mechanism.
- Cancellation, expiry, minimum live progress, and sandbox renewal belong in the first usable
  slice, not a later parity promise.

These are design refinements, not implementation authorization. Review this document before
writing a plan or changing production code.

## 2. Evidence and current integration seams

| Current fact | Source |
|---|---|
| ADK task path is selected through `supportsTaskExecution()` | `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/AdkProvider.java:104` |
| The task gate precedes provider execution | `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java:704` |
| Task timeout currently comes from opencode properties | Same file, `:721` |
| Sandbox manager owns SDK lifecycle and opencode-specific startup | `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeSandboxManager.java:32` |
| `/decide` delegates to `ApprovalGate`, not an ACP callback | `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/controller/ApprovalController.java:118` |
| Gate decision updates a row/tool, optionally completes an in-memory future, and publishes an event | `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/approval/ApprovalGate.java:240` |
| `/answer` is not a gate-decision substitute | `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/controller/ApprovalController.java:154` |
| Approval categories and ask kinds are separate; neither identifies ACP today | `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/Approval.java:20` |
| Ordinary-run approvals can acquire a linked Review card | `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanReviewCardListener.java:97` |
| DecisionPanel uses `/decide` for non-QUESTION asks, and has an Approve-all action | `agent-control-tower/act-dashboard/src/components/ReviewPanels.tsx:18` and `:82` |
| There is no `/review` route today | `agent-control-tower/act-dashboard/src/App.tsx:33` |
| MCP token filter is a shared-bearer check, not a run-scoped authorization layer | `agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/McpTokenFilter.java:25` |
| `decide_approval` calls the same gate without an ownership check | `agent-control-tower/act-mcp/src/main/java/io/aria/conductor/mcp/tools/ApprovalTools.java:36` |
| LLM key creation saves `request.getApiKey()` directly | `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/service/LlmProviderService.java:26` |
| Pack credentials have AES-GCM, but fall back to Base64 without a key | `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/credential/PackCredentialCipher.java:33` |

The spike observed ACP v1, `session/new`, streamed updates, default-model responses and an HTTP
MCP stub call. Resume, multiple sessions per process, Linux distribution, Authorization headers,
hooks and cancellation are not established by that evidence.

## 3. Runtime architecture

```text
Operator UI -> backend run/approval services -> QoderAdkProvider
                                                |
                                      shared SandboxLifecycle
                                                |
                        authenticated HTTP/SSE to Qoder bridge
                                                |
                                        qodercli --acp
                                                |
                                    scoped platform MCP access

permission request -> bridge event -> backend persisted approval -> operator /decide
                   <- bridge response <- host permission coordinator
```

### 3.1 Shared sandbox lifecycle

Extract lifecycle operations from the existing opencode manager: create, workspace upload,
endpoint resolution, bounded command launch, renewal and kill. Keep runtime-specific launch
commands, config generation and health semantics in their adapters. Preserve opencode behavior
and its tests; avoid a generic plugin framework or a single manager full of provider switches.

Target shared component: `SandboxLifecycle`, within `act-execution`. Target provider package:
`io.aria.conductor.execution.adk.qoder`. `QoderAdkProvider`, `QoderBridgeClient` and `QoderProperties`
have distinct responsibilities; do not add an unused transport abstraction for a future CLI mode.

Use one sandbox per prepared agent and one bridge-owned CLI process/session per active run.
One process per run is an isolation decision, not an inferred ACP limitation. Reject a second
active run for the same prepared agent; do not let concurrent runs mutate its workspace. Different
agents may run concurrently. End the CLI process when its run ends; stop/retire cleans the sandbox.

### 3.2 Image and startup

Proposed image context: `agent-control-tower/qoder-sandbox/`, containing a small Node bridge and
a controlled plugin bundle. Proposed internal bridge port: 4097. Linux CLI artifact/package,
license/distribution terms and reproducible checksum/version pin must be verified before choosing
an install command. Do not assume the npm package found in binary strings is installable.

Launch the CLI without a shell, with an explicit argv/env allowlist, absolute workspace, isolated
home/config, controlled project-setting sources and a pinned plugin directory. Do not inherit the
host user's skills, hooks, MCP servers, SDK-entrypoint variables, authentication payload or secrets.
Workspace-controlled config must not relax the permission mode or add arbitrary MCP servers.
The CLI remains in `default`; `auto`, `acceptEdits`, `dontAsk` and bypass modes are not user-selectable
on this governed provider.

The bridge controls only its run's CLI process; it does not execute arbitrary HTTP-supplied shell
commands. Run the CLI unprivileged, without a container-runtime socket or host credential mounts.
An in-sandbox bridge is not by itself a security boundary against every malicious shell command;
backend authorization and network isolation remain mandatory.

### 3.3 Proposed bridge API

| Method/path | Contract |
|---|---|
| `GET /health` | Process/bridge readiness only; never claim PAT/model readiness from liveness |
| `POST /sessions` | Host-owned run binding, workspace and validated MCP/model config; returns bridge session ID |
| `POST /sessions/{id}/prompt` | Start one prompt after ACP initialize/new succeed; return accepted, not a minutes-long response |
| `GET /sessions/{id}/events?after=<sequence>` | SSE events with monotonically increasing sequence and bounded replay |
| `POST /sessions/{id}/permissions/{requestId}` | Idempotent host delivery of a terminal decision for one pending request |
| `POST /sessions/{id}/cancel` | Cancel one run, reject pending requests and terminate its process if cancellation stalls |

Authenticate and bind bridge requests to the backend-created sandbox/run. IDs in URLs are not
credentials. Clients cannot choose another run by replacing a payload field. Credentials and raw
ACP authentication payloads must never appear in logs, SSE events or exception messages.

The bridge tracks JSON-RPC request IDs separately from session IDs and tool-call IDs. Initialization
and session creation are response-driven. Unsupported client-side filesystem/terminal methods
return explicit errors; do not advertise capabilities the bridge cannot implement.

Validate message size, event shape, run/session binding and offered permission options. An invalid
or unknown message must not become an allow. A lost replay window produces an explicit execution
failure rather than a fabricated successful result.

## 4. Credential and model configuration

### 4.1 PAT lifecycle

Provide a Qoder runtime credential editor on the existing provider configuration surface:
set/replace, masked status, explicit test, and remove with confirmation. No PAT is placed in an
agent JSON config, LLM-provider record, plugin, Docker image, repository, command-line argument,
frontend persistence, or report. The earlier temporary plaintext token file is not part of this design.

Persist one Qoder runtime credential record per deployment for the first slice. Proposed entity:
`RuntimeCredential` in `act-common`, keyed by provider ID, with ciphertext and timestamps. Keep the
PAT distinct from Qoder model selection and from LLM-provider API keys.

Reuse the existing AES-GCM primitive only with encryption enabled. The Qoder credential service
must reject saving/using a credential if `PACK_CREDENTIAL_KEY` is missing; it must not accept the
existing Base64 development fallback. Existing pack behavior and existing LLM records remain
unchanged. Do not introduce a second cryptographic implementation solely for this feature.

Response DTOs expose `configured` and a masked suffix, never ciphertext or plaintext. Credential
write payloads are excluded from audit argument logging and tracing. API errors cannot contain
the supplied PAT. Remove revokes future launches; explicit cancellation is required to end an
already-running session. Rotation affects new processes and does not silently replay old tasks.

Proposed operator-only API, under the provider surface:
- `GET /api/v1/adk/providers/qoder/credential`: masked configured status.
- `PUT /api/v1/adk/providers/qoder/credential`: replace the secret.
- `DELETE /api/v1/adk/providers/qoder/credential`: remove it.
- `POST /api/v1/adk/providers/qoder/credential/test`: explicit bounded test, with any credit cost
  disclosed; do not run billable inference on routine health polling.

These are new protected control-plane operations, not endpoints to expose to sandbox agents.
The backend decrypts only for launch; `QODER_PERSONAL_ACCESS_TOKEN` reaches the CLI process via
environment, not argv. Environment injection still exposes a secret to the process that uses it;
container isolation and least-privilege Qoder credentials are required, not a claim of secrecy
from that process.

### 4.2 Model and accounting

Start with Qoder's observed `auto` default. An explicit per-agent Qoder model selection is validated
against the authenticated runtime's advertised model IDs; unsupported IDs fail clearly. No
DeepSeek/OpenAI base URL or DB `LlmProvider` key is forwarded on this path. Model switching needs
a focused ACP test before implementation claims it works.

Report credits/tokens only when the runtime supplies meaningful measured data. Preserve unknown
usage as unknown; zero counters in the spike must not become a cost/budget guarantee. Enforce a
hard wall-clock run limit regardless of accounting. Exact turn/token caps are not claimed until a
runtime enforcement mechanism is verified.

## 5. Per-tool HITL and state ownership

### 5.1 Creation and persistence

The Java coordinator consumes the authenticated bridge event stream. For each permission request
it validates run/session/tool identity and classifies the action. It creates the approval internally;
the worker cannot create an approval with an arbitrary run ID. No new public create-approval MCP
tool or sandbox callback endpoint is needed.

Persist an explicit decision-source field (`LEGACY_GATE` / `ACP_PERMISSION`) separate from
`approvalType` and `askType`. Existing records keep legacy semantics. ACP rows remain approval asks,
not QUESTION asks. Persist correlation in an ACP-specific companion record keyed by approval ID:
provider/run/session/bridge-request/tool-call IDs, offered options, normalized request digest,
redacted display content, expiry and delivery state. A unique request correlation prevents duplicate
SSE delivery from creating duplicate asks. A changed payload for the same correlation is rejected.

Use a schema migration, current database-compatible types and purge ordering for the companion
record. Do not overload `answer` with a callback address or create a fake in-memory gate future.

### 5.2 Decision path

Retain `POST /api/v1/approvals/{id}/decide` and its boolean operator contract. The shared decision
service loads the persisted source and dispatches before legacy future/tool-status/event effects.
`ApprovalAnswerService` remains for question answers and cannot bypass the ACP permission policy.

For ACP approval:
1. Atomically transition a still-PENDING, nonexpired approval for the bound active run.
2. True selects the offered `kind=allow_once` option; false selects `kind=reject_once` or an
   explicitly supported cancellation response. Store the exact selected option ID.
3. Persist decision and delivery state before acknowledging the UI; host delivers it to the bridge
   and retries idempotently only while the same run/session/request remains live.
4. Bridge resolves that original JSON-RPC request once. It must reject stale, foreign, duplicate
   conflicting or changed-payload decisions. Do not start another prompt or call generic resume.

No `allow_always` option, no first-option fallback, and no generic Approve-all for ACP asks.
An unavailable allow-once option is a protocol incompatibility, not permission to choose a
persistent grant. Unexpected mode escalation stops the run and is surfaced as a governance error.

Approval authorizes an attempt; tool success comes only from the subsequent runtime event.
Denial rejects that tool call and returns control to the same CLI session; the CLI may report a
block or propose another action. A user cancelling the run terminates execution instead. Preserve
that distinction from pre-task denial, which currently cancels the entire task.

### 5.3 Expiry, cancellation and recovery

Expiry is evaluated on the server's clock on reads/decisions and by a scheduled sweep. No pending
approval relies on a sleeping thread or a worker still polling to expire. Pending decision transitions
must be atomic against expiry/cancel; exactly one terminal outcome wins.

On expiry, the host delivers a reject/cancel response for the original ACP request and removes any
unused grant. The bridge enforces the same deadline locally if disconnected. A queued allow cannot
be delivered or consumed past that deadline. If approval already won but delivery expired, retain
the APPROVED audit record with an expired delivery outcome; do not report tool execution or rewrite
the decision. A permission timeout rejects that attempt; a hard run deadline terminates the run.

The first slice uses the existing configured approval timeout but also a hard per-run deadline:
human wait consumes wall-clock time, and the effective permission expiry is the earlier of those
deadlines. The UI states that limit. Continue sandbox renewal while the run is waiting.

Cancellation rejects pending ACP requests, invalidates grants, stops the run process and records
remaining asks as expired with a cancellation reason using existing statuses. If ACP cancellation
cannot stop the process within a bounded grace period, terminate its process tree; kill the agent's
sandbox only as the last bounded fallback for that run. Verify no orphaned process remains.

After backend/bridge restart, do not replay a task or infer that a persisted approval means a CLI
is still waiting. The first slice terminates affected executions, expires pending asks, revokes run
credentials and reports restart interruption. Cross-process resume is a later capability gate.

Persist enough delivery state to reconcile a short stream disconnect, but do not promise exactly-once
external side effects. Never retry a completed MCP mutation or shell command automatically.

### 5.4 Run and card states

While waiting for permission, keep the same execution/session owned by the provider and expose a
permission-wait reason. Do not use generic pause/resume transitions to re-enter `executeTask`.
Keep the task-level approval gate unchanged and distinct from tool-level asks.

Publish source-aware approval events. Reuse `KanbanReviewCardListener` to link ordinary-run asks
or create a card when none exists. Ensure a linked IN_PROGRESS card with a pending ask is actually
surfaced to the operator, not merely given an invisible foreign key. Source-aware decision handling
must not mark the card DONE or advance an SDD stage merely because one tool was approved.

## 6. Authorization and platform MCP

### 6.1 Explicit policy, not naming heuristics

Use an explicit reviewed tool-policy registry with read/write/forbidden classification. Names
starting with `list_`, `get_` or `query_` are not sufficient evidence of read-only behavior. Verify
existing tools' side effects and validate parameter-sensitive operations. Unknown tools never
auto-allow. Fail closed when classification or identity is missing.

Read-only means no mutation; it does not waive resource scope or data-access checks. Approved
writes get a one-use grant bound to the exact tool identity, validated argument digest and run.
The platform MCP execution boundary checks/consumes the grant; a direct call that bypasses the
CLI's permission prompt must not bypass write authorization. Two identical concurrent tool calls
need separate grants. Denial leaves no reusable grant.

### 6.2 Worker is not operator

Do not give the Qoder worker the existing shared full-access MCP bearer. Issue short-lived
run-scoped worker credentials and revoke them at run termination. The tool dispatcher enforces
worker scope server-side as well as limiting tool discovery.

Executing workers cannot call `decide_approval`, answer approval questions as a human, change
credential/permission policy, impersonate another run, or relax governance through agent updates
or delegated runs. A worker can request work only within its approved scope; scope cannot increase
through `create_agent`, `update_agent` or `run_agent`. These restrictions override ordinary
write-class HITL: a human approval of a tool attempt cannot grant operator identity to the worker.

The earlier shared MCP token filter and audit aspect are not sufficient for this requirement.
Protect both REST and MCP control-plane paths. Sandbox networking must not permit an alternate
unauthenticated route to the backend's operator APIs or unrestricted legacy MCP endpoint. Existing
local-dev unauthenticated operator mode, if retained, must be unreachable from Qoder sandboxes;
otherwise the governed provider refuses to start.

Worker MCP requests are authenticated with the scoped credential injected at session creation.
Nonempty Authorization header support and network reachability must be validated in the sandbox
before this design is called runnable. No token fallback to anonymous access.

This is a targeted authorization boundary, not a full tenant/RBAC redesign. Any packaging that
cannot enforce the operator/worker separation is a blocked configuration, not a weaker smoke mode.

### 6.3 MCP coverage

Inventory the embedded MCP server, Aria tool handlers and TypeScript MCP package for every changed
public operation. Preserve existing tool names where possible. Shared decision dispatch must be
used by every entry point; workers remain forbidden from deciding even where trusted clients can.
Credential management and bridge control intentionally have no agent-facing MCP equivalents;
test these denials instead of generating tools for privileged endpoints automatically.

## 7. UI and plugin surface

### 7.1 UI

Keep the current route structure. Add `qoder` to provider metadata and the existing agent creation
selector; do not silently migrate agents or templates. Show separate states for sandbox service,
bridge readiness, credential configuration and task failure. A configured PAT alone is not healthy.

Reuse the Review card/DecisionPanel for ACP asks from all runs. Display source agent/run, resolved
tool identity, sanitized arguments or text diff, expiry, and explicit `Allow once` / `Deny` actions.
Render untrusted content as text or through existing safe renderers, never raw CLI-provided HTML.
Redact secrets from payload previews before storage and display. Do not execute suggested fix
commands from the payload.

Disable bulk approval for ACP entries, show decision-versus-delivery status, and handle stale,
expired or concurrently decided asks without a false success toast. Make source-aware behavior
consistent on Overview, drawers and existing Ops approval surfaces. Request-changes/cancel actions
must terminate or reject pending permission state rather than leave an orphaned request.

### 7.2 Qoder plugin

A proposed controlled bundle under `qoder-sandbox/plugin/` supplies concise Aria role/task guidance,
allowed platform-tool usage and evidence conventions. Load it explicitly and keep it non-writable
by the CLI. Its exact manifest/skill format is a validation gate, not assumed from another product.

Hooks can later emit lifecycle/tool telemetry and, if verified, additional deny/context decisions.
They do not replace ACP permission responses, server authorization, or sandbox isolation. Do not
emit duplicate progress/audit records from both ACP and hooks without event correlation. Do not
claim PreToolUse enforcement until a real ACP-mode block/no-side-effect test passes.

## 8. Configuration and default behavior

Proposed `qoder.*` settings cover bridge/image identity, sandbox service connection, hard task
deadline and renewal interval. Use the shared lifecycle for common OpenSandbox settings rather
than duplicating defaults that can drift. Keep PAT in the credential service, not properties dumps.
The effective governed permission mode is fixed, not a configurable bypass switch.

Replace `AgentLoopEngine`'s direct dependency on `openCodeProperties.getMaxTaskMinutes()` for task
execution with provider-resolved constraints. Preserve current opencode values, test overrides,
and cancellation semantics. A provider cannot silently ignore a requested limit; report unsupported
limits rather than claiming enforcement.

Extend explicit local startup selection for `qoder` once its image/auth/network probes pass. Do
not edit CI to supply secrets or change the current langchain CI stack. No default-provider flip,
seed rewrite or opencode removal is included.

## 9. Delivery slices and dependencies

This scope needs multiple implementation slices after approval, not one unreviewed large change.

| Slice | Dependency and exit condition |
|---|---|
| A: complete protocol/environment spike | Verify Linux distribution, clean config, PAT, authenticated MCP, allow-once/deny/cancel and plugin loading in a throwaway sandbox; stop if any required contract fails |
| B: shared lifecycle + selectable runtime | Preserve opencode tests; add Qoder bridge/provider/session isolation, bounded cancellation/renewal/progress, masked encrypted PAT configuration; not a generally usable provider until C |
| C: governed vertical slice | Source-aware persisted permissions, operator/worker authorization, write grants, all-run Review rendering, real local E2E; this is the first usable delivery |
| D: parity and ecosystem | Verified hooks, reconnect refinements, accounting/model features, recovery capabilities; each depends on runtime evidence |
| E: default replacement | Separate explicit approval, full regression and deployment evidence; no silent flip |

B and C are internal work slices, not independently advertised production features with governance
missing. The later implementation plan must name wave dependencies and file ownership. Shared
model/auth/lifecycle changes precede parallel provider/UI work; integration and real E2E follow.
No coding begins from this draft.

## 10. Verification and acceptance

Unit and integration tests run without Qoder credentials; use protocol fixtures with synthetic
secrets and record them as mocks, not runtime proof. Real local E2E uses actual Qoder and
OpenSandbox; credential-dependent cases explicitly skip in CI with a blocker reason.

Required tests before a usable-provider claim:

1. Both old providers retain behavior after lifecycle extraction and timeout-source changes.
2. PAT config round-trips masked responses, rejects absent encryption keys, and never leaks to
   logs/events/fixtures/argv; replacement and removal have the documented launch behavior.
3. Linux sandbox boots a pinned CLI; user/project config cannot inject extra plugins/MCP or
   change governed permission mode. Host filesystem and container runtime socket are inaccessible.
4. A task-level approval happens before CLI execution when required, without being confused
   with later tool requests. Cancelling leaves no run-owned CLI process, credential, pending ask
   or renewal timer. A healthy prepared agent sandbox may remain under its existing bounded idle
   TTL; retiring the agent or the kill fallback removes it completely.
5. Ordinary and kanban runs surface actual permission asks. Allow-once executes exactly the
   approved attempt; the next write still asks. Deny demonstrably prevents its side effect.
6. Authenticated real platform MCP read works automatically; a write stays unexecuted until
   approval, and server audit records only the allowed execution with run correlation.
7. Direct MCP/REST calls, self-approval, stale/wrong-run credentials, replayed grants, permission
   modification and delegation cannot bypass governance. Unknown tool classifications do not allow.
8. Concurrent approve/deny/expire/cancel has one terminal result. Retry does not duplicate asks,
   CLI responses, sessions or writes. Approving after expiry/deadline/cancel is rejected.
9. Approval wait is visible, obeys the hard deadline, renews sandbox TTL and remains cancellable.
   Restart interrupts rather than replays; reconnect either replays correlated events or fails clearly.
10. ACP final result, errors, unknown usage, bounded stream replay and unsupported protocol shapes
    are represented honestly. No zero-token inference is presented as zero cost.
11. Browser golden path and deny/expire/cancel/double-submit paths work in existing Review surfaces;
    console/network checks and other-provider smoke show no regressions.
12. Tests distinguish absent credentials (explicit skip) from a configured but broken provider
    (failure). Linux/protocol/HITL evidence must exist before calling C complete.

Evidence artifacts follow `AGENTS.md`: runnable commands with captured output, committed artifacts
when referenced, and NOT VERIFIED for every unevaluated criterion. No readiness verdict based on
only mocks, a CLI help listing or the Windows stub experiment.

## 11. Verification status and review gate

Verified now: repository seams in Section 2 and the narrowly described Windows observations in
the spike report. No production implementation or full-stack acceptance test was performed.

Unverified: all new contracts, security controls and acceptance criteria above. Linux distribution,
server auth headers, config isolation and cancellation are entry gates for the usable provider.
Hook behavior gates only hook-dependent features in slice D; slice C must work without hooks.

Remaining risks: hidden ACP flag/version compatibility, unavailable accounting, malicious or
misconfigured worker access to operator APIs, and accidental persistent permission grants.
The first usable slice cannot ship while any of those invalidates its advertised guarantees.

User review must confirm Section 1.1's refinements and this scope. After approval, invoke the
writing-plans workflow; do not implement or change defaults merely because this draft is committed.
