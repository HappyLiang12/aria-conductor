# QA Report: Unify Result-Root Configuration for Spec Review

**Spec**: `spec/spec.md`
**Branch**: `sdd/3f115ce0-9510-4d46-95e2-5b754bf3e837`
**Commit under test**: `8ddc2ad1` (sdd dev)
**Date**: 2026-11-08

## Summary

All 6 acceptance criteria are **VERIFIED**. 1774 tests pass across the 4 affected modules with 0 failures and 0 errors.

## Changed Files

| File | Change |
|------|--------|
| `act-common/.../config/WorkspaceProperties.java` | NEW — `@ConfigurationProperties(prefix = "aria.workspaces")` with `runtimeRoot` and `resultRoot` |
| `act-execution/.../runtime/CoreRuntimeConfiguration.java` | Injects `WorkspaceProperties`; removed `@Value` for workspace roots |
| `act-knowledge/.../sdd/SpecReviewCoordinator.java` | Injects `WorkspaceProperties`; removed `@Value`; added resolved path to fallback WARN logs |
| `act-app/.../ActApplication.java` | Added `WorkspaceProperties.class` to `@EnableConfigurationProperties` |
| `act-knowledge/.../sdd/SpecReviewCoordinatorTest.java` | Added test for non-default result-root with `@TempDir` |

## Acceptance Criteria Evaluation

### AC1: Single source of truth — VERIFIED

- `WorkspaceProperties` (`act-common/.../config/WorkspaceProperties.java:13-14`) is the single `@ConfigurationProperties(prefix = "aria.workspaces")` bean with `runtimeRoot` and `resultRoot` fields and explicit defaults.
- `CoreRuntimeConfiguration.runWorkspaceService()` (`act-execution/.../runtime/CoreRuntimeConfiguration.java:92-96`) injects `WorkspaceProperties` and reads both roots from it.
- `SpecReviewCoordinator` constructor (`act-knowledge/.../sdd/SpecReviewCoordinator.java:79,90`) injects `WorkspaceProperties` and resolves `runResultRoot` from `workspaceProperties.getResultRoot()`.
- Grep confirms zero remaining `@Value("${aria.workspaces.result-root...}")` or `@Value("${aria.workspaces.runtime-root...}")` annotations in the codebase.

### AC2: Test with non-default root — VERIFIED

- New test `onBaStepCompleted_withNonDefaultResultRoot_readsSpecFromConfiguredPath` (`SpecReviewCoordinatorTest.java:531-555`) uses `@TempDir Path customRoot` to configure a non-default result-root.
- The test writes a full spec to `<customRoot>/<baRunId>/spec.md`, passes a summary-only `finalOutput`, and asserts the coordinator reads the full file content (not the fallback).
- Test passes as part of the 264-test act-knowledge run.

### AC3: Fallback logging — VERIFIED

- `readSpecFromRunWorkspace()` (`SpecReviewCoordinator.java:295-319`):
  - **File not found** (line 302): `log.warn("SDD spec fallback for run {}: file not found at resolved path {}", runId, specFile)` — includes run ID and resolved absolute path.
  - **IO error** (lines 315-316): `log.warn("SDD spec fallback for run {}: cannot read spec at resolved path {} due to {}", runId, specFile, e.getMessage())` — includes run ID, resolved path, and error reason.
- `runResultRoot` is computed as `Path.of(workspaceProperties.getResultRoot()).toAbsolutePath().normalize()` (line 90), so the logged path is always absolute.

### AC4: Startup diagnostics — VERIFIED

- `recoverPendingDecisions()` (`SpecReviewCoordinator.java:193`), annotated `@EventListener(ApplicationReadyEvent.class)`, logs: `log.info("SDD spec review configured with result-root: {}", runResultRoot)`.
- The logged value is the absolute normalized path, making symlink or relative-path issues visible per the spec's implementation notes.

### AC5: No regression — VERIFIED

Test results (all with `-Dspring.profiles.active=h2`):

| Module | Tests | Failures | Errors | Skipped | Result |
|--------|-------|----------|--------|---------|--------|
| act-common | 131 | 0 | 0 | 0 | BUILD SUCCESS |
| act-knowledge | 264 | 0 | 0 | 4 | BUILD SUCCESS |
| act-execution | 1302 | 0 | 0 | 0 | BUILD SUCCESS |
| act-app | 77 | 0 | 0 | 0 | BUILD SUCCESS |
| **Total** | **1774** | **0** | **0** | **4** | **ALL GREEN** |

All coverage checks met in every module.

### AC6: No new module dependencies — VERIFIED

- `WorkspaceProperties` (`act-common/.../config/WorkspaceProperties.java`) imports only `lombok.Data` and `org.springframework.boot.context.properties.ConfigurationProperties` — no cross-module imports.
- `act-common/pom.xml` has no new dependencies on `act-execution` or `act-knowledge`. The only cross-module reference is the pre-existing H2 test dependency.

## Error Handling Verification

| Scenario | Spec Requirement | Implementation | Status |
|----------|-----------------|----------------|--------|
| Property not configured | Default `./data/workspaces/results` | `WorkspaceProperties.DEFAULT_RESULT_ROOT` (line 20) | VERIFIED |
| Root does not exist | `RunWorkspaceService` provisions it; coordinator falls back with WARN | `provisionedRoot()` in `CoreRuntimeConfiguration.java:106-113`; fallback WARN in `SpecReviewCoordinator.java:302` | VERIFIED (unchanged behavior preserved) |
| Spec file unreadable | Catch exception, WARN with path and error, fall back | `SpecReviewCoordinator.java:314-317` catches `Exception`, logs WARN with path and message | VERIFIED |
| Spec exceeds 50KB | Truncate to `MAX_SPEC_CONTENT_LENGTH` | `SpecReviewCoordinator.java:305,402-403` — unchanged | VERIFIED |

## Verdict

**VERDICT=PASS**

All 6 acceptance criteria verified. 1774 tests pass with 0 failures and 0 errors. No regressions detected. The implementation correctly centralizes workspace configuration in `WorkspaceProperties`, eliminates duplicated `@Value` declarations, adds diagnostic logging at startup and on fallback, and includes a test proving non-default root configuration works end-to-end.

REPORT_ID=7c3e8a14-f2b9-4d05-a6e1-9b8f3c2d7e4a
