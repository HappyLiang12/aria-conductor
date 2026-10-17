# Local Authority Simplification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Loopback requests with no explicit identity get operator authority automatically; the Qoder runtime credential becomes a plain masked-read DB row exactly like the LLM provider config; the Operator access panel is removed and approval refusals stop being silent.

**Architecture:** One `OperatorAuthorityResolver` (precedence: explicit bearer → session cookie+CSRF → worker-never-promoted → loopback fallback → 401) replaces the per-controller operator checks; a new `core_credentials` table + `CoreCredentialService` replaces the AES-256-GCM `runtime_credentials` machinery while keeping the launch-injection mechanics (frozen binding ref → resolve → SecretBundle → credential file) unchanged.

**Tech Stack:** Java 21 / Spring Boot 3.3 (act-common, act-execution, act-app Flyway), React 19 + TanStack Query (act-dashboard), MariaDB/H2 via Flyway V67.

**Spec:** `docs/superpowers/specs/2026-10-05-local-authority-simplification-design.md` (amends `docs/superpowers/specs/2026-09-22-agent-core-execution-modes-design.md` §6.1/§6.2)

## Global Constraints

- Authority precedence is fixed and MUST NOT be reordered: (1) explicit operator bearer → operator; (2) session cookie (+CSRF/Origin on mutations) → operator; (3) explicit worker/run-scoped token → **worker, never promoted, even from loopback (403)**; (4) anonymous loopback → operator; (5) anything else → 401. Invalid/expired tokens are "no identity" (they fall through to 4/5, never 403).
- `X-Forwarded-For` is honored only when the direct peer (`request.getRemoteAddr()`) is in `aria.operator.trusted-proxies`; the client is the rightmost (last) XFF entry. Non-trusted peers: XFF ignored, peer address used.
- The bearer path (`aria.operator.bearer-token` / `ARIA_OPERATOR_BEARER_TOKEN`) is retained (CI + remote deployments); fail-closed when unconfigured stays true for non-loopback callers only.
- Missing Qoder credential still fails admission loudly (message keeps the shape "Runtime credential is not configured: qoder:operator").
- No stored credential value is ever returned by any endpoint or written to any log; reads are masked (`"****" + last4`, `"****"` when length ≤ 4).
- Flyway: append-only. V67 is the next free version. Do not touch applied migrations.
- All Java package roots `io.aria.conductor`. Comments only where the WHY is non-obvious; keep them in English.
- Validation cadence: per-task impact tests (below), wave integration after Task 5 and Task 6, full regression at phase end (Task 8).

---

### Task 1: OperatorAuthorityResolver with loopback fallback and trusted proxies

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/security/OperatorAuthorityResolver.java`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/security/OperatorAuthorityResolverTest.java`

**Interfaces:**
- Consumes: `OperatorSessionService` (existing, act-execution/security), `ActorTokenService.resolveBearer(String)` (existing), `ActorAuthenticationFilter.ACTOR_ATTRIBUTE` (existing).
- Produces: `OperatorAuthorityResolver extends` nothing; `@Service` bean; method
  `public ActorPrincipal resolveOperator(HttpServletRequest request, boolean mutation)`
  throwing `OperatorSessionService.ForbiddenMutationException` (→ HTTP 403) and `SecurityException` (→ HTTP 401). Later tasks inject this bean.

- [ ] **Step 1: Write the failing test**

```java
package io.aria.conductor.execution.security;

import io.aria.conductor.common.security.ActorPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperatorAuthorityResolverTest {

    private static final String OPERATOR_BEARER = "operator-secret";
    private static final String WORKER_BEARER = "worker-secret";

    private final ActorTokenService actorTokens = mock(ActorTokenService.class);
    private final OperatorSessionService sessions = new OperatorSessionService(
            OPERATOR_BEARER, Duration.ofHours(8),
            OperatorSessionService.DEFAULT_ALLOWED_ORIGINS, false);

    private OperatorAuthorityResolver resolver(String trustedProxies) {
        return new OperatorAuthorityResolver(sessions, actorTokens, trustedProxies);
    }

    private void workerTokenResolves() {
        when(actorTokens.resolveBearer(any())).thenAnswer(invocation ->
                WORKER_BEARER.equals(String.valueOf(invocation.getArgument(0)))
                        ? Optional.of(mock(io.aria.conductor.execution.security.ActorPrincipal.class))
                        : Optional.empty());
    }

    @Test
    void explicitOperatorBearerWinsFromAnywhere() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("Authorization", "Bearer " + OPERATOR_BEARER);

        ActorPrincipal principal = resolver("").resolveOperator(request, true);

        assertThat(principal).isNotNull();
    }

    @Test
    void workerBearerIsNeverPromotedEvenFromLoopback() {
        workerTokenResolves();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Authorization", "Bearer " + WORKER_BEARER);

        assertThatThrownBy(() -> resolver("").resolveOperator(request, true))
                .isInstanceOf(OperatorSessionService.ForbiddenMutationException.class)
                .hasMessage("Operator authority required");
    }

    @Test
    void anonymousLoopbackIsOperatorWithoutAnyCredentialConfigured() {
        OperatorSessionService unconfigured = new OperatorSessionService(
                "", Duration.ofHours(8), OperatorSessionService.DEFAULT_ALLOWED_ORIGINS, false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        ActorPrincipal principal =
                new OperatorAuthorityResolver(unconfigured, actorTokens, "").resolveOperator(request, false);

        assertThat(principal).isNotNull();
    }

    @Test
    void anonymousRemoteIsUnauthorized() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");

        assertThatThrownBy(() -> resolver("").resolveOperator(request, true))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Operator session required");
    }

    @Test
    void cookieMutationStillRequiresCsrfAndOrigin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        OperatorSessionService.OperatorSession session = sessions.createSession();
        request.setCookies(new jakarta.servlet.http.Cookie(
                OperatorSessionService.COOKIE_NAME, session.sessionId()));
        request.addHeader("X-CSRF-Token", "wrong");
        request.addHeader("Origin", "http://localhost:5173");

        assertThatThrownBy(() -> resolver("").resolveOperator(request, true))
                .isInstanceOf(OperatorSessionService.ForbiddenMutationException.class)
                .hasMessage("CSRF validation failed");
    }

    @Test
    void trustedProxyXffLoopbackClientIsOperator() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 127.0.0.1");

        assertThat(resolver("10.0.0.5").resolveOperator(request, false)).isNotNull();
    }

    @Test
    void untrustedPeerXffIsIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Forwarded-For", "127.0.0.1");

        assertThatThrownBy(() -> resolver("").resolveOperator(request, false))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void trustedProxyWithoutXffIsNotLoopback() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");

        assertThatThrownBy(() -> resolver("10.0.0.5").resolveOperator(request, false))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void ipv6LoopbackSpellingIsAccepted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("0:0:0:0:0:0:0:1");

        assertThat(resolver("").resolveOperator(request, false)).isNotNull();
    }

    @Test
    void expiredCookieSessionFallsThroughToLoopbackRule() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setCookies(new jakarta.servlet.http.Cookie(
                OperatorSessionService.COOKIE_NAME, "no-such-session"));

        assertThat(resolver("").resolveOperator(request, false)).isNotNull();
    }

    @Test
    void requestHelperReturnsClientAddressHonoringTrustedProxyOnly() {
        MockHttpServletRequest trusted = new MockHttpServletRequest();
        trusted.setRemoteAddr("10.0.0.5");
        trusted.addHeader("X-Forwarded-For", "203.0.113.9, 127.0.0.1");
        assertThat(resolver("10.0.0.5").clientAddress(trusted)).isEqualTo("127.0.0.1");

        MockHttpServletRequest untrusted = new MockHttpServletRequest();
        untrusted.setRemoteAddr("203.0.113.7");
        untrusted.addHeader("X-Forwarded-For", "127.0.0.1");
        assertThat(resolver("10.0.0.5").clientAddress(untrusted)).isEqualTo("203.0.113.7");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn -q test -pl act-execution -Dtest=OperatorAuthorityResolverTest`
