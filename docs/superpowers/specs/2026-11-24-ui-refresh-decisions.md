# UI Refresh — Decisions

Companion to `2026-11-24-ui-refresh-design.md`. Records the alternatives considered and why the chosen design is the one specced.

## D1 Direction: shadcn/AI Elements + assistant-ui (not CopilotKit)

Chosen: design system and chat primitives are adopted inside the existing React 19 + Vite app; the Spring backend stays the only runtime authority.

Alternatives:

| Option | Assessment |
|--------|------------|
| CopilotKit + Node runtime | Richest prebuilt agent UX (HITL interrupts, generative UI, threads, sidebar/popup). Its runtime is TypeScript-only without CopilotKit Intelligence, so a Spring backend needs a second long-lived Node service and a second thread/tool/state model. Adds an ops surface and a second source of truth for agent state. |
| assistant-ui + shadcn (chosen) | No new process. `LocalRuntime` wraps the existing SSE client with one adapter; `ExternalStoreRuntime` is available if TanStack Query later becomes the message store. AI Elements supplies the operator-shaped patterns (Confirmation, Task, Trace, Subagent, Terminal, Test Results). |
| Tambo | Component-level generative UI is strong, but it introduces another agent backend/API key and does not address approvals or the operator console. |

Revisit CopilotKit if all three hold: chat speed outweighs whole-site coherence, a Node sidecar is acceptable in local/CI/deploy, and CopilotKit Intelligence is wanted. The AG-UI boundary in Phase 2 keeps that option open without changing the design system.

Evidence (2026-11-24): CopilotKit repo 38.9k stars, MIT; its docs state the TypeScript runtime is the only runtime that works without Intelligence. assistant-ui repo 13.4k stars, MIT, `@assistant-ui/react` 0.15.38 with React 18/19 peers.

## D2 shadcn AI Elements, not Vercel AI Elements

Vercel AI Elements is deprecated at the end of November 2026 and replaced by shadcn/ui AI Elements (21 agentic patterns, October 2026). The shadcn patterns are copied into the repository; there is no package and no vendor lock.

## D3 `LocalRuntime`, not `ExternalStoreRuntime` or the AG-UI runtime, in Phase 1

- `LocalRuntime` owns messages, branching, editing, regeneration, and cancellation, and needs only a `ChatModelAdapter`.
- `ExternalStoreRuntime` is the fallback if message state must live in TanStack Query; it is not needed while assistant-ui is the only reader.
- `@assistant-ui/react-ag-ui` (0.1.12) is the Phase 2 adapter. It is young; adopting it now would couple Phase 1 to an immature package for no Phase 1 benefit.

## D4 SSE owns turn content; STOMP owns ambient invalidation

Today both channels can carry run content, and neither has a sequence number or replay. The split fixes ownership: SSE describes the current turn; STOMP invalidates Query keys and raises notifications. Replay is deferred to Phase 2.

## D5 Approvals are server-driven, not an assistant-ui `requires-action` gate

The backend keeps the SSE stream open while an approval is pending and continues the same turn after the decision. assistant-ui's approval gate ends the adapter run and re-invokes it; that would require a resumable continuation endpoint the backend does not have. The UI therefore renders `approval_required` as a custom part whose actions post the decision, while the original stream stays open.

## D6 Phase 1 is a vertical slice, then waves

Pilot: shell + Overview + Aria. Remaining pages follow in waves of two. Rationale: 3,298 lines of flow CSS and 12 routes are too large for one reviewable change; the shell and two representative surfaces let the user validate feel with real data first.

## D7 Navigation groups without URL changes

Sidebar groups: Operate, Build, Chat, System. Route paths and deep links are unchanged, so the refresh carries no migration or bookmark risk.

## D8 Tailwind in cascade layers

Legacy flow CSS stays unlayered, which gives it precedence over layered Tailwind output under the CSS cascade. This contains preflight regressions during the wave migration; the 12-route smoke test verifies that containment.

## D9 Runtime badge reads fleet data, not a runtime endpoint

Chosen: the topbar badge derives `core/MODE` from the first non-retired agent
(`GET /api/v1/agents`, already used by Crew and the drawers) and falls back to
the provider catalog already on the bar. No runtime endpoint exists in Phase 1,
and adding one would break S6 (no Spring change).

Alternatives: invent a literal `opencode/HOST` (rejected: the local backend
serves `executionMode: null` and no `executionModes`, so the mode would be a
claim, not a measurement); omit the badge (rejected: the spec requires runtime
visibility). When neither source carries a mode the badge renders the core
alone; a mode is never guessed. The deviation is recorded in the design's
Delivery status.

## Deferred with reasons

| Item | Reason |
|------|--------|
| Token-level streaming | Backend emits one final `message`; requires a streaming contract change (Phase 2 with AG-UI). |
| SSE replay / event log | Needs sequence numbers and a replay endpoint; refetch covers Phase 1. |
| CopilotKit generative UI | Would add a Node runtime and a second state model; revisit under D1 conditions. |
| IA/route renaming | Not needed for the visual refresh; adds link and test churn. |
