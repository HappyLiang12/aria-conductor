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

- Remove `@PostConstruct overlayFromDb()` and `SystemConfigService` field injection via `@Autowired`.
- Inject `SystemConfigService` via constructor.
- Add explicit getters for all 4 properties that call `systemConfigService.get*()` with the field value as default, wrapped in try-catch for DB failure fallback.
- Consumers (`CircuitBreaker`, `RuleVerifier`) require no changes.

### ReportProperties

- Remove `@PostConstruct overlayFromDb()` and `@Autowired SystemConfigService`.
- Inject `SystemConfigService` via constructor.
- Add explicit getters for `generateMaxTokens` and `amendMaxTokens` that read live.
- Consumer (`ReportService`) requires no changes.

### AriaProperties

- Remove `@PostConstruct overlayFromDb()`, `@Autowired SystemConfigService`, and the `maxHistoryTurns`/`sessionTtlMinutes` fields entirely — they have zero consumers anywhere in the codebase.
- Keep only the `systemPrompt` field (YAML/env only, no DB key).
- Update `ActApplication` to still register the class (it's a `@ConfigurationProperties` bean).

### Tests

Each modified properties class gets or updates tests verifying:

1. Getter returns DB value when `SystemConfigService` provides one.
2. Getter falls back to field default when `SystemConfigService` throws.
3. (For `CircuitBreakerProperties` and `ReportProperties`): no stale values — changing the DB value is reflected on the next getter call without restart.

Remove or update existing `@PostConstruct`-focused tests.

## Error Handling

Each live getter wraps the `SystemConfigService` call in try-catch:

```java
public long getMaxTokensPerRun() {
    try {
        return systemConfigService.getLong("circuit.breaker.max.tokens.per.run", maxTokensPerRun, 1000, 10_000_000);
    } catch (Exception e) {
        return maxTokensPerRun; // YAML/env default
    }
}
```

If the DB is unreachable, the application continues with YAML/env defaults — matching the current `@PostConstruct` fallback behavior.

## Not in Scope

- No caching layer in `SystemConfigService`. If DB load becomes measurable in the future, add a TTL cache there — not per-consumer.
- No event/notification system for config changes. Not needed — live reads are sufficient.
- No changes to `LlmClientRetryDecorator`'s existing TTL cache. It can be simplified later to use `SystemConfigService` directly, but that's out of scope.
- No changes to consumers (`CircuitBreaker`, `RuleVerifier`, `ReportService`). Their API surface is unchanged.