Expected: COMPILATION ERROR — `OperatorAuthorityResolver` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package io.aria.conductor.execution.security;

import io.aria.conductor.common.security.ActorPrincipal;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Single authority resolution for every operator-gated route (2026-10-05 local
 * authority simplification; amends the agent-core spec §6.2).
 *
 * <p>Precedence, fixed: an explicit operator bearer credential is operator;
 * an operator session cookie is operator (mutations re-validate CSRF/Origin);
 * an explicit worker/run-scoped credential is authenticated but NEVER promoted
 * to operator -- not even from loopback, so forwarded MCP worker calls keep
 * their restriction; an anonymous request from loopback is the local operator
 * (single-operator localhost deployment); everything else is 401. An invalid
 * or expired token counts as "no identity presented" and falls through to the
 * loopback/401 rules instead of 403.
 */
@Service
public class OperatorAuthorityResolver {

    private final OperatorSessionService operatorSessions;
    private final ActorTokenService actorTokens;
    private final Set<String> trustedProxies;

    public OperatorAuthorityResolver(OperatorSessionService operatorSessions,
            ActorTokenService actorTokens,
            @Value("${aria.operator.trusted-proxies:}") String trustedProxies) {
        this.operatorSessions = operatorSessions;
        this.actorTokens = actorTokens;
        Set<String> proxies = new LinkedHashSet<>();
        if (trustedProxies != null) {
            Arrays.stream(trustedProxies.split(",")).map(String::trim)
                    .filter(entry -> !entry.isEmpty()).forEach(proxies::add);
        }
        this.trustedProxies = Set.copyOf(proxies);
    }

    /**
     * Resolves the caller's operator authority or throws: 403
     * ({@link OperatorSessionService.ForbiddenMutationException}) for a worker
     * credential or a failed cookie-mutation validation, 401
     * ({@link SecurityException}) when nothing verifiable is presented.
     */
    public ActorPrincipal resolveOperator(HttpServletRequest request, boolean mutation) {
        Object attribute = request.getAttribute(ActorAuthenticationFilter.ACTOR_ATTRIBUTE);
        if (attribute instanceof ActorPrincipal principal) {
            principal.requireActive(Instant.now());
            principal.requireOperator();
            return principal;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (operatorSessions.verifyOperatorCredential(authorization)) {
            return ActorPrincipal.operator(null);
        }
        String sessionId = sessionCookie(request);
        OperatorSessionService.OperatorSession session =
                operatorSessions.findSession(sessionId).orElse(null);
        if (session != null) {
            if (mutation) {
                operatorSessions.validateMutation(session,
                        request.getHeader(OperatorSessionService.CSRF_HEADER),
                        request.getHeader(HttpHeaders.ORIGIN));
            }
            return ActorPrincipal.operator(session.expiresAt());
        }
        if (actorTokens.resolveBearer(authorization).isPresent()) {
            throw new OperatorSessionService.ForbiddenMutationException("Operator authority required");
        }
        if (isLoopbackClient(request)) {
            return ActorPrincipal.operator(null);
        }
        throw new SecurityException("Operator session required");
    }

    /**
     * The client address this request really came from: the direct peer, unless
     * the peer is an explicitly trusted proxy, in which case the rightmost
     * X-Forwarded-For entry (added by that proxy) is the client. XFF from any
     * other peer is attacker-controlled and ignored.
     */
    public String clientAddress(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (peer != null && trustedProxies.contains(peer)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.lastIndexOf(',');
                String client = (comma >= 0 ? forwarded.substring(comma + 1) : forwarded).trim();
                if (!client.isEmpty()) {
                    return client;
                }
            }
        }
        return peer;
    }

    private boolean isLoopbackClient(HttpServletRequest request) {
        String client = clientAddress(request);
        return "127.0.0.1".equals(client) || "::1".equals(client)
                || "0:0:0:0:0:0:0:1".equals(client);
    }

