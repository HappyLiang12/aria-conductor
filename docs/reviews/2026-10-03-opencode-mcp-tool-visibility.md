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
| CI at review time (before the follow-up commits) | `gh pr checks 95` | 16 pass, 1 skipped, 0 fail |

## Not verified

- The live sandbox behavior (denied-tools-hidden, the 57-tool listing, the live post-fix Aria
  turn) was reported in PR #95 and NOT re-executed during this review; the config mechanics were
  verified in the sources instead.
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
