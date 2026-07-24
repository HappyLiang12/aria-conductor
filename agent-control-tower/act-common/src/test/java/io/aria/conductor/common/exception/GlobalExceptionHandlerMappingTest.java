package io.aria.conductor.common.exception;

import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Full mapping-table coverage for {@link GlobalExceptionHandler}: every {@code @ExceptionHandler}
 * maps to the expected HTTP status and produces a body carrying the standard keys, plus the
 * h2-profile detail branches vs the generic non-h2 messages.
 */
class GlobalExceptionHandlerMappingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new MockEnvironment());

    private GlobalExceptionHandler h2() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("h2");
        return new GlobalExceptionHandler(env);
    }

    private void assertStandardBody(ResponseEntity<Map<String, Object>> response, HttpStatus status) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKeys("timestamp", "status", "error", "message");
        assertThat(body.get("status")).isEqualTo(status.value());
        assertThat(body.get("error")).isEqualTo(status.getReasonPhrase());
        assertThat((String) body.get("timestamp")).isNotBlank();
    }

    @Test
    void handleNotFound_maps404WithMessage() {
        var response = handler.handleNotFound(new ResourceNotFoundException("Agent", "abc"));
        assertStandardBody(response, HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("message")).isEqualTo("Agent not found with id: abc");
    }

    @Test
    void handleInvalidState_maps409() {
        var response = handler.handleInvalidState(
                new InvalidStateTransitionException("Run", "PENDING", "COMPLETED"));
        assertStandardBody(response, HttpStatus.CONFLICT);
        assertThat(response.getBody().get("message"))
                .isEqualTo("Invalid state transition for Run: PENDING -> COMPLETED");
    }

    @Test
    void handleBudgetExceeded_maps429() {
        var response = handler.handleBudgetExceeded(new BudgetExceededException(150, 100));
        assertStandardBody(response, HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().get("message")).isEqualTo("Token budget exceeded: used 150, limit 100");
    }

    @Test
    void handleApprovalTimeout_maps408() {
        UUID id = UUID.randomUUID();
        var response = handler.handleApprovalTimeout(new ApprovalTimeoutException(id));
        assertStandardBody(response, HttpStatus.REQUEST_TIMEOUT);
        assertThat(response.getBody().get("message")).isEqualTo("Approval " + id + " has timed out");
    }

    @Test
    void handleBadRequest_illegalArgument_maps400() {
        var response = handler.handleBadRequest(new IllegalArgumentException("bad input"));
        assertStandardBody(response, HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message")).isEqualTo("bad input");
    }

    @Test
    void handleIllegalState_maps409() {
        var response = handler.handleIllegalState(new IllegalStateException("tool disabled"));
        assertStandardBody(response, HttpStatus.CONFLICT);
        assertThat(response.getBody().get("message")).isEqualTo("tool disabled");
    }

    @Test
    void handleValidation_joinsFieldErrorsWithSemicolon() {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(
                new FieldError("agent", "name", "must not be blank"),
                new FieldError("agent", "type", "must not be null")));
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);

        var response = handler.handleValidation(ex);
        assertStandardBody(response, HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message"))
                .isEqualTo("name: must not be blank; type: must not be null");
    }

    @Test
    void handleValidation_withNoFieldErrors_fallsBackToValidationFailed() {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(List.of());
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);

        var response = handler.handleValidation(ex);
        assertStandardBody(response, HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message")).isEqualTo("Validation failed");
    }

    @Test
    void handleBadBody_nonH2_returnsGenericMessage() {
        HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);
        var response = handler.handleBadBody(ex);
        assertStandardBody(response, HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message")).isEqualTo("Invalid request body");
    }

    @Test
    void handleBadBody_h2_appendsMostSpecificCause() {
        HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);
        when(ex.getMostSpecificCause()).thenReturn(new RuntimeException("Unexpected token X"));
        var response = h2().handleBadBody(ex);
        assertStandardBody(response, HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message")).isEqualTo("Invalid request body: Unexpected token X");
    }

    @Test
    void handleBadBody_h2_withBlankCause_keepsGenericMessage() {
        HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);
        when(ex.getMostSpecificCause()).thenReturn(new RuntimeException("   "));
        var response = h2().handleBadBody(ex);
        assertThat(response.getBody().get("message")).isEqualTo("Invalid request body");
    }

    @Test
    void handleDataIntegrity_nonH2_maps500Generic() {
        var response = handler.handleDataIntegrity(
                new DataIntegrityViolationException("boom", new RuntimeException("unique violation")));
        assertStandardBody(response, HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().get("message")).isEqualTo("Data integrity violation");
    }

    @Test
    void handleDataIntegrity_h2_appendsDetail() {
        var response = h2().handleDataIntegrity(
                new DataIntegrityViolationException("boom", new RuntimeException("unique violation")));
        assertStandardBody(response, HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().get("message")).isEqualTo("Data integrity violation: unique violation");
    }

    @Test
    void handlePersistence_nonH2_maps500Generic() {
        var response = handler.handlePersistence(new PersistenceException("failure", new RuntimeException("sql error")));
        assertStandardBody(response, HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().get("message")).isEqualTo("Database error");
    }

    @Test
    void handlePersistence_h2_appendsCauseMessage() {
        var response = h2().handlePersistence(new PersistenceException("failure", new RuntimeException("sql error")));
        assertStandardBody(response, HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().get("message")).isEqualTo("Database error: sql error");
    }

    @Test
    void handleNoResourceFound_maps404WithPathMessage() {
        var response = handler.handleNoResourceFound(new NoResourceFoundException(HttpMethod.GET, "/api/v1/missing"));
        assertStandardBody(response, HttpStatus.NOT_FOUND);
        assertThat((String) response.getBody().get("message")).startsWith("Resource not found:");
    }

    @Test
    void handleGeneral_nonH2_returnsOpaqueMessage() {
        var response = handler.handleGeneral(new RuntimeException("secret detail"));
        assertStandardBody(response, HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().get("message")).isEqualTo("An unexpected error occurred");
    }

    @Test
    void handleGeneral_h2_returnsClassNameAndMessage() {
        var response = h2().handleGeneral(new IllegalArgumentException("detail here"));
        assertStandardBody(response, HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().get("message")).isEqualTo("IllegalArgumentException: detail here");
    }

    @Test
    void handleGeneral_h2_withNullMessage_usesNoDetails() {
        var response = h2().handleGeneral(new RuntimeException());
        assertThat(response.getBody().get("message")).isEqualTo("RuntimeException: no details");
    }
}
