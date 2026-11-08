# Spec: Unify Result-Root Configuration for Spec Review

## Problem Statement

The SDD spec review reads the result-root through a duplicated filesystem contract. `SpecReviewCoordinator` (in `act-knowledge`) resolves `<result-root>/<runId>/spec.md` from its own `@Value` default, while `RunWorkspaceService` (in `act-execution`) captures artifacts to that same location. Both modules independently declare the same property:

- `SpecReviewCoordinator.java:78` — `@Value("${aria.workspaces.result-root:./data/workspaces/results}")`
- `CoreRuntimeConfiguration.java:92` — `@Value("${aria.workspaces.result-root:./data/workspaces/results}")`

If the configured root changes for one module only (e.g., via profile override, environment variable, or relocation of storage), the spec review silently degrades: `readSpecFromRunWorkspace()` fails to find the file and falls back to the BA run's `finalOutput`, which is typically just a summary line rather than the full spec content.

This is a **silent data loss** bug — the system continues to function, but with degraded content quality that may not be immediately noticed.

### Evidence

- `SpecReviewCoordinator.java:66,78,89,280-315` — independent `@Value` resolution and filesystem walk
- `RunWorkspaceService.java:87-107,243-250` — captures artifacts to `resultRoot.resolve(runId)`
- `application.yml:125` — single source of truth for the property
- Risk review R-03: `docs/reviews/2026-10-21-pr109-legacy-execution-retirement/architecture-review.md`

## Proposed Solution

Introduce a shared configuration properties bean in `act-common` that both modules consume, eliminating the duplicated `@Value` declarations. Add a startup assertion that verifies the configuration is consistent, and enhance fallback logging to include the resolved path for diagnostics.

### Design

1. **Create `WorkspaceProperties` in `act-common`**: A `@ConfigurationProperties` bean that centralizes the `aria.workspaces.*` properties (runtime-root, result-root).

2. **Refactor `CoreRuntimeConfiguration`**: Inject `WorkspaceProperties` instead of using `@Value` directly. Pass the resolved paths to `RunWorkspaceService` as before.

3. **Refactor `SpecReviewCoordinator`**: Inject `WorkspaceProperties` instead of using `@Value` directly. Use the shared result-root for spec file resolution.

4. **Add startup consistency check**: On application ready, log the resolved result-root from both consumers at INFO level. If the paths diverge (which should be impossible with a shared bean, but guards against future regression), log a WARN.

5. **Enhance fallback logging**: When `readSpecFromRunWorkspace()` falls back to `finalOutput`, log a WARN that includes the resolved path that was attempted, so operators can diagnose misconfiguration.

### Module Dependency

`act-common` is already a dependency of both `act-knowledge` and `act-execution`, so placing the properties bean there introduces no new module dependencies.

### Files to Modify

| File | Change |
|------|--------|
| `act-common/.../config/WorkspaceProperties.java` | **NEW** — `@ConfigurationProperties(prefix = "aria.workspaces")` with `runtimeRoot` and `resultRoot` fields |
| `act-execution/.../runtime/CoreRuntimeConfiguration.java` | Inject `WorkspaceProperties`; remove `@Value` annotations for workspace roots |
| `act-knowledge/.../sdd/SpecReviewCoordinator.java` | Inject `WorkspaceProperties`; remove `@Value` annotation; add resolved path to fallback warning log |
| `act-app/.../ActApplication.java` or config class | Add `@EnableConfigurationProperties(WorkspaceProperties.class)` if not auto-scanned |

## Acceptance Criteria

1. **Single source of truth**: Changing `aria.workspaces.result-root` in configuration affects both artifact capture (`RunWorkspaceService`) and spec reading (`SpecReviewCoordinator`). There is exactly one `@ConfigurationProperties` bean declaring this property.

2. **Test with non-default root**: A new integration or unit test configures a non-default result-root (via `@TempDir` or test property) and proves that:
   - A spec file written to `<temp-root>/<runId>/spec.md` is correctly read by `SpecReviewCoordinator`
   - The captured content matches the file content, not the `finalOutput` fallback

3. **Fallback logging**: When `readSpecFromRunWorkspace()` falls back to `finalOutput` (file missing, unreadable, or empty), a WARN is logged containing:
   - The run ID
   - The resolved absolute path that was attempted
   - The reason for fallback (file not found / not a regular file / IO error)

4. **Startup diagnostics**: On application startup, the resolved result-root is logged at INFO level, making the active configuration visible in logs.

5. **No regression**: All existing tests pass. The `SpecReviewCoordinatorTest` tests continue to work with the injected properties bean.

6. **No new module dependencies**: `act-common` does not gain new dependencies on `act-execution` or `act-knowledge`.

## Error Handling

| Scenario | Behavior |
|----------|----------|
| `aria.workspaces.result-root` not configured | Use default `./data/workspaces/results` (unchanged from current behavior) |
| Configured root does not exist | `RunWorkspaceService` provisions it on startup (unchanged); `SpecReviewCoordinator` logs WARN on fallback with the resolved path |
| Configured root is not a directory | `RunWorkspaceService` throws `UncheckedIOException` at startup (unchanged); `SpecReviewCoordinator` would fail to read specs and fall back to `finalOutput` with a WARN |
| Spec file exists but is unreadable (permissions) | `SpecReviewCoordinator` catches exception, logs WARN with path and error message, falls back to `finalOutput` |
| Spec file exists but exceeds 50KB | Read is truncated to `MAX_SPEC_CONTENT_LENGTH` (unchanged behavior) |
| `finalOutput` is also empty/null | Stored spec content is empty after cleaning; approval is still created with empty content (unchanged) |

## Implementation Notes

- The `WorkspaceProperties` bean should use Lombok `@Data` for getters/setters, following the pattern of `RunAdmissionProperties`.
- Default values should be specified in the properties class, not in `application.yml`, to keep the contract explicit in code.
- The startup log should use the canonical/absolute path to make symlink or relative-path issues visible.
- Consider adding `@Validated` and `@NotBlank` annotations if we want to refuse blank values at startup (optional enhancement).
