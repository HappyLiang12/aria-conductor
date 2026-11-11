# Release note — Aria PA experience (feat/aria-pa-experience)

Program: `docs/superpowers/plans/2026-11-11-aria-pa-experience.md`
Spec: `docs/superpowers/specs/2026-11-11-aria-pa-experience-design.md` (commit `a752acef`)
Status at the time of writing: **implemented, not released.** Push, PR and hosted CI are the operator's
checkpoint and have not been performed. Every "NOT RUN" below is a real gap, not a formality.

## What this release is

Aria stops being a chat box that occasionally needs a different page and becomes a personal assistant
the operator can actually run from the conversation. Three waves, twelve defects (F1-F12):

- **Wave A — conversation flow and approvals.** A parked turn renders its question as a card in the
  conversation and approving it resumes the same turn. One parked conversation no longer blocks the
  next one. Restored history shows an activity strip, not raw JSON assistant bubbles. Every tool's
  governance label is derived from the catalog instead of hand-stamped.
- **Wave B — the truth surface.** A token total of zero is no longer presented as a measured zero when
  the core reported nothing. The five memory READ tools are auto-approved, so a read-only memory turn
  raises no operator ask, while `remember_fact` still asks. Two false capability claims (a tool said to
  be absent, a template said to be unapproved) are replaced by counts the server actually returns.
- **Wave C — memory operation and authority honesty.** Aria can propose a brand-new entity and its
  first fact in one call, the operator sees and decides it on a card without leaving the chat, and
  every operator-only decision actually requires the operator on both the MCP and the REST surface.

## What ships

**Conversation and signals**
- Ordered per-turn SSE signals (`approval_required`, `waiting_input`, `memory_proposal`, tool
  activity) merged idempotently with the `/topic/events` WebSocket and REST reconciliation.
- One shared pick rule (`PendingClarificationAsks.newest`) so the sync reader and the SSE listener name
  the same ask for one park.
- Per-conversation Aria admission with a global cap; a second conversation proceeds while the first is
  parked.
- A panel reducer with explicit settled states, a collapsible activity strip, and restored tool
  status/result identity keyed by the producer's own row-id derivation.

**Truth surface**
- `usageState` (`REPORTED` / `NOT_REPORTED`) and the nullable token total on `RunResponse`
  (`RunResponse.java:44`, classified by `RunService.usageStateOf:526`); `runsWithoutUsage` on the
  dashboard summary. The aggregate (`RunRepository.sumReportedUsage`) and the classifier share one
  expression, so a measured zero and a silence are never conflated.
- The token KPI reads "not reported" with the count of runs that reported nothing.
- `TemplateListing(templates, totalApproved)` replaces the false "no template is approved" claim.

**Memory operation**
- `remember_fact` accepts either `entityId` or `kind` + `canonicalKey`. Only the **new-key** shape
  creates a PENDING entity together with its first PENDING fact in one transaction, and one operator
  decision promotes both. An existing ACTIVE or PENDING key adds a fact to that entity; a RETIRED or
  MERGED key is re-proposed to PENDING by a worker call, with the fact waiting on that proposal.
- A proposal batch is the entity's PENDING facts (spec `:116-117`, `:323-324`). The `entityId` filter on
  `GET /api/v1/memory/proposals` returns exactly the set the decision promotes, uncapped.
- The Memory page lists and opens PENDING entities through an operator-authorised review read; the
  in-panel card shows the batch, settles honestly, and reopens only a `decided-elsewhere` card when the
  same identity row returns to PENDING.
- Aria's prompt now tells it that it has a memory, to recall before asking, to propose durable facts,
  and never to claim a fact is remembered before approval or to decide its own proposals.

**Authority**
- All ten operator-only MCP tools refuse a worker credential with `errorType = FORBIDDEN` and an
  actionable message naming where the operator decides.
- The REST twins are gated: agent retirement, the five LLM-provider mutations, housekeeping execute,
  the fleet-runtime convergence write, report regenerate/archive, and every memory read.
- Refusals in the dashboard point at the operator session instead of rendering a raw HTTP message.

## Schema and configuration changes

**None.** No Flyway migration is added or required. No new configuration key, no environment variable,
no compose change, no Dockerfile change. The re-proposal path reuses the existing identity row because
`UNIQUE(kind, canonical_key)` forbids a second one.

