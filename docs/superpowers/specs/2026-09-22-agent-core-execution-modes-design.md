# Agent cores with Host/Sandbox execution and LangChain retirement

Date: 2026-09-22
Baseline: `0e3286a` in the isolated Aria Conductor worktree.
Status: Written specification approved by the user on 2026-09-22, including deterministic CI/E2E coverage and review corrections; implementation planning authorized.

This is a target design, not an implementation or readiness report. No runtime, CI configuration, database, or repository workspace has been changed by writing it. Approval of this document does not itself authorize implementation, deployment, credential use, or execution of the destructive retirement operation.

This specification supersedes the target architecture and delivery assumptions in the [sandbox-only Qoder design](2026-09-17-qoder-cli-agent-core-design.md) and its [Slices A-C plan](../plans/2026-09-17-qoder-cli-provider.md). The [ACP spike report](../../reviews/2026-09-17-qoder-cli-acp-spike.md) remains historical, partial evidence, not a current compatibility guarantee.

## 1. Approved scope and decisions

| Topic | Decision |
|---|---|
| First delivery | Qoder and OpenCode, each supporting Host and Sandbox; all four combinations require real validation |
| Architecture | Core adapter and execution backend are separate; Aria owns governance and run state |
| Selection | Each agent independently selects its core and execution mode |
| Defaults | New agents default to OpenCode + Sandbox; Host requires explicit selection |
| Existing OpenCode agents | Keep Sandbox semantics; do not silently change execution mode |
| Host location | The machine running the backend, not the browser's machine |
| Host workspaces | Support independent git worktrees and directly selected directories; default to worktrees for coding tasks |
| Trust boundary | Host is for one operator's trusted repositories; untrusted tasks use Sandbox |
| Authentication | Aria manages each core's credentials in both modes; do not rely on personal CLI login |
| Configuration | Controlled settings and minimal environment; no automatic inheritance of personal hooks, skills, or MCP servers |
| Runtime ownership | Each run owns its runtime/session; accept startup overhead for clear cancellation and workspace ownership |
| Governance | Both cores use per-tool approve-once/deny; explicitly permitted read-only actions may be automatic; workers cannot approve themselves |
| Failure handling | No fallback to another core, another execution mode, or Direct workspace access |
| LangChain code | Remove the runtime and all supported entry points, not merely mark them deprecated |
| LangChain data | Hard-delete the selected agents and their related runs, approvals, and audit records; no migration or archival substitute |
| Protected data | Other cores' data, platform settings/credentials, shared business objects, and user repositories remain protected |
| Cleanup activation | Explicit one-time operation, not an ordinary-startup side effect |
| Testing | Preserve business E2E coverage and CI; use mock LLMs and protocol-level mock CLIs where needed |
| Real acceptance | Mock-based CI complements rather than replaces real CLI/LLM testing of all four combinations |

Non-goals are multi-user Host execution, remote desktop runners, arbitrary user-supplied command execution APIs, automatic CLI installation, automatic commit/merge/push, preservation of retired LangChain records, and unrelated UI redesign. A worktree is not an OS security boundary.

### Alternatives considered

The selected design shares execution backends and governance between core adapters. Four independent providers would duplicate lifecycle and policy implementation. A separately deployed universal Runner service would add deployment and authentication costs not required by this single-operator scope. Qoder's protocol bridge is a run-owned component, not that additional permanent service.

## 2. Evidence and current-state constraints

Paths in this table are relative to the repository root. They describe the baseline, not the proposed implementation.

