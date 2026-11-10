# Spec: Single source of truth for the run artifact root (issue #112)

- Issue: #112 — "SDD spec review reads result-root through a duplicated filesystem contract"
- Repository: HappyLiang12/aria-conductor
- Labels: `bug`, `priority:P2`
- Milestone: v0.2 hardening
- Related risk: R-03 in `docs/reviews/2026-10-21-pr109-legacy-execution-retirement/architecture-review.md` (PR #110)

## Problem Statement

The run result root is currently described twice, independently:

- `SpecReviewCoordinator` (`act-knowledge`) resolves `<result-root>/<runId>/spec.md` using its own `@Value` default (`SpecReviewCoordinator.java:66,78,89,280-315`).
- `RunWorkspaceService` (`act-execution`) captures/writes that same artifact using its own configuration and path construction (`RunWorkspaceService.java:87-107,243-250`).
- The default appears in `application.yml:113`.

Because the write side and the read side each own a private copy of the filesystem contract, any change to the root in one module, or any relocation of run storage, can make the reader look somewhere the writer never wrote. When that happens the spec review does not fail loudly: it silently degrades the SPEC_REVIEW input to the BA run's `finalOutput`, which is normally a short summary rather than the full spec. That produces a review of the wrong artifact and is difficult to diagnose because the resolved path is not logged.

The goal is one canonical, shared definition of the run artifact root and one artifact lookup implemented in a single place, so changing the configured root once changes both capture and read.

## Proposed Solution

### 1. One canonical configuration property

Introduce a single `@ConfigurationProperties` bean, `RunArtifactProperties`, registered in `act-common`, bound to the existing result-root property currently declared at `application.yml:113`.

- The existing property key is retained to preserve deployment compatibility. No module keeps a hard-coded or `@Value` default for this key.
- The bean exposes the configured root as a normalized absolute `Path` through one method (for example `resolvedResultRoot()`), and that method is the only place where placeholder/relative-path normalization happens.
- Both `act-execution` (write/capture) and `act-knowledge` (read) depend on this bean. Neither module constructs its own root path.
- If a second, module-local root property exists anywhere, it is removed (or, if removal would break deployments, converted to a deprecated alias that maps onto the canonical property and logs a WARN once at startup). New code must not read it.
- The bean records and validates the effective root once at startup, and `application.yml` remains the single place an operator changes it.

### 2. One artifact lookup owned below both modules

Add `RunArtifactLocator` to `act-common` as the shared filesystem-contract implementation. `act-knowledge` must not walk directories or build result paths itself. The locator should expose at least:

- `Path resultRoot()` — the canonical normalized root from `RunArtifactProperties`.
- `Path specPath(String runId)` — canonical `<result-root>/<runId>/spec.md`, with `runId` validated and the result normalized and verified to remain under the root.
- `Optional<SpecArtifact> readSpec(String runId)` — reads `spec.md` and returns both the content and the resolved path used, or an empty result with a reason (missing file, empty content, unreadable, invalid run id).

`SpecReviewCoordinator` replaces its `@Value` field, its own default, and its manual path construction (`SpecReviewCoordinator.java:66,78,89,280-315`) with an injected `RunArtifactLocator`. `RunWorkspaceService` replaces its path construction (`RunWorkspaceService.java:87-107,243-250`) with the same locator for capture. The locator is the single place that knows the `spec.md` filename and directory layout.

Dependency direction: `act-common` must not depend on `act-execution` or `act-knowledge`; the shared bean and locator live in `act-common` so both modules can consume them without a cycle. If `act-common` is not a suitable home in this codebase, the implementation may live in a new shared module, but it must not live in `act-knowledge` and must not duplicate logic across modules.

### 3. Startup consistency check and observability

- At startup, log the resolved canonical root once at INFO, in a stable, greppable form (for example `run result root resolved: path=<absolute path>`), along with the property key it came from.
- Add a startup self-check that the path used by the capture side and the path used by the read side are the same canonical path. Because both now obtain it from `RunArtifactProperties`, this should hold by construction; assert it anyway so a future regression fails fast rather than silently reading the wrong directory.
- The assertion must compare normalized absolute paths (not raw strings), so trailing separators and `.`/`..` segments cannot produce false mismatches.
- A failing consistency check must stop startup with an actionable error naming the property key and both values.

### 4. Loud fallback

When the spec artifact cannot be read, `SpecReviewCoordinator` must log a WARN/ERROR containing the run id, the reason (missing, empty, unreadable, invalid id), and the fully resolved absolute path that was attempted, before falling back to the BA `finalOutput`. The log message must use a stable marker so operators can search for it (for example `spec artifact unavailable`).

The WARN must not include the spec content. The fallback itself remains supported by product behavior: the review still runs against `finalOutput`, but it is no longer a silent degradation.

### 5. Tests

Add tests that exercise the real configured-root path, not only a mocked locator:

1. **Non-default root end-to-end:** configure the canonical property to a JUnit temporary directory that is not the production default; run the execution-side capture so `<root>/<runId>/spec.md` is written; run the knowledge-side review read; assert the review receives the full captured spec and not the BA `finalOutput`.
2. **Single-place change:** change only the canonical property and assert both the captured file location and the read location move to the new root.
3. **Fallback logging:** remove/corrupt the spec artifact and assert the WARN is emitted, contains the resolved absolute path and run id, and that review input falls back to `finalOutput`.
4. **Path equality:** assert the write path and read path are identical after normalization for the same configured root.
5. **Configuration guards:** blank/blank-after-trim root and path-traversal `runId` fail fast or are rejected with a clear message, depending on the case defined below.

Use the project's existing test framework and logging-capture approach; do not introduce a new test framework.

## Acceptance Criteria

- A single canonical configuration property controls the run result root for both writing/capturing the spec and reading it for SPEC_REVIEW. Changing that property in one place changes both capture and read.
- `SpecReviewCoordinator` no longer defines an independent `@Value` default for the result root and no longer constructs the spec path itself; it obtains the artifact through the shared locator.
- `RunWorkspaceService` obtains the root/artifact path from the same shared configuration/locator; no duplicated path construction remains in either module.
- A test configured with a non-default root proves that a captured `<root>/<runId>/spec.md` is the content read by spec review (not the BA `finalOutput`).
- A startup log line records the resolved absolute root, and a startup check verifies the capture and read roots are the same canonical path.
- When the spec artifact is missing, empty, unreadable, or the run id is invalid, the coordinator logs a WARN containing the run id, reason, and resolved absolute path before falling back to `finalOutput`.
- No log entry contains spec file content or other artifact payloads.
- Existing behavior for deployments that use the documented property (and still use the default root) is unchanged apart from the new log lines.
- Unit and integration tests pass, and the project's configured lint/typecheck/build commands pass.

## Error Handling

- **Property unset or blank:** fail fast at startup with an error naming the property key. Do not silently substitute an implicit default in either module. The message must state that the run result root must be configured.
- **Property is relative or contains unresolved placeholders:** resolve it deterministically against the process working directory (or fail if placeholders cannot be resolved), log the resolved absolute path once at startup, and use that same resolved value for both capture and read. Never use a raw unnormalized string on one side only.
- **Root directory does not exist:** capture may create it (existing writer behavior); the reader must not create it. At startup, log a WARN with the resolved path if it does not exist, but do not fail startup, since an operator may mount storage after boot.
- **Spec file missing:** log WARN with `spec artifact unavailable`, run id, reason `missing`, and the resolved absolute path; fall back to BA `finalOutput`.
- **Spec file empty or whitespace only:** treat as unavailable; log WARN with reason `empty` and the resolved path; fall back to `finalOutput`.
- **Spec file unreadable or I/O error:** log WARN with reason `unreadable`, the resolved path, run id, and the exception class/message; fall back to `finalOutput`. Never log the file content.
- **Invalid/unsafe run id:** reject blank ids and ids containing `..`, path separators, absolute paths, or anything that would escape the root; log WARN with reason `invalid-run-id` and do not perform a filesystem read outside the root. The affected review falls back to `finalOutput` under the existing product behavior.
- **Capture and read roots diverge:** fail startup with an actionable error that names the property key and both normalized absolute paths. This is a programming/configuration defect and must not silently degrade.
- **Existing artifact at the old root after a relocation:** the read reports `missing` with the new resolved path and falls back; the log must make the relocation visible. No automatic migration between roots is part of this change.
- **Both spec artifact and `finalOutput` unavailable:** do not throw a bare `NullPointerException` or submit an empty review silently; surface an explicit unusable-input error through the coordinator's existing failure path and log an ERROR with the run id and resolved path.

## Assumptions

- `act-common` is an acceptable dependency of both `act-execution` and `act-knowledge`; no module cycle is introduced.
- The property key currently at `application.yml:113` is the canonical key to retain. The exact key name and default value will be read from the repository during implementation and must not be changed without a migration note.
- The artifact filename remains `spec.md` and the layout remains `<result-root>/<runId>/spec.md`; this change does not alter the on-disk layout.
- Fallback to `finalOutput` is intended product behavior and remains, but must become observable.

## Out of Scope

- Migrating existing artifacts between old and new roots.
- Changing the review pipeline, prompt, or SPEC_REVIEW output schema.
- Introducing a database or object-store-backed artifact store.
- Atomic-write redesign of `spec.md` capture, except where needed to make the read path robust; partial-write handling beyond a logged read failure is not required.
- Unrelated cleanup of `act-knowledge` or `act-execution`.

## Definition of Done

The shared property bean and artifact locator are implemented, both modules consume them, the duplicated root resolution is removed, the startup path log and equality assertion exist, the fallback WARN includes the resolved path, and the non-default-root end-to-end test plus fallback-logging test pass in CI.
