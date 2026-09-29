# LangChain retirement — operator execution receipt (2026-09-27)

Task 21 of the agent-core execution-modes plan: the operator-authorized scoped
cleanup, executed against the local development database through the production
routes only (`POST /api/v1/maintenance/langchain/preview` and `/execute`). No
deletion was performed by hand and no SQL was issued.

## Target and authorization

- Environment: this worktree's local H2 database at
  `agent-control-tower/act-app/data/act_db.mv.db`, backend started through
  `scripts/start-backend.sh --skip-sandbox` with a synthetic operator bearer.
- Authorization: the operator's instruction to execute Task 21 for this
  program (owner of this development database). The maintenance window is the
  run below; the backend was stopped immediately after.
- Precondition per the task brief: this database is the operator's own target,
  not a fresh/disposable environment (a disposable environment's seed rows are
  handled by the Task 14 setup path, which this run does not use).

## Step 2 — preview and review

```
POST /api/v1/maintenance/langchain/preview
{"previewId":"74f11164-e746-49a8-92ca-cc94fc472eaa",
 "digest":"d6580ec983f3a8c25ec216ca4688d09a2f4d988ccefa0d5d9ec9abc52a81e5d6",
 "createdAt":"2026-09-26T19:51:59.857719200Z",
 "expiresAt":"2026-09-26T20:06:59.857719200Z",
 "agentIds":[],"runIds":[],"approvalIds":[],"auditEventIds":[],
 "permissionRequestIds":[],"trajectoryIds":[],"toolCallIds":[],
 "promptCallIds":[],"agentSessionRunIds":[],"agentToolBindingIds":[],
 "agentSkillBindingIds":[],"kanbanCardIds":[],"workflowChainIds":[]}
```

Every target set is empty: this database holds no agent whose current
`adk_provider` is exactly `langchain`. Reviewed against the expectation before
executing — zero agents, zero owned runs, zero child rows. The selection is
never widened by name, role or substring.

## Step 3 — execute with the previewed digest

```
POST /api/v1/maintenance/langchain/execute
{"previewId":"74f11164-e746-49a8-92ca-cc94fc472eaa",
 "expectedDigest":"d6580ec983f3a8c25ec216ca4688d09a2f4d988ccefa0d5d9ec9abc52a81e5d6"}

{"previewId":"74f11164-e746-49a8-92ca-cc94fc472eaa",
 "executedAt":"2026-09-26T19:52:07.736700500Z",
 "deletedAgents":0,"deletedRuns":0,"deletedApprovals":0,"deletedAuditEvents":0,
 "deletedPermissionRequests":0,"deletedTrajectories":0,"deletedToolCalls":0,
 "deletedRunPromptCalls":0,"deletedAgentPromptCalls":0,"deletedRunBindings":0,
 "deletedAgentSessions":0,"deletedAgentToolBindings":0,"deletedAgentSkillBindings":0,
 "unlinkedKanbanCards":0,"unlinkedWorkflowSteps":0,"noOp":true}
```

## Step 4 — independent verification

- Protected data unchanged: agents by core before `{opencode: 4, qoder: 12}` and
  after `{opencode: 4, qoder: 12}`; runs before 10, after 10. (Captured from
  `GET /api/v1/agents` and `GET /api/v1/runs` around the execute call.)
- Re-preview after execute returns the same digest
  `d6580ec983f3a8c25ec216ca4688d09a2f4d988ccefa0d5d9ec9abc52a81e5d6` with every
  target set still empty: a second execute is a genuine no-op.
- No dangling links or deletions were requested or performed (`noOp: true`,
  every receipt counter 0).

## Verdict

**No-op on this target:** the local development database contains no legacy
LangChain agents, so the authorized cleanup deleted nothing and the receipt
records it truthfully. The preview-first flow (digest-bound execute, unchanged
digest on re-preview, protected data equality) executed end to end against the
real routes. Criteria that require actual LangChain rows — the mixed-core
scoped deletion and its before/after state — are **NOT VERIFIED** on this
target; they are covered by `LegacyRetirementServiceIntegrationTest` and
`RetirementSchemaOrderingIntegrationTest` in the act-app lane.
