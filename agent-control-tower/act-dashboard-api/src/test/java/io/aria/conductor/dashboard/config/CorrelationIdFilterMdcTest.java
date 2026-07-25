package io.aria.conductor.dashboard.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Complements {@code CorrelationIdFilterTest} by verifying the MDC lifecycle: the correlation id is
 * present in the SLF4J {@link MDC} while the downstream chain runs, and is removed afterwards
 * (in the {@code finally} block) regardless of the header source.
 */
class CorrelationIdFilterMdcTest {

    private static final String HEADER = "X-Correlation-ID";
    private static final String MDC_KEY = "correlationId";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void mdc_isPopulatedDuringChainAndClearedAfterwards() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, "corr-42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> mdcDuringChain.set(MDC.get(MDC_KEY));

        filter.doFilter(request, response, chain);

        assertThat(mdcDuringChain.get()).isEqualTo("corr-42");
        assertThat(MDC.get(MDC_KEY)).isNull();
    }

    @Test
    void mdc_usesGeneratedIdWhenHeaderMissing_andMatchesResponseHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> mdcDuringChain.set(MDC.get(MDC_KEY));

        filter.doFilter(request, response, chain);

        // The MDC value seen by the chain must equal the id echoed on the response.
        assertThat(mdcDuringChain.get()).isNotBlank();
        assertThat(mdcDuringChain.get()).isEqualTo(response.getHeader(HEADER));
        assertThat(MDC.get(MDC_KEY)).isNull();
    }

    @Test
    void mdc_isClearedEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, "corr-boom");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            throw new ServletException("downstream failure");
        };

        try {
            filter.doFilter(request, response, chain);
        } catch (ServletException | IOException expected) {
            // propagated after the finally block runs
        }

        assertThat(MDC.get(MDC_KEY)).isNull();
    }
}
