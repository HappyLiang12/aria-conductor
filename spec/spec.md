# Spec: Unify the SDD result-root contract between run capture and spec review

**Issue:** #112 — SDD spec review reads result-root through a duplicated filesystem contract
**Labels:** `bug`, `priority:P2`
**Milestone:** v0.2 hardening
**Modules:** `act-execution`, `act-knowledge`, `act-app`

## Problem Statement

The SDD spec-review path resolves the run result directory twice, from two independent configuration points:

- `RunWorkspaceService` (act-execution) captures a finished run's artifacts into `<result-root>/<runId>/`. Its `resultRoot` comes from the `@Value("${aria.workspaces.result-root:./data/workspaces/results}")` parameter of the `runWorkspaceService` bean in `CoreRuntimeConfiguration`, and is canonicalized with `toRealPath()`.
- `SpecReviewCoordinator` (act-knowledge) reads `<result-root>/<runId>/spec.md` during the BA-completion event. It declares its own `@Value("${aria.workspaces.result-root:./data/workspaces/results}")` constructor parameter and only normalizes with `toAbsolutePath().normalize()`.

The two defaults happen to spell the same value today, but there is no shared contract. If the property is overridden for only one consumer, if a test/wiring supplies a different root, or if storage is relocated, capture and read diverge. `readSpecFromRunWorkspace` then returns `null` and `resolveSpecContent` silently falls back to the BA run's `finalOutput` (typically a short summary). The SPEC_REVIEW approval and the stored SPEC knowledge item then contain the summary instead of the full spec. A missing `spec.md` currently produces no warning at all; only an `IOException` is logged, and that message does not include the resolved path. Because the two resolvers also canonicalize differently, a symlinked root can be logged/resolved as two different paths.