| Evidence | Consequence |
|---|---|
| `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/AdkProvider.java:97-133` | Task execution and abort extension points already exist |
| `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/AdkProviderRegistry.java:39-82` | Unknown provider/default values currently fall back; this must become explicit validation failure |
| `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeAdkProvider.java:163-226,468-499` | Core protocol handling, per-agent instances, sandbox creation, upload, endpoint resolution, and renewal are currently coupled |
| `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java:596-603,719-727` | Task routing exists, but the shared engine obtains its deadline from OpenCode properties |
| `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/adk/opencode/OpenCodeProperties.java:42-50` | The current task-timeout default is 45 minutes; sandbox renewal has separate settings |
| `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/tool/WorkspaceManager.java:54-62,92-125` | Existing workspaces are disposable run directories with age-based sweeping, not retained user repositories |
| `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/Run.java:19-69` | Run records do not contain an execution-core/mode snapshot |
| `agent-control-tower/act-aria/src/main/java/io/aria/conductor/aria/service/AriaService.java:104-128,305-325` | Aria chat uses the common run engine and restores conversation history |
| `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java:764-782` | Current task-prompt construction keeps system material and the last user request; history preservation needs explicit regression coverage |
| `packages/mcp-server/src/tools/agents.ts:26,39` | MCP create/update schemas currently accept only LangChain |
| `scripts/start-backend.ps1:17-23` | SkipSandbox currently selects LangChain rather than a Host backend |
| `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/housekeeping/HousekeepingService.java:286-307` | Run-child deletion exists, but is not a complete agent/audit/shared-reference cleanup |
| `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/WorkflowChain.java:39-41` and `WorkflowStep.java:23,41,48` in the same directory | Workflow references and copied output are embedded in JSON, not independent relational rows |
| `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/kanban/KanbanItem.java:62-70` | Kanban contains logical run/agent links and a separate assignment hint |
| `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/AuditEvent.java:24-39` | Audit association uses resource type/ID and conversation ID, not a typed run FK |
| `.github/actions/start-stack/action.yml:25-38` | Shared CI startup currently provisions Python and selects LangChain |
| `.github/workflows/ci.yml:87-99,120-128,193-215,243-279` | Java/CI/E2E jobs have LangChain dependencies and some real-provider cases depend on credentials |
| `agent-control-tower/act-dashboard/e2e/fixtures.ts:36-46,128-143,206-250` | Existing E2E seeding and approval helpers use real backend APIs; some fixtures assume missing-LLM failure |
| `agent-control-tower/act-app/src/main/java/io/aria/conductor/app/NoopLlmConfig.java:19-35` | A no-op Java LLM bean exists, but it does not intercept a CLI's independent model calls |
| `agent-control-tower/act-test-support/src/main/java/io/aria/conductor/test/MockLlmClient.java:19-54` | The shared mock is a deterministic prompt/response helper, not by itself the complete runtime test harness |
| `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/adk/opencode/OpenCodeHttpClientTest.java:22-35` | WireMock-backed OpenCode HTTP contract tests already provide a useful test seam |

The committed Qoder spike records Windows CLI 1.1.41 ACP initialization, session creation, response streaming, and a stub MCP call. It also records accidental allow-always selection, incomplete configuration isolation, and unverified denial/cancellation behavior. Its model-cost observation is not a promise of free inference.

A metadata-only observation in this design session returned the following; no prompt was executed:

```text
Command: "/d/Qoder/resources/app/resources/bin/x86_64_windows/qodercli.exe" --version
Output: 1.7.0-quest
```

That IDE-bundled executable is not the standalone version in the earlier spike. Its production suitability, authentication, isolation, and ACP behavior remain unverified. No installation path or version above becomes a production default by inference.

## 3. Selection, data contracts, and invariants

### 3.1 Agent configuration

Keep the existing `adkProvider` field as the core identifier for this delivery; show it as **Agent core** in the UI. This avoids a second alias or a wholesale API rename unrelated to execution modes. Its supported production values become `qoder` and `opencode`, never four composite provider IDs.

Add an explicit `executionMode` with `HOST` and `SANDBOX` values. Host coding configuration also contains `workspaceMode` (`WORKTREE` or `DIRECT`), `workspacePath`, and an optional base ref for worktree creation. Worktree is the default when a Host coding repository is configured. A run without a repository binding, such as ordinary operator chat, uses an Aria-owned scratch workspace; it does not implicitly select a user repository.

The same validation applies to dashboard, REST, Java MCP, and TypeScript MCP entry points:

- Absent values on new agents resolve to the documented defaults and are persisted explicitly.
- An explicitly unknown, removed, or unsupported core/mode value is rejected, not defaulted.
- Existing OpenCode records are assigned Sandbox mode during schema evolution, preserving their baseline behavior.
- Worktree mode requires a valid git repository and resolvable base ref. Failure does not switch to Direct.
- Direct mode requires an explicitly selected, trusted backend-local directory. It is not inferred from the browser's current directory.
- Runtime executable paths and image selection are operator configuration, not arbitrary worker-supplied argv or shell commands.
- Changing core/mode or admitting a Host workspace is an operator action; a worker's ordinary write grant does not confer that authority.

Retain the existing provider inventory API and extend its descriptors with supported modes, capability checks, and mode-specific readiness. UI/MCP validation must use the same backend source of truth rather than maintain incompatible lists.

### 3.2 Immutable run binding

Before launching anything, persist a run execution binding containing the resolved core ID, execution mode, workspace binding, credential reference, configuration revision, and deadline. Add observed CLI/protocol version and runtime handle metadata when established. Never persist plaintext secrets in this binding.

The running task uses that binding rather than resolving the agent's mutable settings again during cancellation, approval delivery, or cleanup. Agent configuration changes affect later runs. Legacy rows do not receive invented historical core snapshots.

A runtime handle owns only one run's process tree or sandbox, session, event stream, and temporary configuration. Runtime lifecycle is distinct from the business run status: cancellation is not complete while owned execution can still continue. A failed cleanup remains visible as a cleanup failure and must not be disguised as a successfully cancelled run.

## 4. Component boundaries

These are proposed responsibilities, not claims that new classes already exist.

