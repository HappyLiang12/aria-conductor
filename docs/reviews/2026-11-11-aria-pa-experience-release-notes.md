# Release note — Aria PA experience (feat/aria-pa-experience)

Program: `docs/superpowers/plans/2026-11-11-aria-pa-experience.md`
Spec: `docs/superpowers/specs/2026-11-11-aria-pa-experience-design.md` (commit `a752acef`)
Status at the time of writing: **implemented and published as PR #120.** The hosted CI matrix is green
at the gated head `1d081b01` (a docs-only commit; the product tree is `43cbc00a`). A later fix round
re-runs CI on push. Every "NOT RUN" below is a real gap, not a formality.

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
  creates a PENDING entity and its first PENDING fact as one proposal batch; the fact proposal commits
  in its own transaction (`MemoryProposalWriter.insertProposal` is `REQUIRES_NEW`, so a lost
  proposal-hash race stays survivable), and one operator decision promotes both. An existing ACTIVE or
  PENDING key adds a fact to that entity; a RETIRED or MERGED key is re-proposed to PENDING by a worker
  call, with the fact waiting on that proposal.
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

**Operator safety and refusal honesty (added by the final whole-branch review's fix round)**
- **The live tool frames carry what the audit row carries.** `CoreEventTelemetry` redacts the
  `tool_call` `args` and `tool_result` `result` it streams, exactly as the persisted row is redacted, so
  the activity strip can no longer show a credential the approval card shows as `[REDACTED]`.
- **A memory proposal parks no turn**, so one conversation's undecided proposal no longer disables
  another conversation's composer. The parked-approval and parked-clarification gates are unchanged.
- **A refused clarification answer settles** instead of inviting a retry that can only 409, and the
  composer unlocks. The in-flow approval card now **shows the arguments it is approving**, read through
  the existing REST surface, so a governed write is no longer approved blind.
- **A cross-site refusal is distinguished from an authority refusal on every surface.** The client
  reads the marker from the field each controller actually sends (`message`, `error`, `reason`,
  `detail`), and the tests use those measured body shapes rather than an invented one.
- **A fact blocked on its still-PENDING entity keeps the Reject the server still accepts** (only
  Approve is removed), and settles honestly once a provably-complete inbox page stops listing it.
- **`update_llm_provider` keeps its promise**: a null OR blank optional argument leaves the stored
  value unchanged, so a model sending `""` to mean "keep" can no longer blank a provider's base URL or
  API key.
- **Aria's prompt names a tool that exists**: `instantiate_workflow_template(templateId, parameters)`.
  The governed-template decision is unchanged; the name and every pin were corrected together under an
  operator-approved amendment to the 2026-08-13 spec. `remember_fact`'s description is now true for an
  operator call on a PENDING key and states the re-proposal's retire side effect.
- **Memory kernel atomicity**: the entity INSERT is forced inside the unique-violation catch so a
  concurrent same-key write re-reads the winner instead of failing raw; a rejected fact can be
  proposed again (the probe no longer matches a closed row); retiring an entity closes its open facts
  so no proposal is left decide-less.
- **`DELETE /api/v1/aria/conversations/{id}` resolves operator authority before any read or side
  effect.** It purges trajectories, tool calls and audit rows and cancels runs, and it is reachable
  from a sandbox, so it is now gated like the other destructive routes.
- Clear's copy no longer claims a run keeps living server-side; the Memory page labels a capped page as
  a page rather than a total; the operator notice keeps its 6px spacing as a block-level span; and the
  shipped `aria-reserved: 3` is pinned from the real YAML resource, not only from the Java default.

**The final scoped fix round (T48) — what it changed on the shipped surfaces**
- **Clear now does what its title says for an operator with a session.** The DELETE and the synthesize
  POST were the last mutations outside the shared axios transport, so they carried neither the operator
  `X-CSRF-Token` (the cookie branch of `OperatorAuthorityResolver` runs BEFORE the loopback branch, so
  the gate refuses a tokenless call) nor `X-Correlation-ID`. Both go through `client` now. The
  synthesize route resolves no operator authority today, so rerouting it changes no outcome there; it
  removes the class of call that would break the day that route is gated, and no backend gate was
  invented for it. A refused or failed Clear **keeps the transcript** and says what refused it, why
  nothing was cleared, and where the fix is — the old silent fallback cleared the panel while the
  server still held the conversation.
- **The client's PENDING-inbox cap is pinned to the server's page size** (`PROPOSAL_INBOX_LIMIT` vs
  `MemoryProposalService.INBOX_LIMIT`), so the two can no longer drift into settling a live card as
  decided elsewhere. The panel keeps one source for the number.
- **A batch read still in flight is no longer reported as a change** in the memory proposal card: the
  decision controls stay disabled until the read answers, and a failed first read says it failed
  instead of asking the operator to confirm a change that never happened.
- **An MCP call that presents no credential no longer claims it was an operator decision.** It reused
  the operator-only template, whose tail says "is an operator decision" — a decision the platform
  never made on that path, because nothing had identified the caller. It now says the call carried no
  verifiable credential and was refused before any authority decision. The worker refusal keeps the
  old clause, because there the caller was identified and refused.
- **The ten operator-only descriptions state the auth-mode=none refusal** instead of naming only the
  run-scoped worker (upgrade note 3 below).

## Schema and configuration changes

**No schema change.** No Flyway migration is added or required, and the re-proposal path reuses the
existing identity row because `UNIQUE(kind, canonical_key)` forbids a second one. No new configuration
key, no environment variable, no compose change and no Dockerfile change.

One shipped default changes: `aria.runs.aria-reserved` moves from 1 to 3 in both places that ship it
(`application.yml:78`, `RunAdmissionProperties:24`), with per-conversation serialization. An
installation that sets `ARIA_RUNS_ARIA_RESERVED` keeps its own value.

## Upgrade notes

Nothing to run. Six behaviour changes affect clients other than the dashboard:

1. **Operator authority is now resolved on routes that previously resolved none.** A call presenting a
   worker/run-scoped bearer is refused (403) even from loopback. An anonymous call from a non-loopback
   address is refused (401 in the agent/report modules, 403 in the memory module, whose exception
   handler maps `SecurityException` to 403). An anonymous loopback call is still the local operator, and
   an operator session cookie still works, so the dashboard is unaffected in its documented
   single-operator localhost deployment.
2. **`GET /api/v1/memory/entities/{id}?status=`** now rejects any value other than `PENDING` with a 422
   VALIDATION naming the supported value. Previously four of the five values were silently ignored.
3. **On the shipped default `aria.mcp.auth-mode: none`, a call that presents no MCP credential is
   refused by all ten OPERATOR_ONLY tools — the operator included.** The mode requires no credential
   from the caller and injects none into the request, so no actor is published and
   `McpActorContext.require` fails closed before
   `requireOperator`. The ten descriptions now say that instead of naming only the run-scoped worker.
   This is about the credential a caller sends, not about a mode disabling authentication: a caller
   that presents a valid operator bearer is resolved in every mode
   (`McpServerConfig.actorTransportContext:176-180`; in `actor` mode the filter accepts any actor
   `McpActorContext.resolveBearer` returns, `McpTokenFilter:124-129`). No configuration key changed.
4. **An MCP call with no verifiable credential is refused with a new sentence.** The message used to
   reuse the operator-only template ("… is an operator decision"), which claimed an authority decision
   that path never makes. Same `errorType` (`FORBIDDEN`), same named surface (reconnect with an
   authenticated credential), different wording. A client that matched on the old sentence text was
   matching a claim that was not true; match on `errorType` and the code instead.
5. **`aria.runs.aria-reserved` defaults to 3 (was 1).** Up to three Aria runs may execute at once;
   Aria runs serialize per conversation, so a parked turn blocks only its own conversation. Set
   `ARIA_RUNS_ARIA_RESERVED` to keep the old cap.
6. **`POST /api/v1/memory/entities` answers 409, not 201, when the key resolves to a PENDING or
   decided row.** Nothing was created in that case. A PENDING row is decided in its proposal card; a
   RETIRED/MERGED row is reopened only by a worker's re-proposal, and PATCH cannot change a
   non-ACTIVE entity.

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

Scratch outputs cited below (`gate-final8/`, `final-gate-report-8.md`, `live-bar-report-*.md`,
`live-evidence/`, `.superpowers/`) are not committed; the plan names the steps they were produced from.

| Tier | Command | Result |
| --- | --- | --- |
| Start-script self-tests | `bash scripts/lib/workspace-roots.test.sh`; `pwsh -NoProfile -File scripts/lib/startup-checks.test.ps1` | 15 passed / 0 failed; 38 assertions — PASS at `94955f56` |
| Cross-lane frontend typecheck | `npx tsc --noEmit -p tsconfig.json` | rc=0, 0 bytes — PASS at `9cc22a6a`, `94955f56`; also rc=0 at the T48 head (`pnpm exec tsc --noEmit`) |
| Cross-lane Java compile | `mvn -o -B -T 1C test-compile -DskipTests` | BUILD SUCCESS at `57689bdf` |
| Frontend unit suite | `pnpm exec vitest run` | 90 files / 899 tests passed at `190f1bc8` (lane recovery, coverage OFF). **NOT RUN at the pre-T48 final head**; later lanes ran focused suites (T41: 24 files / 311 tests; T40 recovery: 1 test). **PASS at the T48 head: 92 files / 964 tests, 0 failed** |
| Per-lane module gates (T48) | `bash scripts/mvn-lane.sh inner act-mcp -am`; `... inner act-aria -am` | act-mcp: 273 tests, 0 failures, 0 skipped (ToolCatalogParityTest 19, including the 67/65/10 and 57-line/<3000-char pins; McpActorContextTest 4); act-aria: rc=0 — PASS at the T48 head |
| Per-lane module gates | `bash scripts/mvn-lane.sh inner <modules> -am` | green per lane; counts in each task report |
| Full unit tier | `wave-gate.sh final8` step `unit` | **PASS at the final head `1d081b01`**: run=3263 fail=0 err=0 skip=8 (`gate-final8/wave-gate-console.log`, `final-gate-report-8.md`). Intermediate heads also green: `138e5e05` 3240/0/0/8, `887b564b` 3257/0/0/8 |
| Failsafe tier | `wave-gate.sh final8` step `itg` | **PASS at `1d081b01`**: run=265 fail=0 err=0 skip=6 |
| Frontend coverage ratchet | `pnpm test -- --coverage` | **PASS at `1d081b01`**: 92 files / 1008 tests, coverage 79.85% statements / 83.09% branches / 68.37% functions / 79.85% lines against thresholds 5 / 60 / 40 — no threshold error |
| MCP server coverage | `cd packages/mcp-server && pnpm test -- --coverage` | **RUN at `1d081b01`**: 20 files / 157 tests pass. The gate collects no coverage because `vitest.config.ts:9-14` sets every threshold to 0; a direct measurement at `17fa515a` gave 88.5 / 89.7 / 86.04 / 87.95. `ci.yml:278` runs the same command, so local and hosted are at parity. **Pre-existing repo gap, not introduced by this program** — see residual 6 |
| Dashboard build | `pnpm build` | **PASS at `1d081b01`**: 11/11 reactor entries BUILD SUCCESS, `dist/index.html` 705 B, rc=0 |
| Harness packaging IT | `mvn -Pcore-e2e-harness -pl act-app verify -Dit.test=CoreE2ePackagingIntegrationTest` | **PASS at `1d081b01`**: run=2 fail=0 err=0 skip=0, BUILD SUCCESS |
| Tool-catalog hard pins | `CatalogProbe` against the packaged harness at `1d081b01` (`gate-final8`, `final-gate-report-8.md`) | **PASS, no pin moved across the whole fix round**: DESCRIPTORS=**67**, registered NAMES=**65**, OPERATOR_ONLY=**10**, generated tool section LINES=**57**, CHARS=**1887**, TRUNCATED=false, SECTIONS=1, ceiling 3000. Identical at `138e5e05`, `887b564b` and `1d081b01` |
| Prompt-pin freshness | `unzip -p` the installed `~/.m2` act-aria jar before and after the gate's `install` step, then run the act-mcp pins | **PASS at `1d081b01`** — closes the re-review's P2. Pre-install jar (mtime 23:18:20, 89667 B) carried the removed sentence `as a guess in the proposal`=1 and `as an explicit guess`=0; post-install jar (mtime 00:19:04, 89652 B) carries `as an explicit guess`=**1** and `as a guess in the proposal`=**0**, with positive controls `remember_fact`=1 and `IMPORTANT`=11. `AriaPromptToolNamesTest` 2/0/0 and `ToolCatalogParityTest` 21/0/0 then read the NEW prompt, so the before/after delta is real rather than null by construction |
| Clean-room | fresh worktree, no target/node_modules/dist, full gate | **PASS at `1d081b01`** in `D:/project/pa-clean2`: build outputs removed and measured before the run, `git rev-parse` verified before and after, `mvn -o -B -T 1C clean install -DskipTests` at 00:18:56-00:19:04, 13/13 steps rc=0, overall rc=0. The 16 pre-existing modified screenshot PNGs survived the head move untouched. Earlier from-scratch clean-rooms: `17fa515a` |
| Artifact-first E2E | `e2e-gate.sh final8` and `final8b` — packaged harness + built dist + 5 smoke endpoints + Playwright `--repeat-each=2`, **run twice** | **PASS at `1d081b01`, both runs.** The harness started **natively from the delivered script** (no shim, no replay): `harness boundary: OK`, `harness health: OK`, `setup rc=0`, 5/5 smoke endpoints OK, `dist preview: OK`. Run 1 (`gate-final8`): **517 passed / 0 failed / 1 flaky / 42 skipped, rc=0** (15.7m). Run 2 (`gate-final8b`, fresh work root and fresh DB): **518 passed / 0 failed / 0 flaky / 42 skipped, rc=0** (14.8m). `aria-timeout-cancel.spec.ts:146` and `core-execution-modes.spec.ts:238` each passed both repeats in both runs with no retry markers. Run 1's single flake was `core-permissions.spec.ts:470` (peer-runtime ownership verification), retry-green in 2.2s and repeat-green in 2.1s — the recorded P-1 shared-job class; run 2 had zero flakes. Both ports released by each run's own trap. **The run is doubled deliberately**: the P-2 database-closed defect below once fired in 1 of 2 runs, so one green run would not establish absence. Greps for `90098`, `ClosedByInterruptException`, `database has been closed` and `SQLException` across all four logs (both backend, both Playwright) returned **0/0/0/0**; the 173/172 backend ERROR lines are deliberate negative paths, none mentioning jdbc/h2/closed/interrupt. Artifacts byte-identical before and after both runs |
| P-2 regression — interrupt during H2 I/O closed the database process-wide | `CoreProgressPump.stop()` no longer interrupts; RED then GREEN, then two artifact E2E runs | **FIXED at `138e5e05` (T57).** Before the fix, `stop()` interrupted the poll worker on the assumption that the interrupt only shortens the poll interval, but the worker also runs the sink synchronously (`CoreProgressPump.emit` → `OpenCodeCoreSession` → `CoreExecutionService.publishProgress` → `EventBroadcastListener` → Hibernate insert). An interrupt landing inside that H2 file read raised `java.nio.channels.ClosedByInterruptException`, closing H2's single shared `FileChannel`; every later request then failed `The database has been closed [90098-232]` until restart (6,237 occurrences, 31 Playwright specs failed). `stop()` now clears the running flag and `LockSupport.unpark`s the worker, and the poll wait is `parkNanos` + a flag re-check, so **no interrupt is ever delivered** — the mechanism is removed, not the window narrowed. RED reproduced the actual exception (36 run / 1 fail), then GREEN 36/0/0 on three consecutive runs; module gate `inner act-execution -am` 1511/0/0/0 |
| Live PA bar | runbook Phase 1 O1-O5 on a fresh database, browser only | **PASS — bar met at `138e5e05`** (`live-bar-report-O.md`, evidence in `live-evidence/`). A brand-new operator onboarded entirely in chat on an empty H2 (the previous `data/` moved aside to `data-prev-20261112-221454`, never deleted). **O1** "Hi, I'm Happy…" produced the `New entity person:happy` card plus 3 fact cards in the panel (entity `c8e11398`, 4 PENDING rows in the DB). **O2** the card settled `approved — this memory is now active`; `PERSON person:happy ACTIVE 14:19:28.964Z`, PENDING → 0. **O3** two fact cards (`response_bullets_max 3`, `timezone Asia/Hong_Kong`). **O4** both settled approved, facts ACTIVE, the invented `timezone=UTC` RETIRED `14:22:40Z`, PENDING → 0. **O5** the answer named Happy / Asia/Hong_Kong / max-3-bullets and rendered exactly 3 bullets; run `41645916` shows 2 read-tool rows both policy-`APPROVED` and **zero operator asks** (the memory-read auto-approval amendment working live). **Zero curl and zero page switches for all six operator actions**; the one Configure visit is labelled platform bootstrap, not onboarding, and its confirm dialog now reads the true `opencode/SANDBOX x4` pre-state (F2 fixed, verified live). Console: 0 errors during O1-O5. Artifacts verified by sha256 before launch (jar `65cf15ae…c475`, dist `9e0e0db2…6d07`, bridge `dist/main.js` `30007f1f…b5af`); ports released afterwards by stopping only the seat's own PIDs, identified by command line. **Five findings were recorded by that seat; four are now fixed and independently reviewed (F-O3, F-O4, F-O2, F-O6 — T58/T59/T61/T62/T63), and F-O5 remains OPEN / NOT VERIFIED because its recorded causal story is contradicted by shipped code** — see residuals 8 and 14-18. The bar itself was met on the pre-fix artifact; the fixes are gated at `1d081b01` and their live re-confirmation is owed at the next live bar |
| Governance probes | runbook Phase 1b P1a-P9 and Phase 3 | **PASS on every step that has a producer** at `17fa515a` (`live-bar-report-B.md` Phase 3, `live-bar-report-A.md` P-table): 3.1 PENDING never leaks **PASS** (absent from the manifest while PENDING, present after the operator's in-panel approval); 3.2 no successful self-approval **PASS** with the PENDING-row leg exercised; 3.4 prompt honesty **PASS**; P5 usage truth **PASS** (`not reported` + "6 runs reported no usage", API `{"NOT_REPORTED":6}`); P7 non-loopback authority **PASS** (4/4 mutations 401, no side effect, re-read 40 min later unchanged). **3.3 / P1a / P1b / P6 / P8 NOT RUN — no external producer**: `RunMcpWiring.forRun:51` wires MCP only for Aria, and a live worker reported 34 CLI tools and no MCP namespace. Tier-verified instead: 10/10 tools classified OPERATOR_ONLY, worker refusal + operator positive control in `McpActorTransportIntegrationTest` (`Tests run: 11, Failures: 0` at `gate-final4/itg.log:1399`). **P9 MEASURED RESIDUAL**: unauthenticated loopback `GET /api/v1/llm-providers` → 200 with `apiKeyMasked ****c230` — the disclosed cost of the human-approved narrow authority scope |
| Five-turn scenario | runbook Phase 2 against the 2026-11-10 baseline | **PASS** on the live stack with the real qoder core (`live-bar-report-B.md` T1-T5): briefing with in-panel approval resume and clarification park/resume on the same run id; read-only memory turns produced 8 policy approvals and zero operator asks while the single write asked; usage honesty ("total tokens burned reads 0, with 31 runs reporting no usage data") replaces the baseline's asserted zero; plan honesty about APPROVED templates; recall returned 3/3 DB-verified ids; a second conversation ran while the first was parked. Phase 2 restored-history-after-reload **PASS**. Six live findings surfaced and **were fixed and re-reviewed in T53-T56** (F-B1 raw protocol dump, F-B2 case-sensitive kind filter, F-B3 no retry-without-filter, F2 fleet-banner truth, F3 credential-rejection classification, F-LC1 settled-card wording). **F-B4, the one finding that round left open, is now fixed too** (T58/T61, recall reaches a shaped canonical key by its tail) — see residuals 8 and 14 |
| Hosted CI matrix | PR #120 to `main`, triggered automatically on `pull_request` | **GREEN at `991ef091`** (docs-only commits after product tree `43cbc00a`: `43cbc00a` -> `1d081b01` -> `991ef091` -> `2f65a9d9`): run **43266287720** (`CI`) conclusion `success` — Detect Changed Trees, Frontend Build Check, Build Backend + Harness Distribution, Start Script Self-Tests, Java Integration Tests (Failsafe), all five Java Unit Test groups (execution, api-mcp-app, knowledge-aria, memory, common-agent), E2E Smoke Test (deterministic harness), all four E2E Playwright shards, Docker Build Verification. Run **43266287569** (`Sandbox Lifecycle (real OpenSandbox)`) conclusion `success`. **`TS MCP Server Tests + Coverage` was SKIPPED, and the skip is correct**: `dorny/paths-filter` (`ci.yml:41-53`) gates it on `packages/mcp-server/**`, and `git diff --name-only main...HEAD` contains **0** files under that path — this program changed the Java `act-mcp` module (covered by the green `api-mcp-app` unit job), not the TS package. The local MCP run (20 files / 157 tests) was extra assurance, not a tier this diff required |
| Docker build verification | ci.yml `:335-352` | **PASS on hosted CI** (run 43266287720, `Docker Build Verification` success). No Dockerfile changed in this program |

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
     trajectories, tool calls and audit rows, and cancels runs), is now gated — and the dashboard's
     Clear, which drives it, carries the operator CSRF token through the shared transport (T48), so the
     gate does not lock the operator out of the one route it protects.
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
6. **Only ONE of the two coverage ratchets has ever been exercised.** A non-`~/.m2`-writing dashboard
   coverage preflight at `df93716d` passed with a wide margin (90 files / 882 tests; 79.91% lines /
   82.28% branches / 66.44% functions against thresholds 5 / 60 / 40). Reproduce it with
   `cd agent-control-tower/act-dashboard && pnpm test -- --coverage`. The captured log lives in the
   plan's git-ignored scratch workspace
   (`.superpowers/sdd/2026-11-11-aria-pa-experience/coverage-preflight-df93716d.log`) and is therefore
   NOT a committed artifact — the command and the head are the durable evidence. **The `packages/mcp-server`
   coverage tier HAS now been run** (20 files / 157 tests at `17fa515a` and `138e5e05`) and it collects no
   coverage: `packages/mcp-server/vitest.config.ts:9-14` sets lines/functions/branches/statements thresholds
   to 0. A direct measurement gave 88.5 / 89.7 / 86.04 / 87.95, so the code is well covered but the ratchet
   is inert. `ci.yml:278` runs the same command, so local and hosted are at parity — this is a pre-existing
   repo gap, not a regression from this program. Raising those thresholds is a one-line follow-up.
7. **The retryable-409 taxonomy is pinned for the decision path only.** The card's positive-marker rule
   (above) means a divergent retryable tail cannot mis-settle it: neither
   `MemoryCurrentValueSlot.java:50-51` (`another writer won the race — re-read before writing`) nor
   `MemoryCommandService.java:781` (`re-read before retrying`) carries the already-decided marker, so
   both keep a retry. The slot-claim wording IS now pinned
   (`MemoryProposalServiceTest.SLOT_WON_RACE:106` and
   `anApprovalThatLosesTheEntitySlotCarriesTheSlotWordingAndNotTheDecidedMarker`), because `decideFact`
   runs the slot claim before either flush. `re-read before retrying` is deliberately NOT pinned as a
   decision wording: the taxonomy javadoc (`:94-96`) records that it belongs to the private
   `MemoryCommandService.flushFacts`, unreachable from a proposal decision. The residual is that a
   future edit to that command-path wording would not be caught by a decision-path pin.
8. **F-B4 — `recall_memory` misses the operator's own REPO entities (live, Major). FIXED in T58 + T61,
   independently reviewed — see residual 14 for the mechanism and the two halves of the fix.** Measured on the
   live stack: `recall_memory {"text":"aria-conductor repository release notes PR feedback version"}`
   returned only the `agent:aria` entity, and Aria told the operator "Nothing on file for the
   aria-conductor repo" while the DB held two ACTIVE REPO rows (`HappyLiang12/aria-conductor` and
   `https://github.com/HappyLiang12/aria-conductor.git`). Mechanism from source:
   `MemoryRecallService.recall` first queries `findByCanonicalKeyInAndStatus` with keys derived by
   `MemoryMentionExtractor.candidateKeys` (single words + 2-3 grams, so `aria-conductor`), which equals
   neither stored key, so the lexical fallback must win on its own. **Operator-facing cost:** Aria asks
   for facts it already holds — the opposite of the "recall before asking" contract. Caveat measured, not
   inferred: both rows carry `owner/repo` and URL-shaped keys rather than `repo:`-prefixed keys, so a
   data-shape contribution cannot be excluded. Evidence:
   `.superpowers/sdd/2026-11-11-aria-pa-experience/live-bar-report-B.md` F-B4 with `B-T3-timeline.json`
   entries 4-5 and `B-entities-pre.json`. **Fixed by the same change as residual 14**: a shaped canonical key is now reachable by its
   trailing segment, so both REPO rows are recalled by `aria-conductor`, with a recall test seeding
   exactly those two keys. The legacy key shapes are NOT normalised — no migration ships — but they are
   now reachable, which is the measured operator-facing defect.
