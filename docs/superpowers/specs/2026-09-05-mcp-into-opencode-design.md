# MCP-into-opencode Design

- **Date**: 2026-09-05
- **Status**: Approved in brainstorm (sections 1–5); pending spec review
- **Success bar (v1)**: In the Aria panel, typing `/workflow` makes Aria natively list workflow templates and instantiate one via MCP — the resulting chain appears without touching the Templates-tab Run button. Additionally, an external agent (e.g., Qoder/Claude) can connect to the platform's MCP endpoint and operate the full SDD workflow loop end-to-end (instantiate → approve gate → Dev → QA → verdict), proven by an automated E2E spec and a live subagent run.

## 1. Background

Sandboxed opencode agents get only native file/shell/web tools: `OpenCodeAdkProvider.writeOpenCodeConfig()` writes `opencode.json` with provider + permission config and no MCP section. Platform capabilities (workflow templates, knowledge, approvals, agents, runs) are reachable only via REST/dashboard. This design embeds an MCP server endpoint in the Spring Boot process so that:

- **In-sandbox Aria** can call platform tools natively (the `/workflow` chat path completes without human REST clicks).
- **External agents** can connect to the same endpoint and operate the platform ("user can use other agent to connect to Aria Conductor and operate it").

Key empirical facts (spikes, 2026-09-05, see `mcp-opencode-context.md`):

- Sandbox containers CAN reach the backend: `http://172.30.112.1:8080` and LAN IP → 200. Only `host.containers.internal` (169.254.1.2) is broken on this podman machine — irrelevant, real IPs work.
- The backend has zero MCP capability today (no spring-ai dependency, no `@Tool` handlers); `packages/mcp-server` (TS, stdio-only) exists but is not running anywhere and stays untouched.
- `opencode.json` supports `mcp` with `remote` (url + headers) entries and `{env:VAR}` interpolation; `opencode.sandbox-env` already injects env vars into sandboxes (GH_TOKEN precedent).
- The backend REST is open (no Spring Security); correlation-ID filter is the only servlet filter today.

## 2. Decisions (user-approved)

