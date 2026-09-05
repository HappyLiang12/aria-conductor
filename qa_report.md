# SDD QA Report — Built-in REST API authentication (`ARIA_API_KEY`)

- **Spec:** `spec/spec.md` (Add authentication/authorization to the REST API — issue #1, `security`/`priority:P1`)
- **Branch:** `sdd/25c9a96b-1277-42f3-a18c-46d8d87e6ef7`
- **Repo:** HappyLiang12/aria-conductor
- **HEAD verified:** `142fd70f51cf14026b3862b057c5984524ddf6dd` ("sdd dev")
- **QA date:** 2026-09-05
- **Verdict:** **PASS**

---

## 1. Summary

The branch implements the spec's recommended design: a lightweight
`OncePerRequestFilter` (`ApiKeyAuthFilter`) registered in `act-app` that enforces a
static shared operator key (`Authorization: Bearer <key>`) sourced from
`ARIA_API_KEY` (`app.auth.api-key`), gated so a blank key preserves the permissive
local/dev default. Frontend (dashboard token prompt, `sessionStorage`, axios + raw
`fetch` header wiring), CI `e2e-smoke` (auth-enabled negative/positive assertions),
and docs (`SECURITY.md`, `.env.example`, `README.md`, `docker-compose.yml`) are all
updated to match the spec. Real test suites were run (see §3) and are green; the only
observed failures are provably environmental to this sandbox (missing `python`
runtime / OpenSandbox), not defects of the change.

## 2. Static verification against Acceptance Criteria

| AC | Requirement | Result | Evidence |
|----|-------------|--------|----------|
| 1 | Config gating: blank key ⇒ permissive + startup WARN; pre-existing tests unchanged | ✅ | `ApiKeyAuthProperties.isEnabled()` blank ⇒ filter inert (`ApiKeyAuthFilter.shouldNotFilter`); `AuthConfig` logs `AUTH DISABLED: ARIA_API_KEY not set...` (observed 6× in unit-run log). Full unit tier green. |
| 2 | Enforcement: key set ⇒ `/api/v1/**` (incl. chat/stream, dev/sql) 401 without header | ✅ | Filter protects `/api/v1/**` (+ `/actuator/**` minus health) before dispatch; `ApiKeyAuthIntegrationTest` asserts 401 for `/api/v1/agents`, unknown protected path, and authenticated 200 for agents/dashboard/workflows/knowledge. |
| 3 | Valid token ⇒ 200/expected status | ✅ | `Authorization: Bearer <key>` accepted (constant-time match); integration + unit tests pass. |
| 4 | Wrong/empty/non-Bearer ⇒ 401 JSON (`timestamp/status/error/message`) + `WWW-Authenticate`; never reaches controller | ✅ | `ApiKeyAuthFilterTest` (13 tests) covers missing/empty/wrong/Basic-scheme → 401 with schema + `Bearer realm="aria-conductor"`; `RecordingChain` asserts controller not reached. |
| 5 | `/actuator/health` open (200) with/without token while auth enabled | ✅ | Unit + integration (`actuatorHealthRemainsOpenWithoutToken`) assert 200/UP. |
| 6 | Constant-time comparison; key/header never logged/echoed | ✅ | `MessageDigest.isEqual`; rejection log includes only method/path/correlationId; unit test asserts no key/token in body or captured logs. |
| 7 | Frontend: prompt on 401, attach `Authorization: Bearer`, pages keep working | ✅ | `auth.ts` (sessionStorage, not localStorage), axios request interceptor attaches header, response interceptor prompts on 401 and retries transparently, clears stale token if retry still 401s; all raw `fetch` call-sites (aria SSE stream, conversations, AgentToolPanel, ToolManager) updated via `withAuthHeaders`. Vitest + build green. `/ws` handshake intentionally left unauthenticated and documented (spec Question — deferred). |
| 8 | CI: `e2e-smoke` starts backend with `ARIA_API_KEY`, Bearer on every `/api/v1` curl, unauth health probe, negative 401 assertion; Playwright jobs in default mode | ✅ | `.github/workflows/ci.yml` adds job-level `ARIA_API_KEY` on `e2e-smoke` only (inherited by `start-stack` `java` process); all smoke curls send `-H "Authorization: Bearer ${ARIA_API_KEY}"`; health curl has no credentials; unauthenticated `/api/v1/agents` must return 401 or the job exits 1. `e2e-playwright` and `java-integration-tests` jobs do not set the key (default permissive). |
| 9 | New unit tests + ≥1 integration test booting with key asserting 401 & 200 | ✅ | `ApiKeyAuthFilterTest` (13) + `ApiKeyAuthIntegrationTest` (5, boots full app with key via `@TestPropertySource`). |
| 10 | Docs updated; no secrets added | ✅ | `SECURITY.md` limitation resolved + `ARIA_API_KEY` documented incl. `/ws` note; `.env.example` commented entry; `README.md` notes + curl header; no secrets committed. |

Additional spec-contract checks confirmed statically:
- Error body schema matches `GlobalExceptionHandler` style: `{timestamp, status, error, message}` with generic message `Invalid or missing API key` (does not distinguish missing/bad/malformed).
- Unknown protected path ⇒ 401, not 404 (filter runs pre-dispatch; covered by integration test).
- `OPTIONS` preflight exempt (unit test); CORS `allowedHeaders("*")`, `allowCredentials(false)` in `WebConfig`.
- Non-health `/actuator/**` protected; `/v3/api-docs`, `/swagger-ui`, `/h2-console`, `/ws` exempt (consistent with spec Questions on scope).
- Vite dev/preview `/api` proxy is a plain forward that does not strip the `Authorization` header.
- `application.yml` binds `app.auth.api-key: ${ARIA_API_KEY:}` (relaxed env binding), matching the documented pattern.

## 3. Real test runs & recorded results

Toolchain: Temurin JDK 21.0.12.1, Maven 3.8.7, Node 22, pnpm 9.

### 3.1 Backend unit tier — `mvn -B test -Dspring.profiles.active=h2` (whole reactor)
`BUILD SUCCESS` — all module JaCoCo coverage ratchets satisfied.

| Module | Tests run | Failures | Errors | Skipped |
|--------|----------:|---------:|-------:|--------:|
| act-common | 120 | 0 | 0 | 0 |
| act-agent | 234 | 0 | 0 | 0 |
| act-execution | 684 | 0 | 0 | 0 |
| act-knowledge | 260 | 0 | 0 | 4 |
| act-dashboard-api | 75 | 0 | 0 | 0 |
| act-aria | 338 | 0 | 0 | 4 |
| act-app | 55 | 0 | 0 | 0 |
| **Total** | **1766** | **0** | **0** | **8** |

- Auth-specific: `ApiKeyAuthFilterTest` **13/13 pass**.
- The 8 skips are pre-existing and unrelated: `SandboxExecutorTest` (no container runtime in sandbox; assumption-skip) and `NotificationControllerTest` (`@Disabled` with reason).

### 3.2 Backend integration tier — `mvn -B verify -pl act-app -Dskip.unit.tests=true -Dspring.profiles.active=h2`
**78 tests: 77 pass, 1 failure, 1 skip.** The failure is environmental (see §4):
- `ApiKeyAuthIntegrationTest` **5/5 pass** — the spec AC2–AC5 end-to-end assertions (401 no token, 401 unknown protected path, authenticated 200 on `/api/v1/agents`, `/dashboard/summary`, `/workflows`, `/knowledge?type=WORKFLOW`, wrong token 401, open health).
- `ActIntegrationTest.healthCheck` fails with 503 — requires a live LangChain ADK/python (absent here).

### 3.3 Frontend — `pnpm test -- --coverage` and `pnpm build`
- Vitest **35 files / 220 tests pass** (incl. rewritten `client.test.ts`, 10 tests covering no-token, Bearer attach, 401 prompt + transparent retry, stale-token clearing). Coverage thresholds met (`all files 31.21% lines / 73.49% branches / 49.56% funcs`).
- `pnpm build` (tsc `-b` + vite build) **success**.

### 3.4 Live smoke (best-effort)
A direct `java -jar act-app-...jar --spring.profiles.active=h2` boot in this sandbox with `ARIA_API_KEY` set reached `GET /actuator/health → 200 UP` while auth was enabled (health exempt confirmed), but the full-stack boot then aborted during Aria's OpenCode pre-warm because no OpenSandbox is reachable at `:8090` in this environment (degraded-startup path hit a `NULL not allowed for column CREATED_AT` constraint). This is environmental, not auth-related; the 401/200 semantics are authoritatively covered by `ApiKeyAuthIntegrationTest` against a real embedded server.

## 4. Environmental notes (not defects)

1. **`act-execution` `OpenCodePropertiesBindingTest` (2 tests)** initially failed in this QA sandbox only because the sandbox runtime exports env vars `OPENCODE=1` / `OPENCODE_PID=20`, which Spring relaxed-binding tries to bind onto the `opencode.*` properties. After unsetting them the module's **684/684 tests pass**. CI does not export these.
2. **`ActIntegrationTest.healthCheck`** returns 503 because no `python` binary exists here, so LangChain ADK instances stay DOWN (`Cannot run program "python": Exec failed, error: 2`). CI provisions a LangChain ADK venv (`PYTHON_PATH`). The auth filter is not involved (health is exempt and reachable).
3. **Live `java -jar` boot** could not reach steady state without OpenSandbox (`:8090`) — see §3.4.

None of the above indicate a spec gap or defect in the delivered change.

## 5. Verdict

**PASS** — The implementation satisfies all ten acceptance criteria and the Error
Handling / Questions decisions in `spec/spec.md`. Real unit, integration, and
frontend suites pass; every observed failure is attributable to missing external
runtimes in this QA sandbox, not to the code under review.
