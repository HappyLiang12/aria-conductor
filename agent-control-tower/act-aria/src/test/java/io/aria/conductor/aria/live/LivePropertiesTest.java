package io.aria.conductor.aria.live;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LivePropertiesTest {

    @Test
    void absentValuesFallBackToTheDocumentedDefaults() {
        LiveProperties properties = new LiveProperties(false, null, "  ", 0, 0);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.bridgeScript()).isEqualTo(LiveProperties.DEFAULT_BRIDGE_SCRIPT);
        assertThat(properties.credentialsFile()).isEqualTo(LiveProperties.DEFAULT_CREDENTIALS_FILE);
        assertThat(properties.readyDeadlineMs()).isEqualTo(5000L);
        assertThat(properties.maxSessionSeconds()).isEqualTo(7200);
    }

    @Test
    void explicitValuesAreKeptAndTrimmed() {
        LiveProperties properties = new LiveProperties(true, " packages/bridge/dist/index.js ",
                " ~/.aria-conductor/live.env ", 200, 3600);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.bridgeScript()).isEqualTo("packages/bridge/dist/index.js");
        assertThat(properties.credentialsFile()).isEqualTo("~/.aria-conductor/live.env");
        assertThat(properties.readyDeadlineMs()).isEqualTo(200L);
        assertThat(properties.maxSessionSeconds()).isEqualTo(3600);
    }

    @Test
    void noopBridgeClosesWithoutASideEffect() {
        LiveBridge bridge = new NoopLiveBridgeFactory().open("session", "conv");

        bridge.close();

        assertThat(LiveBridge.NOOP).isSameAs(bridge);
    }
}
