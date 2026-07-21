# Live Config Reload for SystemConfig-backed Properties

## Problem

Three `@ConfigurationProperties` classes (`CircuitBreakerProperties`, `ReportProperties`, `AriaProperties`) read from `SystemConfigService` only once at `@PostConstruct`. Changes made via the dashboard Settings UI (`SystemConfigController` PUT) are saved to the DB but not picked up by the running application until restart.

**Symptom:** User changes `circuit.breaker.max.tokens.per.run` from 100000 to 1000000 in the UI, but agents still trip the circuit breaker at 100000 tokens.

## Root Cause

`@PostConstruct` is a one-shot lifecycle hook. The properties classes cache DB values in fields at startup and never re-read. This is a systemic pattern — 3 of 6 `@ConfigurationProperties` classes in the project have it.

## Audit

| Class | Hot reload? | Config keys | Consumed by |
|-------|-------------|-------------|-------------|
| `CircuitBreakerProperties` | No | 4 | `CircuitBreaker`, `RuleVerifier` |
| `ReportProperties` | No | 2 | `ReportService` |
| `AriaProperties` | No | 2 | **Dead code** (no consumers) |
| `LlmClientRetryDecorator` | Yes (TTL 60s) | 2 | N/A |
| `AriaChatController` | Yes (live read) | 1 | N/A |
| `DefaultLlmClient` | Yes (live read) | 1 | N/A |
| `LangChainAdkProperties` | YAML/env only | 0 | N/A |
| `AdkSystemProperties` | YAML/env only | 0 | N/A |
| `LlmProperties` | YAML/env only | 0 | N/A |

## Approach

**Read on every access.** Properties classes keep their field defaults (from YAML/env). Explicit getter methods delegate to `SystemConfigService` with the field value as fallback default. `@PostConstruct` DB overlay is removed.

### Why not caching

The `SystemConfigService` performs simple PK lookups on a ~10-row table — sub-millisecond. The 2 existing live-read consumers (`AriaChatController`, `DefaultLlmClient`) prove this is safe. Adding caching adds complexity (TTL, invalidation, stale reads) for negligible performance gain.

## Changes

### CircuitBreakerProperties

- Remove `@PostConstruct overlayFromDb()`.
- Keep `SystemConfigService` as an `@Autowired` field. (Constructor injection is **not** used: these are `@ConfigurationProperties` beans registered via `@EnableConfigurationProperties`, so a constructor would trigger constructor binding and treat `SystemConfigService` as a bindable property. Field injection avoids that.)
- Add explicit getters for all 4 properties that call `systemConfigService.get*()` with the field value as default, wrapped in try-catch for DB failure fallback. Each failure is surfaced via a `log.warn` (the class keeps `@Slf4j`).
- Consumers `CircuitBreaker.check()` / `RuleVerifier.verify()` snapshot all config values into locals once per call, so each key is read from `SystemConfig` a single time per call — this keeps the decision, log message and exception consistent and avoids redundant reads on this hot path. Their public API is unchanged.

### ReportProperties

- Remove `@PostConstruct overlayFromDb()`.
- Keep `SystemConfigService` as an `@Autowired` field (same rationale as `CircuitBreakerProperties`).
- Add explicit getters for `generateMaxTokens` and `amendMaxTokens` that read live, logging a `log.warn` on DB failure.
- Consumer (`ReportService`) requires no changes (it reads each value once per call).

### AriaProperties

- Remove `@PostConstruct overlayFromDb()`, `@Autowired SystemConfigService`, and the `maxHistoryTurns`/`sessionTtlMinutes` fields entirely — they have zero consumers anywhere in the codebase.
- Keep only the `systemPrompt` field (YAML/env only, no DB key).
- `ActApplication` already registers the class via `@EnableConfigurationProperties` — no change needed there.
- Remove the now-orphaned `aria.max-history-turns` / `aria.session-ttl-minutes` entries from the `application*.yml` profiles and the dashboard Settings UI (`SystemConfigPanel.tsx`), and drop the seeded DB rows in migration `V31__remove_orphaned_aria_session_config.sql` (V14 is left untouched as an applied migration).

### Tests

Each modified properties class gets or updates tests verifying:

1. Getter returns DB value when `SystemConfigService` provides one.
2. Getter falls back to field default when `SystemConfigService` throws, and emits a `log.warn`.
3. (For `CircuitBreakerProperties` and `ReportProperties`): no stale values — changing the DB value is reflected on the next getter call without restart.

Fallback (2) and no-stale (3) coverage spans **all** getters of each class, not a representative one. `CircuitBreaker` / `RuleVerifier` additionally have tests asserting each config value is read exactly once per `check()` / `verify()` call.

Remove or update existing `@PostConstruct`-focused tests.

## Error Handling

Each live getter wraps the `SystemConfigService` call in try-catch:

```java
public long getMaxTokensPerRun() {
    try {
        return systemConfigService.getLong("circuit.breaker.max.tokens.per.run", maxTokensPerRun, 1000, 10_000_000);
    } catch (Exception e) {
        log.warn("Failed to read 'circuit.breaker.max.tokens.per.run' from SystemConfig, using default {}",
                maxTokensPerRun, e);
        return maxTokensPerRun; // YAML/env default
    }
}
```

If the DB is unreachable, the application continues with YAML/env defaults — matching the current `@PostConstruct` fallback behavior.

## Not in Scope

- No caching layer in `SystemConfigService`. If DB load becomes measurable in the future, add a TTL cache there — not per-consumer.
- No event/notification system for config changes. Not needed — live reads are sufficient.
- No changes to `LlmClientRetryDecorator`'s existing TTL cache. It can be simplified later to use `SystemConfigService` directly, but that's out of scope.
- Consumer API surfaces are unchanged. `CircuitBreaker` / `RuleVerifier` now snapshot config once per call (consistency + fewer reads); `ReportService` reads live per call.
