# Risk map evidence index

Every node and risk edge in `risk-map.dot` traces to one of the sources below. Confidence follows the evidence model: high means direct code/config/test evidence, medium means multiple partial signals, low means inference.

## Nodes

| Node | Meaning | Source refs | Confidence |
| --- | --- | --- | --- |
| `actionable` | Actionable failure type and repair surface | `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/exception/ActionableFailure.java`, `.../ErrorSurface.java:8` | high |
| `handler` | HTTP error body adds code/surface for actionable failures | `.../GlobalExceptionHandler.java:148-167`; `GlobalExceptionHandlerMappingTest` | high |
| `engine` | Completion error selection, bounded message, persistence | `.../AgentLoopEngine.java:1934-1966`, `2109-2122`; `AgentLoopEngineWaitingInputTest` | high |
| `wrapper` | Non-TaskExecutionException causes wrapped in TaskExecutionException | `.../AgentLoopEngine.java:1268-1275`; `TaskExecutionException.java:10` | high |
| `runctx` | First actionable failure wins | `.../RunContext.java:140-159`; `RunContextTest` | high |
| `deadline` | Deadline timer with reason recorded after verified cancel | `.../CoreExecutionService.java:839-877`, `1095-1115`; `CoreExecutionServiceTest` | high |
| `coordinator` | Termination failure map and consumption | `.../RunInputCoordinator.java:137-158`; `RunInputCoordinatorTest` | high |
| `v70` | Run error identity columns | `act-app/src/main/resources/db/migration/V70__run_error_contract.sql` | high |
| `event` | Event and response carry identity | `RunCompletedEvent.java`, `RunResponse.java`, `RunServiceTest` | high |
| `v71` | Deadline config seed | `V71__seed_task_deadline_config.sql`; `V71SeedTaskDeadlineConfigTest` | high |
| `controller` | Write-time deadline validation | `SystemConfigController.java:54-82`; `SystemConfigControllerTest` | high |
| `props` | Runtime deadline read with clamping | `TaskDeadlineProperties.java:53-54`; `TaskDeadlinePropertiesTest` | high |
| `provider` | Provider-level legacy deadline fallback | `application.yml:51-56`; `OpenCodeAdkProvider.java:848-855` | high |
| `presentation` | Surface to action mapping and legacy text matching | `act-dashboard/src/api/errorPresentation.ts:6-110`; `errorPresentation.test.ts` | high |
| `notice` | Repair action rendering | `ErrorNotice.tsx`; drawer/workspace tests | high |
| `panel` | UI range metadata and Save guard | `SystemConfigPanel.tsx:48`, `210-325`; `SystemConfigPanel.test.tsx` | high |
| `unit`, `ci` | Test and CI evidence | `mvn clean test ...` BUILD SUCCESS, 2,757 tests; CI `39603811844` success | high |
| `fault` | Fault-injection E2E | `act-dashboard/e2e/api/fault-injection.api.spec.ts:15-17`, `45` | high |

## Risk edges

| Risk | Edge | Why it is a risk | Evidence | Confidence |
| --- | --- | --- | --- | --- |
| R-01 | deadline -> unvalidated path | Timer/stop/persistence is unit-tested with a manual scheduler; no integration or E2E covers the real scheduled path | `CoreExecutionServiceTest` (ManualDeadlineScheduler); `grep -rn "aria.tasks.deadline\|system-config\|SystemConfig\|Run Deadline" act-dashboard/e2e` empty | high |
| R-02 | fault -> provider, stale premise | Spec says `opencode.max-task-minutes` governs the task deadline; the engine now passes `aria.tasks.deadline-minutes` via `TaskContext` | `fault-injection.api.spec.ts:15-17,45`; `AgentLoopEngine.java:1147-1149`; `application.yml:51-56` | high |
| R-03 | actionable/presentation/v70 | Enum, TS union, persisted strings, and text matcher must stay in sync; no contract test | `ErrorSurface.java:8`; `errorPresentation.ts:6-47`; V70 | high |
| R-04 | props/controller/panel/v71 | Four independent 1-1440 definitions | Paths in the report table | high |
| R-05 | wrapper -> runctx | Top-level type check loses identity through wrappers; no current producer inside `executeTask` | `RunContext.java:152`; `AgentLoopEngine.java:1268-1275`; grep for `throw new Actionable` in `adk/` and `runtime/core/` empty | medium |
| R-06 | event -> uninstrumented | Identity is persisted and rendered but not counted | grep for `MeterRegistry` in `act-execution`/`act-agent` empty | high |
| R-07 | v70 -> checksum change | Editing an applied migration breaks validation; repair-on-migrate does not cover successful migrations | Observed local Flyway checksum mismatch; schema-history query | high |
| R-08 | actionable -> no guidance | Contract absent from agent/architecture docs | grep of `AGENTS.md`, `docs/architecture.md` empty | high |
| R-09 | coordinator -> no sweeper | Map is consumed only on completion | `RunInputCoordinator.java:137-158`; no cleanup path | medium |

## Rendering

Graphviz was not available in this environment (`dot -V` -> command not found), so only the DOT source is committed. To render:

```bash
dot -Tsvg docs/reviews/2026-10-21-pr108-error-contract-risk-review/risk-map.dot \
  -o docs/reviews/2026-10-21-pr108-error-contract-risk-review/risk-map.svg
```