Evidence: `SpecReviewCoordinator.java:66,78,89,280-315`, `RunWorkspaceService.java:87-107,243-250`, `application.yml:113`, and risk review R-03 in `docs/reviews/2026-10-21-pr109-legacy-execution-retirement/architecture-review.md` (PR #110).

## Proposed Solution

### 1. One configuration bean for both roots

Add `io.aria.conductor.execution.runtime.WorkspaceRootProperties` in act-execution:

- Annotated `@Component` and `@ConfigurationProperties(prefix = "aria.workspaces")`.
- String fields `runtimeRoot` (default `./data/workspaces/runs`) and `resultRoot` (default `./data/workspaces/results`). The class exposes constants for both defaults.
- Accessors `Path runtimeRootPath()` and `Path resultRootPath()` resolve the configured strings through `WorkspacePaths.configuredRoot(value, "aria.workspaces.runtime-root" | "aria.workspaces.result-root")`, preserving the existing `%VAR%` expansion/refusal behavior and returning an absolute normalized path.
- The public property names and environment overrides (`ARIA_WORKSPACES_RUNTIME_ROOT`, `ARIA_WORKSPACES_RESULT_ROOT`) stay exactly as they are. No new operator-facing setting is introduced.

This bean is the single source of truth. act-knowledge already depends on act-execution, so no new module dependency or cycle is created.

### 2. Capture is wired from the bean

Change `CoreRuntimeConfiguration.runWorkspaceService(...)` to accept `WorkspaceRootProperties roots` instead of the two `@Value` root parameters, and pass `provisionedRoot(roots.runtimeRootPath()...)` / `roots.resultRootPath()` into `RunWorkspaceService` after provisioning. Keep the existing first-boot directory provisioning and `WorkspacePaths.configuredRoot` behavior.

Add a read-only `Path resultRoot()` accessor to `RunWorkspaceService` returning the canonical result root captured at construction. The existing Path-based constructors stay intact for tests.

### 3. Artifact lookup owned by act-execution

Add a small lookup API so act-knowledge no longer reconstructs the filesystem layout itself:

```java
public interface RunArtifactLocator {
    Optional<Path> find(UUID runId, String relativePath);
    Path pathOf(UUID runId, String relativePath);
    Path resultRoot();
}
```

`ResultRootArtifactLocator` (act-execution, `@Component`) implements it on top of `WorkspaceRootProperties`:

- `find` returns the file only when it is a readable regular file under the canonical `<resultRoot>/<runId>/`; otherwise `Optional.empty()`.
- `pathOf` returns the absolute resolved path to name in logs, whether or not the file exists; it never throws for a missing file.
- `resultRoot()` returns the same canonical root used for capture.
- Reject a null run id, or a blank/absolute/`..`-bearing relative path, with `IllegalArgumentException` (the coordinator always passes the constant `"spec.md"`).
- Containment check: the file's real path must still start with the canonical run result directory, so a symlink escaping the run directory is never read.

### 4. Spec review reads through the lookup

In `SpecReviewCoordinator`:

- Replace the `@Value("${aria.workspaces.result-root...}")` constructor parameter with `RunArtifactLocator runArtifactLocator`.
- `readSpecFromRunWorkspace(runId)` calls `locator.find(runId, "spec.md")` and reads at most `MAX_SPEC_CONTENT_LENGTH` (50 KiB) as today.
- `resolveSpecContent` keeps its current precedence: non-blank page file content, else `finalOutput`; `cleanSpecContent` is unchanged.
- Every fallback logs exactly one WARN that includes the BA run id and the resolved expected path from `locator.pathOf(runId, "spec.md")`. This covers: null run id, missing file, non-regular file, unreadable file, and escaping symlink.

### 5. Startup consistency guard

Add `WorkspaceRootConsistencyCheck`, an `@Component` with an `@EventListener(ApplicationReadyEvent.class)` method that:

- Reads `RunWorkspaceService.resultRoot()` and `RunArtifactLocator.resultRoot()`.
- Compares them with `Files.isSameFile` (falling back to `toRealPath()` equality); on mismatch, throws `IllegalStateException` naming both paths and `aria.workspaces.result-root`, failing startup fast.
- Logs the resolved result root once at INFO when consistent.

Because both values now derive from one bean, this guard is defense in depth: it turns any future wiring drift into a startup failure instead of silent spec degradation.

### 6. Documentation

Add a comment next to `aria.workspaces.result-root` in `application.yml` stating that this single property drives both run artifact capture and SDD spec review. No layout, endpoint, UI, approval-state, cleaning, or truncation behavior changes.

### Compatibility and rollout

No data migration is required: the on-disk layout stays `<result-root>/<runId>/spec.md`, defaults are unchanged, and existing environment variables keep working. Existing `RunWorkspaceService` constructors remain available to tests.

## Acceptance Criteria

1. **Single setting.** Setting `aria.workspaces.result-root` once (property or environment variable) changes both the directory `RunWorkspaceService.capture` writes to and the directory `SpecReviewCoordinator` reads from. A repository-wide search finds no `aria.workspaces.result-root` `@Value`/property lookup outside `WorkspaceRootProperties`.
2. **Defaults unchanged.** With no configuration, both roots resolve to `<cwd>/data/workspaces/runs` and `<cwd>/data/workspaces/results`, exactly as before.
3. **Non-default root proves capture is read.** With a temporary non-default result root `A`, a test causes a BA run's `spec.md` to be captured under `A/<runId>/` and then delivers a `BaStepCompletedEvent` whose `finalOutput` is a different summary. The stored SPEC knowledge version and the SPEC_REVIEW approval content equal the file content from `A/<runId>/spec.md`, not the summary. The test must exercise the real wiring path (both consumers built from one `WorkspaceRootProperties`), not two hand-passed roots.
4. **Fallback warning names the path.** With the same non-default root and no `spec.md`, the fallback to `finalOutput` logs exactly one WARN containing the BA run id and the absolute resolved path `A/<runId>/spec.md`. Captured via `OutputCaptureExtension`.
5. **Unreadable/non-regular file fallback.** A `spec.md` that is a directory, a blank file, or otherwise unreadable falls back to `finalOutput` with a WARN containing the resolved path.
6. **Startup guard.** A unit test with a workspace service and locator configured to different roots asserts `ApplicationReadyEvent` handling throws `IllegalStateException` naming both paths; the consistent case does not throw.
7. **No regression.** Existing `SpecReviewCoordinatorTest` behavior (idempotency, approval creation, decide/route, resubmit, cancellation, cleaning, truncation, git handoff) still passes after the constructor change.
8. **Build and quality gates.** `cd agent-control-tower && mvn -pl act-execution,act-knowledge,act-app -am test` passes with the module JaCoCo thresholds unchanged.

## Error Handling

| Condition | Behavior |
| --- | --- |
| `aria.workspaces.result-root` (or runtime root) blank | Startup fails with the existing `IllegalArgumentException` from `WorkspacePaths.configuredRoot`, naming the property; no directory is created. |
| Root contains an unexpanded `%NAME%` token | Startup fails with the existing `IllegalStateException` naming the property and variable; a literal `%NAME%` directory is never created. |
| Result root cannot be created | Startup fails with `UncheckedIOException` naming the path. |
| Workspace service and artifact locator roots disagree | `ApplicationReadyEvent` fails with `IllegalStateException` naming both resolved paths and `aria.workspaces.result-root`; the app does not serve traffic with a degraded spec path. |
| BA run id is null | Skip the file lookup, log WARN with the run id and the expected path, fall back to `finalOutput`; the approval is still created. |
| `spec.md` does not exist | Log WARN containing the absolute resolved path and run id, fall back to `finalOutput`; no exception. |
| `spec.md` is not a regular file (directory, socket, device) | Treat as absent, log WARN with the path, fall back to `finalOutput`. |
| `spec.md` cannot be read (`IOException`, encoding failure) | Log WARN with the path and the cause message, fall back to `finalOutput`; never propagate. |
| `spec.md` resolves outside `<resultRoot>/<runId>/` via a symlink | Treat as absent, log WARN with the path, fall back; the outside file is never read. |
| File content is empty or whitespace-only | Fall back to `finalOutput` with the same WARN path message. |
| Spec exceeds 50 KiB | Truncate to 50 KiB via the existing `MAX_SPEC_CONTENT_LENGTH` guard; no error. |
| Invalid relative path (blank, absolute, or containing `..`) or null run id passed to `RunArtifactLocator.find/pathOf` | Throw `IllegalArgumentException`; this is a programming error, since the coordinator always passes `"spec.md"`. |

---

Summary: The spec replaces the duplicated `aria.workspaces.result-root` resolution with one `WorkspaceRootProperties` bean in act-execution, routes both `RunWorkspaceService` capture and `SpecReviewCoordinator` reads through an act-execution-owned `RunArtifactLocator`, adds a startup consistency guard, and requires the fallback warning to include the resolved path. Tests cover a non-default root end to end, missing/unreadable files, and startup divergence.