9. **The entity-kind vocabulary exists twice.** `MemoryQueryService.normalizeKind` and the extracted
   `MemoryEntityKind` carry the same seven-kind set and the same trim/upper-case/refuse-unknown rule.
   Both are tested, so they cannot silently diverge today, but the read path should delegate to
   `MemoryEntityKind` — a one-line change plus deleting the private copy.
10. **Clear's cancel set skips PAUSED.** `RunService.java:59` and `CoreExecutionService.java:1229-1236`
    enumerate the cancellable states without PAUSED, so a paused run survives a conversation Clear.
    Measured in review, not live; the operator-visible effect is a run that keeps its conversation link.
11. **The Aria card shows the ACP `input` payload unredacted while the Review Queue masks it.** Two
    surfaces, one datum, two policies. Neither leaks a credential in the measured turns, but the
    inconsistency is a truthfulness defect waiting for a tool whose input carries one.
12. **P-1 — shared-job identity race in the E2E harness.** `CoreE2eProcessBackend.java:205` can attribute
    a job to the wrong run under concurrent reuse. Harness-only (test infrastructure), no product path;
    it did not fire in either final E2E run.
13. **Legacy lowercase `kind` rows.** The normalisation added in T53/T55 refuses unknown kinds and
    upper-cases input, but rows written before it keep their stored spelling; an operator merge would be
    needed to canonicalise them. No migration ships in this program, by design.

