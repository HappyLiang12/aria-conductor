package io.aria.conductor.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PickupRejectedExceptionTest {

    private final Environment env = new MockEnvironment();

    @Test
    void mapsToConflictWithCodeAndDetails() {
        PickupRejectedException ex = new PickupRejectedException(
                "NO_ELIGIBLE_AGENT",
                "No pickup-eligible agent",
                Map.of("evaluated", 4, "excluded", List.of(Map.of("name", "Aria", "reason", "RESERVED_OPERATOR_AGENT"))));

        ResponseEntity<Map<String, Object>> response =
                new GlobalExceptionHandler(env).handlePickupRejected(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody())
                .containsEntry("status", 409)
                .containsEntry("message", "No pickup-eligible agent")
                .containsEntry("code", "NO_ELIGIBLE_AGENT")
                .containsKey("details");
        assertThat(response.getBody().get("details").toString()).contains("RESERVED_OPERATOR_AGENT");
    }

    @Test
    void toleratesNullDetails() {
        PickupRejectedException ex = new PickupRejectedException("RUN_NOT_FOUND", "gone", null);

        ResponseEntity<Map<String, Object>> response =
                new GlobalExceptionHandler(mock(Environment.class)).handlePickupRejected(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "RUN_NOT_FOUND");
        assertThat(response.getBody().get("details")).isEqualTo(Map.of());
    }
}
