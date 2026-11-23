package io.aria.conductor.aria.live;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class LiveUpgradeFilterTest {

    private final LiveUpgradeFilter filter = new LiveUpgradeFilter();

    @Test
    void plainGetOnTheLivePathIsUpgradeRequired() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", LiveUpgradeFilter.PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(426);
        assertThat(response.getHeader("Upgrade")).isEqualTo("websocket");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void anUpgradeRequestReachesTheHandler() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", LiveUpgradeFilter.PATH);
        request.addHeader("Upgrade", "WebSocket");
        request.addHeader("Connection", "keep-alive, Upgrade");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void upgradeHeaderWithoutConnectionUpgradeIsNotAHandshake() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", LiveUpgradeFilter.PATH);
        request.addHeader("Upgrade", "websocket");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(426);
    }

    @Test
    void aPostOnTheLivePathIsNotAnsweredWith426() throws Exception {
        // The contract's plain-request case is a GET; any other method belongs to
        // the application (404/405), not to this filter.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", LiveUpgradeFilter.PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isNotEqualTo(426);
    }

    @Test
    void otherPathsAreUntouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/aria/live/extra");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