1. **No new process** — the MCP endpoint lives inside the Spring Boot process (rejected: Java-spawned node subprocess; rejected: mcp-server container; rejected: stdio server inside sandbox). `packages/mcp-server` is not modified.
2. **Spring Boot upgrade** 3.3 → 3.4.x is approved as part of this work (required by Spring AI 1.0 GA MCP server starter). Upgrade is plan-step 1, gated by the full test suite.
3. **Full platform tool surface** — MCP tools mirror ALL existing service capabilities (parity with REST/dashboard semantics), organized by domain; not just the three `/workflow` tools.
4. **Auth deferred, but implemented** — `aria.mcp.auth-mode: none | token`, default `none` in v1 (no filter active, no header needed). `token` mode ships fully working (Bearer filter + header in opencode.json + `ARIA_MCP_TOKEN` injection) so hardening later is config-only.
5. **Debug mode** — `aria.mcp.debug: true | false` (default `false`; `true` in the h2 dev profile). When on, MCP error results include the full exception class, message, and stack trace so external-agent users can debug or submit issues. When off: message + error-type code only.
6. **CI isolation** — `application-test.yml` sets `aria.mcp.enabled: false` (endpoint not registered in CI integration tests except MCP's own test slice).

## 3. Architecture

```
Spring Boot backend (:8080)
├─ REST /api/v1/**                      (unchanged, open)
├─ NEW /mcp (streamable HTTP, spring-ai-starter-mcp-server-webmvc)
│    ├─ McpTokenFilter (only when auth-mode=token)
│    └─ @Tool beans (act-mcp) → in-process domain services
└─ OpenCodeAdkProvider.writeOpenCodeConfig()
     ├─ Aria-role agent only: mcp.aria-conductor = {
     │    type:"remote", url:"http://<sandboxHostIp>:8080/mcp",
     │    headers.Authorization (only in token mode) }
     └─ token-mode only: inject ARIA_MCP_TOKEN into that sandbox's env

sandbox container (Aria): opencode MCP client ──> /mcp ──> tools
external agent (user's Qoder/Claude): MCP client ──> http://host:8080/mcp
```

- **Port 8080 reused** — the sandbox→backend route already exists (spike-proven); no new port, no new network leg.
- **Host address resolution** — `SandboxHostResolver` (act-execution, pure function, unit-tested): config override `aria.mcp.sandbox-host-address` wins; otherwise auto-resolve a non-loopback IPv4 preferring podman/WSL host-side adapter ranges (172.16.0.0/12 as observed: 172.30.112.1), falling back to other private ranges. Resolved per config-write (sandbox creation), not cached across boots (DHCP dynamism).
- **Aria-role determination** — the config-write site knows the agent entity (seeded Aria orchestrator: fixed seed UUID `00000000-…-0001` / name `Aria`, matching the V42 seeding convention). The mcp block is written only for that agent; the check lives next to `writeOpenCodeConfig` and is unit-tested for both roles.
- **Fall back**: if `spring-ai-starter-mcp-server-webmvc` proves incompatible after the Boot upgrade, the fallback is a minimal in-house HTTP-MCP controller implementing the small tools-only protocol surface. Chosen only on evidence of incompatibility.

## 4. Components

New Maven module **`act-mcp`** (`io.aria.conductor.mcp`), depends on domain modules, depended on by `act-app`:

| Component | Responsibility |
|---|---|
| `McpServerConfig` | starter wiring; `aria.mcp.enabled/auth-mode/debug/token` properties; `@ConditionalOnProperty(enabled)` |
| `McpTokenFilter` | servlet filter on `/mcp/*` — registered only in `token` mode; 401 on missing/invalid Bearer (constant-time compare) |
| `tools/AgentTools` | `@Tool` wrappers → AgentService |
| `tools/RunTools` | → RunService |
| `tools/WorkflowTools` | → WorkflowTemplateService / chain service |
| `tools/KnowledgeTools` | → KnowledgeService |
| `tools/SkillTools` | → skill service surface |
| `tools/ApprovalTools` | → ApprovalGate / approval read services |
| `tools/OpsTools` | → housekeeping/ops services |
| `tools/ProviderTools` | → LLM provider config services (act-execution) |
| `tools/AriaTools` | → Aria chat session & orchestration services (act-aria) |

- Every tool wrapper is thin (DTO reshaping only where the MCP schema requires); it calls the SAME service methods the REST controllers call — behavioral parity by construction.
- **Parity test**: a test asserts the registered tool list equals the curated expected list per domain, so adding a service capability without an MCP tool fails the build visibly.
- act-execution additions: mcp block + host resolver + (token mode) env injection in `writeOpenCodeConfig()`; config keys in `application.yml` (+ h2/mariadb/test profile overrides).
- Parent pom: Boot 3.3 → 3.4.x, Spring AI BOM; `act-mcp` module registration.

## 5. Data Flow

**Flow A — in-sandbox Aria `/workflow`:** user types `/workflow` → existing skill injection (PR #74) → at first use, sandbox creation writes `opencode.json` with the mcp remote block (Aria only) → opencode connects to `/mcp` at tool-use time → Aria calls `list_workflow_templates` → presents options → `instantiate_workflow_template` (same `WorkflowTemplateService.instantiateTemplate` path as the Templates-tab Run button) → chain created → WS events update the dashboard as usual → Aria reports the chain id. No human REST click anywhere.

**Flow B — external agent:** user configures their agent once (URL `http://host:8080/mcp`; no headers in `none` mode; `Authorization: Bearer <token>` in `token` mode) → discovers the full platform tool surface → operates via tool calls with REST-identical semantics.

Not in scope: agent-to-agent messaging — clients share platform state, not direct channels.

## 6. Error Handling & Governance

| Failure | Behavior |
|---|---|
| Missing/invalid Bearer (`token` mode) | 401 pre-handshake (constant-time compare); surfaced as tool error |
| `aria.mcp.enabled=false` | endpoint + filter not registered → 404 |
| Domain errors (404 unknown id, 409 state conflict, 400 validation) | tool wrapper catches → MCP error result with the service's own message + error-type code; `debug=true` adds full exception class + stack trace |
| Unexpected exception | logged server-side; generic error to client (+ stack only in debug mode) |
| MCP unreachable from sandbox | declaration is passive — opencode contacts `/mcp` only at tool use; native tools unaffected; Aria degrades and reports |
| Token rotation (`token` mode) | picked up at next sandbox creation; existing sandboxes keep old token (documented) |
| Long-running operations | no blocking-wait tools; instantiate returns ids immediately; polling via `get_run_*` tools keeps MCP requests short |

Governance (explicit, documented): with auth-mode `none`, any client that can reach :8080 has operator-level tool access — same trust level as the open REST. The audit trail is the safeguard: every tool invocation logs tool name, key args, duration, outcome (both modes). Mutating tools (approve/reject/cancel/delete) are exposed deliberately. Fine-grained per-client/per-tool policy = future work.

## 7. Testing Strategy

1. **Boot upgrade gate** (plan step 1): parent pom bump + full `mvn clean test -Dspring.profiles.active=h2` green before any MCP code.
2. **act-mcp unit tests (TDD)**: filter (none/token × 401/200, constant-time), debug-mode error result shape (stack present/absent), parity test (registered tools = curated domain lists), each wrapper delegating to its service (mocked).
3. **act-execution tests (TDD)**: `writeOpenCodeConfig` — Aria agent gets the mcp remote block (no header in `none` mode; header in `token` mode), SDD workers never do; host resolver pure-function tests; token-mode sandbox-env injection.
4. **Integration test**: `/mcp` handshake + `list_workflow_templates` round-trip in a dedicated profile slice (enabled, in-memory DB).
5. **MCP E2E scenario spec (new)**: `act-dashboard/e2e/api/mcp-sdd-workflow.api.spec.ts` — an MCP **client** (TS SDK, same dependency family as packages/mcp-server) connects to `http://localhost:8080/mcp` and drives the SAME scenario as `sdd-workflow.spec.ts` purely through MCP tools: instantiate development-workflow → poll chain → approve the SPEC_REVIEW gate via MCP approval tool → Dev runs → QA runs → verdict → chain COMPLETED. Runs in CI (langchain stack, same as the REST twin) and locally (opencode, with the runtime-tolerant poll windows from the e2e hardening work).
6. **Live acceptance (Qoder-dispatched subagent)**: at implementation acceptance, a dispatched subagent acts as the external MCP client and executes the SDD scenario against the live local stack — the human-visible proof that "another agent can connect and operate Aria Conductor" end-to-end.

## 8. Implementation Phasing

Each phase lands as its own PR with the full-suite gate green:

1. **Phase 1 — Upgrade**: Spring Boot 3.3 → 3.4.x (+ Spring AI BOM), no behavior change; full suite green.
2. **Phase 2 — Core loop**: `act-mcp` module (config, filter, parity harness) + `WorkflowTools`, `KnowledgeTools` + act-execution wiring (mcp block, host resolver, token-mode injection) + `mcp-sdd-workflow.e2e.spec.ts` → the `/workflow` success bar is met at the end of this phase.
3. **Phase 3 — Full parity**: remaining domain tools (Agents, Runs, Skills, Approvals, Ops, Provider, Aria) + parity test expanded to the complete curated list; external-agent recipe into README/quickstart.

## 9. Risks

| Risk | Mitigation |
|---|---|
| Spring AI starter incompatible with upgraded Boot | upgrade gate first; minimal in-house HTTP-MCP controller fallback |
| Host IP resolution picks an unreachable address on some topology | config override property; resolver logs chosen address; spike proved the heuristic ranges on podman+Windows |
| Tool surface drift as services evolve | parity test fails the build on missing tools |
| Operator-level exposure with auth deferred | audit logging from day one; `token` mode is a config flip away |
| DHCP-changing host IPs | resolved per sandbox creation; override available |

## 10. Out of Scope (future work)

- Per-client/per-tool authorization policies; per-client tokens
- Agent-to-agent messaging channels
- Changes to `packages/mcp-server`
- MCP resources/prompts (tools only in v1)
