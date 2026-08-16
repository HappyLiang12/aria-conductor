# SDD QA Report

- **Branch:** `sdd/8c2f411b-562c-41bf-94a6-2c51fe7058cb`
- **Commit reviewed:** `a5ec439` ("sdd dev") on top of spec-approval commit `8568f88`
- **Spec:** `spec/spec.md` — "review_knowledge requires UUID but Aria has no name-to-UUID lookup tool" (#38)
- **QA date:** 2026-08-16
- **Verdict:** PASS

## Summary

The implementation fully satisfies the spec's recommended scope (Option "c": both id-or-name
resolution on `review_knowledge`/`retire_knowledge` AND a new `find_knowledge` lookup tool).
Static review found all 9 acceptance criteria addressed, and the real test suites for the three
modules named in the spec's validation commands all pass. One integration-test failure
(`ActIntegrationTest.healthCheck`) is pre-existing, environment-dependent, and unrelated to the
spec change (reproduced identically on the base commit `8568f88`).

## Static verification vs. spec (acceptance criteria)

| AC | Requirement | Status | Evidence |
|----|-------------|--------|----------|
| 1 | Aria can review by name (PENDING -> APPROVED, `KnowledgeApprovedEvent`, no `Invalid UUID string`) | PASS | `KnowledgeToolHandler.reviewKnowledge` resolves `id` via `resolveIdOrName`, passes the resolved UUID to `knowledgeService.reviewKnowledge(UUID, request)` (`KnowledgeToolHandler.java:162-171`); `KnowledgeService` publishes `KnowledgeApprovedEvent` (`KnowledgeService.java:216`). |
| 2 | UUID input unchanged; existing handler tests pass unmodified | PASS | `resolveIdOrName` tries `UUID.fromString` first (`KnowledgeToolHandler.java:209-210`). `KnowledgeToolHandlerTest` (22 tests) and `KnowledgeToolHandlerEdgeCasesTest` (18 tests) all pass, including the pre-existing UUID-path tests. |
| 3 | `retire_knowledge` accepts id-or-name; APPROVED -> RETIRED | PASS | `retireKnowledge` uses `resolveIdOrName` and reports `Status: RETIRED` (`KnowledgeToolHandler.java:174-182`). |
| 4 | New `find_knowledge` tool returns ID/Type/Status | PASS | `case "find_knowledge" -> findKnowledge(arguments)` in `execute` (`KnowledgeToolHandler.java:43`); returns `Name | ID | Type | Status` lines (`KnowledgeToolHandler.java:184-199`). |
| 5 | Tool registry + role wiring (Flyway V40 seed, ARIA grant, test seed + registry tests) | PASS | `V40__seed_find_knowledge_tool.sql` inserts `tool_definitions` row + grants to `ARIA` via `role_tool_templates`. `ToolRegistrySeedTest`, `RoleDefaultsIntegrationTest`, and `tool-registry-seed.sql`/`role-defaults-seed.sql` updated. All pass. |
| 6 | Prompt text updated in both `AriaDefaultAgentInitializer` and `AriaService` | PASS | Both files document `review_knowledge`/`retire_knowledge` accept `id (UUID or item name)` and list `find_knowledge`. |
| 7 | User-friendly error paths (no raw `Invalid UUID string` leak) | PASS | Unknown name -> `Error: Knowledge not found: <value>`; ambiguous name -> `Error: Multiple knowledge items found with name '<name>'. Specify the UUID: <name> | <id> | <type> | <status>, ...`; missing `name` on `find_knowledge` -> `Error: Missing required parameter: name`. |
| 8 | Tests added covering name resolution, unknown name, multi-match, invalid-UUID-as-name, `find_knowledge` success/not-found/multi-match; repository slice test for `findByName` | PASS | 8 new tests in `KnowledgeToolHandlerTest`, 4 new tests in `KnowledgeToolHandlerEdgeCasesTest`, 2 new `findByName` tests in `KnowledgeItemRepositorySliceIntegrationTest`. All pass. |
| 9 | Validation commands pass: `mvn test -pl act-aria`, `-pl act-knowledge`, `-pl act-app` | PASS | See results below. |

