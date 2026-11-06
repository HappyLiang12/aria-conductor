# Local Authority Simplification: Loopback Operator + Plain Core Credentials

- Date: 2026-10-05
- Status: design approved by the operator (2026-10-05, brainstorming session). Implementation not started.
- Scope: agent-control-tower (act-common, act-execution, act-agent, act-app), act-dashboard, qoder-sandbox compose/deploy files, CI.

## Problem

The operator-facing control plane added by PRs #90/#92/#93 (2026-09-19..30) does not work
out of the box in the local single-operator deployment and hides its failures:

1. `POST /api/v1/approvals/{id}/decide` is operator-gated
   (`agent-control-tower/act-execution/.../controller/ApprovalController.java:207-221`).
   The gate requires `ARIA_OPERATOR_BEARER_TOKEN` to be provisioned on the backend
   (`OperatorSessionService.java:63,94-103` fails closed when unset). Nothing provisions it
   locally: `docker-compose.yml` (backend service, lines 33-67) sets neither the token nor
   `ARIA_RUNTIME_CREDENTIAL_KEY`, `scripts/` never reference it, and `.env.example:31,38`
   has both commented out. Every tool-permission approval therefore answers 401 and the ask
   wedges until the run deadline expires it (`PermissionCoordinator.java:82,615-624`).
2. The refusal is invisible: `ReviewQueue.tsx:33-54` renders no mutation error at all;
   `OpsPage.tsx:175,187` shows a generic "Approve failed. Retry."; only the TaskDrawer /
   ReviewWorkspace DecisionPanel explains the operator-session requirement
   (`ReviewPanels.tsx:161-166`).
3. Session establishment is per-tab: the CSRF token lives in `sessionStorage`
   (`operatorSession.ts:25,97-104`), so a second tab (or an 8h expiry) sends the cookie
   without the CSRF header -> 403 "CSRF validation failed"
   (`OperatorSessionService.java:154-161`).
4. The Qoder runtime credential is stored encrypted (AES-256-GCM + PBKDF2,
   `RuntimeCredentialService.java:60-88`) behind operator-only endpoints
   (`QoderCredentialController.java:264-318`) with a dedicated key config
   (`ARIA_RUNTIME_CREDENTIAL_KEY`, `RuntimeCredentialService.java:76`). The operator's
   ruling (2026-10-05): the credential must be handled the same way as the LLM provider
   config — which is a plain DB row with masked reads (`LlmProviderService.java:176-196`,
   table `llm_providers`, `V1__init_schema.sql:154-164`) and unauthenticated CRUD
   (`LlmProviderController.java`).

## Operator Decisions (2026-10-05)

- D1: Loopback auto-authority. Requests from loopback with no explicit identity are granted
  operator authority automatically. The Operator access panel is removed.
- D2: The Qoder runtime credential follows the llm-config pattern: plain storage, masked
  reads, no dedicated encryption machinery. "Just follow llm-config way, no need over
  complicated."
- D3: Accepted governance cost: any process on the operator's machine can act as operator
  (consistent with the existing unauthenticated LLM-provider surface). Explicit worker
  identity is still never promoted to operator (see authority precedence below).
- D4: The bearer-token path is retained for CI and remote/non-loopback deployments.

## Design

### 1. Authority resolution with loopback fallback

A single authority resolver (extracted from the per-controller checks in
`ApprovalController.requireOperator`, `QoderCredentialController.rejectNonOperator`,
`MaintenanceController`, `RuntimeCredentialService.requireOperator`) applies this
precedence:

1. Explicit identity always wins: an operator bearer header -> operator; a worker/run-scoped
   token -> worker (never promoted to operator, regardless of source address). This keeps
   the MCP worker restriction meaningful: forwarded worker calls keep their worker
   authority even though the MCP server itself runs on loopback.
2. Operator session cookie (+ CSRF header on mutations, Origin check unchanged) -> operator.
3. No identity presented and `RemoteAddr` is loopback (127.0.0.1 / ::1) -> operator.
4. Otherwise -> the current 401/403 semantics, unchanged.

Trusted proxies: containerized deployments (docker-compose frontend proxy) present the
proxy's IP, not loopback. New config `aria.operator.trusted-proxies` (env
`ARIA_OPERATOR_TRUSTED_PROXIES`, default empty): when the direct peer is a listed trusted
proxy, the client address is taken from `X-Forwarded-For` (first untrusted hop from the
right); `X-Forwarded-For` from any non-trusted peer is ignored. Local dev (vite on the
host, backend on the host) needs no configuration.

`docker-compose.yml` sets `ARIA_OPERATOR_TRUSTED_PROXIES` for the backend to the frontend
service address.

