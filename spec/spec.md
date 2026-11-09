## Problem Statement

`SpecReviewCoordinator` (act-knowledge) resolves `<result-root>/<runId>/spec.md` from its own `@Value("${aria.workspaces.result-root:./data/workspaces/results}")` (`SpecReviewCoordinator.java:66,78,89,280-315`), while `RunWorkspaceService` (act-execution), which captures that file, resolves the same property through `WorkspacePaths.configuredRoot(...)` and then canonicalises it in its constructor (`RunWorkspaceService.java:107,115-153,441`, wired in `CoreRuntimeConfiguration.java:87-113`, property at `application.yml:119-125`).

The two resolvers are not equivalent:

| Step | Capture (`RunWorkspaceService`) | Read (`SpecReviewCoordinator`) |
|---|---|---|
| Property source | `@Value` in `CoreRuntimeConfiguration#runWorkspaceService` | own `@Value` on the constructor |
| Windows `%NAME%` | expanded from the environment, unknown token refused (F21) | taken literally, creating an impossible directory name |
| Blank value | refused by `WorkspacePaths.configuredRoot` | `Path.of("")` becomes the process working directory |
| Canonical form | `toRealPath()` after `Files.createDirectories` | `toAbsolutePath().normalize()` only |
| Missing root | created at wiring | never created |

Consequences: changing the root for one module, a `%VAR%` token, a symlinked/junction root, or a blank value makes the reader look somewhere other than the directory capture wrote. `spec.md` then "does not exist", and the SPEC_REVIEW approval content silently degrades to the BA run's `finalOutput` — typically a short summary with the `SPEC_ID=` marker stripped — with no warning and no path in any log. The degradation is invisible until a reviewer notices they are approving a summary instead of the full spec. This is risk R-03 of the PR #110 review referenced by the issue (that review file is not present at this head; the issue body is the source).

## Proposed Solution

Make the configured root a single shared bean; both the capture path (`RunWorkspaceService`) and the read path (`SpecReviewCoordinator`) obtain their root from that one bean. No new module dependency is needed: act-knowledge already depends on act-execution (it already imports `GitBranchService` and `ApprovalRepository`).

### 1. New `WorkspaceProperties` bean (act-execution, `io.aria.conductor.execution.runtime`)

```java
@Component
@ConfigurationProperties(prefix = "aria.workspaces")
public class WorkspaceProperties {
    private String runtimeRoot = "./data/workspaces/runs";     // same default as application.yml
    private String resultRoot  = "./data/workspaces/results";  // same default as application.yml

    public Path runtimeRootPath();   // lazily resolved once, cached
    public Path resultRootPath();    // lazily resolved once, cached
}
```

Resolution of each path, performed once per instance and cached:

1. `WorkspacePaths.configuredRoot(value, "aria.workspaces.runtime-root" | "aria.workspaces.result-root")` — preserves the existing F21 behaviour (environment expansion of `%NAME%`, refusal of unknown tokens and blank values).
2. `Files.createDirectories(path)` — first boot with an empty data directory keeps working.
3. `path.toRealPath()` — both consumers now receive the same canonical absolute path.

On first resolution the bean logs one INFO line containing both canonical roots, e.g. `aria.workspaces resolved: runtime-root=<path>, result-root=<path>`. Explicit setters invalidate the cached paths (needed by tests and harmless under rebinding). Defaults and the property names are unchanged, so no configuration migration and no new environment variable.

### 2. `CoreRuntimeConfiguration` wiring

- `runWorkspaceService(...)` takes `WorkspaceProperties workspaces` and passes `workspaces.runtimeRootPath()` / `workspaces.resultRootPath()` to `RunWorkspaceService`.
- The private `provisionedRoot(...)` helper moves into `WorkspaceProperties` (its behaviour is unchanged). No other bean changes.

### 3. `SpecReviewCoordinator`

