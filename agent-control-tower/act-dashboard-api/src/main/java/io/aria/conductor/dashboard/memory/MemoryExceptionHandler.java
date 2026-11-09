package io.aria.conductor.dashboard.memory;

import io.aria.conductor.memory.service.MemoryConflictException;
import io.aria.conductor.memory.service.MemoryNotFoundException;
import io.aria.conductor.memory.service.MemoryValidationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The memory REST error envelope (spec §9): exactly {@code ok=false}, {@code errorType},
 * {@code message} with the explicit codes {@code VALIDATION}, {@code NOT_FOUND},
 * {@code CONFLICT}, {@code FORBIDDEN}. Exception messages name a field or a state, never a
 * credential value, so the message can be returned verbatim.
 * <p>
 * Scoped to {@link MemoryController} and ordered ahead of the platform-wide advice so the
 * memory routes keep this contract instead of the generic {@code status/error} body.
 */
@RestControllerAdvice(assignableTypes = MemoryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MemoryExceptionHandler {

    static final String VALIDATION = "VALIDATION";
    static final String NOT_FOUND = "NOT_FOUND";
    static final String CONFLICT = "CONFLICT";
    static final String FORBIDDEN = "FORBIDDEN";

    /** Fixed conflict text: no constraint name, SQL statement, or row value is echoed. */
    static final String DATA_INTEGRITY_CONFLICT_MESSAGE =
            "the write conflicts with an existing memory row; re-read and retry with a distinct key";

    @ExceptionHandler(MemoryValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MemoryValidationException ex) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, VALIDATION, ex.getMessage());
    }

    /** A path/query value that cannot be converted (e.g. a malformed UUID) is input error. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String expected = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "the required type";
        return body(HttpStatus.UNPROCESSABLE_ENTITY, VALIDATION,
                "Invalid value for parameter '" + ex.getName() + "': expected " + expected);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, VALIDATION, "Invalid request body");
    }

    @ExceptionHandler(MemoryNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(MemoryNotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler({MemoryConflictException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<Map<String, Object>> handleConflict(RuntimeException ex) {
        return body(HttpStatus.CONFLICT, CONFLICT, ex.getMessage());
    }

    /**
     * A unique-index collision (a duplicate {@code kind+canonicalKey} entity, a duplicate
     * knowledge link) is a governed conflict, not a server error. The cause text names the
     * constraint, the SQL statement, and on h2 the offending values, so the response carries
     * a fixed message instead. Without this mapping the exception falls through to the
     * platform-wide advice (500, key/detail echoed under h2).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        return body(HttpStatus.CONFLICT, CONFLICT, DATA_INTEGRITY_CONFLICT_MESSAGE);
    }

    /** A worker credential or a failed origin/CSRF check is authenticated but not operator. */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(SecurityException ex) {
        String message = ex.getMessage() == null || ex.getMessage().isBlank()
                ? "Operator authority required" : ex.getMessage();
        return body(HttpStatus.FORBIDDEN, FORBIDDEN, message);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String errorType, String message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", false);
        envelope.put("errorType", errorType);
        envelope.put("message", message);
        return ResponseEntity.status(status).body(envelope);
    }
}
