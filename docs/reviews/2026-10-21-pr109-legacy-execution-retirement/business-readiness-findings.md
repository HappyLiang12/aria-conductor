# Business-Application Fitness Findings — PR #109

This repository is a governance/orchestration platform, not a transactional business app, but it
has business-critical workflows: agent execution, SDD spec review, approvals, kanban transitions,
and knowledge versioning. The dimensions below apply to those workflows.

## Dimension Summary

| Dimension | Assessment | Evidence |
|-----------|------------|----------|
| Business model clarity | Mostly clear; one vocabulary seam remains ("tool" now means pack/credential only) | `ToolPackController.java:14-19`; `V69` comments |
| Data and state understanding | Strong for approvals and runs; V69 is intentionally destructive and documented | `V69:9-45`; `MigrationIntegrationTest.java:81-135` |
| End-to-end path grounding | SDD spec review path improved; cancel path truthful but partial | `SpecReviewCoordinator.java:280-315`; `RunExecutionListener.java:32-49` |
| Agent instruction use | Good: AGENTS.md and architecture docs now state the cost bound, execution model, and API contracts | `AGENTS.md:61`; `docs/architecture.md:77-78` |
| Business validation readiness | Strong unit/integration coverage; one E2E gap for retained pack credentials | `coverage-map.json:1458-1470`; CI 16/16 |

## Findings

### B-01: SDD spec review now grounds the operator decision in the real artifact (positive)

The BA run's captured `spec.md` is read verbatim (bounded at 50K chars) instead of relying on a
summary. This directly improves the spec-approval business step: the reviewer sees what the BA
wrote, not a model-generated digest.

Evidence: `SpecReviewCoordinator.java:280-315`; `SpecReviewCoordinatorTest` 25/25.
Risk: the file handoff depends on a duplicated result-root contract (R-03).

### B-02: Cancel semantics are recorded honestly but the business outcome is ambiguous (risk)

An operator who cancels a run expects the work to stop. The system records `CANCELLED` immediately
and mirrors the state into the engine, but the live core is not asked to stop; it is bounded by
the deadline. The business consequence is that side effects may continue after the UI says the run
was cancelled.

Evidence: `RunService.java:208-219`; `AgentLoopEngine.java:515-538`; no production caller of
`CoreExecutionService.cancel` (`:839`).
Validation: R-01 acceptance criteria; operator wording must not promise stopped compute.

### B-03: Approval lifecycle ownership is explicit after V69 (positive)

V69 expires only `TOOL_CALL` + `APPROVAL` + `LEGACY_GATE` asks. REVIEW_REQUEST (display-only),
CLARIFICATION (run-input lifecycle), and ACP_PERMISSION (native lifecycle) survive. This preserves
the business meaning of live operator asks across the upgrade.

Evidence: `V69:28-37`; `MigrationIntegrationTest.java:132-135`.
Validation: migration test in CI.

### B-04: The retired tool catalogue leaves a vocabulary seam (low)

`tool_definitions`, `tool_packs`, `pack_credentials`, and `ToolPackController` remain. They no
longer dispatch tools; packs exist for git credential handoff. A future reader (human or agent)
may confuse this with the removed tool-execution governance.

Evidence: `ToolPackController.java:14-19`; `V69:11-13` retains definitions for audit.
Validation: document the distinction in `docs/architecture.md`; R-06.

### B-05: Regression coverage is honest about what was removed, but one retained path lost E2E (medium)

Three governance specs were deleted because their features are gone; one spec was trimmed to its
runtime-agnostic approvals case. The coverage map records that no run-owned replacement exists for
pack registration, risk tiers, or harness tool removal. The risk is the retained pack credential
path, not the removed features.

Evidence: `e2e/agent-core/coverage-map.json:1458-1470`.
Validation: add the pack credential E2E or record the drop decision (R-04).

### B-06: Agent instructions now match the runtime (positive)

AGENTS.md no longer describes a Python/ADK runtime, and the execution model, cost bound, and
agent-creation contracts are documented. This reduces the chance that an agent reintroduces the
removed path or assumes a token cap exists.

Evidence: `AGENTS.md` project positioning + high-risk table; `docs/architecture.md:77-78`; this PR's
docs changes.

## Assumptions and Missing Evidence

| Item | Status | Validation |
|------|--------|------------|
| Single-node deployment (in-process admission, local result root) | UNKNOWN | Confirm deployment topology; see A-01 |
| Operators take a pre-upgrade backup | Assumed practice, not enforced | Add to release notes; R-05 |
| No external consumer depends on the removed tool APIs | INFERRED from in-repo references | Check external integrations before closing the migration issue |

## Bottom Line

The retirement improves business-path clarity (one execution model, truthful approvals, real spec
content) but leaves one operator-facing ambiguity: cancel promises state, not stopped compute. That
must be tracked and worded correctly; it is not a merge blocker because it restores existing
behavior rather than introducing it.