The five findings below were measured by the final onboarding seat at `138e5e05` on a **fresh empty
database** (`live-bar-report-O.md`). They are recorded, not fixed: the bar they were found in is met, and
a product edit now would invalidate the gated head. Each carries its mechanism and its remedy.

14. **F-O3 — recall returns empty for the operator's own entity (Major, product).** On a clean DB,
    `recall_memory` returned **empty** twice for "What do you remember about me?" while `person:happy`
    held 4 ACTIVE facts. The entity's stored `displayName` is the canonical key `person:happy`, not
    "Happy", and recall matches only canonical key / display name / aliases / focus
    (`MemoryMentionExtractor.java:102-116`) after a candidate-key plus bounded-lexical pass
    (`MemoryRecallService.java:75-104`) — **fact values are never matched**, and the operator's name lives
    only in the `display_name` fact. Empty recall means the automatic manifest `memory` section is absent
    (`MemoryContextContributor.java:93-101`, `:158-166`): 4 manifests, 0 `memory` sections. Aria answered
    O5 correctly by falling back to `list_memory_entities` + `get_memory_entity`, so the bar passes on the
    tool path while the injection path is dead. **This independently corroborates residual 8 (F-B4) on a
    clean database, so it is not a stale-DB artefact.** **FIXED in T58 + T61, independently reviewed (verdict: approve).** Two halves: an approved entity
    whose name was defaulted now adopts its operator-approved `display_name` fact (only within the
    entity's own sensitivity, never overwriting an operator-chosen name, recorded in the audit
    assertion), and a shaped canonical key is reachable by its tail — `person:happy` by `happy`, both
    REPO shapes by `aria-conductor` — with a shape guard so a namespace-only key (`person:`) cannot name
    an entity from prose. Recall still does NOT match fact values: that was deliberately rejected,
    because fact values can be sensitive and recall egress is fail-closed at the ceiling.
    **Already-stored defaulted names:** recall is fixed for them, but their cards still render the
    canonical key. The repair path is a PATCH rename, or retire -> re-propose -> approve — NOT
    "re-approve", which is unreachable for an ACTIVE key. No backfill ships, because this program adds
    no migration.
