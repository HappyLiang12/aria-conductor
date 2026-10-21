# Architecture Review — PR #109: Retire the Legacy NATIVE/ADK Execution Path

- Date: 2026-10-21
- Scope: branch `refactor/retire-legacy-native-path`, PR #109 against `main`
- Reviewed head: `02e4453e` (base merge-base `5d16e349`)
- Merge status: PR #109 merged on 2026-10-21 as squash commit `60d542f1`; this review was performed
  against the pre-merge head above.
- Review type: pre-merge architecture risk and quality review
- Stakeholders: maintainers, operators, future contributors to the run-owned core runtime
- Decision under review: merge PR #109, then close the retirement issue batches

## 1. Review Goal

Confirm that the retirement of the dead in-JVM NATIVE/ADK execution path does not leave the
system with unresolved correctness, operability, data, or validation risks, and separate risks
introduced by this change from pre-existing accepted risk.

Risk tolerance applied: high sensitivity to run-state correctness and data loss; medium
sensitivity to operational cost and test coverage of retained surfaces.

## 2. Evidence Base

| Evidence | Result |
|----------|--------|
| Diff scope | 303 files changed, 1,306 insertions, 24,032 deletions (2 added, 145 modified, 156 deleted) |
| CI on reviewed head | `gh pr checks 109` → 16 pass, 0 fail; PR state OPEN, MERGEABLE |
| Execution tests | `mvn test -pl act-execution` → 928 tests, BUILD SUCCESS |
| Migration and seed tests | `act-app` → 60 tests, BUILD SUCCESS (includes `MigrationIntegrationTest.v69_retiresTheLegacySurface_andKeepsLiveAsks`) |
| SDD tests | `SpecReviewCoordinatorTest` → 25/25 |
| Engine dispatch tests | `AgentLoopEngineCoreDispatchTest` → cancellation cases at lines 258 and 274 |
| Contract checks | `node --test e2e/agent-core/cutover-contract.test.mjs` → 6/6 |
| Frontend | `pnpm build` + 480 vitest tests pass |
| MCP server | 157 tests pass |

Command outputs and source references are recorded in [evidence.md](evidence.md).

## 3. What Changed

- Removed the in-JVM agent loop, its ToolHandler SPI, harness profiles, tool assignment
  controllers/services, and the tool-governance E2E specs that asserted only those surfaces.
- Added `V69__retire_legacy_execution_surface.sql`: normalizes `NATIVE` agents to `ADK`,
  disables legacy tool definitions, deletes assignment rows, retires `TOOL` knowledge items,
  expires only `TOOL_CALL` + `APPROVAL` + `LEGACY_GATE` asks, and deletes harness/circuit-breaker/ADK
  configuration keys.
- Hardened run start/cancel races in `AgentLoopEngine` and `RunRepository` with conditional
  status writes and updated-at maintenance.
- Restored full SDD spec retrieval by reading the BA run's captured `spec.md` from the run result
  directory (bounded to 50K chars), falling back to `finalOutput`.
- Retained `ToolPackController` because the git handoff still resolves credentials from the git
  pack; pack approval no longer enables any tool.

## 4. Current-State Architecture (post-change)

```text
Operator / Kanban / Housekeeping
        |
        v
RunService (act-agent) --CANCELLED + RunCompletedEvent--> RunExecutionListener
        |                                                      |
        |                                                      v
        |                                            AgentLoopEngine (mirror state)
        v
Flyway V69 (retirement)                     Run-owned core runtime (OpenCode/Qoder)
                                                    |
                                                    v
                                        RunWorkspaceService capture -> result-root/<runId>/spec.md
                                                    |
                                                    v
                                        SpecReviewCoordinator (act-knowledge) reads spec.md
```

The run-owned core runtime is the only execution path. The legacy tool catalogue survives as
non-dispatchable history (`tool_definitions.enabled = FALSE`), and pack management survives for
credential handoff.

## 5. Findings

Scores use probability (1–3) × impact (1–3). Confidence describes evidence strength, not severity.
Every finding lists whether this PR introduced or exposed it.