## Upgrade notes

Nothing to run. Two behaviour changes affect clients other than the dashboard:

1. **Operator authority is now resolved on routes that previously resolved none.** A call presenting a
   worker/run-scoped bearer is refused (403) even from loopback. An anonymous call from a non-loopback
   address is refused (401 in the agent/report modules, 403 in the memory module, whose exception
   handler maps `SecurityException` to 403). An anonymous loopback call is still the local operator, and
   an operator session cookie still works, so the dashboard is unaffected in its documented
   single-operator localhost deployment.
2. **`GET /api/v1/memory/entities/{id}?status=`** now rejects any value other than `PENDING` with a 422
   VALIDATION naming the supported value. Previously four of the five values were silently ignored.

## Behaviour changes to watch

- **The fleet-runtime write is operator-only.** `PUT /api/v1/agents/runtime` still deliberately bypasses
  the per-agent runtime-MIX guard (that decision is unchanged and pinned), but it now resolves operator
  authority first. A worker can no longer rewrite the fleet's `(core, mode)` pair.
- **`POST /api/v1/reports/generate` and `/{id}/amend` stay worker-permitted**, because their MCP twins
  `generate_report` / `amend_report` are `ToolPolicy.WORKER_WRITE`. Gating them would have broken a
  worker capability. See the residual below about their forwarding payloads.
- **A read-only memory turn raises zero operator asks.** `remember_fact` and the other writes still ask.
- **A PENDING entity is invisible to every agent-facing read** — recall, the context contributor, the
  default list, the default detail, `get_memory_entity`, and the proposals inbox (which withholds ENTITY
  rows from a non-operator). The operator sees it through the explicit review read.
- **A 409 on a proposal decision is disambiguated**: an already-decided conflict settles the card as
  decided elsewhere; a retryable optimistic-lock conflict keeps a retry that can succeed. The
  discrimination is a POSITIVE marker, not a substring of the retryable text: the card settles only
  when the body is `errorType = CONFLICT` **and** its message contains
  `a decided proposal cannot be reopened` (`AriaProposalCard.tsx:54,68-75`). Every other 409 — including
  the current-value slot's `another writer won the race` and the command service's
  `re-read before retrying` — keeps the ordinary error path and its retry, so an unknown wording fails
  safe toward retryable. The parent-still-PENDING refusal has its own marker and settles on the entity
  decision (`:65-66`). The REST envelope is closed to four error codes, so no new code or field was
  added; the server-side mutual-exclusion pins keep the markers disjoint.
- **A re-proposal returns the same identity row to PENDING.** An ACTIVE row is never demoted, and a
  re-proposal retires the retired entity's stale live graph so a new batch cannot inherit old values.

## Verification evidence (filled at each gate)

| Tier | Command | Result |
| --- | --- | --- |
| Start-script self-tests | `bash scripts/lib/workspace-roots.test.sh`; `pwsh -NoProfile -File scripts/lib/startup-checks.test.ps1` | 15 passed / 0 failed; 38 assertions — PASS at `94955f56` |
| Cross-lane frontend typecheck | `npx tsc --noEmit -p tsconfig.json` | rc=0, 0 bytes — PASS at `9cc22a6a`, `94955f56` |
| Cross-lane Java compile | `mvn -o -B -T 1C test-compile -DskipTests` | BUILD SUCCESS at `57689bdf` |
| Frontend unit suite | `npx vitest run` | 89 files / 852 tests passed at `58e20c68` (coverage OFF) |
| Per-lane module gates | `bash scripts/mvn-lane.sh inner <modules> -am` | green per lane; counts in each task report |
| Full unit tier | `wave-gate.sh` step `unit` | **NOT RUN** at the final head |
| Failsafe tier | `wave-gate.sh` step `itg` | **NOT RUN** at the final head |
| Frontend coverage ratchet | `pnpm test -- --coverage` | **NOT RUN** — no lane ran coverage; this is the first run |
| MCP server coverage | `pnpm test -- --coverage` | **NOT RUN** |
| Dashboard build | `pnpm build` | **NOT RUN** at the final head |
| Harness packaging IT | `mvn -Pcore-e2e-harness -pl act-app verify -Dit.test=CoreE2ePackagingIntegrationTest` | **NOT RUN** at the final head |
| Clean-room | fresh worktree, no target/node_modules/dist, full gate | **NOT RUN** |
| Artifact-first E2E | `e2e-gate.sh` — packaged harness + built dist + 5 smoke endpoints + Playwright `--repeat-each=2` | **NOT RUN** |
| Live PA bar | runbook Phase 1 O1-O5 on a fresh database, browser only | **NOT RUN** |
| Governance probes | runbook Phase 1b P1a-P9 and Phase 3 | **NOT RUN** |
| Five-turn scenario | runbook Phase 2 against the 2026-11-10 baseline | **NOT RUN** |
| Hosted CI matrix | push + PR + `workflow_dispatch` | **DEFERRED to the operator's checkpoint** |
| Docker build verification | ci.yml `:335-352` | **DEFERRED to hosted CI** (no Dockerfile changed) |

