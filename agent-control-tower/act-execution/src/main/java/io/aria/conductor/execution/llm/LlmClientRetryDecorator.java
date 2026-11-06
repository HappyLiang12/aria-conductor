package io.aria.conductor.execution.llm;

import io.aria.conductor.agent.service.SystemConfigService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class LlmClientRetryDecorator implements LlmClient {

    // ponytail: 60s TTL cache. SystemConfigService.get hits the DB per call with no caching;
    // refresh together so each complete() normally costs 0 DB queries, not 2. Per-field volatile
    // is fine — a rare stale read just uses a slightly outdated (still-valid) config value.
    private static final long CONFIG_TTL_MS = 60_000L;

    private final LlmClient delegate;
    private final SystemConfigService configService;

    private volatile int cachedMaxRetries = 3;
    private volatile int cachedBaseBackoffMs = 2000;
    private volatile long cacheExpiresAt = 0L;

    public LlmClientRetryDecorator(LlmClient delegate, SystemConfigService configService) {
        this.delegate = delegate;
        this.configService = configService;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        refreshConfigIfNeeded();
        int maxRetries = cachedMaxRetries;
        int baseBackoffMs = cachedBaseBackoffMs;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return delegate.complete(request);
            } catch (Exception e) {
                int httpStatus = extractHttpStatus(e);
                LlmErrorClassifier.LlmErrorClass errorClass = LlmErrorClassifier.classify(e, httpStatus);

                // Permanent errors → fail immediately, no retry
                if (errorClass == LlmErrorClassifier.LlmErrorClass.PERMANENT) {
                    logErrorWithFullContext(request, e, httpStatus, attempt);
                    throw e;
                }

                // Last attempt → fail after logging
                if (attempt >= maxRetries) {
                    logErrorWithFullContext(request, e, httpStatus, attempt);
                    throw e;
                }

                // Retry with exponential backoff + ±20% jitter to avoid stampeding concurrent retries
                long backoffMs = baseBackoffMs * (1L << attempt); // 2s, 4s, 8s
                long jitter = backoffMs / 5; // 20%
                long sleepMs = backoffMs - jitter + ThreadLocalRandom.current().nextLong(0, 2 * jitter + 1);
                log.warn("LLM retry attempt {}/{} after ~{}ms (errorClass={}, httpStatus={}): {}",
                        attempt + 1, maxRetries, sleepMs, errorClass, httpStatus, e.getMessage());

                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
            }
        }
        throw new IllegalStateException("unreachable");
    }

    @Override
    public Flux<String> stream(LlmRequest request) {
        // Streaming bypasses retry by design: mid-stream failure leaves partial tokens already
        // emitted, so a clean retry isn't possible. Callers needing resilience should use complete().
        return delegate.stream(request);
    }

    private void refreshConfigIfNeeded() {
        long now = System.currentTimeMillis();
        if (now >= cacheExpiresAt) {
            cachedMaxRetries = configService.getInt("llm.retry.max.attempts", 3, 0, 10);
            cachedBaseBackoffMs = configService.getInt("llm.retry.backoff.base.ms", 2000, 500, 30000);
            cacheExpiresAt = now + CONFIG_TTL_MS;
        }
    }

    private void logErrorWithFullContext(LlmRequest request, Exception e, int httpStatus, int attempts) {
        int charCount = request.messages() != null
                ? request.messages().stream().mapToInt(m -> m.content() != null ? m.content().length() : 0).sum()
                : 0;
        log.error("LLM call failed after {} attempts: model={}, provider={}, messages={}, charCount={}, httpStatus={}, error={}",
                attempts + 1,
                request.model() != null ? request.model() : "default",
                "dynamic",
                request.messages() != null ? request.messages().size() : 0,
                charCount,
                httpStatus,
                e.getMessage(),
                e);
    }

    private int extractHttpStatus(Exception e) {
        if (e instanceof LlmHttpException) {
            return ((LlmHttpException) e).getStatusCode();
        }
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof LlmHttpException) {
                return ((LlmHttpException) cause).getStatusCode();
            }
            cause = cause.getCause();
        }
        return 0;
    }
}