| Component | Owns | Must not own |
|---|---|---|
| Core adapter | Protocol/session setup, prompt/context translation, model selection, event/usage normalization, native permission replies and cooperative cancellation | Container creation, global provider fallback, operator authority |
| Execution backend | Environment allocation, controlled launch, endpoint resolution, process/resource supervision, renewal where relevant, verified termination | Prompt semantics, approval decisions, arbitrary commands from API callers |
| Shared workspace management | Ownership records, repository/base-ref resolution, worktree creation, Direct locks, snapshot transfer, retained results | Unconditional deletion of external directories |
| Aria run orchestration | Immutable binding, deadlines, admission/readiness, approval correlation, state transitions, audit and result persistence | Core-specific CLI flags or OpenCode-specific shared timeouts |
| Credential/config services | Core-appropriate secret resolution, controlled configuration, worker credentials, redaction | Personal-login inheritance or handing operator credentials to workers |

Evolve the existing provider/engine seams rather than add a competing registry. Remove LangChain-only turn-level paths when their remaining callers have been accounted for; preserve generic LLM, tool, workflow, and approval functions that still have production callers.

### 4.1 Core and backend matrix

| Combination | Run-owned launch | Core communication |
|---|---|---|
| Qoder / Host | Local Node ACP bridge and its Qoder child process | Backend uses the authenticated bridge; bridge drives Qoder ACP over stdio |
| Qoder / Sandbox | The bridge and Qoder inside a run-owned image | Same bridge contract through a sandbox-resolved endpoint |
| OpenCode / Host | Local `opencode serve` process | Native OpenCode HTTP protocol |
| OpenCode / Sandbox | `opencode serve` in a run-owned sandbox | Same native HTTP adapter through a sandbox-resolved endpoint |

The Qoder bridge belongs in a mode-neutral package, with sandbox image packaging consuming the same built artifact used by Host. It is not duplicated under two runtime implementations. The bridge owns its Qoder child; the Host backend must supervise both, not merely terminate the bridge's listening socket.

Use run-owned runtimes in the first delivery, including OpenCode. Do not retain the current per-agent mutable workspace/server cache as an implicit exception. Configuration readiness may be checked without keeping an idle runtime alive; a configured idle agent is not unhealthy merely because no run-owned process exists.

Host endpoints bind to loopback with per-run authentication. Sandbox endpoints use authenticated transport and appropriate network restrictions. Neither bridge nor backend exposes arbitrary shell execution as a public launch API. Process launch is derived from trusted core profiles using explicit executable/argument/environment boundaries.

## 5. Run lifecycle and workspace behavior

### 5.1 Execution sequence

1. Resolve and validate core/mode/capabilities, credential references, and workspace admission; record the run binding.
2. Acquire the workspace lease/lock and prepare the run-owned working directory and controlled configuration.
3. Allocate the selected execution environment and start the runtime; verify the expected protocol endpoint before prompting.
4. Create one session, apply the requested supported model, and send system material, task input, and relevant conversation context.
5. Stream progress and permission requests through Aria while enforcing the shared deadline and resource supervision.
6. Quiesce and terminate all run-owned writers, including background descendants, while retaining read access to the workspace filesystem. A prompt-completion event alone is not proof that writes have stopped.
7. After writer termination is verified, capture final output, observed usage, file changes, and test/tool outcomes into retained result storage.
8. Destroy remaining runtime/environment resources, then release workspace locks and publish the honest terminal outcome.

Writer termination and environment destruction are distinct backend operations. In Sandbox mode, keep the sandbox filesystem/export facility available after core writers stop and until export finishes; killing the sandbox first would destroy the export source. Apply the same ordering on cancellation and failure. If writer quiescence or export cannot be verified, report incomplete/unavailable results rather than a final stable diff, and retain the Direct exclusion lock while a writer may survive.

Aria chat and Kanban/workflow dispatch use this common task contract. Conversation context must survive the transition away from turn-level LangChain calls; taking only the last user message is not an acceptable replacement for multi-turn behavior.

### 5.2 Workspace ownership

Every workspace records its ownership and lifecycle policy, not merely a path or directory age.

- **External Direct directory:** user-owned; never recursively removed by cleanup or housekeeping. Only one platform writer is admitted for overlapping canonical directory trees, including ancestor/descendant selections and symlink/junction aliases, not just identical path strings. The lock cannot prevent an external editor or shell from writing; the UI must not imply otherwise.
- **Managed git worktree:** created from a resolved commit in an operator-admitted repository, with a unique run-owned location/ref. Do not alter the source worktree's index or overwrite its uncommitted edits. Show the base commit and make clear that existing uncommitted source edits are not automatically copied.
- **Managed scratch:** used when no repository is needed; removable only when it is a positively identified Aria-owned resource, the run has stopped, and retention permits it.
- **Sandbox workspace:** use an admitted source snapshot/upload rather than an unrestricted host-directory mount. Export reviewable changes to managed result storage; never auto-apply them over the source repository.

Preserve worktree results for review. Platform cleanup never automatically merges, commits, pushes, or discards them. Removal of a retained worktree/result is a distinct operator action. Validate canonical paths, symlinks/junctions and ownership before any platform filesystem cleanup. Git common-directory operations must also be serialized where needed; different worktrees do not make shared repository metadata race-free.

