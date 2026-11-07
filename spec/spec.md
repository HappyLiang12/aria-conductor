# Spec: Fix Git HTTPS Authentication for Agent-Core `git push` (Issue #60)

## Problem Statement

`git push` over HTTPS always fails with exit code 128 (authentication error) when executed by agent cores (OpenCode or Qoder) in the SDD workflow, even when `GITHUB_TOKEN` is correctly stored in the pack credential store and injected as the `GH_TOKEN` environment variable.

**Root cause**: Git HTTPS does not read `GH_TOKEN` or `GITHUB_TOKEN` from the process environment. Git requires one of:
1. A credential helper that returns the token on stdin
2. The token embedded in the remote URL (`https://x-access-token:TOKEN@github.com/...`)
3. `GIT_ASKPASS` pointing to a script that echoes the token

The current implementation only sets `GH_TOKEN` as an environment variable (via `application.yml` `sandbox-env` → `CoreRuntimeConfiguration.openCodeModelProviderEnvironment()` → `OpenCodeCoreAdapter.launchProfile()` → `OwnedProcessController.launch()`). This works for the `gh` CLI (which reads `GH_TOKEN` natively) but not for `git` itself.

**Impact**: The entire git push / PR creation workflow is broken. Agents can clone, edit, and commit locally but cannot push results to the remote. This is the core value proposition of the SDD git workflow (V47 migration prompts agents to `git push origin {branchName}`).

**Architecture context**: The `GitPackHandler` class referenced in the issue does not exist as implemented Java code — it is dead design metadata (`handler_class = 'gitPackHandler'` in V33 migration). Git push is executed by agent cores using their native shell/bash tools. The backend's role is credential injection, workspace preparation, and governance (risk tiers, approval gates).

**Secondary issue**: The audit trail does not propagate exit code 128 as a failure. `ToolAuditAspect` logs `outcome=ok` when a Java handler returns normally, regardless of child process exit codes. This masks the authentication failure, causing agents to retry, burn tokens, and eventually hit the circuit breaker.

## Proposed Solution

### Primary Fix: Configure Git Credential Helper in Workspace Preparation

Add a git credential helper configuration step to `RunWorkspaceService` (which already uses `ProcessBuilder` for git worktree operations and sets `GIT_TERMINAL_PROMPT=0`). Before the agent core is launched, the backend configures git in the run workspace to use a credential helper that echoes the token.

**Implementation approach**:

1. **Extract token resolution into a shared service**: The token resolution logic currently in `GitBranchConfig` (pack credential store → `GH_TOKEN` alias → `System.getenv`) should be extracted into a `GitCredentialResolver` service that can be used by both `GitBranchConfig` (for REST API calls) and the workspace preparation step (for agent-core git operations).

2. **Add credential helper setup to `RunWorkspaceService`**: After the workspace is provisioned and before the core is launched, configure a git credential helper in the workspace's git config:
   - **Linux/macOS (sandbox and host)**: `git config credential.helper '!f() { echo "password=$GH_TOKEN"; }; f'`
   - **Windows host**: Use a platform-appropriate credential helper command (e.g., a small batch/PowerShell script that echoes the token, or use `git config credential.helper manager` if available, or write a temporary script).
   - The credential helper references `$GH_TOKEN` from the environment (which is already injected), so the token value does not appear in git config files on disk.

3. **Set `GIT_TERMINAL_PROMPT=0`** in the core launch environment to prevent git from prompting interactively (this is already done in `RunWorkspaceService.runGit()` but not in the core launch environment).

4. **Handle the `GIT_ASKPASS` constraint**: `GIT_ASKPASS` is explicitly forbidden in `CoreConfigurationService.FORBIDDEN_ENVIRONMENT_VARIABLES` (line 112) as a security measure against process injection. The credential helper approach does not require `GIT_ASKPASS` and is therefore compatible with the existing security policy. Do NOT remove `GIT_ASKPASS` from the forbidden list.

### Secondary Fix: Propagate Non-Zero Exit Codes in Audit

Update the audit recording for tool executions so that non-zero exit codes from child processes are recorded as `FAILED` rather than `SUCCESS`. This is a separate concern from the credential helper fix but is mentioned in the issue as a related problem.

**Scope note**: The issue mentions this as "separate issue" and the current audit architecture does not directly record individual tool exit codes (the `AuditEvent` entity has no `exitCode` or `result` field; `ToolCall.status` is set by the core execution path, not by inspecting git exit codes). A full fix would require changes to how agent cores report tool results to the backend. This spec addresses the primary fix (credential helper) and notes the audit gap as a follow-up.

## Acceptance Criteria

