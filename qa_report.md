# QA Report — Single source of truth for the run artifact root (#112)

- Repository: `HappyLiang12/aria-conductor`
- Branch: `sdd/b3019551-f2f7-40f0-ab56-2e776c1ff2cb`
- Reviewed commit: `8b5447cf6c6985d2163f2a844ba90b9dd7c04257` ("sdd dev")
- Spec: `spec/spec.md`
- Review date: 2026-11-10
- Verdict: **PASS** (all acceptance criteria verified with a green run)

## Environment

| Item | Value |
|------|-------|
| JDK | Eclipse Temurin 21.0.13+9 (downloaded into the sandbox; no system JDK/Maven present) |
| Maven | Apache Maven 3.9.9 |
| Command base | `cd agent-control-tower && mvn -B -ntp ... -Dspring.profiles.active=h2` |

The system image had no `java`/`mvn`; both were installed under `/workspace/.tools`
(outside the checkout). No source file was modified for testing. To make the existing
integration tier runnable, the already-committed bridge source
`packages/qoder-acp-bridge/` was built with `npm install && npm run build` (its `dist/`
and `node_modules/` are git-ignored). The generated `package-lock.json` was deleted
afterwards; `git status --short` is clean apart from this report.

## What changed (inspection)

- `act-common/.../artifact/RunArtifactProperties.java` — single `@ConfigurationProperties`
  bean bound to `aria.workspaces.result-root`; `resolvedResultRoot()` is the one
  normalization point (blank refuses by name, `%NAME%` expansion, relative anchored to
  the working directory); resolves once, logs `run result root resolved: path=... property=...`
  at INFO, and WARNs without failing when the directory is absent.
- `act-common/.../artifact/RunArtifactLocator.java` — the only place that knows
  `<result-root>/<runId>/spec.md`; `resultRoot()`, `resultDir()`, `specPath()`,
  `readSpecResult()`/`readSpec()`, run-id validation (blank, separators, `..`, absolute)
  and containment check under the root.
- `act-common/.../artifact/SpecReadResult.java`, `SpecArtifact.java` — content + resolved
  path, or an unavailable result carrying `missing` / `empty` / `unreadable` /
  `invalid-run-id` and the attempted absolute path.
- `act-common/.../artifact/RunArtifactStartupCheck.java` — startup equality assertion of
  the capture property root and the locator read root (normalized absolute paths), with an
  actionable error naming the property and both values.
- `act-knowledge/.../sdd/SpecReviewCoordinator.java` — the result-root `@Value` and manual
  path construction are gone; it injects `RunArtifactLocator`, logs the stable marker
  `spec artifact unavailable` with run id, reason and resolved path (plus exception detail
  for `unreadable`, never content) before falling back, and refuses an unusable input
  (no artifact and no `finalOutput`) with an ERROR instead of an empty review/NPE.
- `act-execution/.../runtime/RunWorkspaceService.java` — capture uses
  `artifacts.resultDir(...)`; the legacy `Path` constructors only adapt to a locator for
  tests. `CoreRuntimeConfiguration` now injects the locator instead of declaring its own
  `@Value` result-root default.
- `application.yml:125` still holds the documented key/default: `aria.workspaces.result-root`.

## Acceptance criteria verification

| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| 1 | One canonical property controls capture and read; changing it moves both | VERIFIED | `SpecArtifactRootEndToEndTest#aNonDefaultRootCapturesTheSpecThatSpecReviewReads` and `#changingOnlyTheCanonicalPropertyMovesBothCaptureAndRead` (6/6 pass); `RunWorkspaceService` uses the injected locator, `SpecReviewCoordinator` injects the same locator |
| 2 | `SpecReviewCoordinator` has no independent `@Value` default and does not build the spec path | VERIFIED | Source inspection: field/ctor inject `RunArtifactLocator`; only `@Value` left is `approvals.timeout-ms`; `readSpecFromRunWorkspace` delegates to `readSpecResult` |
| 3 | `RunWorkspaceService` uses the shared root/locator; no duplicated path construction | VERIFIED | `RunWorkspaceService:465` uses `artifacts.resultDir(...)`, `:1034` uses `artifacts.resultRoot()`; `CoreRuntimeConfiguration:91` injects `RunArtifactLocator`; no `@Value` result-root remains (`grep`) |
| 4 | Non-default-root test proves the captured `spec.md` is what review reads, not `finalOutput` | VERIFIED | `SpecArtifactRootEndToEndTest` captures through the real `RunWorkspaceService`/backend and asserts `submitKnowledge` receives the full captured spec; `baSummary` is different text |
| 5 | Startup log of resolved absolute root + startup check that capture/read roots match | VERIFIED | INFO line observed in a real Spring context run (below); `RunArtifactStartupCheck.afterPropertiesSet` compares normalized absolute paths and fails startup on mismatch. Note: by construction both sides share the same bean/locator, so the assertion is a guard rather than an independent computation |
| 6 | Missing/empty/unreadable/invalid run id logs WARN with run id, reason, resolved path before fallback | VERIFIED | Coordinator log path (`SpecReviewCoordinator:280-315`); `SpecArtifactRootEndToEndTest#aMissingArtifactLogsTheResolvedPathAndRunIdThenFallsBack`, `#anEmptyArtifactLogsTheEmptyReasonAndFallsBack`; `RunArtifactLocatorTest` covers unreadable/invalid with the attempted path |
| 7 | No log contains spec content/payload | VERIFIED | `RunArtifactLocatorTest#anUnreadableSpecReportsUnreadableWithoutContent`; fallback test asserts the WARN does not contain `baSummary`; source logs only ids/reasons/paths/exception detail |
| 8 | Documented/default-root deployments behave as before apart from new logs | VERIFIED | Full app context booted with the default root (`.../act-app/data/workspaces/results`); all pre-existing unit tests across the reactor pass |
| 9 | Unit and integration tests pass; build passes | VERIFIED | Full unit tier across all modules green (2,609 tests, 0 failures, 0 errors, 19 skipped); integration tier for the three touched modules green (109 tests, 0 failures, 0 errors, 10 skipped); `mvn install -DskipTests` green |

