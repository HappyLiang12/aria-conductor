# SDD runtime hardening: triage, dependencies and lanes

Date: 2026-11-07
Source: `docs/reviews/2026-11-06-live-sdd-qa-findings.md` (24 findings from the live SDD exercise)
Base: `origin/main` at `3975da6b`; integration branch `fix/sdd-runtime-hardening`.

## Priority and dependency

### P0 - blocks a trustworthy live SDD rerun

| ID | Finding | Depends on | Lane |
|----|---------|------------|------|
| F13/F10 | Core shell cannot see `gh`, `mvn`, `java`; reduced PATH | - | runtime |
| F14 | Core sees invalid GH tokens; pack credential is not injected | F8 | runtime |
| F20 | Run scratch resolves to the operator git repo | - | runtime |
| F21 | Literal `%SystemDrive%` workspace path | F20 | runtime |
| F11 | SDD prompts hardcode `/workspace/...` | - | workflow |
| F15 | BA starts in an empty scratch, no checkout | F20 | runtime + workflow |
| F19 | QA report commit can delete the branch tree | - | workflow |
| F22 | PASS accepted while AC is `NOT VERIFIED` | - | workflow |
| F8 | Git credential stored but needs a restart; no editor | - | runtime + ui |

### P1 - correctness, safety and operator UX

| ID | Finding | Depends on | Lane |
|----|---------|------------|------|
| F7 | ACP approvals lack tool name/arguments; per-call only | bridge contract | bridge + api + ui |
| F17 | No tool-call/trajectory audit through the product API | bridge events | bridge + api |
| F9 | `totalTokensUsed=0` for real runs | bridge usage event | bridge + api + ui |
| F2 | Sync chat claims completion / hides the run error | - | api |
| F6 | Hikari leak warning on long sync chat | - | api |
| F5 | 500 instead of 400/405 for client errors | - | api |
| F18 | Stale legacy run review requests | - | workflow |
| F24 | Completed step cards stay in REVIEW | F18 | workflow + ui |

### P2 - polish and design decisions

| ID | Finding | Depends on | Lane |
|----|---------|------------|------|
| F3 | Bridge-entry default is cwd-relative; circular hint | - | runtime |
| F4 | Qoder executable default does not resolve on Windows | - | runtime |
| F12 | Windows long-path clone failure | - | runtime |
| F16 | `inject` does not reach a live core | - | api |
| F1 | Qoder credential not adopted from CLI login | - | runtime (preflight + hint) |
| F23 | Maven error counts read as "0 failures" | - | workflow prompt |

## Lanes (file ownership)

| Lane | Worktree / branch | Owns | Must not touch |
|------|-------------------|------|----------------|
| runtime | `D:/project/aria-sdd-lane-runtime` / `lane/sdd-runtime` | `act-execution/.../runtime/**`, `execution/git/**`, `execution/credential/**`, `execution/tool/WorkspaceManager.java`, `application.yml`, `scripts/start-backend.*` | bridge TS, act-aria, workflow listener, kanban, frontend, migrations |
| bridge | `D:/project/aria-sdd-lane-bridge` / `lane/sdd-bridge` | `packages/qoder-acp-bridge/**` | everything else |
| api | `D:/project/aria-sdd-lane-api` / `lane/sdd-api` | `act-aria/**`, `act-common/.../GlobalExceptionHandler.java`, `act-execution/.../controller/ApprovalController.java`, `RunToolCallController.java`, approval DTO/entity, `V76__*.sql` | bridge TS, runtime env, workflow listener, kanban, frontend |
| workflow | `D:/project/aria-sdd-lane-workflow` / `lane/sdd-workflow` | `act-execution/.../listener/WorkflowAutoChainer.java`, `act-agent/.../WorkflowService.java`, `act-execution/.../kanban/KanbanTransitionService.java` + listeners, `V77__*.sql` | runtime env, bridge TS, act-aria, frontend |
| ui | `D:/project/aria-sdd-fixes` / `fix/sdd-runtime-hardening` | `act-dashboard/**` | Java, bridge, scripts |

## Cross-lane contracts

1. Bridge event additions (lane bridge):
   - `permission.request`: `{ requestId, toolName, input, options: [{optionId, kind, name}] }`
   - `tool.call` / `tool.result`: `{ toolCallId, toolName, input, result, isError }`
   - `usage`: `{ inputTokens, outputTokens, totalTokens }` when the ACP frame carries it.
   - launch environment: the bridge forwards an optional `ARIA_CORE_EXTRA_PATH` entry into the
     qoder CLI `PATH` without dropping the inherited value.
2. Runtime (lane runtime) consumes those events and:
   - injects the resolved git pack credential as `GH_TOKEN`/`GITHUB_TOKEN` into the core env,
   - sets `GIT_CEILING_DIRECTORIES` to the run workspace root,
   - sets `GIT_CONFIG_*`/longpaths for Windows,
   - persists tool calls and trajectory rows, and adds token totals to the run.
3. API (lane api) exposes the new approval fields and run usage; the UI lane codes against
   `toolName`, `arguments`, `inputTokens`/`totalTokens` and degrades when absent.
4. Workflow (lane workflow) owns all SDD prompt changes and the verdict/tree guard. The UI lane
   renders `REVIEW` only when a card still has an open ask.

## Verification ladder

1. Task: each lane runs its focused module tests and reports counts.
2. Wave: integration branch runs `mvn test -pl act-execution,act-agent,act-aria,act-app` plus
   `pnpm --dir agent-control-tower/act-dashboard test` and
   `pnpm --dir packages/qoder-acp-bridge test`.
3. Phase: full `mvn clean test -Dspring.profiles.active=h2` on the integration branch.
4. Clean-room: fresh worktree, `pnpm install --frozen-lockfile`, build, unit gate.
5. Live: restart the backend from the integration branch, run the same live SDD scenario
   (Aria picks issue #81, chain BA -> spec review -> dev -> QA), and compare against this plan.

## Risks

- The qoder CLI shell PATH may be a fixed internal PATH; the runtime lane must prove the PATH
  injection with a real core run, not only a unit test.
- The gate changes (F22) can block legitimate PASS verdicts; require a narrow, testable rule
  ("PASS with an explicit NOT VERIFIED acceptance criterion fails the step").
- The UI lane works against new fields before the API lane lands; keep the fixtures tolerant.
- Migrations are fixed: api owns V76, workflow owns V77. No other lane adds a migration.
