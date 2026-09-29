package io.aria.conductor.dashboard.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dev-server CORS policy must accept both loopback spellings a browser can
 * use for the dashboard: {@code http://localhost:*} and {@code http://127.0.0.1:*}.
 * The numeric form previously fell outside {@code allowedOriginPatterns}, so
 * every write (POST/PUT/DELETE) answered 403 "Invalid CORS request" while reads
 * still worked — the failure depended on which spelling the operator typed.
 *
 * <p>Both registered configurers cover {@code /**}, so each is exercised on its
 * own context: the dashboard module and act-common both have to allow loopback.
 */
class LocalOriginCorsConfigTest {

    private static final String LOCALHOST_ORIGIN = "http://localhost:5173";
    private static final String LOOPBACK_IP_ORIGIN = "http://127.0.0.1:5173";
    private static final String FOREIGN_ORIGIN = "http://evil.example.com";

    @Test
    void dashboardWebConfig_acceptsBothLoopbackSpellingsAndRefusesForeignOrigins() throws Exception {
        assertLoopbackPreflight(mvcWith(WebConfig.class), LOCALHOST_ORIGIN);
        assertLoopbackPreflight(mvcWith(WebConfig.class), LOOPBACK_IP_ORIGIN);
        assertPreflightRefused(mvcWith(WebConfig.class), FOREIGN_ORIGIN);
    }

    @Test
    void commonWebConfig_acceptsBothLoopbackSpellingsAndRefusesForeignOrigins() throws Exception {
        assertLoopbackPreflight(mvcWith(io.aria.conductor.common.config.WebConfig.class), LOCALHOST_ORIGIN);
        assertLoopbackPreflight(mvcWith(io.aria.conductor.common.config.WebConfig.class), LOOPBACK_IP_ORIGIN);
        assertPreflightRefused(mvcWith(io.aria.conductor.common.config.WebConfig.class), FOREIGN_ORIGIN);
    }

    private void assertLoopbackPreflight(MockMvc mockMvc, String origin) throws Exception {
        mockMvc.perform(options("/cors-probe")
                        .header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin));
    }

    private void assertPreflightRefused(MockMvc mockMvc, String origin) throws Exception {
        mockMvc.perform(options("/cors-probe")
                        .header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }

    private MockMvc mvcWith(Class<?>... configurers) {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(EnableWebMvcConfiguration.class);
        context.register(configurers);
        context.register(CorsProbeController.class);
        context.refresh();
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    @EnableWebMvc
    static class EnableWebMvcConfiguration {
    }

    @RestController
    static class CorsProbeController {

        @GetMapping("/cors-probe")
        String probe() {
            return "pong";
        }
    }
}