## Actual test runs

### Green runs (final evidence)

1. Full unit tier, all modules:
   `cd agent-control-tower && mvn -B -ntp test -Dspring.profiles.active=h2`
   Result: **BUILD SUCCESS**, total time 05:48.

   | Module | Tests | Failures | Errors | Skipped |
   |--------|------:|---------:|-------:|--------:|
   | act-common | 151 | 0 | 0 | 0 |
   | act-agent | 312 | 0 | 0 | 0 |
   | act-execution | 1351 | 0 | 0 | 11 |
   | act-knowledge | 269 | 0 | 0 | 4 |
   | act-dashboard-api | 90 | 0 | 0 | 0 |
   | act-aria | 187 | 0 | 0 | 4 |
   | act-mcp | 172 | 0 | 0 | 0 |
   | act-app | 77 | 0 | 0 | 0 |
   | **Total** | **2609** | **0** | **0** | **19** |

   Note: `act-test-support` has no test classes (no surefire reports).

   New/changed tests inside that run:
   - `SpecArtifactRootEndToEndTest`: 6 run, 0 failures, 0 errors
   - `SpecReviewCoordinatorTest`: 25 run, 0 failures, 0 errors
   - `RunArtifactPropertiesTest`: 8 run, 0 failures, 0 errors
   - `RunArtifactLocatorTest`: 7 run, 0 failures, 0 errors
   - `RunWorkspaceServiceTest`: 43 run, 0 failures, 0 errors
   - `WorkspacePathsConfiguredRootTest`: 5 run, 0 failures, 0 errors

2. Integration tier for the touched modules:
   `mvn -B -ntp verify -pl act-common,act-knowledge,act-execution -Dskip.unit.tests=true -Dspring.profiles.active=h2 -Djacoco.skip=true`
   Result: **BUILD SUCCESS**.
   - act-execution failsafe: 97 run, 0 failures, 0 errors, 10 skipped
   - act-knowledge failsafe: 12 run, 0 failures, 0 errors, 0 skipped
   - act-common failsafe: 0 tests
   - Total: 109 run, 0 failures, 0 errors, 10 skipped

3. Build/compile:
   `mvn -B -ntp install -DskipTests`
   Result: **BUILD SUCCESS** (all 10 reactor modules).

4. Full Spring context (startup log + bean wiring):
   `mvn -B -ntp test -pl act-app -Dspring.profiles.active=h2 -Dtest=SchemaConsistencySmokeTest -Djacoco.skip=true`
   Result: **BUILD SUCCESS**, 1 test, 0 failures/errors. Observed:
   `INFO ... RunArtifactProperties : run result root resolved: path=/workspace/repo/agent-control-tower/act-app/data/workspaces/results property=aria.workspaces.result-root`
   followed by the expected "does not exist" WARN (non-fatal by spec). `RunArtifactStartupCheck`
   was instantiated in this context and did not fail.

### Failed/non-green runs (reported honestly)

1. A targeted `act-common -Dtest=...` run executed the 15 selected tests with 0 failures/0
   errors but the build failed at `jacoco:check` (subset coverage 0.14 < 0.55). This is a
   coverage-gate failure of a filtered run, not a test failure; the full-module run above is
   the green evidence.
2. First integration attempt:
   `mvn verify -pl act-common,act-knowledge,act-execution -Dskip.unit.tests=true ...`
   Result: **FAILURE** — `act-execution` failsafe reported `Tests run: 97, Failures: 0, Errors: 17`
   (17 errors in `CoreAdapterContractTest`, all `IllegalState: nothing found below ... for
   packages/qoder-acp-bridge/dist/main.js`), and `act-knowledge` was skipped. The cause was the
   unbuilt bridge `dist/` prerequisite, not the change under review. After building the bridge
   (`npm install && npm run build` in `packages/qoder-acp-bridge`) the same command was re-run
   from scratch and was fully green (run 2 above). The errored run is **not** used as evidence
   for any criterion.

## Findings / residual notes

- No functional defect or spec gap was found. The implementation follows the required shared
  `act-common` bean/locator with the required dependency direction.
- The startup consistency check compares the properties root against the locator root. Because
  production wiring injects the same singleton locator into both capture and read, this is a
  guard around the shared bean rather than a second independently computed path. The spec
  explicitly anticipated that the invariant "holds by construction", so this satisfies the
  criterion, but a future module that reintroduces its own root would not be caught by this
  particular assertion until that module is wired through the check.
- The 19 skipped unit tests and 10 skipped integration tests are pre-existing environment-gated
  skips (Windows/POSIX/root/optional-core gates), not failures.

## Checkout/commit handling

Only `qa_report.md` is added. Committed with an explicit pathspec:
`git add -- qa_report.md && git commit -m 'sdd qa report' -- qa_report.md`.
`git diff --stat HEAD~1` must list `qa_report.md` as the only changed file before pushing.
