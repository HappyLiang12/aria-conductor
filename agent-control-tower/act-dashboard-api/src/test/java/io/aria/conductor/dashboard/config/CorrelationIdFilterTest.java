package io.aria.conductor.dashboard.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Verifies the {@link CorrelationIdFilter} behaviour:
 *
 * <ul>
 *   <li>An incoming {@code X-Correlation-ID} header is echoed in the response.</li>
 *   <li>If the header is absent or blank, the filter generates a UUID and
 *       returns it on the response.</li>
 *   <li>The downstream filter chain is invoked exactly once.</li>
 * </ul>
 */
class CorrelationIdFilterTest {

    private static final String HEADER = "X-Correlation-ID";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void incomingHeader_isEchoedInResponse() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, "test-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(HEADER)).isEqualTo("test-123");
        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    void missingHeader_generatesUuid() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String generated = response.getHeader(HEADER);
        assertThat(generated).isNotBlank();
        // Should be a parseable UUID.
        assertThat(UUID.fromString(generated)).isNotNull();
        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    void blankHeader_generatesUuid() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String generated = response.getHeader(HEADER);
        assertThat(generated).isNotBlank();
        assertThat(UUID.fromString(generated)).isNotNull();
    }
}