| ID | Severity | Score | Confidence | Finding | Evidence | Introduced by PR |
|----|----------|-------|------------|---------|----------|------------------|
| R-01 | High | 2×3=6 | High | External cancel records `CANCELLED` and publishes an event, but no production code asks the live core for a verified stop. `CoreExecutionService.cancel` (the verified-stop path) has zero non-test callers. A cancelled run's core can keep executing side effects and consuming tokens until the wall-clock deadline. | `RunService.java:208-219`, `RunExecutionListener.java:32-49`, `AgentLoopEngine.java:515-538`, `CoreExecutionService.java:839`, `docs/architecture.md:77-78` | No — restored main behavior, now documented truthfully |
| R-02 | High | 2×3=6 | High | No per-run token cap: a runaway run is bounded only by the wall-clock deadline. Cost exposure is explicit and accepted. | `AGENTS.md:61`, `docs/architecture.md:77-78` | No — pre-existing, explicitly accepted |
| R-03 | Medium | 2×2=4 | High for coupling, medium for frequency | SDD spec retrieval crosses the module boundary through the filesystem: `act-knowledge` recomputes the result-root path from a duplicated property default, while `act-execution` captures the file. If result-root is reconfigured for one module only, or storage relocates, spec review silently falls back to `finalOutput` (truncated/summary). | `SpecReviewCoordinator.java:66,78,89,280-315`, `RunWorkspaceService.java:87-107,243-250`, `application.yml:113` | Yes — added in the review-fix round |
| R-04 | Medium | 2×2=4 | Medium | Deleting three governance E2E specs removes coverage for tool-pack registration and risk tiers; those features are gone, so that removal is expected. The retained pack credential path (git handoff) never had E2E coverage even before this PR, and the pack APIs remain live. The credential gap is pre-existing; the deletion removes the only adjacent governance suite. | `e2e/agent-core/coverage-map.json:1458-1470`, `ToolPackController.java:14-19`, deleted `git-pack-governance.spec.ts` at `5d16e349` (registration `:49-68`, risk tiers `:28,75-90`; no credential case) | Partly — spec deletion introduced; credential gap pre-existing |
| R-05 | Medium | 1×3=3 | High | V69 is destructive and irreversible: deletes tool assignments, nulls `agents.tool_ids/skill_ids`, and deletes configuration keys. Recovery is a restore of the pre-upgrade backup; Flyway re-applies V69 on the next start. The migration test replays the statements, but no pre-upgrade backup check exists. | `V69__retire_legacy_execution_surface.sql:9-45`, `MigrationIntegrationTest.java:81-135` | Yes — new forward migration |
| R-06 | Low | 2×1=2 | Medium | Residual tool/pack schema and APIs remain intentionally (`tool_packs`, `pack_credentials`, `tool_definitions`, `ToolPackController`). They are not dispatch paths, but they preserve a second meaning of "tool" for future readers. | `ToolPackController.java:14-19`, `V69` comments 11-12 | No — retention decision |
| R-07 | Low | 1×2=2 | Medium | Approval decision races remain possible outside the ACP path: `ApprovalGate.decideApproval` does a plain read-modify-write, and `cancelAllPendingForRun` settles rows separately. The ACP decision path is serialized by an in-process monitor plus a PESSIMISTIC_WRITE lock, but SPEC_REVIEW decisions are not. | `ApprovalGate.java:62-91,116-132`, `PermissionCoordinator.java:280-290`, `ApprovalDecisionLockRepository.java:24-28` | No — pre-existing |
| R-08 | Low | 1×2=2 | High | Contract asymmetry: REST `CreateAgentRequest.agentType` is `@NotNull`, while the MCP `create_agent` tool defaults a blank value to `ADK`. This is intentional convenience but must stay documented. | `CreateAgentRequest.java:26-27`, `AgentTools.java:58,65-67`, `packages/mcp-server/src/tools/agents.ts:22,29` | Yes — docs/contracts cleaned up in this PR |

### Findings mapped to diagrams

- [risk-map.dot](risk-map.dot) — impact paths for R-01, R-02, R-03, R-04, R-05, R-06, R-08
- [risk-quality.dot](risk-quality.dot) — quality attributes → architecture elements → all risks R-01..R-08
  (R-07 appears only in this view)
- Rendered SVGs: [risk-map.svg](risk-map.svg), [risk-quality.svg](risk-quality.svg)
  (Graphviz 16.1.0, portable Windows build)
- Quality scenarios and current status: [quality-scenarios.md](quality-scenarios.md)
- Prioritized actions: [remediation-plan.md](remediation-plan.md)
- Business-application fitness: [business-readiness-findings.md](business-readiness-findings.md)

## 6. Accepted Behavior Changes

1. External cancel is a recorded transition, not a verified stop. UI/API consumers must not infer
   that compute has stopped. This matches `main`; the PR removes a prototype that could not obtain
   `StopProof` from parked/exited runtimes and documents the actual semantics.
2. No per-run token cap. Runs are bounded by the wall-clock deadline only.
3. `agentType` remains required on the REST contract and optional with an `ADK` default over MCP.
4. V69 intentionally expires only legacy-gate asks; live review, clarification, and ACP asks survive
   (asserted by `MigrationIntegrationTest`).

## 7. Assumptions and Unknowns

| ID | Assumption | Why it matters | Validation step |
|----|------------|----------------|-----------------|
| A-01 | Deployment is single-node. Admission is an in-process monitor and the result root is a local filesystem path. | R-01 and R-03 change shape if multiple backend nodes share a database but not a filesystem; V69 and admission would also need coordination. | Document deployment topology; if multi-node is planned, add a shared storage/admission design before enabling it. |
| A-02 | Operators take a database backup before upgrading to V69. | V69 cannot be rolled back safely by code. | Add a pre-upgrade backup note to release instructions; verify on a staging copy. |
| A-03 | `RunCompletedEvent` is the only terminal signal that downstream consumers rely on for cancel. | R-01's blast radius depends on this. | Grep consumers of `RunCompletedEvent`; confirm none of them assumes compute has stopped. |

## 8. Verdict

**Mergeable from an architecture-risk perspective, with tracked follow-ups.** No Critical finding
was introduced. The two High-severity items (R-01, R-02) predate this PR and are accepted system
properties, not regressions; R-01 is now documented instead of implied. The Medium items are
contained: R-03 has a safe fallback, R-04 is documented in the coverage map, and R-05 has a test
that proves its postconditions. At review time, merge required explicit operator confirmation per
the project's merge protocol; the PR was merged on 2026-10-21 as `60d542f1`.

Recommended before merge: confirm the release note includes the V69 backup/repair guidance and the
"cancel records state; compute is bounded by the deadline" operator wording.

Recommended after merge: open the R-01 follow-up (coordinator-owned verified cancel), the R-03
follow-up (single result-root contract), and the R-04 follow-up (pack credential E2E or an explicit
decision to drop the surface).