### 2. Operator access panel removal, honest errors

- Remove `OperatorAccessPanel` from `ProvidersPage.tsx:52-53` and delete the component;
  `runtimeCredentials.ts` drops `applyOperatorHeaders()` (the CSRF header application on the
  shared axios client moves into the operator-session flow only, which survives for remote
  use).
- CSRF token storage moves from `sessionStorage` to `localStorage`
  (`operatorSession.ts:25`) so multi-tab approvals stop producing 403s on the cookie path.
- All approval/refusal errors are rendered: `ReviewQueue.tsx` gains error display for both
  mutations; `OpsPage.tsx:175,187` renders `apiErrorMessage` (the backend wording) instead
  of the generic retry line.

### 3. Plain core credential store

- New entity/table `core_credentials` (`core_id`, `environment_variable`, `value`, audit
  timestamps; Flyway migration at the next free version). Values are stored as given
  (plaintext, same posture as `llm_providers.api_key`); reads return masked metadata only
  (`"****" + last4`), never the stored value.
- New controller `CoreCredentialController` at `/api/v1/cores/{coreId}/credential`:
  GET (masked metadata), PUT (upsert), DELETE, POST `/test` (retains the honest 503 probe
  seam of `QoderCredentialController.java:187-192`). Authority: the same resolution as
  section 1 (effectively frictionless locally, bearer-gated remotely).
- `RuntimeCredentialService` is reduced to a resolver: `resolve(coreId)` reads the plain
  row and returns the `SecretBundle`; the AES-256-GCM/PBKDF2/AAD machinery, the
  `qoder:operator` fixed reference, and the `ARIA_RUNTIME_CREDENTIAL_KEY` dependency are
  deleted (`RuntimeCredentialService.java:25-88,165-236`).
- The injection mechanics are unchanged: `CoreRunLauncher.java:168-171` still freezes the
  credential reference per run, `CoreExecutionService.java:245-252` still resolves at
  launch, `QoderCoreAdapter.java:238-248` still writes the run-owned credential file and
  passes `--credential-env`/`--credential-file`. Missing credential still fails admission
  loudly (`RuntimeCredentialService.java:104-106` semantics preserved; the
  `RuntimeCredentialsCard.tsx:172-174` warning text stays true).
- Migration: create `core_credentials`; no data carryover from `runtime_credentials`
  (the store has never been configured in this deployment; the operator re-enters the PAT
  once); drop `runtime_credentials`.
- `RuntimeCredentialsCard` is reworked onto the new API: no operator-session prerequisite,
  no 401/403 "Operator-only surface" state (`RuntimeCredentialsCard.tsx:142-149` removed);
  stays on `/providers` next to the cores it belongs to.
- README.md:297-298 and `.env.example:31,38` updated: bearer token documented as
  CI/remote-only; `ARIA_RUNTIME_CREDENTIAL_KEY` removed.

### 4. Spec amendment

`docs/superpowers/specs/2026-09-22-agent-core-execution-modes-design.md` gains an
amendment section (2026-10-05) recording: §6.2 operator-only approval decisions now include
the loopback fallback with the precedence rule; §6.1 managed credentials move to plaintext
storage with masked reads; fail-admission-clearly is retained. The original text is not
rewritten.

### 5. CI

`.github/actions/start-stack/action.yml:58,60` and
`.github/workflows/nightly-sdd-llm-smoke.yml:59,111`: the smoke stack keeps provisioning
`ARIA_OPERATOR_BEARER_TOKEN` (exercises the bearer path); `ARIA_RUNTIME_CREDENTIAL_KEY` is
dropped; the smoke script stores the Qoder credential through the new endpoint.

## Testing

- Unit: authority resolver precedence (bearer operator / worker token never promoted /
  cookie+CSRF / loopback fallback / remote anonymous 401); trusted-proxy parsing (XFF from
  trusted peer only); controller tests for `/decide` and the new credential endpoints under
  each authority outcome.
- Integration: launch admission fails without a stored credential and succeeds after PUT;
  migration creates/drops the expected tables.
- Dashboard (vitest): reworked credential card, error rendering on ReviewQueue/OpsPage.
- E2E: containerized stack with `ARIA_OPERATOR_TRUSTED_PROXIES` — browser approval works
  without any token paste; direct remote call without identity still 401.
- Cadence per operator standard: per-task impact tests, wave integration, full regression
  at phase end.

## Out of scope

- The WAITING_INPUT clarification loop (separate spec, 2026-10-05).
- Reworking the MCP `OPERATOR_ONLY` policy class (the precedence rule covers it).
- Auth for the remaining unauthenticated surfaces (LLM providers, agents, kanban) —
  unchanged by ruling.
