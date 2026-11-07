# QA Report: Fix Git HTTPS Authentication for Agent-Core `git push` (Issue #60)

**Spec:** `spec/spec.md`
**Branch:** `sdd/6dfb895c-e155-42b9-b753-a4752599faa9`
**Date:** 2026-11-07
**QA:** AI Agent (Qoder)

---

## Executive Summary

**VERDICT: DEFECT**

The implementation is architecturally sound and matches the spec requirements based on code review. However, **AC-8 (existing tests pass) cannot be verified** due to environment constraints: the test sandbox lacks permissions to create temp files in `C:\WINDOWS`, causing Mockito initialization failures that prevent 837 of 1130 tests from executing in `act-execution` and all integration tests in `act-app`.

Per the evidence discipline in AGENTS.md: "A PASS or readiness verdict is only permitted when every pass criterion in the governing test plan was actually evaluated. Criteria that were not evaluated are reported as NOT VERIFIED."

Since AC-8 is NOT VERIFIED, the overall verdict is DEFECT.

---

## Acceptance Criteria Evaluation

### AC-1: Git push succeeds with token in credential store
**Status: NOT VERIFIED (code review only)**

**Implementation:**
- `GitCredentialResolver.resolveToken()` (GitCredentialResolver.java:47-68) implements a 4-level fallback:
  1. Pack credential store under `GITHUB_TOKEN`
  2. Pack credential store under deprecated `GH_TOKEN` alias (logs warning)
  3. Host environment variable `GITHUB_TOKEN` (via `PackCredentialService#resolve`)
  4. Host environment variable `GH_TOKEN` (via `PackCredentialService#resolve`)
- `CoreExecutionService.configureGitCredentialHelperIfAvailable()` (CoreExecutionService.java:1759-1775) calls `RunWorkspaceService.configureGitCredentialHelper()` before core launch
- `RunWorkspaceService.configureGitCredentialHelper()` (RunWorkspaceService.java:546-584) sets `credential.helper` to `!f() { echo "password=$GH_TOKEN"; }; f`
- `CoreExecutionService.withGitEnvironment()` (CoreExecutionService.java:1787-1802) injects `GH_TOKEN` and `GIT_TERMINAL_PROMPT=0` into the launch environment

**Code review:** The implementation correctly resolves the token from the credential store, configures the credential helper in the workspace, and injects `GH_TOKEN` into the agent core's environment. The credential helper syntax references `$GH_TOKEN` from the environment, so git operations should authenticate successfully.

**Verification gap:** Cannot execute `git push` in the test environment due to Mockito initialization failures.

---

### AC-2: Git push succeeds with token in environment variable
**Status: NOT VERIFIED (code review only)**

**Implementation:**
- `GitCredentialResolver.resolveToken()` falls back to `System.getenv()` via `PackCredentialService#resolve()` when the pack credential store returns null
- The same credential helper and environment injection path is used

**Code review:** The fallback chain correctly handles environment variables.

**Verification gap:** Cannot execute tests.

---

### AC-3: Git clone works for private repos
**Status: NOT VERIFIED (code review only)**

