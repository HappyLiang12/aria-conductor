package io.aria.conductor.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.exception.GlobalExceptionHandler;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Base class for lightweight controller slice tests.
 * <p>
 * Uses MockMvc standalone setup (no Spring context) with the shared
 * {@link GlobalExceptionHandler} pre-registered, so controller tests verify
 * the exact error mapping production uses without paying context startup cost.
 * <p>
 * Usage:
 * <pre>{@code
 * class MyControllerTest extends WebMvcTestBase {
 *     private final MyService service = mock(MyService.class);
 *     private final MockMvc mvc = mockMvcFor(new MyController(service));
 * }
 * }</pre>
 */
public abstract class WebMvcTestBase {

    /** Shared mapper configured like production (JavaTime module etc. via classpath discovery). */
    protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    /**
     * Build a standalone MockMvc for the given controllers with the production
     * {@link GlobalExceptionHandler} as controller advice.
     */
    protected MockMvc mockMvcFor(Object... controllers) {
        return MockMvcBuilders.standaloneSetup(controllers)
                .setControllerAdvice(new GlobalExceptionHandler(new MockEnvironment()))
                .build();
    }

    /** Serialize an object to JSON for request bodies. */
    protected static String json(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize test fixture to JSON", e);
        }
    }
}