15. **F-O4 — a resumed run asserts stale proposal state (Major, product/prompt). FIXED in T59,
    independently reviewed.** The run parked on a clarification; the operator approved the entity batch
    at `14:19:28Z`; on resume the run's final output asserted "the three `person:happy` memory proposals
    are still PENDING and will stay that way until you decide them" — false at `14:20:15Z`, when the rows
    were ACTIVE and `?status=PENDING` returned 0. Evidence: run `637f9cad` `finalOutput`,
    `O2-entities-after.json`, `O2-proposals-after.json`. The prompt now requires a fresh
    `list_memory_proposals` read in the same turn before any present-tense status sentence, otherwise
    "as of my last check", and names park/resume as a moment that invalidates an earlier read. The rule
    is prompt-side by ruling: the defect is an assertion the model makes from stale tool output, not a
    value the backend computes. **A text pin holds text, not obedience** — live re-confirmation of the
    park -> approve -> resume sequence is owed at the next live bar.
16. **F-O2 — unrequested facts proposed as memory (Minor, prompt). FIXED in T59 + T62, independently
    reviewed.** For "Hi, I'm Happy. I'm the operator here." the model proposed `role = Aria Conductor
    operator` and `timezone = UTC`, neither stated by the operator, and invited a new operator to approve
    both. The ruled wording is now: propose only facts the operator actually stated in this turn;
    anything inferred is said in prose as an explicit guess and is NEVER proposed as a fact. The first
    attempt told Aria to "label a guess in the proposal itself", which is uncarryable — `remember_fact`
    takes `entityId, factKey, valueText, sourceRef, kind, canonicalKey` and builds
    `AddFactCommand(factKey, valueText, valueType, sensitivity, sourceRef)`, with no label field, so the
    instruction could only have polluted the value the operator approves. T62 removed that channel and
    moved the constraint BEFORE the T22 instruction that licenses proposing, following the T53 precedent
    in the same file. The T22 sentence itself is unchanged.
