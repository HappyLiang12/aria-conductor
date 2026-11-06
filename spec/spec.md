# Spec: Suppress Expected Execd Warmup ERROR Logs During Sandbox Startup

**Issue:** #81  
**Title:** [opencode] Expected execd warmup retry logs a full ERROR stack trace on every sandbox start

---

## Problem Statement

On every opencode sandbox startup, the provider probes the sandbox exec channel with a trivial `true` command (`awaitExecdReady`). The first attempt legitimately fails while the sandbox execd service is still warming up — the SDK returns from `create` with `skipHealthCheck=true`, so the sandbox may not be reachable yet.

The application handles this by retrying and logs at INFO level, but the OpenSandbox SDK internally logs the same failure at **ERROR with a full stack trace**, making a normal, self-recovering warmup look like a crash:

```
14:45:15.344 INFO  ... c.alibaba.opensandbox.sandbox.Sandbox : create sandbox ... completed
                   for sandbox fd22b14b-80a9-4fc1-abe1-9f8f403db56e
                   (skipHealthCheck=true, sandbox may not be ready yet)
14:45:15.370 ERROR ... c.a.o.s.i.a.service.CommandsAdapter : Failed to run command (length: 4)
java.net.ConnectException: Failed to connect to /127.0.0.1:59469
  at okhttp3.internal.connection.RealConnection.connectSocket(RealConnection.kt:297)
  ...
14:45:22.383 INFO  ... OpenCodeSandboxManager : Starting opencode serve in sandbox fd22b14b...
14:45:30.572 INFO  ... OpenCodeAdkProvider : OpenCode sandbox fd22b14b... ready for agent
```

**Impact:**
- Every opencode-mode startup prints a misleading `ERROR` + stack trace that looks like a failure
- Log scanners and operators cannot distinguish a real sandbox failure from the expected warmup retry
- Operators may chase non-issues, wasting debugging time

**Root Cause:**
The ERROR is emitted by the third-party SDK logger `com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.CommandsAdapter` (artifact `com.alibaba.opensandbox:sandbox:1.0.18`), not by application code.

---

## Proposed Solution

### Primary Fix: Suppress SDK Adapter Logger

Configure the logging framework to quiet the SDK adapter logger that emits the spurious ERROR:

**File:** `agent-control-tower/act-dashboard-api/src/main/resources/logback-spring.xml`

Add a logger entry for the SDK's `CommandsAdapter` class, setting its level to `WARN` (or `OFF`):

```xml
<logger name="com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.CommandsAdapter" level="WARN"/>
```

This ensures:
- The SDK's expected warmup failures (connection refused during probe) are suppressed at ERROR level
- Genuine warnings and errors from the SDK still surface
- The application's own INFO-level retry messages remain the single source of truth for warmup progress

### Secondary Enhancement (Optional): Pre-Probe Delay

Add a short delay before the first execd probe to reduce the frequency of expected connection failures. This is a defense-in-depth measure that reduces log noise even if the logger suppression is not applied.

**Location:** The warmup probe logic in `SandboxLifecycle.java` (or the test harness `QoderSandboxHarness.java`)

**Implementation:** Insert a brief sleep (e.g., 500ms-1s) after sandbox creation before the first `awaitExecdReady` probe attempt.

---

## Acceptance Criteria

1. **No ERROR-level ConnectException during warmup**
   - Starting the backend with `-AdkProvider opencode` emits no ERROR-level `ConnectException` or `Failed to run command` log entries during normal sandbox warmup
   - The SDK's `CommandsAdapter` logger is configured at WARN level or higher

2. **Application INFO logs preserved**
   - The project's own INFO-level retry messages (e.g., "Sandbox execd not ready yet (attempt N)") continue to appear
   - Operators can still observe warmup progress through application logs

3. **Genuine failures still surface**
   - A genuinely unreachable execd (warmup never succeeds) still results in an error
   - The application's `awaitExecdReady` returns `false` after exhausting retries and logs its budget-exhausted INFO message
   - The run fails with `TaskExecutionException.Cause.SANDBOX_UNAVAILABLE` as before

4. **No regression in sandbox startup**
   - Sandbox creation, warmup, and readiness detection work identically to before
   - All existing tests pass without modification

---

## Error Handling

| Scenario | Behavior |
|----------|----------|
| **Normal warmup (execd not ready on first probe)** | SDK ERROR suppressed; application INFO retry message logged; probe succeeds on retry; startup proceeds normally |
| **Execd never becomes ready (budget exhausted)** | Application logs final INFO message; run fails with `SANDBOX_UNAVAILABLE`; no change from current behavior |
| **Genuine SDK error (not warmup-related)** | SDK WARN/ERROR messages at WARN level or above still appear; operator can diagnose real issues |
| **Logging configuration missing or invalid** | Spring Boot fails fast at startup with clear configuration error; no silent fallback |

---

## Implementation Notes

- The logger name `com.alibaba.opensandbox.sandbox.infrastructure.adapters.service.CommandsAdapter` is from the third-party SDK artifact `com.alibaba.opensandbox:sandbox:1.0.18`
- The logback configuration supports both standard console logging and JSON logging profiles; the logger entry should be added outside the `<springProfile>` blocks to apply to both
- No code changes are required for the primary fix — only logging configuration
- The optional pre-probe delay would require changes to `SandboxLifecycle.java` or the test harness

## Testing

- **Manual verification:** Start the backend with `-AdkProvider opencode` and observe logs during sandbox warmup
- **Log assertion test (optional):** Add a test that captures log output during sandbox creation and asserts no ERROR-level entries from `CommandsAdapter` appear during normal warmup