Generated runtime configuration belongs in run-owned configuration storage, not an automatic overwrite of the user's `opencode.json`, Qoder settings, or credentials. Exported artifacts exclude generated secret-bearing configuration and reject unsafe path traversal or links. Direct-mode changes are observed relative to the pre-run state so existing user edits are not misrepresented as all produced by the agent.

### 5.3 Deadlines, cancellation, and restart

Move shared task-deadline ownership out of OpenCode properties. Preserve the existing 45-minute default as the initial shared default unless explicitly configured otherwise. A run's hard wall-clock deadline includes preparation and human waiting; each approval also has an expiry no later than that deadline. Keep resource renewal active during permission waits, and stop renewal during terminal cleanup.

An explicit hard round/token constraint may only be accepted where its enforcement is verified. Prompt instructions and a flag supported only in another CLI mode are not enforcement. Unknown usage remains unknown; do not claim a credit/token budget was enforced using absent telemetry.

Cancellation first attempts the core's verified cooperative mechanism, then performs bounded termination of the owned process tree or sandbox. The supervisor remains responsible if a core ignores cancellation. Test cancellation before session creation, during a prompt, while awaiting approval, and during result collection. Pending requests expire/reject and late decisions cannot resume execution.

Persist sufficient runtime ownership to recover after a backend crash without killing unrelated processes. Validate ownership rather than trust a reused PID. A restart interrupts affected runs and cleans orphaned owned execution; it never automatically replays approvals or restarts a half-completed coding task. Do not release a Direct writer lock while a surviving process can still modify that directory.

### 5.4 Truthful pause and resume

Preserve the existing pause/resume business capability through a verified core/backend control path, not the old Java turn-loop flag. The baseline `AgentLoopEngine.java:258-289` changes run/session status, while its task-await path at `:822-843` only reacts to cancellation; that alone cannot stop a CLI.

- A manual pause remains a pending control request until the adapter/backend confirms a safe suspended or resumable checkpoint with no further tool execution or task advancement. Do not report PAUSED merely because the database flag changed.
- The capability gate must verify native checkpoint/pause or backend supervision behavior for the pinned core/OS, including in-flight tools and descendants. Do not assume an ACP pause method, that process suspension preserves every network session, or that local pause cancels already billed remote inference.
- While paused, retain workspace ownership and necessary sandbox renewal. The original hard deadline and approval expiries continue; pause cannot extend a run indefinitely.
- Manual pause and permission waiting are separate control reasons. Resume does not approve a pending ask. An allow decision received while manually paused is not delivered to the core until an explicit resume and fresh validity check; an expired or cancelled grant is never replayed.
- Resume continues the same run binding/checkpoint, not a new task replay or a fresh mutable agent configuration. Failure to resume is explicit and must not duplicate completed writes.
- During capability development, an unsupported pause/resume request fails explicitly without claiming a state transition. It remains a release blocker for the preserved behavior, not a reason to delete its tests or silently advertise reduced parity.

Test manual pause during active work and during an approval wait, no writes/task advancement after pause acknowledgment, explicit resume, expiry/cancel while paused, and interaction with Kanban pause/resume entry points.

## 6. Credentials, configuration, and permission governance

### 6.1 Managed credentials

Resolve credentials through Aria in both modes. OpenCode may consume the configured model-provider credentials; Qoder consumes its configured runtime credential. These are core-specific credential types behind a common resolution boundary, not interchangeable tokens or copies of the operator's home directory.

New stored runtime secrets require authenticated encryption and a configured encryption key; no plaintext/Base64 fallback. Mask reads, never return stored secrets, and never place them in command arguments, logs, SSE payloads, fixtures, or result artifacts. Inject only credentials needed by the selected run. Existing protected platform credentials are not deleted by LangChain retirement.

Credential/configuration health is separate from process liveness. Missing configuration must fail admission clearly. An explicit credential test may perform a bounded real call with disclosed cost; normal health polling must not spend inference credits. Deleting a credential blocks future launches; already injected credentials are not magically revoked, and active execution is stopped through the normal cancellation path.

### 6.2 Controlled configuration and authorization

Use an explicit environment allowlist and controlled HOME/config/plugin/MCP sources. Do not inherit SDK entrypoint variables, personal authentication payloads, or ambient configuration. Repository content and personal settings must not relax permission policy, add uncontrolled MCP servers, or replace the core executable. Supported project instructions and approved plugins are explicit inputs, not permission to load every user customization.

Aria is the authority for permission decisions. Operator and worker credentials must be distinct across **all** relevant REST/UI/MCP routes, not just `decide_approval`. Core selection, Host workspace admission, credential management, approval decisions, and retirement execution are operator-only actions. A scoped worker token is bound to a run and may only perform its permitted operations.