- Constructor: replace the `@Value("${aria.workspaces.result-root:...}") String runResultRoot` parameter with `WorkspaceProperties workspaces`; store `this.runResultRoot = workspaces.resultRootPath()`.
- At construction, log at INFO: `SDD spec review reads run artifacts from <runResultRoot>`. Together with the bean's startup line this is the startup visibility the issue asks for; equality is guaranteed structurally because there is now exactly one resolver, and the wiring test in AC2 pins it.
- `readSpecFromRunWorkspace(UUID runId)` keeps its contract: read at most `MAX_SPEC_CONTENT_LENGTH` (50 KB) **characters** of `<result-root>/<runId>/spec.md` as UTF-8, return `null` when unavailable, and never propagate an I/O error. It additionally requires the file's real path to stay inside the run's real result directory; a symlink that escapes it is treated as absent.
- `resolveSpecContent(...)`: when the file is absent, blank, unreadable or escapes the run directory, log a WARN that includes the resolved absolute `spec.md` path and the reason, then fall back to `cleanSpecContent(event.getFinalOutput())` exactly as today. All existing cleaning/truncation behaviour is unchanged.
- All other coordinator logic (idempotency, approval creation, write-back, branch handoff, resubmit, cancellation, startup recovery) is untouched.

### 4. Resulting flow

BA step completes → the run is captured by `RunWorkspaceService#capture` into `resultRoot.resolve(runId)` (from the shared bean) → `SpecReviewCoordinator` reads `<resultRoot>/<runId>/spec.md` (from the same bean). One property, one resolution, one canonical path.

### Rejected alternatives

- **A new artifact-lookup port in act-common**: valid but heavier; the defect is duplicated resolution, and act-knowledge already depends on act-execution. A port adds indirection without removing any coupling.
- **Only a startup equality assertion while keeping two resolvers**: an assertion cannot hold after the values are read; a later rebind or a different code path can still drift. Sharing the bean removes the second resolver instead of observing it.
- **Per-call path resolution in the coordinator**: still duplicates the property key and the F21 rules; rejected for the same reason.

## Acceptance Criteria

