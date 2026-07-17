package io.aria.conductor.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals("An unexpected error occurred", response.getBody().get("message"));
    }

    @Test
    void buildResponse_containsExpectedKeys() {
        ResponseEntity<Map<String, Object>> response = handler.handleGeneral(new RuntimeException("x"));

        Map<String, Object> body = response.getBody();
        assertEquals(500, body.get("status"));
        assertEquals("Internal Server Error", body.get("error"));
        assertEquals("An unexpected error occurred", body.get("message"));
        //noinspection DataFlowIssue
        assertEquals(true, body.containsKey("timestamp"));
    }
}