Verify or supply an operator-authenticated boundary; an existing operator session has not been established by the reviewed sources. Use a separately provisioned single-operator access credential/session, never the worker/core secret. Do not leave an anonymous REST approval route that bypasses the MCP worker restriction. The UI must obtain operator authority through the local operator setup rather than a token shipped in frontend assets or handed to a CLI. This is not a new multi-user account/RBAC product.

Application authorization does not turn a same-OS-user Host process into a hostile-code sandbox. Host is explicitly restricted to trusted workloads; a malicious process with the operator's OS authority is outside its isolation guarantee.

### 6.3 Permission normalization

Both cores emit a normalized request tied to run, runtime/session, request ID, offered decision options, tool name, argument digest, and expiry. Persist correlation before displaying an ask. Existing Review surfaces are reused for ordinary and Kanban-dispatched runs.

Only explicitly classified and authorized read-only operations can be automatic. Writes, shell execution, and side-effecting MCP operations require a run-bound approve-once/deny decision. Unknown operations are not auto-approved. Selecting a native option uses verified semantics, not its position or a guessed fixed option ID; never select allow-always as an allow-once substitute.

A write grant authorizes one matching operation, with replay, changed arguments, different runs, and self-approval rejected at the execution boundary. Do not execute a native write and then execute the same side effect a second time through MCP. An existing whole-task approval setting does not waive per-tool governance. Batch UI actions must not create persistent native permission escalation.

Each adapter must demonstrate permission delivery, denial without side effects, repeated allow-once prompts, expiry, and cancellation with its actual pinned core. If it cannot satisfy the contract, that combination is not ready; it must not silently run with permissive settings.

## 7. Complete LangChain retirement

### 7.1 Code and operational removal

Remove the LangChain provider, process/HTTP/runtime code that has no remaining callers, Python package/image/dependencies, supported configuration, old startup selection/fallback, and legacy-only CI setup/jobs. Remove or replace hardcoded LangChain choices in agent creation, templates, Aria tool handlers, UI, both MCP implementations, and default initialization.

Update startup scripts so explicitly selected Host execution does not require Docker/Podman/OpenSandbox. Sandbox dependency checks remain mode-specific; Host does not mean running outside a containerized backend's own OS environment, and no host escape or runtime socket mount is introduced. Remove the legacy LangChain-only compose path; containerized delivery must describe its actually supported core/backend topology rather than pretend it starts the operator's desktop CLI.

Do not erase generic LLM, tool, knowledge, approval, workflow, or progress behavior merely because a class name includes ADK. Replace test fixtures and scenario drivers before removing their sole runtime dependency. Update current setup/architecture/contributor guidance; retain historical evidence as clearly historical rather than rewriting old observations.

Append schema migrations; do not rewrite applied Flyway migrations or their checksums. Old seed migrations may still mention LangChain historically, but the resulting supported installation must not expose runnable LangChain agents or recreate them through an initializer. Separate schema evolution from the explicit destructive cleanup operation below.

### 7.2 One-time bounded hard deletion

The cleanup has preview and explicit execute phases, usable during a controlled maintenance window. It is not called from normal application startup, a health check, or a worker tool.

Define the target agent set from records explicitly selected as LangChain before any legacy auto-repointing. Freeze their IDs and related run/approval/child IDs in the preview. Show counts and target identities; execution must verify the selection has not changed and that owned runtimes, pending approvals, and new dispatch cannot race the purge. This is not a database reset and does not archive old record contents.

The existing Run entity has no reliable execution-core snapshot. Therefore this is an **agent-ownership cleanup**, not a claim to reconstruct every historical core invocation. Do not widen the target set using names, roles, missing values, substring searches, or old defaults. An agent already configured for another core is outside the set; ambiguous or conflicting ownership must be resolved explicitly rather than guessed.

Delete run-owned trajectories, tool calls, prompt calls, approvals, sessions, and other registered run children before runs. Include direct agent-owned children not reachable solely by run ID, such as prompt records without a run. Delete agent assignments/bindings and the selected agents after dependents. The existing `purgeRuns` order is a starting point, not proof that audit and cross-domain references are covered.

Select audit rows using verified structured resource type/ID relationships to the target agent/run/approval/child sets. A shared conversation ID or text mentioning an ID is insufficient authority to delete unrelated audit events. Preserve shared tool/skill definitions, knowledge, settings, credentials, and business records.

For retained Kanban/workflow objects, remove only links to deleted targets, including embedded workflow step IDs. Do not delete an entire card or mixed-core workflow because one step used LangChain. Keep other steps and their data unchanged. Active shared workflows that depend on the targets must be quiesced and explicitly resolved before executing cleanup; do not leave them running against missing identities. Make removed-target links render safely and reject resuming an unresolved step rather than guessing a replacement agent.

Use transactional, repeatable deletion with explicit failure reporting. Protected-record checks compare before/after state, allowing only the approved link removals; filesystem operations are outside the database purge and never delete user repositories. A cleanup failure must not lead to a broader retry filter. A rerun with no remaining selected records is a no-op.

