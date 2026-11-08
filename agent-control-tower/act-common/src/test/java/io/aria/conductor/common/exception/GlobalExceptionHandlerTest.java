package io.aria.conductor.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final Environment mockEnv = mock(Environment.class);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(mockEnv);

    {
        when(mockEnv.getActiveProfiles()).thenReturn(new String[0]);
    }

    @Test
    void handleNoResourceFound_returns404_withPathMessage() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/api/v1/nonexistent");
        ResponseEntity<Map<String, Object>> response = handler.handleNoResourceFound(ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("Resource not found: No static resource /api/v1/nonexistent.", response.getBody().get("message"));
    }

    @Test
    void handleGeneral_returns500() {
        Exception ex = new RuntimeException("test error");
        ResponseEntity<Map<String, Object>> response = handler.handleGeneral(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("An unexpected error occurred. Check the backend log for the stack trace.",
                response.getBody().get("message"));
    }

    /**
     * F27: a stale class loader view used to escape every handler (an Error is
     * not an Exception) and the container rendered a raw ServletException 500.
     * The operator gets a named, actionable 503 instead: restart the backend
     * from the current checkout.
     */
    @Test
    void handleLinkageError_returns503_withStableActionableContract() {
        LinkageError ex = new NoClassDefFoundError(
                "io/aria/conductor/common/model/ToolCallResponse");

        ResponseEntity<Map<String, Object>> response = handler.handleLinkageError(ex);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        //noinspection DataFlowIssue
        assertEquals(503, body.get("status"));
        assertEquals("Service Unavailable", body.get("error"));
        assertEquals("BACKEND_MODULE_STALE", body.get("code"));
        assertEquals("RUNTIME", body.get("surface"));
        //noinspection DataFlowIssue
        assertTrue(body.containsKey("timestamp"));
        String message = String.valueOf(body.get("message"));
        assertTrue(message.contains("do not match the current checkout"), message);
        assertTrue(message.contains("Restart the backend from the current checkout"), message);
        assertTrue(message.contains("a module build while the backend is running"), message);
        assertFalse(message.contains("ToolCallResponse"),
                "the class detail is only shown on the h2 profile: " + message);
    }

    @Test
    void handleLinkageError_includesTheClassDetailOnH2() {
        GlobalExceptionHandler h2Handler = handlerWithProfiles("h2");
        LinkageError ex = new NoClassDefFoundError(
                "io/aria/conductor/common/model/TrajectoryResponse");

        Map<String, Object> body = h2Handler.handleLinkageError(ex).getBody();

        //noinspection DataFlowIssue
        String message = String.valueOf(body.get("message"));
        assertTrue(message.contains("NoClassDefFoundError: "
                + "io/aria/conductor/common/model/TrajectoryResponse"), message);
        assertEquals("BACKEND_MODULE_STALE", body.get("code"));
        assertEquals("RUNTIME", body.get("surface"));
    }

    private static GlobalExceptionHandler handlerWithProfiles(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new GlobalExceptionHandler(env);
    }

    @Test
    void buildResponse_containsExpectedKeys() {
        ResponseEntity<Map<String, Object>> response = handler.handleGeneral(new RuntimeException("x"));

        Map<String, Object> body = response.getBody();
        assertEquals(500, body.get("status"));
        assertEquals("Internal Server Error", body.get("error"));
        assertEquals("An unexpected error occurred. Check the backend log for the stack trace.",
                body.get("message"));
        //noinspection DataFlowIssue
        assertEquals(true, body.containsKey("timestamp"));
    }
}
