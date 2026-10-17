# Fleet Runtime Governance — Design (2026-10-11)

Status: approved-by-dialog (operator decisions recorded in §2)
Amends: docs/superpowers/specs/2026-09-22-agent-core-execution-modes-design.md (§11 amendment added there on approval)

## 1. Problem

The operator (single-operator local deployment) wants ONE runtime for the whole agent
fleet, configured once:

- Configure the fleet's core + execution mode in the dashboard's Configure section;
  applying it covers every agent — no per-agent configuration.
- Mixed usage is NOT allowed: any action that would put the fleet into two distinct
  (core, mode) pairs is hard-rejected.
- Changing the runtime in running time must be smooth: no manual cleanup, no errors —
  running work transitions by state class (§5), not by cancellation.

Today the runtime is emergent from per-agent configs (currently mixed: 3x qoder/HOST,
3x opencode/SANDBOX), there is no UI to change an existing agent's core/mode, and the
operator must not need per-agent operations.

## 2. Operator decisions (2026-10-11 dialog)

1. Enforcement strength: **hard block** mixing (409), not a warning.
2. Configuration shape: **fleet-wide only** — one core+mode pair applied to all agents;
   per-agent editing is explicitly removed from the UI ("no need to let agent config
   each by each").
3. Live migration: **drain for RUNNING runs; answer-time auto-migration for
   WAITING_INPUT runs; new runs directly on the new core.** No cancel+redispatch.
4. Indicator: a RUNTIME card under Executive Summary · LIVE (Overview page), plus
   per-agent and per-run badges.

## 3. Fleet runtime model

- The fleet runtime is **emergent**: the distinct set of (adkProvider, executionMode)
  over non-retired agents. It is not separately stored — uniformity is enforced at
  every write, so the emergent value is always either one pair (uniform) or a
  transient mixed set that only `PUT /api/v1/agents/runtime` can converge.
- Enforcement matrix for `POST /api/v1/agents` and `PUT /api/v1/agents/{id}`:

| Fleet state | Write (core, mode) | Result |
|---|---|---|
| uniform A | A | 200 |
| uniform A | B (B != A) | **409** `Runtime mixing is not allowed: the fleet runs A; use PUT /api/v1/agents/runtime to change it fleet-wide` |
| mixed (A+B) | anything | **409** `The fleet runtime is mixed; converge it first via PUT /api/v1/agents/runtime` |

- Retirement/deletion is exempt (removing an agent can never create a mix; it can
  only converge one).
- `PUT /api/v1/agents/runtime` {adkProvider, executionMode}: one transaction updating
  every non-retired agent's core/mode. Validates the pair against the ADK provider
  inventory (declared core; supported modes). Authority posture matches the existing
  agent config routes (unauthenticated config CRUD, loopback auto-authority).
- Existing frozen-binding guarantees are unchanged for runs already dispatched: a run
  is never silently re-routed; the ONLY re-route path is the explicit, operator-driven
  migration in §5 (explicit is not silent).

## 4. Backend changes (act-agent)

1. `PUT /api/v1/agents/runtime` batch endpoint (+ AgentService.applyRuntimeToAll).
2. Uniformity guard in AgentService.createAgent/updateAgent (matrix above); conflict
   surfaces as 409 via the existing exception mapping.
3. `RunResponse` gains `coreId` + `executionMode`, mapped from the run's
   RunExecutionBinding (null when the run has no binding).
4. Cross-core answer migration (act-execution):
   - The comparison is made **loop-side**: after the parked loop wakes on an answer,
     and before it re-prompts, it compares its frozen (coreId, executionMode) with
     the agent's current config. The /answer route needs no special handling — it
     settles and wakes exactly as today.
   - Unchanged core → today's behavior byte-for-byte (same-session continuation,
     bare answer, no history).
   - Diverged → migration branch: the loop tears down the current runtime (verified
     stop + finalize of the old runtime), re-freezes a superseding binding for the
     run (recording the operator-driven migration), opens the new runtime on the new
     core, and continues with a re-seeded task: `CoreTask(system, history =
     trajectory turns, userPrompt = the answer)`. CoreTask already carries history.
   - The [NEED-INPUT] seed rule applies as today on qoder cores (appended by
     QoderCoreSession).
5. Spec §2 amendment: same-session continuation is guaranteed only while the core is
   unchanged; a core change continues via context re-seed from the durable trajectory.

## 5. Migration semantics per run state

| State | On Apply / on answer | Indicator |
|---|---|---|
| PENDING | next dispatch uses the new core (fresh binding) | — |
| RUNNING (mid-turn) | **drain**: finishes on its frozen core; no error; runs out naturally | "draining xN" on the RUNTIME card while any frozen != current |
| WAITING_INPUT (parked) | **answer-time migration** (§4.4): answering resumes on the new core with re-seeded context; nothing for the operator to do | part of draining count until answered |
| terminal | none | — |

Draining count = non-terminal runs whose frozen (core, mode) != their agent's current
config. It reaches zero without operator action (runs end; parked runs are answered
or finalized).

## 6. Frontend (act-dashboard)

1. **ConfigureModal — new "Runtime" tab** (replaces the abandoned per-agent idea):
   - One core dropdown + one mode dropdown (governed inventory options; a removed
     core renders unsupported), Apply-to-all button with a ConfirmDialog showing the
     composition change (from A/B mix -> target).
   - Composition display: uniform -> green `qoder/HOST - 6 agents`; mixed -> amber
     breakdown (`qoder/HOST x3 - opencode/SANDBOX x3`).
2. **Executive Summary - LIVE (OverviewPage)**: new RUNTIME stat card showing the
   composition (uniform green / mixed amber / draining count). Click opens the
   Configure modal on the Runtime tab.
3. **Crew**: agent cards and the AgentDrawer header gain a `core - mode` badge
   (agent data already carries adkProvider/executionMode).
4. **RunsPage**: each run row shows its frozen `core/mode` (from the extended
   RunResponse; `-` when null).
5. Query invalidation on runtime apply: agents, runs, kanban-items, dashboard
   summary.

## 7. Testing

- Backend (act-agent): uniformity matrix tests (create/update x uniform/mixed),
  batch endpoint tests (all agents updated in one transaction; inventory-invalid
  pair rejected), RunResponse mapping (with/without binding).
- Backend (act-execution): cross-core migration — parked run + agent core switched +
  answer -> old runtime torn down (verified stop recorded), new binding superseding,
  run completes on the new core, trajectory re-seeded (history present), same-core
  answers unchanged (regression pins from Plan B stay green).
- Frontend (vitest): Runtime tab (render, apply flow, confirm dialog), RUNTIME stat
  card (uniform/mixed/draining), Crew badge, Runs row badge.
- Live: apply qoder/HOST on the local fleet (currently mixed) -> badges converge,
  dispatch a small task end-to-end on the converged fleet.

## 8. Out of scope

- Per-agent editing UI (explicitly removed by the operator decision).
- Cancel+redispatch of RUNNING runs.
- Automatic provisioning of the qoder SANDBOX image (license-gated); HOST is the
  supported qoder mode on this deployment.
- Historical per-run core statistics.

## 9. Known consequences (disclosed to the operator)

- After converging to qoder/HOST, the SDD BA/DEV/QA agents run on the qoder core for
  the first time — a small verification task should follow the apply.
- A mid-turn core change is physically impossible; the drain semantics mean the OLD
  core must remain usable until drained (true on this deployment: both cores installed).
- The same-session continuation guarantee is consciously narrowed to same-core
  continuations (§4.5 amendment).