After successful cleanup, create only missing required built-in agents using the new default, without restoring old runs or overriding surviving other-core agents. Remove the old initializer behavior that first migrates selected LangChain agents to another core, as it would change the deletion set. Re-enable dispatch only after the cleanup and protected-data checks succeed.

### 7.3 Fresh installation, upgrade, and CI bootstrap

Historical `agent-control-tower/act-app/src/main/resources/db/migration/V42__seed_sdd_role_agents.sql:13-16` inserts LangChain BA/DEV/QA rows even in a new database. Keep this applied migration immutable and handle initialization explicitly:

| Context | Required sequence |
|---|---|
| Fresh installation | Create the database and apply schema history with dispatch held; run an explicitly authorized first-install setup that previews and executes scoped cleanup of the historical seed rows; create the missing built-ins with OpenCode + Sandbox; validate readiness before releasing dispatch |
| Upgrade, cleanup authorized | Apply additive schema changes with legacy admission/dispatch blocked; preview the unchanged LangChain ID set, quiesce affected work, execute scoped cleanup, then create missing built-ins and verify protected data |
| Upgrade, cleanup not authorized | Keep legacy rows unavailable and visibly marked as requiring retirement; do not repoint, execute, warm, or delete them. Preserve access to operator controls and other-core operation; dependent legacy workflows remain blocked until setup is completed |
| Disposable CI database | The dedicated harness performs the same explicit setup/cleanup operation with a synthetic operator credential before business E2E seeding or dispatch; mock peers satisfy runtime dependencies, not a database shortcut that skips retirement logic |

The explicit first-install/setup operation is separate from ordinary backend startup. It must not infer authorization to delete an existing database from the absence of one built-in agent. A failed or deferred setup reports incomplete readiness rather than leaving SDD roles apparently usable. Tests cover fresh setup, populated upgrade, withheld authorization, setup failure, and repeat execution without changing protected records.

## 8. E2E and CI preservation

The added user requirement is mandatory: E2E must remain runnable and CI must remain useful after the Python runtime is removed. A green pipeline obtained by deleting shared scenarios or skipping them for missing credentials does not satisfy this design.

### 8.1 Verification tiers

| Tier | Real components | Substitutes | What it proves |
|---|---|---|---|
| Unit/contract | Production serializers, adapters, policy/state code | Targeted fakes/WireMock | Local logic and protocol contracts |
| Host process integration | Real launch/supervision, bridge, pipes/HTTP, workspace and filesystem | Executable mock Qoder CLI; mock OpenCode service | Handshake, framing, permissions, ownership, cancellation and errors without inference |
| Required PR browser/API E2E | Real backend, H2, controllers, authorization, approval state, frontend/browser, production core adapters and Qoder bridge | Deterministic LLM and protocol peers; explicit test-only sandbox transport fixture where needed | End-to-end application behavior, not native-core or container readiness |
| Sandbox lifecycle integration | Real supported OpenSandbox/container stack and lifecycle backend | Small test image with mock core/protocol peers | Container startup, endpoint access, upload/export, renewal and kill without account credentials |
| Live acceptance | Real Qoder/OpenCode, real models, real Host/Sandbox, real platform/UI | No substitute for core/model/environment | Readiness of the four supported core/mode combinations |

Each result labels its tier. A simulated sandbox in a PR UI job cannot be reported as container coverage. Run the real sandbox lifecycle lane for changes to backends, bridge/images, workspace transfer, or startup, without multiplying container setup across every UI shard. If that required lane cannot run, its criterion remains unverified rather than silently passing through the simulation.

### 8.2 Mock LLM and mock CLI contracts

A no-op Java `LlmClient` only replaces Java-side calls. It does not replace the LLM used internally by a Qoder/OpenCode subprocess. Provide both levels where a scenario needs them:

- A deterministic Java LLM implementation/HTTP stub for internal classifier/helper calls, with recorded requests and explicit scenario responses.
- A **real spawned mock Qoder CLI process** speaking the validated newline-delimited ACP protocol. Exercise initialize, session creation before prompting, streamed updates, permission requests/responses, normal completion, cancellation/exit, stderr handling, and malformed/partial messages. Native cancellation method details must come from the pinned-core gate, not invention.
- A mock OpenCode service implementing the native endpoints/events used by the production adapter, extending the existing HTTP contract-test seam rather than replacing the Java adapter with a precomputed result.

The mock Qoder CLI must perform actual bounded filesystem changes in a disposable test workspace **only after** the corresponding allow-once reply, and leave files untouched after denial. It must offer repeated requests with distinct IDs and support expiry/cancel/pause/resume/race scenarios. A controlled background child must continue attempting writes after the mock's prompt-completion event so tests prove writers stop before the final diff/export and locks are released. Scripted failures must include invalid authentication, handshake failure, unsupported mode/model/control operations, disconnect, timeout, malformed events, and non-cooperative shutdown. The mock OpenCode peer must support equivalent behavioral scenarios.

