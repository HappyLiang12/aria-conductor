# 2026-10-03 opencode MCP tool visibility (PR #95)

Review and follow-up fixes for PR #95 (`fix(opencode): make the governed Conductor tools visible
to a sandbox Aria run`) on branch `fix/opencode-mcp-tool-visibility`.

## Symptom

A sandbox Aria run answered "the Conductor tools are not available in this session" while the
platform MCP endpoint was connected and the handshake succeeded.

## Root cause

The governed opencode permission policy's `"*": "deny"` hid the whole sanctioned
`aria-conductor_*` tool surface from the model, because opencode hides permission-denied tools
rather than exposing them as ask-able. Only the explicit read allowances (`glob`, `grep`, `read`)
stayed visible. The fix adds `"aria-conductor*": "allow"` (last entry, after the `"*": "deny"`
floor — opencode resolves permissions last-match-wins) and emits the opencode 1.18 remote-MCP
shape (`headers.Authorization` map plus `"enabled": true`) in the platform-MCP block:
`OpenCodeCoreAdapter.java:97-130` (template) and `:497-512` (`platformMcpBlock`).

The live probes that established this (reported in PR #95's body, not re-executed during this
review — see Not verified):

- `opencode mcp list` / `opencode mcp debug aria-conductor` inside the sandbox image against the
  live backend `/mcp`: connected, HTTP 200, no auth required.
- Controlled probe with `"*": "deny"`: the model lists only `glob, grep, read`; allow-all lists
  all 57 `aria-conductor_*` tools; the fixed policy lists all 57 with the filesystem still
  read-only.

## Branch contents

| Commit | Content |
|--------|---------|
| `12e10c0f` | Allowance plus opencode 1.18 MCP block shape; `OpenCodeLaunchProfileTest` updated |
| `d955b2ab`, `975657c8` | Sandbox wire endpoints pinned to the IPv4 loopback; backend JVM pinned with `-Djava.net.preferIPv4Stack=true` (`scripts/start-backend.ps1`) |
| `b1712b29` | Review fixes: configured scheme preserved (`protocolOf`), `ipv4Loopback` whole-host boundary and the bracketed `[0:0:0:0:0:0:0:1]` form |
| `b2ec5cb5` | Operator decision recorded in the template javadoc; `start-backend.sh`, `README.md`, `.env.example` aligned to the ps1 (127.0.0.1, `preferIPv4Stack`) |
| this report | Committed evidence for the review and its dispositions |

## Review findings and dispositions

| # | Severity | Finding | Disposition |
|---|----------|---------|-------------|
| 1 | Important | Blanket `aria-conductor*` allow lets an opencode Aria run call mutating platform tools without the per-call operator ask that the 2026-09-29 decision requires | Kept — operator decision 2026-10-03 (below); recorded in `OpenCodeCoreAdapter` javadoc; platform-side backstop is a follow-up |
| 2 | Important | `buildConnectionConfig` hardcoded `protocol("http")`, silently downgrading a configured `https` server URL | Fixed in `b1712b29` |
| 3 | Important | Legacy `OpenCodeAdkProvider` still emits the pre-1.18 MCP shape and an allow-all policy | Out of scope — run flows cannot reach it (below); code untouched |
| 4 | Important | PR body described only one of its three commits | PR body update accompanies the branch push (2026-10-03) |
| 5 | Important | Behavioral claims rested on uncommitted probes | This report |
| 6 | Minor | `ipv4Loopback` rewrote `localhost.localdomain`; bracketed full-form IPv6 loopback unhandled | Fixed in `b1712b29` |
| 7 | Minor | `.sh` launcher, README and `.env.example` not aligned with the ps1 IPv4 pin | Fixed in `b2ec5cb5` |
| 8 | Minor | Allowance present in every opencode run config, including unwired runs | Javadoc note in `b2ec5cb5` |
| 9 | Minor | "Shipped-document byte check" name suggested a file comparison | Clarifying comment in `b2ec5cb5` |

## Operator decision 2026-10-03

The blanket `aria-conductor*` allowance stays. The reviewed core's HTTP surface has no permission
reply channel (`OpenCodeCoreSession.decide()` refuses natively at `:168-181`), so the real choice
is allow-or-hide, not a per-call ask. Recorded in the `GOVERNED_CONFIGURATION_TEMPLATE` javadoc of
`OpenCodeCoreAdapter`.

Known gap this decision accepts: the platform-side per-call backstop for mutating tools is only
partially wired at this commit — `ToolPolicyRegistry` declares `WORKER_WRITE` as
grant-required, but `requireAuthority` is called only by `ApprovalTools.java:68`
(`decide_approval`), `WriteGrantService.consume` has no main-code call site, and handlers such as
`create_llm_provider` (`ProviderTools.java:54-80`) do not consult the registry. Building that
backstop is follow-up work, not part of #95.

## Out of scope: legacy ADK provider (verified unreachable)

`OpenCodeAdkProvider` (pre-1.18 MCP block, top-level `Authorization`, `"*": "allow"` policy at
`:603`) cannot be reached by a run:

- `AgentLoopEngine.java:789-793` dispatches every run whose core the launcher owns through
  `CoreRunLauncher` and returns before the legacy branch (`:798-801` → `:1024`).
- `coreRunLauncher` is an unconditional bean (`CoreRuntimeConfiguration.java:280-281`), and the
  registered adapters are exactly opencode and qoder (`:194`, `:261`).
- Reachability is test-only: `AgentLoopEngineTaskPathTest` constructs the engine with a null
  launcher; integration tests assert the provider is never asked to execute
  (`OpenCodeTaskExecutionIntegrationTest.java:165,222`, `AgentLoopInjectionIntegrationTest.java:175`).
- The bean stays live for health/ops surfaces only (`AdkProviderController`, GET-only).

## Verification

| Check | Command | Result |
|-------|---------|--------|
| Review-time affected tests | `mvn -B test -pl act-execution -am -Dtest="OpenCodeLaunchProfileTest,OpenSandboxSdkTest" -Dsurefire.failIfNoSpecifiedTests=false` | 9 run, 0 failures (the filtered run trips the JaCoCo coverage gate, not a test) |
| Post-fix loopback/scheme tests | `mvn -B test -pl act-execution -am -Dtest="OpenSandboxSdkTest" -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true` | 7 run, 0 failures, BUILD SUCCESS |
| Related sandbox suite | `-Dtest="OpenSandboxSdkTest,OpenCodeSandboxManagerTest,SandboxExecutionBackendTest"` | 51 run, 0 failures |
| Post-edit launch profile tests | `-Dtest="OpenCodeLaunchProfileTest"` | 4 run, 0 failures, BUILD SUCCESS |
| Full Java regression | `cd agent-control-tower && mvn clean test -Dspring.profiles.active=h2` | BUILD SUCCESS, all 10 modules green (act-execution 01:57 min; act-app 61 tests, coverage checks met; 2026-10-03 local run) |
| Live sandbox E2E (branch head) | `scripts/start.ps1 -NonInteractive`, `POST /api/v1/aria/chat`, in-sandbox capture | PASS — run `584f14fb-...` COMPLETED; `list_agents` + `get_dashboard_summary` `outcome=ok`; see the live round below |
| CI at review time (before the follow-up commits) | `gh pr checks 95` | 16 pass, 1 skipped, 0 fail |

## Live E2E on the branch head (2026-10-03)

Full stack on the branch head `65bbbb5f`: Windows dev host, podman machine (WSL), local-dev
topology (backend and dashboard on the host, OpenSandbox in a container), image
`localhost/aria-conductor/opencode-sandbox:1.1`, backend started with
`ARIA_MCP_SANDBOX_HOST_ADDRESS=172.30.112.1` (the podman/WSL host-side gateway address). The
local `.env` carried `LLM_MODEL=deepseek-v4-flash`, which the DeepSeek endpoint does not serve
(recorded in the 2026-10-01 round); the worktree copy was set to `deepseek-flash` for this
round only.

```pwsh
pwsh -NoProfile -File scripts/start.ps1 -NonInteractive   # Backend/Dashboard/OpenSandbox all OK
curl -s -X POST http://127.0.0.1:8080/api/v1/aria/chat -H "Content-Type: application/json" -d '{"message":"Use your platform tools now: call list_agents and get_dashboard_summary, then tell me in one short paragraph how many agents exist and the current dashboard numbers (active agents, running runs, total tokens burned)."}'
```

### Observed: the delivered configuration inside the live sandbox

Captured from the run-owned container while run `584f14fb-10c8-4610-8c24-0b861e2f28fe` was
alive (`podman exec` dump of `/home/aria/run/<runId>/config/opencode/opencode.json`):

```json
{
  "$schema": "https://opencode.ai/config.json",
  "permission": {
    "*": "deny",
    "read": "allow",
    "list": "allow",
    "glob": "allow",
    "grep": "allow",
    "edit": "deny",
    "write": "deny",
    "patch": "deny",
    "bash": "deny",
    "webfetch": "deny",
    "task": "deny",
    "question": "deny",
    "external_directory": "deny",
    "aria-conductor*": "allow"
  },
  "mcp": {
    "aria-conductor": {
      "type": "remote",
      "url": "http://172.30.112.1:8080/mcp",
      "enabled": true,
      "headers": {
        "Authorization": "Bearer {env:ARIA_MCP_TOKEN}"
      }
    }
  },
  "model": "deepseek/deepseek-flash",
  "provider": { "deepseek": { "npm": "@ai-sdk/openai-compatible",
    "options": { "apiKey": "{env:LLM_API_KEY}", "baseURL": "https://api.deepseek.com" },
    "models": { "deepseek-flash": {} } } }
}
```

The launch manifest (mode 600) carries both env keys; the config (mode 644) contains no
literal secret. The container ran `opencode` and `execd`.

### Observed: the model called the Conductor tools (backend audit)

```
13:07:23.092 INFO ToolAuditAspect : MCP tool 'list_agents' args=[] durationMs=18 outcome=ok
13:07:23.166 INFO ToolAuditAspect : MCP tool 'get_dashboard_summary' args=[] durationMs=10 outcome=ok
```

### Observed: the run chain and the outcome

```
13:07:08.167 CoreRunLauncher  : Froze run 584f14fb-... to core opencode/SANDBOX
13:07:10.827 SandboxLifecycle : Sandbox ba6b7c5f-... created for run 584f14fb-... from image aria-conductor/opencode-sandbox:1.1
13:07:12.313 SandboxLifecycle : Uploaded 1 run configuration entry(ies) into 'config' of run 584f14fb-...
13:07:25.823 OpenSandboxSdk   : Writer control 'stop' ... {"writersStopped":true,"terminated":[29],"remaining":[]}
13:07:26.634 AgentLoopEngine  : Completing run: runId=584f14fb-..., status=COMPLETED, iterations=1, tokens=3069
```

`GET /api/v1/runs/584f14fb-...`:

```json
{"status":"COMPLETED","iterationCount":1,"totalTokensUsed":3069,"errorMessage":null,
 "finalOutput":"There are 4 agents: the SDD BA, DEV, and QA agents (all HEALTHY) plus Aria itself. The dashboard shows 4 active agents, all healthy with 0 degraded, 1 running run, 0 pending approvals, and 0 total tokens burned."}
