# Evidence Ledger — PR #109 Risk Review

Every claim in the review maps to a command or a committed source location below. Items not
directly observed are labelled INFERRED or UNKNOWN in the review.

## Commands and Captured Output

| Command | Output |
|---------|--------|
| `git merge-base HEAD origin/main` | `5d16e3497ea56401e2e9531bf61d172fb2b1daea` |
| `git diff --shortstat 5d16e349...HEAD` | `303 files changed, 1306 insertions(+), 24032 deletions(-)` |
| `git diff --name-status --find-renames 5d16e349...HEAD \| awk '{print $1}' \| sort \| uniq -c` | `2 A`, `156 D`, `145 M` |
| `gh pr view 109 --json number,state,mergeable,headRefOid,baseRefName` | `{"number":109,"state":"OPEN","mergeable":"MERGEABLE","headRefOid":"02e4453e6fafa6ad2562945c7ef476daa6e91de7","baseRefName":"main"}` |
| `gh pr checks 109 \| awk -F'\t' '{c[$2]++} END {for (k in c) print k, c[k]}'` | `pass 16` |
| `grep -rn "coreExecutionService\.cancel\|\.cancel(runId" --include=*.java agent-control-tower \| grep -v test` | no production caller of `CoreExecutionService.cancel` |
| `grep -n "@Version" .../Approval.java \| wc -l` | `0` |
| `grep -n "deadline" .../runtime/core/OpenCodeCoreSession.java` | deadline check at `:102-109` |
| `grep -n "result-root" .../application.yml` | `113: result-root: ${ARIA_WORKSPACES_RESULT_ROOT:./data/workspaces/results}` |
| `grep -n "capture\|resultRoot" .../RunWorkspaceService.java` | `:87`, `:91`, `:107`, `:243-250` |
| `grep -n "legacyExecutionRetirement" .../coverage-map.json` | `:1458-1470` |

## Test Evidence (run on reviewed head)

| Scope | Command | Result |
|-------|---------|--------|
| Execution engine | `mvn test -pl act-execution` | 928 tests, BUILD SUCCESS |
| Migration/seed | `mvn test -pl act-app` | 60 tests, BUILD SUCCESS |
| SDD | `mvn test -pl act-knowledge -Dtest=SpecReviewCoordinatorTest` | 25/25 |
| MCP server | `pnpm test` in `packages/mcp-server` | 157 tests pass |
| Cutover contract | `node --test e2e/agent-core/cutover-contract.test.mjs` | 6/6 |
| Frontend | `pnpm build` + `pnpm test` | build OK, 480 tests pass |
| CI | `gh pr checks 109` | 16 pass / 0 fail |

## Source References

| ID | Location | Shows | Confidence |
|----|----------|-------|------------|
| S-01 | `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/service/RunService.java:208-219` | Cancel sets `CANCELLED`/`completedAt`, publishes `RunCompletedEvent` only | High |
| S-02 | `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/listener/RunExecutionListener.java:32-49` | Listener carries cancel to engine; javadoc: "records the transition only ... does not stop the core" | High |
| S-03 | `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/engine/AgentLoopEngine.java:515-538` | `cancelRun` updates context/session; no coordinator stop request | High |
| S-04 | `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreExecutionService.java:839` | Verified-stop cancel exists, no production caller | High |
| S-05 | `docs/architecture.md:77-78`; `AGENTS.md:61` | Deadline is the only bound; no per-run token cap | High |
| S-06 | `agent-control-tower/act-knowledge/src/main/java/io/aria/conductor/knowledge/sdd/SpecReviewCoordinator.java:66,78,89,280-315` | Runs root injected via own `@Value`; reads `resultRoot/<runId>/spec.md` | High |
| S-07 | `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/RunWorkspaceService.java:87-107,243-250` | Capture writes to configured result root after verified stop | High |
| S-08 | `agent-control-tower/act-app/src/main/resources/application.yml:113` | Single declared property with local default | High |
| S-09 | `agent-control-tower/act-app/src/main/resources/db/migration/V69__retire_legacy_execution_surface.sql:9-45` | Destructive normalization, scoped ask expiry, config deletion | High |
| S-10 | `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/MigrationIntegrationTest.java:81-135` | Replays V69 and asserts live asks survive | High |
| S-11 | `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/engine/AgentLoopEngineCoreDispatchTest.java:258,274` | Cancel-before-dispatch/provisioning tests | High |
| S-12 | `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/controller/ToolPackController.java:14-19` | Pack API retained for git handoff; no tool enablement | High |
| S-13 | `e2e/agent-core/coverage-map.json:1458-1470` | Deleted/rewritten specs and lack of run-owned replacements | High |
| S-14 | `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/approval/ApprovalGate.java:62-91,116-132`; `PermissionCoordinator.java:280-290`; `ApprovalDecisionLockRepository.java:24-28` | ACP decisions locked; generic decide and sweep are plain read-modify-write | High |
| S-15 | `agent-control-tower/act-agent/src/main/java/io/aria/conductor/agent/dto/CreateAgentRequest.java:26-27`; `act-mcp/.../AgentTools.java:58,65-67`; `packages/mcp-server/src/tools/agents.ts:22,29` | REST requires `agentType`; MCP defaults to `ADK` | High |
| S-16 | `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/RunAdmissionQueue.java:24-48` | Admission state is in-process memory | High |
| S-17 | `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/tool/WorkspaceManager.java:36-67`; `RunWorkspaceService.java:87-107` | Local filesystem roots | High |

## Not Verified

- Whether any external consumer calls the removed tool or harness APIs (INFERRED negative from
  in-repo references only).
- Multi-node behavior of admission, result-root capture, and cancel (UNKNOWN; A-01).
- Actual operator upgrade backups (UNKNOWN; A-02).

## Artifacts

- `architecture-review.md`
- `risk-quality.dot` + `risk-quality.svg`
- `risk-map.dot` + `risk-map.svg`
- `quality-scenarios.md`
- `remediation-plan.md`
- `business-readiness-findings.md`
- `evidence.md` (this file)

## Render Command

```text
C:\Users\User\Tools\graphviz-16.1.0\Graphviz-16.1.0-win64\bin\dot.exe -Tsvg <file>.dot -o <file>.svg
```

Graphviz source: official `windows_10_cmake_Release_Graphviz-16.1.0-win64.zip` release asset
(installed per-user; winget and chocolatey required administrator rights on this host).
