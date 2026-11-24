package io.aria.conductor.aria.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class LivePropertiesTest {

    @Test
    void absentValuesFallBackToTheDocumentedDefaults() {
        LiveProperties properties = new LiveProperties(false, null, "  ", null, 0, 0, false, " ", "  ", 0);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.bridgeScript()).isEqualTo(LiveProperties.DEFAULT_BRIDGE_SCRIPT);
        assertThat(properties.credentialsFile()).isEqualTo(LiveProperties.DEFAULT_CREDENTIALS_FILE);
        assertThat(properties.realtimeUrl()).isEmpty();
        assertThat(properties.readyDeadlineMs()).isEqualTo(5000L);
        assertThat(properties.maxSessionSeconds()).isEqualTo(7200);
        // The direct locator is opt-in (spec D6): off, the documented origin, the vision
        // model and the 1.5 s whole-call budget.
        assertThat(properties.directLocatorEnabled()).isFalse();
        assertThat(properties.directBaseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(properties.directModel()).isEqualTo("deepseek-flash");
        assertThat(properties.directTimeoutMs()).isEqualTo(1500L);
    }

    @Test
    void explicitValuesAreKeptAndTrimmed() {
        LiveProperties properties = new LiveProperties(true, " packages/bridge/dist/index.js ",
                " ~/.aria-conductor/live.env ", " wss://example.invalid/api-ws/v1/realtime ", 200, 3600,
                true, " http://127.0.0.1:9 ", " custom-vision ", 800);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.bridgeScript()).isEqualTo("packages/bridge/dist/index.js");
        assertThat(properties.credentialsFile()).isEqualTo("~/.aria-conductor/live.env");
        assertThat(properties.realtimeUrl()).isEqualTo("wss://example.invalid/api-ws/v1/realtime");
        assertThat(properties.readyDeadlineMs()).isEqualTo(200L);
        assertThat(properties.maxSessionSeconds()).isEqualTo(3600);
        assertThat(properties.directLocatorEnabled()).isTrue();
        assertThat(properties.directBaseUrl()).isEqualTo("http://127.0.0.1:9");
        assertThat(properties.directModel()).isEqualTo("custom-vision");
        assertThat(properties.directTimeoutMs()).isEqualTo(800L);
    }

    @Test
    void aNonPositiveDirectTimeoutFallsBackToTheBudget() {
        // A zero or negative budget would mean "no deadline", which the backup cannot
        // honour: it must degrade within 1.5 s by default.
        assertThat(new LiveProperties(false, null, null, "", 0, 0, true, null, null, -5)
                .directTimeoutMs()).isEqualTo(1500L);
    }

    @Test
    void aBlankRealtimeUrlIsEmptyNotNull() {
        // Empty means "the child derives its own endpoint" (Task 5), never a blank value
        // handed to the child as QWEN_REALTIME_URL.
        assertThat(new LiveProperties(false, null, null, "   ", 0, 0, false, null, null, 0).realtimeUrl()).isEmpty();
    }

    @Test
    void aDisabledLivePathCannotOpenABridge() {
        LiveProperties properties = new LiveProperties(false, null, null, "", 0, 0, false, null, null, 0);
        QwenLiveBridgeFactory factory = new QwenLiveBridgeFactory(
                properties, new LiveCredentials(properties), new ObjectMapper());

        assertThatThrownBy(() -> factory.open(session(), LiveBridgeListener.NONE))
                .isInstanceOf(LiveBridgeUnavailableException.class);
    }

    @Test
    void anEnabledLivePathWithoutCredentialsCannotOpenABridge() {
        LiveProperties properties = new LiveProperties(true, null,
                " ~/.aria-conductor/does-not-exist-anywhere.env ", "", 0, 0, false, null, null, 0);
        QwenLiveBridgeFactory factory = new QwenLiveBridgeFactory(
                properties, new LiveCredentials(properties), new ObjectMapper());

        assertThatThrownBy(() -> factory.open(session(), LiveBridgeListener.NONE))
                .isInstanceOf(LiveBridgeUnavailableException.class);
    }

    private static LiveSession session() {
        return new LiveSession("session", "conv", mock(WebSocketSession.class), null);
    }
}
