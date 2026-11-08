# Spec: E2E Coverage for Tool-Pack Credential Path

**Issue**: #113  
**Labels**: tech-debt, priority:P3  
**Date**: 2026-11-08

---

## Problem Statement

PR #109 retired the legacy tool-execution surface (NATIVE/ADK in-JVM path) and deleted three governance E2E specs that asserted on it:

- `git-pack-governance.spec.ts` — pack registration and risk tiers
- `agent-tool-skill-recommendations.spec.ts` — role-default tools and Manage-tools dialog
- `harness-governance.spec.ts` — harness profiles and weak-model-safe tool removal

The deletion was correct: those specs asserted features that no longer exist. However, **pack management and credential storage remain live** because the git handoff still resolves its token from the git pack's stored credentials through `PackCredentialService`.

### Evidence of the Gap

| Artifact | Location | Status |
|----------|----------|--------|
| `ToolPackController.java` | `act-execution/.../controller/ToolPackController.java:14-19` | Retained for git handoff; no tool enablement |
| `PackCredentialService.java` | `act-execution/.../credential/PackCredentialService.java` | Resolves credentials with precedence: per-agent → pack-global → host env fallback |
| `PackGitTokenSource.java` | `act-execution/.../git/PackGitTokenSource.java` | Uses `GIT_PACK_ID = "pack-git-0001"` and resolves `GITHUB_TOKEN` (with `GH_TOKEN` as deprecated alias) |
| `GitCredentialCard.tsx` | `act-dashboard/src/components/GitCredentialCard.tsx` | UI editor for the git pack credential |
| `coverage-map.json:1458-1470` | `e2e/agent-core/coverage-map.json` | Records that no run-owned replacement exists for the deleted governance specs |
| Deleted `git-pack-governance.spec.ts` | `5d16e349` | Covered registration (`:49-68`) and risk tiers (`:28,75-90`); no credential case |

The retained credential path has **no E2E test**, and deleting the adjacent governance suite makes the gap harder to notice.

---

## Proposed Solution

Add **one focused Playwright E2E spec** that covers the retained pack credential path end-to-end:

### Scope

The spec exercises the **credential storage and resolution path**, not the removed governance features (pack registration, risk tiers, tool enablement).

### Test Cases

1. **Store a credential via the API**
   - `POST /api/v1/packs/pack-git-0001/credentials` with `{key: "GITHUB_TOKEN", value: "<test-token>"}`
   - Assert response: `{status: "stored", key: "GITHUB_TOKEN", restartRequired: false}`

2. **Store a per-agent override credential**
   - `POST /api/v1/packs/pack-git-0001/credentials` with `{key: "GITHUB_TOKEN", value: "<agent-token>", agentId: "agent-123"}`
   - Assert response: `{status: "stored", key: "GITHUB_TOKEN", restartRequired: false}`

3. **Validate required fields**
   - `POST /api/v1/packs/pack-git-0001/credentials` with missing `key` or `value`
   - Assert response: `400 Bad Request` with `{error: "key and value are required"}`

4. **UI: GitCredentialCard stores a credential**
   - Navigate to the Configure page
   - Fill the git credential input with a test token
   - Click "Save git credential"
   - Assert the success message: "Credential stored — new workflow runs resolve it without a restart."
   - Assert the input is cleared after save (secret never outlives the request)

5. **UI: GitCredentialCard validates empty input**
   - Attempt to save with an empty credential
   - Assert error message: "A git credential value is required"

### File Location

`agent-control-tower/act-dashboard/e2e/pack-credential.api.spec.ts`

### Coverage Map Update

Update `e2e/agent-core/coverage-map.json` to record that the credential path now has E2E coverage, closing the gap left by the deleted governance specs.

---

## Acceptance Criteria

| # | Criterion | Verification |
|---|-----------|--------------|
| AC-1 | A Playwright spec covers the retained pack credential path (API + UI) | `npx playwright test e2e/pack-credential.api.spec.ts` passes |
| AC-2 | The spec exercises the full credential storage contract: store, per-agent override, validation | Test cases 1-5 above |
| AC-3 | The coverage map records the new spec and closes the gap for the deleted governance specs | `coverage-map.json` updated |
| AC-4 | No governance assertions remain for removed features (pack registration, risk tiers, tool enablement) | Spec does not assert on `POST /api/v1/packs`, `/approve`, `/reject`, or `enabled`/`status` fields |
| AC-5 | The spec runs in CI without requiring a real GitHub token or backend restart | Uses test-only values; `restartRequired: false` asserted |

---

## Error Handling

| Scenario | Expected Behavior |
|----------|-------------------|
| Missing `key` in credential body | `400 Bad Request` with `{error: "key and value is required"}` |
| Missing `value` in credential body | `400 Bad Request` with `{error: "key and value is required"}` |
| Empty credential value in UI | Client-side validation: "A git credential value is required"; no API call |
| Backend unreachable during store | UI shows error alert via `apiErrorMessage()`; secret input cleared |
| Unknown pack ID | `404 Not Found` (credential store delegates to pack repository) |
| Decryption failure (credential key rotation) | `PackGitTokenSource` logs warning with pack ID and key only (never the value); git operations report credential as unavailable |

---

## Implementation Notes

### API Contract (from `ToolPackController.java`)

```typescript
// Store credential
POST /api/v1/packs/{id}/credentials
Body: { key: string; value: string; agentId?: string }
Response 200: { status: "stored"; key: string; restartRequired: false }
Response 400: { error: "key and value are required" }
```

### Resolution Precedence (from `PackCredentialService.java`)

1. Per-agent override (`packId` + `agentId` + `credKey`)
2. Pack-global (`packId` + `credKey` with `agentId = null`)
3. Host environment fallback (`System.getenv(credKey)`)

### UI Contract (from `GitCredentialCard.tsx`)

- `data-testid="git-credential-card"` — container
- `data-testid="git-credential-outcome"` — success message
- Input type `password` with `autoComplete="off"` — secret never persisted in DOM
- On success: shows `GIT_CREDENTIAL_STORED_IMMEDIATE` ("Credential stored — new workflow runs resolve it without a restart.")
- On error: shows `role="alert"` with error message

### Test Data

- Pack ID: `pack-git-0001` (constant in `GitBranchConfig.java`)
- Credential key: `GITHUB_TOKEN` (canonical) or `GH_TOKEN` (deprecated alias)
- Test token value: any non-empty string (e.g., `test-token-e2e-<timestamp>`)

---

## Questions

_None — the issue presents two clear options and the acceptance criteria disambiguate the scope._