Notes on specific spec points:

- The resolution helper mirrors `AgentToolHandler.resolveAgentId` (UUID-first, then name lookup)
  and adds the spec-mandated ambiguity detection (names are not unique in the `knowledge_items`
  schema, no unique constraint). Confirm `KnowledgeToolHandler.java:207-230`.
- No DB migration was needed for review/retire (tool schema already types `id` as `string`);
  `V40` only seeds the new tool. Migration numbering is correct (`V40` follows `V39`).
- Out-of-scope items correctly untouched: `packages/mcp-server` `review_knowledge` and the REST
  `POST /api/v1/knowledge/:id/review` remain UUID-only (spec Questions Q3).

## Real test results

Environment: Temurin JDK 21.0.12, Maven 3.9.9, `mvn` run in `/workspace/repo/agent-control-tower`.

### Unit tests (surefire)

| Command | Tests | Failures | Errors | Skipped | Result |
|---------|-------|----------|--------|---------|--------|
| `mvn test -pl act-aria` | 327 | 0 | 0 | 4 | BUILD SUCCESS |
| `mvn test -pl act-knowledge` | 214 | 0 | 0 | 4 | BUILD SUCCESS |
| `mvn test -pl act-app` | 30 | 0 | 0 | 0 | BUILD SUCCESS |

Spec-relevant unit test classes:

- `KnowledgeToolHandlerTest`: 22/22 passed
- `KnowledgeToolHandlerEdgeCasesTest`: 18/18 passed
- `ToolRegistrySeedTest`: 4/4 passed (includes `find_knowledge` in expected platform tools)

### Integration tests (failsafe, `mvn verify`)

- `act-app`: 56 run, 1 failure, 1 skipped. The only failure is `ActIntegrationTest.healthCheck`
  (expected 200 OK, got 503 SERVICE_UNAVAILABLE). **Pre-existing / environmental** — see Findings.
  All spec-relevant tests pass:
  - `RoleDefaultsIntegrationTest`: 3/3 passed (asserts `find_knowledge` resolves for `qa` role)
  - `KnowledgePromotionIntegrationTest`: 4/4 passed (PENDING -> APPROVED lifecycle, includes review-by-name data flow)
  - `MigrationIntegrationTest`: 3/3 passed (Flyway V40 applies cleanly)
  - `ApprovalFlowIntegrationTest`, `ToolCallIntegrationTest`, `ToolPipelineIntegrationTest`,
    `WorkflowChain/Lifecycle/Governance/RegressionIntegrationTest`: all pass
- `act-knowledge`: 14 run, 0 failures — includes `KnowledgeItemRepositorySliceIntegrationTest`
  (11/11) with the new `findByName` multi-match and no-match tests.
- `act-aria`: BUILD SUCCESS (no failsafe tests configured).

## Findings

1. **`ActIntegrationTest.healthCheck` fails with 503 SERVICE_UNAVAILABLE** — this test asserts the
   full application actuator `/actuator/health` returns UP. It fails because a health indicator is
   DOWN in this sandbox (no container runtime detected for the OpenCode sandbox health indicator;
   `sandbox.health.enabled` is only disabled under the `h2` profile, and this test runs under
   `test`/`noop-llm` profiles). Reproduced identically on the base commit `8568f88` (spec approval,
   before any implementation), so it is a pre-existing, environment-dependent failure with **no**
   relation to the spec change. Not counted against the spec.
2. **No defects or spec gaps found.** All 9 acceptance criteria are implemented and the targeted
   validation commands pass. The V40 migration, role grants, prompt text, error messages, and test
   parity all match the spec's specified text exactly.

## Verdict

**PASS** — the branch implements the spec as written; all required tests pass; the only failing
test is a pre-existing, environment-dependent health check unrelated to this change.

---

VERDICT=PASS
REPORT_ID=2f4d4f39-04d2-4c7c-9c1b-3e0d6a5f8b21