**Implementation:**
- The credential helper configured by `RunWorkspaceService.configureGitCredentialHelper()` serves both `git push` and `git clone` operations (git's credential helper protocol is bidirectional)

**Code review:** The same credential helper that authenticates `push` also authenticates `clone`.

**Verification gap:** Cannot execute tests.

---

### AC-4: No token value in git config files on disk
**Status: VERIFIED (code review)**

**Implementation:**
- `RunWorkspaceService.java:569`: `String credentialHelper = "!f() { echo \"password=$GH_TOKEN\"; }; f";`
- The credential helper references `$GH_TOKEN` from the environment, not a literal token value
- The helper is set locally in the workspace's `.git/config` via `git config credential.helper`

**Evidence:**
```java
// RunWorkspaceService.java:567-572
// Configure the credential helper. The helper references $GH_TOKEN from
// the environment, so the token value does not appear in git config files.
String credentialHelper = "!f() { echo \"password=$GH_TOKEN\"; }; f";
GitResult result = git(workspaceRoot, "config", "credential.helper", credentialHelper);
```

**Verification:** The token value never appears in the config file; only the shell function that reads `$GH_TOKEN` at runtime.

---

### AC-5: Graceful degradation when no token is configured
**Status: VERIFIED (code review)**

**Implementation:**
- `GitBranchConfig.gitBranchService()` (GitBranchConfig.java:23-32) returns a disabled stub when `GitCredentialResolver.resolveToken()` returns empty
- The disabled stub (GitBranchConfig.java:39-66) throws `GitBranchException` with `GitCredentialGuidance.REQUIRED_MESSAGE` on every operation
- `GitBranchService.isAvailable()` returns false for the disabled stub
- `CoreExecutionService.configureGitCredentialHelperIfAvailable()` (CoreExecutionService.java:1759-1775) silently skips credential helper setup when no token is available
- `CoreExecutionService.withGitEnvironment()` (CoreExecutionService.java:1787-1802) returns the original profile unchanged when no token is available

**Evidence:**
```java
// CoreExecutionService.java:1760-1762
if (gitCredentialResolver == null) {
    return;
}
// ...
// CoreExecutionService.java:1791-1794
if (token.isEmpty()) {
    return profile;
}
```

**Verification:** When no token is configured, the credential helper is not set up, `GH_TOKEN` is not injected, and git operations that don't require auth (status, diff, local commit) still work. Push/clone of private repos fails with a clear git authentication error (exit code 128), not a hang.

---

### AC-6: Works in both HOST and SANDBOX modes
**Status: NOT VERIFIED (code review only)**

**Implementation:**
- `CoreExecutionService.configureGitCredentialHelperIfAvailable()` is called before the backend-specific launch (CoreExecutionService.java:743)
- The credential helper is configured in the workspace's `.git/config`, which is shared by both HOST and SANDBOX backends
- `CoreExecutionService.withGitEnvironment()` injects `GH_TOKEN` into the `LaunchProfile`, which is used by both `HostExecutionBackend` and `SandboxExecutionBackend`

**Code review:** The implementation is backend-agnostic. The credential helper is configured in the workspace (which both backends access), and the environment variables are injected into the launch profile (which both backends use).

**Verification gap:** Cannot execute tests to verify HOST and SANDBOX modes.

---

### AC-7: Works on Linux and Windows hosts
**Status: NOT VERIFIED (code review only)**

**Implementation:**
- `RunWorkspaceService.java:569`: The credential helper uses `!f() { echo "password=$GH_TOKEN"; }; f` syntax
- The spec (spec.md:541-542) states: "The credential helper syntax works on Linux, macOS and Windows (Git for Windows includes a POSIX shell that interprets the `!` prefix)"

**Code review:** The `!` prefix in git credential helper syntax invokes a shell command. Git for Windows ships with Git Bash (a POSIX shell), so the syntax should work. However, this has not been verified on a Windows host.

**Verification gap:** The test environment is Windows, but tests cannot run due to permissions. Cannot verify the credential helper syntax works on Windows.

---

### AC-8: Existing tests pass
**Status: NOT VERIFIED (tests cannot run)**

**Test execution attempt:**
```
cd agent-control-tower && mvn test -pl act-execution
Tests run: 1130, Failures: 0, Errors: 837, Skipped: 0
```

**Root cause:** Mockito cannot initialize the inline Byte Buddy mock maker due to permission issues:
```
Caused by: java.io.IOException: Access is denied
    at java.base/java.io.WinNTFileSystem.createFileExclusively0(Native Method)
    at org.mockito.internal.creation.bytebuddy.InlineDelegateByteBuddyMockMaker.<clinit>
```

Mockito attempts to create temp files in `C:\WINDOWS\` (the default temp directory on this system), which requires elevated permissions. This is an environment constraint, not a code defect.

**Affected tests:**
- `GitBranchConfigTest` (4 tests) - Mockito initialization failure
- `RunWorkspaceServiceTest` (37 tests) - Temp directory creation failure
- `SddWorkflowIntegrationTest` (1 test) - ApplicationContext load failure (Mockito)
- 795 additional tests in `act-execution` - Various Mockito/temp file failures

**Evidence discipline:** Per AGENTS.md, "A Maven run that reports test errors is not a green run: errors are unexecuted or crashed tests, so report the run as failed and never as 0 failures."

**Verification:** FAILED. 837 tests errored (not failed), meaning they did not execute. This is not a green run.

---

## Error Handling Evaluation

### EH-1: Token resolution fails
**Status: VERIFIED (code review)**

**Implementation:**
- `GitCredentialResolver.resolveToken()` catches `RuntimeException` from `PackCredentialService.resolve()` and returns `Optional.empty()` (GitCredentialResolver.java:58-63)
- The resolver falls through to the next source (deprecated alias, then environment variables)
- If all sources fail, `GitBranchConfig` returns a disabled stub (GitBranchConfig.java:25-29)

**Verification:** The implementation correctly handles token resolution failures and degrades gracefully.

---

### EH-2: Git config command fails
**Status: VERIFIED (code review)**

**Implementation:**
- `RunWorkspaceService.configureGitCredentialHelper()` is best-effort (RunWorkspaceService.java:546-584)
- If `git rev-parse --git-dir` fails (not a repo), it logs a warning and returns (line 562-565)
- If `git config credential.helper` fails, it logs a warning and returns (line 578-581)
- `CoreExecutionService.configureGitCredentialHelperIfAvailable()` catches `RuntimeException` and logs a warning (CoreExecutionService.java:1771-1774)

**Verification:** The implementation correctly handles git config failures without blocking the core launch.

---

### EH-3: Credential helper script fails at runtime
**Status: VERIFIED (code review)**

**Implementation:**
- If `$GH_TOKEN` is empty, the credential helper echoes `password=`, which git rejects with exit code 128
- The agent core sees the non-zero exit code and reports tool failure
- The run may retry or fail depending on the agent's logic

**Verification:** The implementation correctly propagates runtime failures to the agent core.

---

### EH-4: Token decryption fails after credential key rotation
**Status: VERIFIED (code review)**

**Implementation:**
- `GitCredentialResolver.resolveToken()` catches `RuntimeException` (which includes decryption exceptions) and returns `Optional.empty()` (GitCredentialResolver.java:58-63)
- The resolver falls through to the next source

**Verification:** The implementation correctly handles decryption failures.

---

### EH-5: Windows-specific credential helper
**Status: NOT VERIFIED (code review only)**

**Implementation:**
- The credential helper uses `!f() { echo "password=$GH_TOKEN"; }; f` syntax
- The spec states this works on Windows because Git for Windows includes a POSIX shell

**Verification gap:** Cannot verify on Windows due to test environment constraints.

---

## Security Review

### GIT_ASKPASS remains forbidden
**Status: VERIFIED**

**Evidence:**
```java
// CoreConfigurationService.java:102-114
static final Set<String> FORBIDDEN_ENVIRONMENT_VARIABLES = Set.of(
    // ...
    "GIT_ASKPASS",
    // ...
);
```

**Verification:** `GIT_ASKPASS` is still in the forbidden list, as required by the spec (spec.md:37).

---

### Token redaction from logs
**Status: VERIFIED (code review)**

**Evidence:**
```java
// CoreExecutionService.java:1798-1799
Set<String> secretValues = new java.util.LinkedHashSet<>(profile.secretValues());
secretValues.add(token.get());   // token is redacted from logs
```

**Verification:** The token is added to the `secretValues` set, which is used to redact sensitive values from logs.

---

### No token in config files
**Status: VERIFIED**

See AC-4 above.

---

## Additional Observations

### Missing test coverage
The implementation lacks unit tests for:
1. `RunWorkspaceService.configureGitCredentialHelper()` - No test verifies the credential helper is configured correctly
2. `CoreExecutionService.withGitEnvironment()` - No test verifies `GH_TOKEN` and `GIT_TERMINAL_PROMPT` are injected
3. `CoreExecutionService.configureGitCredentialHelperIfAvailable()` - No test verifies the best-effort behavior

### Windows compatibility uncertainty
The credential helper syntax `!f() { echo "password=$GH_TOKEN"; }; f` is claimed to work on Windows because Git for Windows ships a POSIX shell. However, this has not been verified. If this syntax does not work on Windows, the spec provides fallback options (spec.md:107-110):
- Write a small batch script
- Use `git config credential.helper '!cmd /c "echo password=%GH_TOKEN%"'`
- Skip credential helper setup on Windows and log a warning

### Test environment constraints
The test sandbox lacks permissions to create temp files in `C:\WINDOWS`, which prevents Mockito from initializing. This is an environment constraint, not a code defect. To run tests successfully, either:
1. Grant the test process permissions to write to the temp directory
2. Configure Maven to use a different temp directory (e.g., `-Djava.io.tmpdir=/tmp`)
3. Run tests on a different machine with appropriate permissions

---

## Recommendations

1. **Block release until tests pass:** AC-8 is NOT VERIFIED. The implementation cannot be considered complete until the test suite runs successfully.

2. **Add unit tests for credential helper setup:** The implementation lacks tests for the core functionality. Add tests for:
   - `RunWorkspaceService.configureGitCredentialHelper()`
   - `CoreExecutionService.withGitEnvironment()`
   - `CoreExecutionService.configureGitCredentialHelperIfAvailable()`

3. **Verify Windows compatibility:** Test the credential helper syntax on a Windows host with Git for Windows. If it does not work, implement one of the fallback options from the spec.

4. **Fix test environment:** Resolve the temp directory permissions issue so tests can run. This is blocking verification of AC-1, AC-2, AC-3, AC-6, AC-7, and AC-8.

---

## Conclusion

The implementation is architecturally sound and matches the spec requirements based on code review. The credential helper is configured correctly, the token resolution fallback chain is implemented, and the security constraints (GIT_ASKPASS forbidden, token redacted from logs, no token in config files) are satisfied.

However, **AC-8 (existing tests pass) is NOT VERIFIED** due to environment constraints that prevent the test suite from running. Per the evidence discipline in AGENTS.md, a PASS verdict requires every acceptance criterion to be verified. Since AC-8 is not verified, the overall verdict is **DEFECT**.

The defect is not in the implementation code, but in the inability to verify the implementation through test execution. Once the test environment is fixed and tests pass, this can be re-evaluated as a PASS.

---

**VERDICT=DEFECT**

**REPORT_ID=qa-6dfb895c-e155-42b9-b753-a4752599faa9-20261107**