    private static String sessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (OperatorSessionService.COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
```

Check `ActorPrincipal.requireActive(Instant)` exists (it is called by `QoderCredentialController.operator` today, `QoderCredentialController.java:465`); if the method has a different name, use the exact one from that file.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd agent-control-tower && mvn -q test -pl act-execution -Dtest=OperatorAuthorityResolverTest`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/security/OperatorAuthorityResolver.java agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/security/OperatorAuthorityResolverTest.java
git commit -m "feat(security): operator authority resolver with loopback fallback and trusted proxies"
```

---

### Task 2: Wire the resolver into ApprovalController and MaintenanceController

**Files:**
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/controller/ApprovalController.java` (decide method lines 260-307, requireOperator helper lines 319-333, constructor + imports)
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/controller/MaintenanceController.java` (rejectNonOperator/operator/sessionCookie helpers, lines 997-1048, constructor)
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/controller/ApprovalControllerTest.java` (extend; create if absent — check with `ls`)

**Interfaces:**
- Consumes: `OperatorAuthorityResolver.resolveOperator(HttpServletRequest, boolean)` from Task 1.
- Produces: unchanged HTTP contracts, EXCEPT anonymous loopback now succeeds where it was 401. All existing controller tests keep passing unchanged (they authenticate explicitly).

- [ ] **Step 1: Write the failing test (loopback decision succeeds; worker token still 403)**

Add to the existing `ApprovalControllerTest` (follow its current rigging; the two new cases):

```java
    @Test
    void loopbackAnonymousDecisionIsAuthorized() throws Exception {
        mvc.perform(post("/api/v1/approvals/{id}/decide", pendingApprovalId())
                        .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; })
                        .contentType("application/json")
                        .content("{\"approved\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    void workerBearerDecisionRemainsForbidden() throws Exception {
        mvc.perform(post("/api/v1/approvals/{id}/decide", pendingApprovalId())
                        .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; })
                        .header("Authorization", "Bearer worker-token")
                        .contentType("application/json")
                        .content("{\"approved\":true}"))
                .andExpect(status().isForbidden());
    }
```

Adapt `pendingApprovalId()` to whatever helper the existing test uses to persist a PENDING (non-native) approval; if the test class stubs `permissionCoordinator`, stub `isNativePermissionRequest` to false and `approvalGate.decideApproval` to a no-op. Match the existing file's conventions.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn -q test -pl act-execution -Dtest=ApprovalControllerTest`
Expected: `loopbackAnonymousDecisionIsAuthorized` FAILS with 401; the worker case may already pass (403 today only for a *resolvable* worker token — keep the test, it pins the rule).

- [ ] **Step 3: Implement**

ApprovalController — constructor gains `OperatorAuthorityResolver operatorAuthority` (keep the other dependencies; delete the now-unused `actorTokens` field only if nothing else in the class references it — run `grep -n "actorTokens" act-execution/src/main/java/io/aria/conductor/execution/controller/ApprovalController.java` first). Replace the decide method's identity block:

```java
    @PostMapping("/{id}/decide")
    public ResponseEntity<Map<String, Object>> decideApproval(
            @PathVariable UUID id,
            @RequestBody DecideApprovalRequest request,
            HttpServletRequest request) {
```

In Java you cannot have two parameters named `request`; rename the body param to `body`:

```java
    @PostMapping("/{id}/decide")
    public ResponseEntity<Map<String, Object>> decideApproval(
            @PathVariable UUID id,
            @RequestBody DecideApprovalRequest body,
            HttpServletRequest request) {
        log.info("Approval decision: id={}, approved={}", id, body.approved());

        ActorPrincipal operator;
        try {
            operator = operatorAuthority.resolveOperator(request, true);
        } catch (OperatorSessionService.ForbiddenMutationException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
        }

        try {
            if (permissionCoordinator.isNativePermissionRequest(id)) {
                permissionCoordinator.decide(id,
                        body.approved() ? PermissionChoice.ALLOW_ONCE : PermissionChoice.DENY, operator);
            } else {
                approvalGate.decideApproval(id, body.approved(), body.reason());
            }
            return ResponseEntity.ok(Map.of(
                    "approvalId", id,
                    "approved", body.approved(),
                    "status", "processed"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
    }
```

Delete the private `requireOperator` helper (lines 313-333) and the now-unused `@CookieValue`/`@RequestHeader` imports if unreferenced. Add `import jakarta.servlet.http.HttpServletRequest;` and the resolver import.

MaintenanceController — delete the private `operator(...)` and `sessionCookie(...)` helpers, inject `OperatorAuthorityResolver`, and rewrite the wrapper:

```java
    private ResponseEntity<Object> rejectNonOperator(HttpServletRequest request, boolean mutation) {
        try {
            operatorAuthority.resolveOperator(request, mutation);
            return null;
        } catch (OperatorSessionService.ForbiddenMutationException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Operator credential required"));
        }
    }
```

`initializeBuiltins` currently passes `ActorPrincipal.operator(null)` onward; use the resolved principal from the resolver call instead:

```java
    @PostMapping("/initialize-builtins")
    public ResponseEntity<Object> initializeBuiltins(HttpServletRequest request) throws OperatorSessionService.ForbiddenMutationException, SecurityException {
        ActorPrincipal operator = operatorAuthority.resolveOperator(request, true);
        return ResponseEntity.ok(setup.initializeMissingBuiltins(operator));
    }
```

(The checked-exception decoration is only needed if `resolveOperator`'s exceptions are not runtime-supertype-handled here — they are runtime exceptions, so the `throws` clause is optional; keep the method body exactly as shown minus `throws` if the compiler is satisfied.)

- [ ] **Step 4: Run the tests**

Run: `cd agent-control-tower && mvn -q test -pl act-execution -Dtest="ApprovalControllerTest,MaintenanceController*Test,QoderCredentialControllerTest"`
Expected: PASS — all pre-existing tests keep their outcomes (explicit-identity paths unchanged); the two new cases pass.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/controller/ApprovalController.java agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/controller/MaintenanceController.java agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/controller/ApprovalControllerTest.java
git commit -m "feat(security): resolve operator authority via the shared resolver on approval and maintenance routes"
```

---

### Task 3: Dashboard — remove the Operator access panel, persist CSRF across tabs, surface approval errors

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/pages/ProvidersPage.tsx` (imports lines 1-6, remove `<OperatorAccessPanel />` at line 52 and its comment)
- Delete: `agent-control-tower/act-dashboard/src/components/OperatorAccessPanel.tsx` (and its test file if one exists: `ls agent-control-tower/act-dashboard/src/components/ | grep -i operator`)
- Modify: `agent-control-tower/act-dashboard/src/api/operatorSession.ts` (4 storage call sites)
- Modify: `agent-control-tower/act-dashboard/src/components/ReviewQueue.tsx` (mutations lines 1643-1664 + render)
- Modify: `agent-control-tower/act-dashboard/src/pages/OpsPage.tsx` (mutations lines 1690-1713)

**Interfaces:**
- Consumes: `apiErrorMessage` from `operatorSession.ts` (existing).
- Produces: `OPERATOR_SESSION_STORAGE_KEY` record moves from `sessionStorage` to `localStorage` (same key `aria.operator.session`); no component may import `OperatorAccessPanel` anymore.

- [ ] **Step 1: Write the failing vitest for the storage move and error surfacing**

Check the existing test setup first: `ls agent-control-tower/act-dashboard/src/api/*.test.* agent-control-tower/act-dashboard/src/components/*.test.* 2>/dev/null` and follow the colocated `*.test.ts(x)` convention you find (vitest). Create `agent-control-tower/act-dashboard/src/api/operatorSession.test.ts`:

```typescript
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { establishOperatorSession, loadOperatorSession } from './operatorSession';

vi.mock('./client', () => ({ default: { post: vi.fn(), delete: vi.fn(), defaults: { headers: { common: {} } } } }));

describe('operator session storage', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    vi.clearAllMocks();
  });

  it('persists the session record in localStorage so other tabs reuse it', async () => {
    const { default: client } = await import('./client');
    (client.post as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: { csrfToken: 'csrf-1', expiresAt: new Date(Date.now() + 3600_000).toISOString() },
    });

    await establishOperatorSession('secret');

    expect(localStorage.getItem('aria.operator.session')).not.toBeNull();
    expect(loadOperatorSession()?.csrfToken).toBe('csrf-1');
  });
});
```

- [ ] **Step 2: Run vitest to verify it fails**

Run: `cd agent-control-tower/act-dashboard && pnpm test -- --run src/api/operatorSession.test.ts`
Expected: FAIL — record is in sessionStorage, localStorage is empty.

- [ ] **Step 3: Implement the dashboard changes**

`operatorSession.ts` — replace the four `sessionStorage` references with `localStorage` (in `readStoredRecord`, `loadOperatorSession`, `clearOperatorSession`, `establishOperatorSession`), and update the class-doc line "kept in per-tab sessionStorage" to "kept in localStorage (shared by all tabs of this browser profile)".

`ProvidersPage.tsx` — delete the `import { OperatorAccessPanel } ...` line and the `<OperatorAccessPanel />` JSX block with its comment (`{/* Operator authority (spec 6.2) ... */}`). Delete `OperatorAccessPanel.tsx`. Run `grep -rn "OperatorAccessPanel" agent-control-tower/act-dashboard/src` and remove every remaining reference (none should survive).

`ReviewQueue.tsx` — surface refusals (replace both mutations and add the error render):

```tsx
import { apiErrorMessage, applyOperatorHeaders } from '../api/operatorSession';

// inside the component, above the mutations:
  const [actionError, setActionError] = useState<string | null>(null);

  const approveMutation = useMutation({
    mutationFn: (id: string) => {
      applyOperatorHeaders();
      return approveApproval(id);
    },
    onSuccess: () => {
      setActionError(null);
      queryClient.invalidateQueries({ queryKey: ['approvals'] });
      queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
    },
    onError: (err: unknown) => setActionError(apiErrorMessage(err, 'Approve failed.')),
  });

  const rejectMutation = useMutation({
    mutationFn: (id: string) => {
      applyOperatorHeaders();
      return rejectApproval(id);
    },
    onSuccess: () => {
      setActionError(null);
      queryClient.invalidateQueries({ queryKey: ['approvals'] });
      queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
    },
    onError: (err: unknown) => setActionError(apiErrorMessage(err, 'Reject failed.')),
  });
```

Add `useState` to the React import if absent, and render the error right after the `<h2>` block:

```tsx
      {actionError && (
        <div className="error-state" role="alert">{actionError}</div>
      )}
```

`OpsPage.tsx` — replace the two `onError` lines so the backend wording surfaces:

```tsx
    onError: (err: unknown) => setToast({ kind: 'err', msg: apiErrorMessage(err, 'Approve failed. Retry.') }),
```

```tsx
    onError: (err: unknown) => setToast({ kind: 'err', msg: apiErrorMessage(err, 'Deny failed. Retry.') }),
```

Add `apiErrorMessage` to the existing `operatorSession` import in that file (check the current import first: `grep -n "operatorSession" agent-control-tower/act-dashboard/src/pages/OpsPage.tsx`).

- [ ] **Step 4: Run vitest and the type-check build**

Run: `cd agent-control-tower/act-dashboard && pnpm test -- --run && pnpm build`
Expected: tests PASS, build completes with no type errors.

- [ ] **Step 5: Commit**

```bash
git add -A agent-control-tower/act-dashboard/src
git commit -m "feat(dashboard): drop the operator access panel, share the CSRF record across tabs, surface approval refusals"
```

---

### Task 4: core_credentials store — entity, repository, migration, service

**Files:**
- Create: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/CoreCredential.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/repository/CoreCredentialRepository.java`
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/credential/CoreCredentialService.java`
- Create: `agent-control-tower/act-app/src/main/resources/db/migration/V67__core_credentials.sql`
- Test: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/credential/CoreCredentialServiceTest.java`

**Interfaces:**
- Consumes: `SecretBundle(String reference, Map<String,String> environment)` (existing record, act-execution/runtime/SecretBundle.java), `ActorPrincipal` (existing).
- Produces (Task 5 and the launch path depend on these EXACT signatures):
  - `public static final String QODER_CREDENTIAL_REFERENCE = "qoder:operator";`
  - `public static final String QODER_CORE_ID = "qoder";`
  - `public static final String QODER_ENVIRONMENT_VARIABLE = "QODER_PERSONAL_ACCESS_TOKEN";`
  - `public SecretBundle resolve(String credentialRef)` — throws `IllegalArgumentException("Runtime credential is not configured: " + credentialRef)` when absent (admission keeps failing loudly).
  - `public MaskedMetadata put(String coreId, String value, ActorPrincipal actor)`; `public MaskedMetadata metadata(String coreId)`; `public void delete(String coreId, ActorPrincipal actor)`.
  - `public record MaskedMetadata(String credentialRef, String coreId, String environmentVariable, boolean configured, String maskedSecret, Instant updatedAt)`.

- [ ] **Step 1: Write the failing test**

```java
package io.aria.conductor.execution.credential;

import io.aria.conductor.common.model.CoreCredential;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.repository.CoreCredentialRepository;
import io.aria.conductor.execution.runtime.SecretBundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoreCredentialServiceTest {

    private static final String SECRET = "pat-secret-value-1234";

    private final CoreCredentialRepository repository = mock(CoreCredentialRepository.class);
    private final CoreCredentialService service =
            new CoreCredentialService(repository, Clock.systemUTC());

    @BeforeEach
    void saveReturnsWhatWasGiven() {
        when(repository.save(any())).then(returnsFirstArg());
    }

    @Test
    void resolveReturnsSecretBundleForTheFrozenReference() {
        org.mockito.ArgumentCaptor<CoreCredential> saved =
                org.mockito.ArgumentCaptor.forClass(CoreCredential.class);
        service.put(CoreCredentialService.QODER_CORE_ID, SECRET, ActorPrincipal.operator(null));
        verify(repository).save(saved.capture());
        when(repository.findById(CoreCredentialService.QODER_CORE_ID))
                .thenReturn(Optional.of(saved.getValue()));

        SecretBundle bundle = service.resolve("qoder:operator");

        assertThat(bundle.reference()).isEqualTo("qoder:operator");
        assertThat(bundle.environment())
                .containsEntry("QODER_PERSONAL_ACCESS_TOKEN", SECRET);
        assertThat(bundle.toString()).doesNotContain(SECRET);
    }

    @Test
    void resolveOfMissingCredentialFailsAdmissionLoudly() {
        assertThatThrownBy(() -> service.resolve("qoder:operator"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Runtime credential is not configured: qoder:operator");
    }

    @Test
    void metadataIsMaskedAndNeverCarriesTheValue() {
        org.mockito.ArgumentCaptor<CoreCredential> saved =
                org.mockito.ArgumentCaptor.forClass(CoreCredential.class);
        service.put(CoreCredentialService.QODER_CORE_ID, SECRET, ActorPrincipal.operator(null));
        verify(repository).save(saved.capture());
        when(repository.findById(CoreCredentialService.QODER_CORE_ID))
                .thenReturn(Optional.of(saved.getValue()));

        CoreCredentialService.MaskedMetadata metadata = service.metadata("qoder");

        assertThat(metadata.configured()).isTrue();
        assertThat(metadata.maskedSecret()).isEqualTo("****1234");
        assertThat(metadata.toString()).doesNotContain(SECRET);
    }

    @Test
    void putRejectsBlankValuesAndUnknownCores() {
        assertThatThrownBy(() -> service.put("qoder", "  ", ActorPrincipal.operator(null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.put("opencode", SECRET, ActorPrincipal.operator(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported core");
    }

    @Test
    void putAndDeleteRequireAnOperatorPrincipal() {
        assertThatThrownBy(() -> service.put("qoder", SECRET, null))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.delete("qoder", null))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void deleteRemovesTheRow() {
        service.put(CoreCredentialService.QODER_CORE_ID, SECRET, ActorPrincipal.operator(null));

        service.delete(CoreCredentialService.QODER_CORE_ID, ActorPrincipal.operator(null));

        org.mockito.Mockito.verify(repository).deleteById("qoder");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn -q test -pl act-execution -Dtest=CoreCredentialServiceTest`
Expected: COMPILATION ERROR — `CoreCredential` / `CoreCredentialRepository` / `CoreCredentialService` do not exist.

- [ ] **Step 3: Implement entity, repository, service, migration**

`CoreCredential.java`:

```java
package io.aria.conductor.common.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One plain core credential row (2026-10-05 local authority simplification;
 * amends the agent-core spec §6.1): handled exactly like
 * {@code llm_providers.api_key} -- stored as given, masked on read, no
 * dedicated encryption machinery. The launch path still resolves it only into
 * the memory-only SecretBundle; the value is never logged and never returned
 * by any endpoint.
 */
@Entity
@Table(name = "core_credentials")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoreCredential {

    @Id
    @Column(name = "core_id", nullable = false, length = 64)
    private String coreId;

    /** The exact child environment variable the secret may be injected as. */
    @Column(name = "environment_variable", nullable = false, length = 128)
    private String environmentVariable;

    @Column(name = "value", nullable = false, columnDefinition = "TEXT")
    private String value;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
```

`CoreCredentialRepository.java`:

```java
package io.aria.conductor.execution.repository;

import io.aria.conductor.common.model.CoreCredential;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoreCredentialRepository extends JpaRepository<CoreCredential, String> {
}
```

`CoreCredentialService.java`:

```java
package io.aria.conductor.execution.credential;

import io.aria.conductor.common.model.CoreCredential;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.repository.CoreCredentialRepository;
import io.aria.conductor.execution.runtime.SecretBundle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Plain credential store for core launches. The run binding freezes the
 * reference ({@code qoder:operator}); resolve() is the single plaintext exit
 * and exists only to build the memory-only SecretBundle for the launch. A
 * missing credential fails admission loudly. Management (put/delete) requires
 * an operator principal; reads are masked, the value is never echoed and never
 * logged.
 */
@Service
public class CoreCredentialService {

    /** Reference of the single Qoder runtime credential, recorded in run bindings. */
    public static final String QODER_CREDENTIAL_REFERENCE = "qoder:operator";
    public static final String QODER_CORE_ID = "qoder";

    /** The exact variable the pinned CLI consumes (capability evidence, qoder/HOST). */
    public static final String QODER_ENVIRONMENT_VARIABLE = "QODER_PERSONAL_ACCESS_TOKEN";

    private final CoreCredentialRepository credentials;
    private final Clock clock;

    @Autowired
    public CoreCredentialService(CoreCredentialRepository credentials, Clock clock) {
        this.credentials = Objects.requireNonNull(credentials, "Credential repository is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    public SecretBundle resolve(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            throw new IllegalArgumentException("Runtime credential reference is required");
        }
        String coreId = coreIdOf(credentialRef);
        CoreCredential row = credentials.findById(coreId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Runtime credential is not configured: " + credentialRef));
        return new SecretBundle(credentialRef, Map.of(row.getEnvironmentVariable(), row.getValue()));
    }

    public MaskedMetadata put(String coreId, String value, ActorPrincipal actor) {
        requireOperator(actor);
        if (!QODER_CORE_ID.equals(coreId)) {
            throw new IllegalArgumentException("Unsupported core credential: " + coreId);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A runtime credential value is required");
        }
        Instant now = clock.instant();
        CoreCredential row = credentials.findById(coreId)
                .orElseGet(() -> CoreCredential.builder()
                        .coreId(coreId)
                        .environmentVariable(QODER_ENVIRONMENT_VARIABLE)
                        .createdAt(now)
                        .build());
        row.setValue(value.trim());
        row.setUpdatedAt(now);
        credentials.save(row);
        return metadata(coreId);
    }

    public MaskedMetadata metadata(String coreId) {
        return credentials.findById(coreId)
                .map(row -> new MaskedMetadata(credentialRefOf(row.getCoreId()), row.getCoreId(),
                        row.getEnvironmentVariable(), true, mask(row.getValue()), row.getUpdatedAt()))
                .orElseGet(() -> new MaskedMetadata(credentialRefOf(coreId), coreId,
                        QODER_ENVIRONMENT_VARIABLE, false, null, null));
    }

    public void delete(String coreId, ActorPrincipal actor) {
        requireOperator(actor);
        credentials.deleteById(coreId);
    }

    private static String coreIdOf(String credentialRef) {
        int colon = credentialRef.indexOf(':');
        return colon < 0 ? credentialRef : credentialRef.substring(0, colon);
    }

    private static String credentialRefOf(String coreId) {
        return QODER_CORE_ID.equals(coreId) ? QODER_CREDENTIAL_REFERENCE : "";
    }

    static String mask(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        return "****" + value.substring(value.length() - 4);
    }

    private static void requireOperator(ActorPrincipal actor) {
        if (actor == null) {
            throw new SecurityException("Operator authority required");
        }
        actor.requireOperator();
    }

    /** Masked credential metadata safe for REST/UI responses -- never the value. */
    public record MaskedMetadata(String credentialRef, String coreId, String environmentVariable,
            boolean configured, String maskedSecret, Instant updatedAt) {
    }
}
```

`V67__core_credentials.sql` (column types mirror V60):

```sql
-- Local authority simplification (2026-10-05 spec): the Qoder runtime
-- credential moves from the encrypted runtime_credentials store to a plain
-- row handled like llm_providers.api_key. No data carryover: the encrypted
-- store has never been configured in this deployment; the operator re-enters
-- the credential once. Append-only; no applied migration is touched.
CREATE TABLE core_credentials (
    core_id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    environment_variable VARCHAR(128) NOT NULL,
    value                TEXT         NOT NULL,
    created_at           TIMESTAMP    NOT NULL,
    updated_at           TIMESTAMP
);
DROP TABLE runtime_credentials;
```

- [ ] **Step 4: Run the tests and the Flyway migration test**

Run: `cd agent-control-tower && mvn -q test -pl act-execution -Dtest=CoreCredentialServiceTest && mvn -q test -pl act-app -Dtest=MigrationIntegrationTest`
Expected: PASS both. If `MigrationIntegrationTest` asserts a migration inventory, add V67 to its list following the file's existing pattern.

- [ ] **Step 5: Commit**

```bash
git add agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/CoreCredential.java agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/repository/CoreCredentialRepository.java agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/credential/CoreCredentialService.java agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/credential/CoreCredentialServiceTest.java agent-control-tower/act-app/src/main/resources/db/migration/V67__core_credentials.sql
git commit -m "feat(credential): plain core_credentials store with masked reads (V67)"
```

---

### Task 5: Swap the launch path and the REST surface to the new store; delete the encrypted machinery

**Files:**
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/credential/CredentialProbe.java` (move the interface + `CredentialTestOutcome` record out of `QoderCredentialController.java:212-225`)
- Create: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/controller/CoreCredentialController.java`
- Delete: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/controller/QoderCredentialController.java`
- Delete: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/credential/RuntimeCredentialService.java`
- Delete: `agent-control-tower/act-common/src/main/java/io/aria/conductor/common/model/RuntimeCredential.java`
- Delete: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/repository/RuntimeCredentialRepository.java`
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreRunLauncher.java` (lines 168-171: `RuntimeCredentialService.QODER_CREDENTIAL_REFERENCE` → `CoreCredentialService.QODER_CREDENTIAL_REFERENCE`)
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreExecutionService.java` (resolve call at 245-252 → `CoreCredentialService`)
- Modify: `agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreRuntimeConfiguration.java` (whatever references `RuntimeCredentialService` — `grep -n "RuntimeCredentialService" agent-control-tower/act-execution/src/main/java/io/aria/conductor/execution/runtime/CoreRuntimeConfiguration.java`)
- Modify: `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/e2e/CoreE2eSetup.java`, `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/CoreBindingPersistIntegrationTest.java`, `agent-control-tower/act-app/src/test/java/io/aria/conductor/app/MigrationIntegrationTest.java` (references found by the grep in Step 0)
- Delete: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/credential/RuntimeCredentialServiceTest.java`
- Delete: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/controller/QoderCredentialControllerTest.java`
- Create: `agent-control-tower/act-execution/src/test/java/io/aria/conductor/execution/controller/CoreCredentialControllerTest.java`

**Interfaces:**
- Consumes: `CoreCredentialService` (Task 4), `OperatorAuthorityResolver` (Task 1), `CredentialProbe`/`CredentialTestOutcome` (moved verbatim).
- Produces: REST surface `GET/PUT/DELETE /api/v1/cores/{coreId}/credential` and `POST /api/v1/cores/{coreId}/credential/test`. The GET view record: `CoreCredentialView(String credentialRef, String coreId, String environmentVariable, boolean configured, String maskedSecret, Instant updatedAt, boolean testSupported)`.

- [ ] **Step 0: Map every remaining reference**

Run: `grep -rn "RuntimeCredentialService\|QoderCredentialController\|runtime_credentials\|RuntimeCredentialRepository" agent-control-tower --include=*.java | grep -v target`
Record every hit; each is edited or deleted in this task. (Known set: CoreRunLauncher, CoreExecutionService + its test, CoreRuntimeConfiguration, CoreE2eSetup, CoreBindingPersistIntegrationTest, MigrationIntegrationTest, the two test classes being deleted, RuntimeCredentialServiceTest.)

- [ ] **Step 1: Write the failing controller test**

```java
package io.aria.conductor.execution.controller;

import io.aria.conductor.execution.credential.CoreCredentialService;
import io.aria.conductor.execution.repository.CoreCredentialRepository;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import io.aria.conductor.test.WebMvcTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CoreCredentialControllerTest extends WebMvcTestBase {

    private static final String SECRET = "pat-secret-value-1234";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CoreCredentialRepository repository;

    @MockitoBean
    private CredentialProbe probe;

    private CoreCredentialService service;

    @BeforeEach
    void rig() {
        service = new CoreCredentialService(repository, java.time.Clock.systemUTC());
        OperatorSessionService sessions = new OperatorSessionService(
                "operator-bearer-secret", Duration.ofHours(8), "http://localhost:5173", false);
        // Match the existing WebMvcTestBase rigging style for standalone MockMvc:
        // see QoderCredentialControllerTest (deleted) and WebMvcTestBase.mockMvcFor(...).
        mvc = mockMvcFor(new CoreCredentialController(service,
                new OperatorAuthorityResolver(sessions,
                        org.mockito.Mockito.mock(io.aria.conductor.execution.security.ActorTokenService.class),
                        ""), Mockito.mock(CredentialProbe.class), Duration.ofSeconds(20)));
    }

    @Test
    void loopbackPutStoresAndReturnsMaskedMetadata() throws Exception {
        mvc.perform(put("/api/v1/cores/qoder/credential")
                        .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; })
                        .contentType("application/json")
                        .content(SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.maskedSecret").value("****1234"))
                .andExpect(jsonPath("$.environmentVariable").value("QODER_PERSONAL_ACCESS_TOKEN"));
    }

    @Test
    void getReturnsMaskedMetadataAndNeverTheValue() throws Exception {
        service.put("qoder", SECRET, io.aria.conductor.common.security.ActorPrincipal.operator(null));

        mvc.perform(get("/api/v1/cores/qoder/credential")
                        .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.maskedSecret").value("****1234"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString(SECRET))));
    }

    @Test
    void anonymousRemotePutIsUnauthorized() throws Exception {
        mvc.perform(put("/api/v1/cores/qoder/credential")
                        .with(request -> { request.setRemoteAddr("203.0.113.7"); return request; })
                        .contentType("application/json")
                        .content(SECRET))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testWithoutCredentialIsConflict() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/cores/qoder/credential/test")
                        .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; }))
                .andExpect(status().isConflict());
    }
}
```

Adapt the rigging to `WebMvcTestBase`'s actual helper signature (open `agent-control-tower/act-test-support/src/main/java/io/aria/conductor/test/WebMvcTestBase.java` — the deleted `QoderCredentialControllerTest` used `mockMvcFor(...)` with real services over mocked repositories; mirror it exactly, including how it registered `CredentialProbe`).

- [ ] **Step 2: Run test to verify it fails**

Run: `cd agent-control-tower && mvn -q test -pl act-execution -Dtest=CoreCredentialControllerTest`
Expected: COMPILATION ERROR — `CoreCredentialController` does not exist.

- [ ] **Step 3: Implement — extract the probe, write the controller, rewire the launch path**

`CredentialProbe.java` — move verbatim from `QoderCredentialController.java:212-225`:

```java
package io.aria.conductor.execution.credential;

import java.time.Duration;

/**
 * The bounded credential test (one prompt, one timeout) is a separate port:
 * a component without the run-owned core bridge simply has no bean of this
 * type, and the controller answers an honest 503 instead of a fabricated
 * result.
 */
public interface CredentialProbe {
    CredentialTestOutcome test(io.aria.conductor.execution.runtime.SecretBundle credential, Duration timeout);

    record CredentialTestOutcome(boolean authenticated, String model, String detail,
            java.util.Map<String, Object> usage) {
    }
}
```

(Copy the EXACT shape from `QoderCredentialController.java:212-225` — method name and record components — rather than trusting this sketch; keep the original javadoc.)

`CoreCredentialController.java`:

```java
package io.aria.conductor.execution.controller;

import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.credential.CoreCredentialService;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plain core credential surface (2026-10-05 simplification): the same authority
 * resolution as every other operator-gated route -- which on loopback means no
 * ceremony at all -- and llm-config-style masked reads. The stored value is
 * never returned and never logged; the bounded test stays an explicit,
 * never-polled endpoint with an honest 503 when no probe is wired.
 */
@RestController
@RequestMapping("/api/v1/cores/{coreId}/credential")
public class CoreCredentialController {

    public static final Duration TEST_TIMEOUT = Duration.ofSeconds(20);

    public record CoreCredentialView(String credentialRef, String coreId, String environmentVariable,
            boolean configured, String maskedSecret, Instant updatedAt, boolean testSupported) {

        static CoreCredentialView of(CoreCredentialService.MaskedMetadata metadata, boolean testSupported) {
            return new CoreCredentialView(metadata.credentialRef(), metadata.coreId(),
                    metadata.environmentVariable(), metadata.configured(), metadata.maskedSecret(),
                    metadata.updatedAt(), testSupported);
        }
    }

    private final CoreCredentialService credentials;
    private final OperatorAuthorityResolver operatorAuthority;
    private final CredentialProbe probe;
    private final Duration testTimeout;

    @Autowired
    public CoreCredentialController(CoreCredentialService credentials,
            OperatorAuthorityResolver operatorAuthority,
            ObjectProvider<CredentialProbe> credentialProbe) {
        this(credentials, operatorAuthority, credentialProbe.getIfAvailable(), TEST_TIMEOUT);
    }

    public CoreCredentialController(CoreCredentialService credentials,
            OperatorAuthorityResolver operatorAuthority, CredentialProbe probe, Duration testTimeout) {
        this.credentials = credentials;
        this.operatorAuthority = operatorAuthority;
        this.probe = probe;
        this.testTimeout = testTimeout;
    }

    @GetMapping
    public ResponseEntity<Object> getCredential(@PathVariable String coreId, HttpServletRequest request) {
        ActorPrincipal operator = operator(request, false);
        if (operator == null) {
            return unauthorized();
        }
        return ResponseEntity.ok(CoreCredentialView.of(credentials.metadata(coreId), probe != null));
    }

    @PutMapping
    public ResponseEntity<Object> putCredential(@PathVariable String coreId,
            @RequestBody(required = false) String rawBody, HttpServletRequest request) {
        ActorPrincipal operator = operator(request, true);
        if (operator == null) {
            return unauthorized();
        }
        if (rawBody == null || rawBody.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "A runtime credential value is required"));
        }
        CoreCredentialService.MaskedMetadata masked =
                credentials.put(coreId, rawBody.trim(), operator);
        log.info("Core credential for {} replaced by operator", coreId);
        return ResponseEntity.ok(CoreCredentialView.of(masked, probe != null));
    }

    @DeleteMapping
    public ResponseEntity<Object> deleteCredential(@PathVariable String coreId, HttpServletRequest request) {
        ActorPrincipal operator = operator(request, true);
        if (operator == null) {
            return unauthorized();
        }
        credentials.delete(coreId, operator);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test")
    public ResponseEntity<Object> testCredential(@PathVariable String coreId, HttpServletRequest request) {
        ActorPrincipal operator = operator(request, true);
        if (operator == null) {
            return unauthorized();
        }
        if (!credentials.metadata(coreId).configured()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Qoder runtime credential is not configured"));
        }
        if (probe == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "tested", false,
                    "reason", "The bounded Qoder credential test requires the run-owned core bridge,"
                            + " which is not wired in this component; no model call was made."));
        }
        io.aria.conductor.execution.runtime.SecretBundle credential = credentials.resolve(
                CoreCredentialService.QODER_CREDENTIAL_REFERENCE);
        CredentialProbe.CredentialTestOutcome outcome = probe.test(credential, testTimeout);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tested", true);
        response.put("authenticated", outcome.authenticated());
        response.put("model", outcome.model());
        response.put("detail", outcome.detail());
        response.put("usage", outcome.usage());
        response.put("costDisclosure", "One bounded prompt through the pinned core with the configured"
                + " credential; only provider-reported usage is reported, and unknown usage stays unknown.");
        return ResponseEntity.ok(response);
    }

    /** Operator or loopback operator; null means 401. 403s keep their exception path. */
    private ActorPrincipal operator(HttpServletRequest request, boolean mutation) {
        try {
            return operatorAuthority.resolveOperator(request, mutation);
        } catch (OperatorSessionService.ForbiddenMutationException e) {
            throw e;
        }
    }

    private static ResponseEntity<Object> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Operator credential required"));
    }
}
```

Notes for the implementer: add the missing imports (`OperatorSessionService` for the catch clause, `lombok.extern.slf4j.Slf4j` + `@Slf4j` for `log` — check which logging annotation the deleted controller used and mirror it); a `ForbiddenMutationException` escaping translates to 403 the same way it did in `QoderCredentialController.rejectNonOperator` — if the app has no global handler for it, wrap each endpoint's `operator(request, ...)` call in try/catch returning `forbidden()` exactly like the old controller did (copy that helper).

Rewire the launch path:
- `CoreRunLauncher.java:169-171` — replace `RuntimeCredentialService.QODER_CREDENTIAL_REFERENCE` with `CoreCredentialService.QODER_CREDENTIAL_REFERENCE` (import swap).
- `CoreExecutionService.java:245-252` — the `RuntimeCredentialService` field becomes `CoreCredentialService`; the resolve call is unchanged (`resolve(credentialRef)`), the thrown `IllegalArgumentException` handling is unchanged.
- `CoreRuntimeConfiguration.java` — swap the bean wiring per the grep from Step 0 (it likely constructs/injects `RuntimeCredentialService`).
- act-app tests — per the Step 0 grep: `CoreE2eSetup` PUTs the credential through the REST surface (update the URL to `/api/v1/cores/qoder/credential`; the loopback harness may keep or drop its bearer header — keep the header, it must stay valid); `CoreBindingPersistIntegrationTest` and `MigrationIntegrationTest` reference the old table/entity — update to `core_credentials`/`CoreCredential` (binding persistence asserts the frozen ref string `qoder:operator`, which is unchanged).

Then delete: `RuntimeCredentialService.java`, `RuntimeCredentialServiceTest.java`, `RuntimeCredential.java`, `RuntimeCredentialRepository.java`, `QoderCredentialController.java`, `QoderCredentialControllerTest.java`.

- [ ] **Step 4: Run the tests**

Run: `cd agent-control-tower && mvn -q test -pl act-execution && mvn -q test -pl act-app -Dtest="CoreE2e*,CoreBindingPersistIntegrationTest,MigrationIntegrationTest"`
Expected: PASS. `CoreExecutionServiceTest` may reference the old service type — fix its wiring to `CoreCredentialService` (same `resolve` contract).

- [ ] **Step 5: Commit**

```bash
git add -A agent-control-tower
git commit -m "feat(credential): core credential REST surface on the plain store; remove the encrypted runtime-credential machinery"
```

---

### Task 6: Rework RuntimeCredentialsCard onto the new API

**Files:**
- Modify: `agent-control-tower/act-dashboard/src/api/runtimeCredentials.ts` (paths + metadata type + drop `applyOperatorHeaders`)
- Modify: `agent-control-tower/act-dashboard/src/components/RuntimeCredentialsCard.tsx` (imports line 1472 area, operator-rejection render block lines 1589-1603, header docstring)

**Interfaces:**
- Consumes: `/api/v1/cores/qoder/credential` (Task 5); view shape `CoreCredentialView` → TS type `{ credentialRef, coreId, environmentVariable, configured, maskedSecret, updatedAt, testSupported }` (note: no `encryptionKeyConfigured` anymore).
- Produces: same exported function names (`getQoderCredential`, `putQoderCredential`, `deleteQoderCredential`, `testQoderCredential`, `QoderCredentialMetadata`, `CredentialTestOutcome`) so the card's imports barely change.

- [ ] **Step 1: Update the API client**

In `runtimeCredentials.ts`: change the base path to `/api/v1/cores/qoder/credential`; remove the `applyOperatorHeaders` import and every call to it; redefine `QoderCredentialMetadata` as:

```typescript
export interface QoderCredentialMetadata {
  credentialRef: string;
  coreId: string;
  environmentVariable: string;
  configured: boolean;
  maskedSecret: string | null;
  updatedAt: string | null;
  testSupported: boolean;
}
```

- [ ] **Step 2: Update the card**

`RuntimeCredentialsCard.tsx`:
- Remove `applyOperatorHeaders` and `isOperatorRejection` from the `operatorSession` import (keep `apiErrorMessage`).
- Delete the operator-rejection block (`{metadataQuery.isError && (...)}` lines 1589-1596) and replace it with:

```tsx
      {metadataQuery.isError && (
        <div className="error-state">
          <div>{apiErrorMessage(metadataQuery.error, 'Failed to load the Qoder runtime credential.')}</div>
        </div>
      )}
```

- Change the metadata render guard `{metadata && !isOperatorRejection(metadataQuery.error) && (` to `{metadata && (`.
- In `testUnavailableReason`, delete the `metadata.testSupported === false` comment's mention of the operator session if any; the branch itself stays.
- Update the component docstring: remove the "spec §6.1" encryption wording; state the plain store + masked reads.

- [ ] **Step 3: Type-check, test, build**

Run: `cd agent-control-tower/act-dashboard && pnpm test -- --run && pnpm build`
Expected: PASS. If a card test file exists (glob `RuntimeCredentialsCard*.test.*`), update its expectations for the removed operator-rejection state.

- [ ] **Step 4: Commit**

```bash
git add agent-control-tower/act-dashboard/src
git commit -m "feat(dashboard): qoder credential card on the plain core credential surface"
```

---

### Task 7: Configuration, docs, CI, compose

**Files:**
- Modify: `docker-compose.yml` (backend environment block, lines 1896-1930)
- Modify: `README.md` (operator/credential sections around line 297)
- Modify: `.env.example` (lines 31, 38)
- Modify: `.github/actions/start-stack/action.yml` (lines 1973-1980: drop the `ARIA_RUNTIME_CREDENTIAL_KEY` echo)
- Modify: `.github/workflows/nightly-sdd-llm-smoke.yml` (lines 2005-2009: drop `ARIA_RUNTIME_CREDENTIAL_KEY`; grep the workflow for any old-endpoint credential PUT and update the URL)

**Interfaces:** none new; this task keeps every deployment path honest about the new model.

- [ ] **Step 1: Compose — trusted proxy for the containerized frontend**

In `docker-compose.yml`, add to the backend `environment:` block (after `JAVA_TOOL_OPTIONS`):

```yaml
      # The containerized frontend proxies /api to this backend, so the direct
      # peer is the proxy, not the operator's browser. Trust it to tell us the
      # real client address (X-Forwarded-For); loopback auto-authority applies
      # to the browser session it forwards.
      ARIA_OPERATOR_TRUSTED_PROXIES: ${ARIA_OPERATOR_TRUSTED_PROXIES:-frontend}
```

Check the frontend service's container name/hostname in the same file first (`grep -n "frontend\|container_name" docker-compose.yml`) and use the actual resolvable name — if the frontend has no `container_name`, the compose service key (e.g. `frontend`) is the DNS name; Docker resolves it, but `getRemoteAddr()` sees the container IP, not the name. **Compose service names do not resolve to static IPs** — therefore the trusted-proxy value must be an IP or a resolvable hostname. If the frontend is a container whose IP varies, set the proxy list from the network subnet instead and document it in README (e.g. `172.18.0.0/16` is NOT supported by the exact-match resolver — in that case run the frontend on the host network or set `network_mode` so the peer is loopback; document the chosen path in README). Do not invent CIDR support.

- [ ] **Step 2: Docs**

`README.md` around line 297: rewrite the operator-credential bullets to state (a) local/loopback deployments need no operator token — the dashboard is authorized automatically; (b) `ARIA_OPERATOR_BEARER_TOKEN` is for CI and remote/non-loopback deployments; (c) `ARIA_OPERATOR_TRUSTED_PROXIES` exists for proxied containerized deployments; (d) the Qoder credential is stored plainly and masked on read at `/providers`. `.env.example`: delete the `# ARIA_RUNTIME_CREDENTIAL_KEY=` line (line 38); annotate line 31 `# ARIA_OPERATOR_BEARER_TOKEN=` with "CI/remote only; loopback needs no token". Grep docs for stale wording: `grep -rn "ARIA_RUNTIME_CREDENTIAL_KEY\|encryption-key" README.md docs/ .env.example | grep -v superpowers` and fix each hit except historical spec/review documents (never rewrite history docs).

- [ ] **Step 3: CI**

`action.yml` lines 1973-1980: delete the `echo "ARIA_RUNTIME_CREDENTIAL_KEY=$(openssl rand -base64 32 | tr -d '\n')" \}` line (keep the other three env exports). `nightly-sdd-llm-smoke.yml` lines 2002-2009: same deletion. Then find how each pipeline stores the credential: `grep -n "credential" .github/actions/start-stack/action.yml .github/workflows/nightly-sdd-llm-smoke.yml` — the harness (`CoreE2eSetup`) was already updated in Task 5; update any remaining `curl`/script that POSTs to `/api/v1/adk/providers/qoder/credential` to `/api/v1/cores/qoder/credential`.

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml README.md .env.example .github
git commit -m "chore(config): loopback authority and plain credential store across compose, docs and CI"
```

---

### Task 8: Wave verification and leftover sweep

**Files:** none created; verification only.

**Interfaces:** gates the whole plan.

- [ ] **Step 1: Leftover sweep**

Run: `grep -rn "ARIA_RUNTIME_CREDENTIAL_KEY\|RuntimeCredentialService\|runtime_credentials\|QoderCredentialController\|adk/providers/qoder/credential\|OperatorAccessPanel\|encryptionKeyConfigured" agent-control-tower packages .github scripts docker-compose.yml README.md .env.example --include=*.java --include=*.ts --include=*.tsx --include=*.yml --include=*.yaml --include=*.md --include=*.mjs --include=*.json 2>/dev/null | grep -v node_modules | grep -v target | grep -v "docs/superpowers\|docs/reviews\|docs/architecture\|CHANGELOG"`
Expected: zero hits. Fix whatever appears (excluding historical documents, which stay).

- [ ] **Step 2: Module and app tests**

Run: `cd agent-control-tower && mvn -q test -pl act-execution,act-common,act-app`
Expected: PASS (Flyway migration against H2 included via act-app tests).

- [ ] **Step 3: Frontend**

Run: `cd agent-control-tower/act-dashboard && pnpm test -- --run && pnpm build`
Expected: PASS.

- [ ] **Step 4: Live smoke on the local stack (operator-visible behavior)**

Start the backend (`pwsh -NoProfile -File scripts/start-backend.ps1 -SkipSandbox` or the operator's usual local stack). Verify with curl from the same machine:

```bash
curl -s http://localhost:8080/api/v1/cores/qoder/credential | head -c 300
curl -s -X PUT http://localhost:8080/api/v1/cores/qoder/credential -H 'Content-Type: application/json' -d 'smoke-pat-1234' | head -c 300
```

Expected: both 200 (loopback auto-authority, no token, no CSRF); GET shows `"maskedSecret":"****1234"` and never the value. Then in the browser: open `/providers` — no Operator access panel, the credential card loads without any session; open Ops/Review and approve a pending ask (or verify no 401 appears in the network tab when one exists).

- [ ] **Step 5: Commit any remaining fixes and record the wave result**

```bash
git status && git add -A agent-control-tower agent-control-tower/act-dashboard && git commit -m "test(authority): wave verification fixes for the local authority simplification" || true
```

(Commit only if Step 1-4 produced changes; an empty wave commit is not created.)