### AC-1: Git push succeeds with token in credential store
- **Given** a valid `GITHUB_TOKEN` is stored in the pack credential store (`POST /api/v1/packs/pack-git-0001/credentials`)
- **When** an SDD workflow run reaches the DEV or QA step that executes `git push origin {branchName}`
- **Then** the push succeeds (exit code 0) and the commits are visible on the remote branch

### AC-2: Git push succeeds with token in environment variable
- **Given** `GITHUB_TOKEN` is set as an environment variable on the backend process (e.g., via docker-compose `.env`)
- **When** an agent core executes `git push` over HTTPS
- **Then** the push succeeds (exit code 0)

### AC-3: Git clone works for private repos
- **Given** a valid `GITHUB_TOKEN` with repo scope is configured
- **When** an agent core executes `git clone https://github.com/org/private-repo.git`
- **Then** the clone succeeds (the credential helper also serves `git clone` auth)

### AC-4: No token value in git config files on disk
- **Given** the workspace is prepared with the credential helper
- **When** the git config files in the workspace are inspected
- **Then** the token value does not appear in any config file (the credential helper references `$GH_TOKEN` from the environment, not a literal token)

### AC-5: Graceful degradation when no token is configured
- **Given** no `GITHUB_TOKEN` is available (not in credential store, not in environment)
- **When** an agent core attempts `git push`
- **Then** git fails with a clear authentication error (not a hang or interactive prompt), and the failure is recorded in the tool call status

### AC-6: Works in both HOST and SANDBOX modes
- **Given** the platform is running in HOST mode (no container) or SANDBOX mode (OpenSandbox)
- **When** an agent core executes git push
- **Then** the credential helper is correctly configured in both modes

### AC-7: Works on Linux and Windows hosts
- **Given** the backend is running on Linux or Windows
- **When** the workspace is prepared
- **Then** the credential helper command is appropriate for the host OS

### AC-8: Existing tests pass
- **Given** the changes are implemented
- **When** `cd agent-control-tower && mvn test -pl act-execution` and `mvn test -pl act-app` are run
- **Then** all existing tests pass, including `SddWorkflowIntegrationTest` and `GitBranchConfigTest`

## Error Handling

### EH-1: Token resolution fails
- **Scenario**: The pack credential store has no token, and `GITHUB_TOKEN`/`GH_TOKEN` are not set in the environment
- **Behavior**: `GitCredentialResolver` returns an empty/absent token. The workspace preparation step skips credential helper configuration. The `GitBranchService` bean degrades to the disabled stub (existing behavior). Agent cores can still execute git operations that don't require authentication (e.g., `git status`, `git diff`, local `git commit`). Push/clone of private repos fails with a clear git authentication error.

### EH-2: Git config command fails
- **Scenario**: The `git config credential.helper ...` command fails (e.g., git not installed, workspace directory not a git repo, permission denied)
- **Behavior**: Log a warning with the failure reason. Continue with core launch (the core may still succeed if the remote URL has embedded credentials or if the operation doesn't require auth). Do not fail the run solely because credential helper setup failed.

### EH-3: Credential helper script fails at runtime
- **Scenario**: The credential helper is configured but fails when git invokes it (e.g., `$GH_TOKEN` is empty, script is not executable)
- **Behavior**: Git reports an authentication error (exit code 128). The agent core sees the non-zero exit code and reports tool failure. The run may retry or fail depending on the agent's logic.

### EH-4: Token decryption fails after credential key rotation
- **Scenario**: The `PACK_CREDENTIAL_KEY` is rotated, and stored tokens can no longer be decrypted
- **Behavior**: `PackCredentialService.resolve()` catches the decryption exception and returns null (existing behavior in `GitBranchConfig`). The resolver falls back to `GH_TOKEN` alias, then to `System.getenv("GITHUB_TOKEN")`. If all fail, credential helper is not configured (see EH-1).

### EH-5: Windows-specific credential helper
- **Scenario**: The backend runs on Windows, where shell syntax differs from Linux
- **Behavior**: Use a Windows-compatible credential helper command. Options:
  - Write a small batch script (`echo password=%GH_TOKEN%`) to the workspace and reference it
  - Use `git config credential.helper '!cmd /c "echo password=%GH_TOKEN%"'`
  - If no Windows-compatible approach works, log a warning and skip credential helper setup on Windows (agents on Windows hosts would need to use the `GitBranchService` REST API path instead of shell git push)

## Questions

[No questions — the approach is determined by the existing architecture constraints: `GIT_ASKPASS` is forbidden, the credential helper must reference `$GH_TOKEN` from the environment (not embed the token in config), and the fix must work in both HOST and SANDBOX modes.]