## Residual risks

1. **Authority is resolved per route, not by a filter — so the surface is only partly closed.** This
   program added `resolveOperator` to the routes it names above; it did NOT register
   `ActorAuthenticationFilter`, add Spring Security, or change `server.address` (the operator explicitly
   chose the narrow fix). Two consequences follow, and both are live:
   - **HOST-mode loopback GET.** A HOST core is a child process on the backend host, so its `webfetch`
     GET arrives from loopback and is auto-authorised on routes that resolve authority. The governed
     HOST template denies `bash`/`write`/`edit` and allows `webfetch`/`websearch`, so this residual is
     read-only GET, not mutation.
   - **Routes that resolve no authority are reachable by ANY caller, including a sandbox worker.**
     `RunMcpWiring.java:76-77` gives the core the same host:port as the REST API, so a sandbox is not
     network-isolated from it. Mutating routes outside this program's gated set still resolve none; the
     per-route table in `.superpowers/sdd/2026-11-11-aria-pa-experience/final-review-rest.md` is the
     authoritative list. The most destructive of them, `DELETE /api/v1/aria/conversations/{id}` (purges
     trajectories, tool calls and audit rows, and cancels runs), is being gated in the fix round.
   A SANDBOX worker's peer address is the bridge gateway rather than loopback, so it is refused on
   routes that DO resolve authority — that is not a protection on routes that resolve none. Closing the
   whole surface needs the filter registration or bind-address change the operator declined.
2. **The TS MCP report forwarding payloads do not match the Java DTOs.** `reports.ts:14,36` send fields
   `GenerateReportRequest` / `AmendReportRequest` reject, so the worker path to those two ungated routes
   is not currently exercisable. Pre-existing, outside this program's scope, and NOT fixed here. If the
   payloads are ever aligned, those routes become live worker writes and their policy must be re-checked.
3. **The plan's hosted-CI-before-next-wave gate was not satisfiable within the objective's boundary.**
   Waves B and C ran without a hosted-green predecessor. The local ladder is the substitute evidence and
   is weaker in one known way: OS/Node/browser portability and the Docker image job are untested locally.
4. **MariaDB execution** is unverified; every local tier runs the H2 profile.
5. **The qoder usage spike outcome** is to be measured in the live phase and recorded here.
6. **Coverage ratchets were exercised once, ahead of the full gate.** A non-`~/.m2`-writing coverage
   preflight at `df93716d` passed with a wide margin (log:
   `.superpowers/sdd/2026-11-11-aria-pa-experience/coverage-preflight-df93716d.log`). The full clean-room
   gate re-runs both threshold-enforcing tiers; a threshold failure there is a real failure to triage,
   not to suppress.
7. **The retryable-409 wordings are not all pinned server-side.** The card's positive-marker rule
   (above) means a divergent retryable tail cannot mis-settle it: `MemoryCurrentValueSlot.java:50-51`
   says `another writer won the race — re-read before writing` and `MemoryCommandService.java:781` says
   `re-read before retrying`, and neither carries the already-decided marker, so both keep a retry.
   The residual is TEST COVERAGE, not behaviour: the taxonomy pins in `MemoryProposalServiceTest` cover
   the two proposal flushes, and a fix round is extending them to the slot-claim wording reachable from
   `decideFact`. Until that lands, a future edit to the slot wording would not be caught by a pin.