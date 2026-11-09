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

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
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

    /**
     * Fixed validation text for an integrity failure that is not a key collision: no column
     * name, length, SQL statement, or driver class is echoed.
     */
    static final String DATA_INTEGRITY_VALIDATION_MESSAGE =
            "the request data does not fit a memory field; check required values and their maximum lengths";

    /** A bounded walk over the cause chain; diagnostics are nested at most a few levels deep. */
    private static final int MAX_CAUSE_DEPTH = 16;

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
     * knowledge link, a repeated {@code proposal_hash}) is a governed conflict. Any other
     * integrity failure — a value that overflows a V81 VARCHAR column, a NOT NULL rule —
     * is input the row cannot hold, so it is a validation error, not a conflict. Both paths
     * return a fixed message: the cause text names the constraint, the SQL statement, and
     * on h2 the offending values. Without this mapping the exception falls through to the
     * platform-wide advice (500, key/detail echoed under h2).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        if (isUniqueCollision(ex)) {
            return body(HttpStatus.CONFLICT, CONFLICT, DATA_INTEGRITY_CONFLICT_MESSAGE);
        }
        return body(HttpStatus.UNPROCESSABLE_ENTITY, VALIDATION, DATA_INTEGRITY_VALIDATION_MESSAGE);
    }

    /**
     * H2 reports a unique violation as SQLState 23505; MySQL/MariaDB report duplicate-key
     * vendor code 1062 under the generic SQLState 23000. When the driver state is absent
     * (a translated or synthetic exception), the unique-index wording is the only marker.
     */
    private static boolean isUniqueCollision(DataIntegrityViolationException failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof SQLException sql && isDuplicateKey(sql)) {
                return true;
            }
            if (mentionsUniqueIndex(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isDuplicateKey(SQLException sql) {
        return "23505".equals(sql.getSQLState()) || sql.getErrorCode() == 1062;
    }

    private static boolean mentionsUniqueIndex(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("unique") || lower.contains("duplicate entry") || lower.contains("duplicate key");
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