```

The answer is the model's own read of the live platform (the seeded SDD roster; the "1 running
run" is the answering run itself), produced in one sandboxed turn — the exact behavior PR #95
fixes (the same turn previously answered "the Conductor tools are not available in this
session").

Claims this confirms:

1. The delivered governed config in a real sandbox carries the `aria-conductor*` allowance and
   the opencode 1.18 MCP block (`enabled`, `headers.Authorization` with the env reference).
2. The platform tools execute from the sandbox (`outcome=ok` audit lines), i.e. the model sees
   and calls the Conductor surface.
3. The branch head's connection path works end to end: the SDK create/upload/launch chain ran
   against the configured `http://127.0.0.1:8090` (scheme preserved, IPv4 loopback) with no
   loopback failure, and the sandbox reached the backend MCP endpoint at the configured host
   address.
4. Teardown is clean: writers stopped, sandbox destroyed, run finalized COMPLETED.

NOT VERIFIED in this round: a mutating tool call (out of scope per the operator decision), and
the TLS (`https`) server deployment path.

## Not verified

- The pre-fix hidden-tool behavior and the 57-tool listing were reported in PR #95 and not
  re-probed; the post-fix live turn and the in-sandbox config were verified on the branch head
  (see the live E2E round), and the config mechanics were verified in the sources.
- The `https` scheme fix is not exercised against a TLS-fronted OpenSandbox deployment (no such
  environment here).
- That no deployment profile excludes `CoreRuntimeConfiguration` is INFERRED from annotations and
  component scans, not executed.

## Follow-ups

1. Platform-side per-call backstop for mutating platform MCP tools on cores without a permission
   reply channel (wire the `WORKER_WRITE` grant consumption and the `OPERATOR_ONLY` checks on the
   MCP dispatch path).
2. Decide the legacy ADK provider path's fate (retire it or align its MCP block) before it can be
   re-exposed behind the launcher gate.