1. **One resolver.** In main sources, `aria.workspaces.result-root` is bound only by `WorkspaceProperties` (plus `application.yml`); `SpecReviewCoordinator` holds no `@Value` for it, and both `RunWorkspaceService` (capture) and `SpecReviewCoordinator` (read) receive their root from the same `WorkspaceProperties` bean.
2. **One change moves both.** A test with a non-default result root asserts the path used by `RunWorkspaceService` and the path used by `SpecReviewCoordinator` are equal to `WorkspaceProperties.resultRootPath()` (add a package-private `resultRoot()` accessor to `RunWorkspaceService` if needed; the coordinator's adopted path is asserted through its stored field or an accessor).
3. **Captured spec is read from a non-default root.** A test writes `spec.md` under `<tempRoot>/<baRunId>/spec.md`, constructs the coordinator with `WorkspaceProperties.resultRoot = <tempRoot>`, dispatches `BaStepCompletedEvent` with a different `finalOutput`, and asserts the stored knowledge/approval content equals the file content (not `finalOutput`).
4. **Fallback warns with the resolved path.** When `spec.md` is missing, blank, unreadable, a directory, or an escaping link, exactly one WARN is logged and its message contains the absolute resolved path `<resultRoot>/<baRunId>/spec.md`; the fallback content is the cleaned `finalOutput`.
5. **Canonical, F21-safe roots.** `WorkspaceProperties` resolves through `WorkspacePaths.configuredRoot` and `toRealPath()`: blank values are refused naming the property, an unknown `%NAME%` token is refused naming the token and property, and a resolved root equals its own `toRealPath()`.
6. **Bounded, jailed reads.** The read is capped at 50 KB characters; a `spec.md` that is not a regular file, or whose real path leaves `<result-root>/<runId>/`, yields no content plus the AC4 warning. No read outside the run result directory is possible.
7. **Behaviour preserved.** Every existing `SpecReviewCoordinatorTest` case (cleaning, 50 KB truncation, marker stripping, idempotency, approval routing, Git handoff, cancellation, resubmit, startup recovery) still passes unchanged in intent.
8. **Startup observability.** `WorkspaceProperties` logs both canonical roots once at INFO, and `SpecReviewCoordinator` logs the root it reads from at INFO; no secret or credential value appears in either line.
9. **Build and gates.** `act-execution` and `act-knowledge` tests are green and their JaCoCo ratchets hold (act-execution 0.58 line / 0.42 branch; act-knowledge 0.70 line / 0.54 branch); the full unit tier is green.
10. **No structural surprises.** No new Maven module dependency, no change to module direction, no Flyway migration, no change to `application.yml` keys or defaults, and no rename of `spec.md`.

## Error Handling

| Scenario | Detection | Behaviour |
|---|---|---|
| `aria.workspaces.result-root` (or `runtime-root`) blank | `WorkspacePaths.configuredRoot` | Startup fails with `IllegalArgumentException` naming the property; no directory is created or used. |
| Unknown `%NAME%` in a configured root | `WorkspacePaths.configuredRoot` | Startup fails with `IllegalStateException` naming the token and the property; a literal `%NAME%` directory is never created (F21). |
| Root cannot be created or canonicalised (permissions, a file at that path) | `Files.createDirectories` / `toRealPath` | Startup fails with `UncheckedIOException` naming the path; no silent fallback to a different or relative root. |
| Run result directory absent (not yet captured, or removed by retention/housekeeping) | `Files.isRegularFile` false | WARN with the resolved absolute `spec.md` path and the reason; fall back to `finalOutput`. |
| `spec.md` is a directory | `Files.isRegularFile` false | Same as above; no attempt to read it as a file. |
| `spec.md` is a symlink/junction escaping `<result-root>/<runId>/` | real-path containment check | WARN identifying the path and the escape; treat as absent and fall back. |
| `IOException` / permission error while reading | `catch (IOException)` | WARN with the path and the exception message; fall back. The event handler never throws, so the BA gate cannot wedge on an unreadable artifact. |
| File exists but is empty/whitespace | read result blank | WARN with the path; fall back. |
| File larger than 50 KB | bounded read | Read exactly `MAX_SPEC_CONTENT_LENGTH` characters; `cleanSpecContent` still truncates; no OOM and no error. |
| `baRunId` is `null` | null check before resolving | WARN naming the configured result root and that the run id is null; fall back to `finalOutput`. |
| Neither file nor `finalOutput` yields content | both sources empty | WARN names both sources; downstream validation is unchanged (current behaviour preserved). |
| Property changes while the backend runs | bean resolved once | The change applies after restart, consistent with the existing `@Value` semantics; documented in the bean's Javadoc. |

## Out of Scope

- Renaming `spec.md`, moving spec storage out of the result root, or changing retention/housekeeping.
- Refactoring unrelated readers of workspace roots (e.g. `WorkspaceManager`, diff preview).
- Any database migration or change to the approval/content schemas.
- Replacing the `@Value` semantics with reactive refresh of workspace roots.

## Testing and Verification

New/updated tests:

- `act-execution`: `WorkspacePropertiesTest` (defaults, non-default temp root, canonical form, blank and unknown-token refusal delegated to `WorkspacePaths`, both paths from one instance) and a wiring test proving `RunWorkspaceService` and the coordinator share `resultRootPath()`.
- `act-knowledge`: `SpecReviewCoordinatorTest` updated to construct with `WorkspaceProperties`; add `readsCapturedSpecFromConfiguredNonDefaultResultRoot` (AC3) and `fallbackToFinalOutputLogsResolvedSpecPath` using a Logback `ListAppender<ILoggingEvent>` (AC4); keep all existing cases.
- Existing `WorkspacePathsConfiguredRootTest` continues to cover token expansion refusal.

Commands (per `AGENTS.md`):

```
cd agent-control-tower
mvn test -pl act-execution -Dtest="WorkspacePropertiesTest,CoreRuntimeConfigurationTest,RunWorkspaceServiceTest"
mvn test -pl act-knowledge -Dtest=SpecReviewCoordinatorTest
bash scripts/mvn-lane.sh gate act-execution
bash scripts/mvn-lane.sh gate act-knowledge
mvn clean test -Dspring.profiles.active=h2        # full unit tier, or `bash scripts/mvn-lane.sh shard 3`
```

## Risks and Notes

- `WorkspaceProperties` provisions the roots on first use; tests must point it at `@TempDir`, never the repository's `./data`.
- Making resolution fail-fast preserves the current wiring's fail-fast behaviour for capture and adds it symmetrically for the read side; this is intentional, not a regression.
- The reported line numbers for `application.yml` in the issue (113) do not match this revision; the property is at `application.yml:119-125`.
- The fallback path remains (a BA run may legitimately never write `spec.md`), so the fix is not to delete it but to make the correct path the shared default and the fallback observable.

---

Summary: The spec unifies `aria.workspaces.result-root` into a single `WorkspaceProperties` bean used by both `RunWorkspaceService` (capture) and `SpecReviewCoordinator` (read), keeps F21 token/blank semantics, and makes the `finalOutput` fallback a WARN containing the resolved path, with tests for a non-default root and the warning.