Mocks must not declare a run completed directly in the database or bypass the bridge, authorization, approval coordinator, or UI under test. Recorded live protocol fixtures and periodic real-core contract checks keep mocks from becoming a self-consistent invented protocol.

### 8.3 Test isolation and production boundary

Keep new mock executables, scenario controls, and sandbox substitutions in test resources/test harness artifacts, not production images or an end-user selectable mock core. Use a dedicated CI harness to wire these fixtures to production application components. Do not introduce an unauthenticated production override or weaken operator/worker checks to make mocks convenient.

The proposed executable composition is explicit:

- Keep the existing `backend-jar` production artifact unchanged by fixture packaging. Add a separate `backend-e2e-harness` distribution built from the same production revision.
- The harness distribution contains production application classes/resources, ordinary module/runtime dependency jars, only the dedicated harness classes and selected test support, the built Qoder bridge, and mock protocol executables. Do not assume test classes can be added to an executable Spring Boot jar merely by setting a profile.
- A test-source launcher, proposed as `io.aria.conductor.app.e2e.CoreE2eApplication`, starts the real application with explicit harness configuration. Run it on an ordinary JVM classpath containing the application classes, harness classes and dependency jars, rather than using `java -jar` on the untouched production jar. Keep unrelated test configurations out of the harness classpath/component scan.
- The backend build job compiles and packages this distribution, including its Node/bridge assets. Required smoke and Playwright jobs depend on and download it; the shared start-stack action launches this composition for both backend-only and browser E2E, with the pinned Java/Node toolchains and a per-job database.
- The harness wires deterministic Java LLMs and protocol peers while retaining real controllers, core adapters, bridge, policy/approval services and persistence. Its explicit operator-authenticated setup performs Section 7.3 before tests start. Production artifacts must have no fixture launcher, scenario controls, or test-only sandbox substitution.

Use synthetic credentials, deterministic fixture scenarios, unique temporary git repositories/worktrees, per-shard databases, separate ports, and run-owned processes. No test uses the operator's repository, account session, HOME, or real credentials by default. Unexpected external model traffic is a failure. Ensure all Java-side LLM entry points are substituted, including any directly qualified raw client a scenario uses.

Keep core IDs and production mode selection semantics in the scenarios; do not hide defects by testing an unrelated generic mock provider only. Normal missing-dependency tests remain deliberately failing scenarios. Happy-path E2E must reach its intended completed/approval state with mocks, rather than accept FAILED merely because no real key was supplied.

### 8.4 Preserve coverage and the pipeline

Before replacing legacy fixtures, map every affected test to its business purpose and replacement. Preserve agent lifecycle/readiness, Aria multi-turn/stream/cancel, Kanban pickup and review, workflow/SDD gates, knowledge/tool behavior, git/artifact flow, and MCP parity. Removal of an obsolete LangChain-only protocol test must be justified by actual runtime retirement and mapped replacement coverage, never by a desire to make a failing suite green; shared behavioral tests cannot disappear with that file name.

Do not add skips, disables, expected failures, removed scenarios, or looser assertions to obtain green results. Assertions and mock verifications must compare concrete expected states, values, IDs, file bytes, arguments, and side-effect counts. Do not substitute `any()`, `expect.any(...)`, `anything()`, mere non-null checks, or a list of alternative terminal outcomes for the expected result. Capture dynamic IDs from the operation under test and compare subsequent records against those exact IDs. Keep required mock-backed coverage runnable without live credentials.

Retain the backend build, Java unit/integration separation, frontend build/Vitest, MCP tests, smoke checks, and sharded Playwright jobs. Update `.github/actions/start-stack/action.yml` to start the deterministic core harness instead of provisioning Python/LangChain. Ensure Node/bridge/mock artifacts are available even in backend-only smoke jobs, not just frontend jobs. Keep teardown and failure diagnostics scoped to test-owned resources.

Update change filters and job dependencies for the shared runtime code, bridge package, test fixtures, images, scripts, and workflow/action files so relevant tests cannot be skipped by a path-filter mismatch. Remove Python-only coverage/audit jobs with their package, but preserve relevant mutation, dependency, Java, frontend and MCP checks. Do not lower coverage thresholds or disable shared scenarios to compensate for removal.

Default required PR E2E must work with no Qoder PAT or real LLM API key. Separate live/nightly cases behind explicit live execution and credential configuration. Retarget the current LangChain-based SDD nightly to a supported core, retaining its spec-approval behavior. A missing live credential produces an explicit NOT VERIFIED/skip reason only in that live tier, not in required mock-backed business E2E. Secret presence alone must not make a normal PR test unexpectedly spend credits.

## 9. Acceptance matrix and gates

All requirements below are target checks. None has been executed for this unimplemented design.

