package io.aria.conductor.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorOriginGuardTest {

    private final OperatorOriginGuard guard = new OperatorOriginGuard(
            "http://localhost:*,http://127.0.0.1:*");

    @Test
    void exactAllowedOriginMatches() {
        assertThat(guard.isAllowed("http://localhost:5173")).isTrue();
        assertThat(guard.isAllowed("http://127.0.0.1:5173")).isTrue();
    }

    @Test
    void foreignOriginIsRejected() {
        assertThat(guard.isAllowed("http://evil.example")).isFalse();
        assertThat(guard.isAllowed("http://localhost.evil.example:5173")).isFalse();
    }

    @Test
    void nullAndBlankOriginsAreNeverAllowed() {
        assertThat(guard.isAllowed(null)).isFalse();
        assertThat(guard.isAllowed("   ")).isFalse();
    }

    @Test
    void portPatternsRequireANumericPort() {
        assertThat(guard.isAllowed("http://localhost:")).isFalse();
        assertThat(guard.isAllowed("http://localhost:abc")).isFalse();
        assertThat(guard.isAllowed("http://localhost:00000")).isTrue();
    }

    @Test
    void aHostThatMerelyStartsWithThePatternHostIsNotAMatch() {
        assertThat(guard.isAllowed("http://localhost.evil:5173")).isFalse();
    }

    @Test
    void crossSiteMeansPresentButForeign_andMissingOriginIsNotCrossSite() {
        assertThat(guard.isCrossSite("http://evil.example")).isTrue();
        // null Origin is the local-tool path: the callers interpret per contract.
        assertThat(guard.isCrossSite(null)).isFalse();
        assertThat(guard.isCrossSite("http://localhost:5173")).isFalse();
    }

    @Test
    void blankConfigEntriesAreIgnored() {
        OperatorOriginGuard sparse = new OperatorOriginGuard(" http://localhost:* ,, ");
        assertThat(sparse.isAllowed("http://localhost:5173")).isTrue();
        assertThat(sparse.isAllowed("http://127.0.0.1:5173")).isFalse();
    }
}