17. **F-O6 — "Tokens burned: 0" in prose while usage is unreported (Minor, prompt honesty). FIXED in
    T59, independently reviewed.** The O1 briefing printed the numeral `0` with an honest parenthetical,
    while `usageState` is `NOT_REPORTED` and every token field is `null`. The prompt now requires the
    prose to say `not reported` exactly as the dashboard KPI does, and never to print a numeral `0` for
    an unreported state.
18. **F-O5 — empty-DB first load logs a 404 (Minor, UI/console). OPEN / NOT VERIFIED — the recorded
    causal story is contradicted by shipped code.** The live seat reported
    `GET /api/v1/aria/conversations/latest` -> 404 plus `net::ERR_ABORTED` on the first load against a
    fresh database, with nothing user-visible. The independent re-review verified, and the controller
    confirmed by reading the code, that `AriaConversationController.getLatest()` returns
    `ResponseEntity.noContent().build()` — **204, never 404** — when no run has a conversationId. So that
    endpoint cannot produce the reported 404 on an empty DB, and a client-side null-reading cannot remove
    a browser-logged network 404 in any case. The console capture was never preserved as an artifact,
    only prose. **T60's client change is kept** (reading 404 as "none yet" is correct REST and matches the
    same file's DELETE convention) but the finding is NOT closed. The producer is unattributed.
    INFERRED, not proven: a first page load racing backend readiness, before MVC mappings are registered,
    would answer 404 for a mapped path. Next step: on the next live first load, capture the console AND
    the network entry as committed artifacts, read the response body and the correlation id, and
    attribute the producer before any further code change.