| ID | Required scenario | Evidence |
|---|---|---|
| V1 | Qoder Host, Qoder Sandbox, OpenCode Host, OpenCode Sandbox each complete a coding task | Real CLI/model run, independent file/diff and tool/test outcome verification |
| V2 | Both Host workspace strategies; source dirty-worktree behavior; invalid/non-git worktree request; concurrent Direct writer | Preserved source state, exclusive ownership, explicit failure without fallback |
| V3 | Ordinary run and Kanban-dispatched run with live Review ask | Browser interaction and correlated persisted run/approval/card states |
| V4 | Approve once, second write asks again, deny produces no side effect | Native protocol response plus independent filesystem checks |
| V5 | Expiry, duplicate/late/changed-argument/cross-run decision, worker self-approval through REST and MCP | Rejected authorization and no unintended execution |
| V6 | Cancel before session, during prompt, pending permission, result collection; crash/restart | Verified owned process/container termination, no replay, no lost Direct lock while writers survive |
| V7 | Missing CLI/key, invalid model, unreachable sandbox, protocol failure | Correct error and no core/mode/workspace fallback |
| V8 | Managed credentials/config; hostile ambient/project settings and SDK variables | No unauthorized inheritance, escalation, secret leakage, or accidental personal login |
| V9 | Long run and human wait near deadline; sandbox renewal | Hard deadline honored and resource kept alive only while owned execution is valid |
| V10 | Agent edit during a run and later new run | Stable execution binding for the first run; new selection only on the next |
| V11 | Aria conversation history, streaming/cancel, workflow/SDD, git/artifact and knowledge/tool regressions | Existing business scenarios ported and executed, not silently removed |
| V12 | Mixed-core retirement preview/execute, shared JSON/kanban links, direct agent-owned children, repeated cleanup | Scoped hard deletion, protected-data comparison, no dangling links or filesystem purge |
| V13 | Fresh install and upgrade; cleanup absent and explicitly executed; built-in initialization | No supported LangChain path or hidden migration/fallback; other-core configuration preserved |
| V14 | Required CI with real credentials unset and no personal CLI installed | Deterministic mock-backed E2E succeeds through real application boundaries |
| V15 | Real sandbox with mock protocol peers, including a writer surviving prompt completion | Actual SDK/container lifecycle; writers stop before stable export; environment destruction and lock release follow export |
| V16 | Core/mode selection, masked credentials, readiness/errors, Review decisions, pause/resume/cancellation UI | Browser golden/edge paths and console/network checks |
| V17 | Manual pause/resume through ordinary and Kanban entry points; approval/expiry/cancel while paused | No false PAUSED state, task advancement or writes after acknowledgment; no approval bypass, deadline extension, or repeated completed write |

Record OS, architecture, backend mode, core/bridge versions, selected and observed model, and actual available usage for live results. Missing accounting is not zero; one zero-credit call does not prove future runs are free. The first validation environment includes Windows Host execution and Linux sandbox images; do not advertise untested Host operating systems from a mock result alone.

After implementation and deterministic regression, the explicitly requested real Qoder E2E uses model ID `efficient` exclusively. Do not silently substitute `auto`, another tier, a mock, or a standalone echo probe. Capture model selection/acknowledgment and available effective-model/usage evidence; do not relabel a configured model as an independently observed model. Missing credentials or unmet capabilities block the requested live acceptance rather than becoming a skipped success.

### Delivery gates

1. **Capability gate:** verify distributable/pinned CLI artifacts, licensing/install assumptions, Host and Linux-image startup, controlled configuration, model/authentication, native permissions, pause/resume, cancellation, and process supervision. Unsupported behavior is a blocker, not a reason to bypass governance or drop preserved lifecycle scenarios.
2. **Integration gate:** implement the shared bindings/backends/adapters, workspace/credential/permission lifecycle, API/MCP/UI changes, and deterministic fixtures. Keep business coverage intact while removing legacy-only paths.
3. **Regression gate:** run impacted tests per task, integration per wave, then full Java/frontend/MCP and browser regression plus the live four-combination matrix. Preserve separate mock and real evidence.
4. **Retirement gate:** verify the cleaner on mixed-core disposable data first; perform actual scoped cleanup only as a separately confirmed operation after replacement verification. No ordinary startup mass deletion.

The implementation plan must list file ownership, dependencies, independent work waves, and non-conflicting build lanes. Writers must not concurrently run Maven against the same module/output. No commits, pushes, CI changes, runtime launches, or real-data cleanup are authorized by this design-writing session alone.

## 10. Verification status of this document

Verified inputs are the cited committed source/configuration, the historical committed spike, the metadata-only CLI observation above, and the user's explicit decisions. The four target runtime combinations, new authorization boundary, mock CLI/harness, revised pipeline, and deletion mechanism are **NOT IMPLEMENTED / NOT VERIFIED**.

The next gate is user review of this written specification. Only after that approval should a new implementation plan replace the superseded sandbox-only plan. The approved implementation plan is [Cross-Core Host/Sandbox Execution Implementation Plan](../plans/2026-09-22-agent-core-execution-modes.md). No successful build, test suite, CI run, browser check, native-core permission enforcement, or data cleanup is claimed here.