19. **Card-settle polling window (observation, INFERRED, not a defect claim).** For ~2.5s after a batch
    decision the three sibling fact cards still showed `pending` with live Approve/Reject although their
    rows were ACTIVE; a reload reconciled them. The panel settles cards off its PENDING-inbox reconcile
    (`AriaProposalCard.tsx:186-190`, `:203-206`), which polls — the observed window may simply be the poll
    interval, which was not measured. Confirm the interval before treating this as a defect.
20. **Short, numeric and common-word key tails still match (disclosed, not fixed).** The T61 shape guard
    refuses an EMPTY tail, so `person:` no longer names an entity from prose. A tail of `a`, `1` or
    `main` is still a valid non-empty segment and still matches that ordinary word at rank 0, where it
    can displace a real DISPLAY_NAME match inside `MAX_ENTITIES = 12`. A minimum-length rule would not
    fix `main` or `123`, so closing this needs a common-word/stopword decision, not a one-line guard.
    No measured operator-facing failure yet; recorded rather than expanded into another round.
21. **The FOCUS token pass is still open (disclosed, pinned as known).** A focus reference of `person:`
    no longer yields a guarded tail, but `candidateKeys` -> `addPhrases` still adds the bare token
    `person` to the exact-key candidate set, so an entity whose canonical key is literally `person`
    remains reachable at rank 0 from that focus. T63 pins this at the `candidateKeys` level as a
    disclosed residual, so a future change to the token pass cannot pass silently. The tail clause is
    closed; the token pass is not.
22. **`rePropose` discards an operator-chosen display name (pre-existing).**
    `MemoryCommandService.java:209` resets `displayName` to the canonical key when a command supplies
    none, so a re-proposal against an existing key silently drops a name the operator chose. T58's
    adoption on approve partly masks the symptom. Pre-existing, outside this program's five findings,
    and not fixed here; the remedy is to preserve a non-defaulted stored name on the re-propose path.