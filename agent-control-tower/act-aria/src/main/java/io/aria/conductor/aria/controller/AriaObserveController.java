package io.aria.conductor.aria.controller;

import io.aria.conductor.agent.service.SystemConfigService;
import io.aria.conductor.aria.dto.AriaObserveRequest;
import io.aria.conductor.aria.service.AriaObservationValidator;
import io.aria.conductor.aria.service.AriaObserveService;
import io.aria.conductor.execution.security.OperatorAuthorityResolver;
import io.aria.conductor.execution.security.OperatorSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Optional;

/**
 * The glasses' one entry point: an observation becomes an Aria turn. Every
 * pre-stream refusal is JSON with its own status, so the phone never has to
 * read an SSE error for a request that never became a run.
 *
 * <p>The stream is the method's declared return type, not one branch of a
 * {@code ResponseEntity<?>}: Spring selects its return-value handler from the
 * declared type, and only a {@code ResponseBodyEmitter} (here {@link SseEmitter})
 * starts the async response. Refusals therefore leave the handler as a
 * {@link Refusal} and are rendered as JSON by {@link #refuse(Refusal)}.
 */
@RestController
@RequestMapping("/api/v1/aria")
public class AriaObserveController {

    private final AriaObserveService observeService;
    private final OperatorAuthorityResolver operatorAuthority;
    private final SystemConfigService systemConfigService;

    public AriaObserveController(AriaObserveService observeService,
                                 OperatorAuthorityResolver operatorAuthority,
                                 SystemConfigService systemConfigService) {
        this.observeService = observeService;
        this.operatorAuthority = operatorAuthority;
        this.systemConfigService = systemConfigService;
    }

    @PostMapping(value = "/observe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> observe(@RequestBody(required = false) AriaObserveRequest request,
                                              HttpServletRequest httpRequest) {
        try {
            operatorAuthority.resolveOperator(httpRequest, true);
        } catch (OperatorSessionService.ForbiddenMutationException e) {
            throw new Refusal(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (SecurityException e) {
            throw new Refusal(HttpStatus.UNAUTHORIZED, e.getMessage());
        }

        AriaObservationValidator.Result validated =
                AriaObservationValidator.validate(request, httpRequest.getContentLengthLong());
        if (validated.isRejected()) {
            AriaObservationValidator.Rejection rejection = validated.rejection();
            throw new Refusal(rejection.status(), rejection.message());
        }

        Optional<AriaObservationValidator.Rejection> preflight = observeService.preflight(request);
        if (preflight.isPresent()) {
            throw new Refusal(preflight.get().status(), preflight.get().message());
        }

        long sseTimeoutMs = systemConfigService.getLong(
                "aria.sse.timeout.ms", 600_000L, 30_000L, 3_600_000L);
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        observeService.stream(request, validated.image(), emitter);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    /**
     * A pre-stream refusal is JSON even when the client asked for the stream:
     * the concrete content type is set here so negotiation against
     * {@code Accept: text/event-stream} cannot turn 403/415/409 into a 406.
     */
    @ExceptionHandler(Refusal.class)
    public ResponseEntity<Map<String, String>> refuse(Refusal refusal) {
        return ResponseEntity.status(refusal.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", refusal.getMessage() == null ? "error" : refusal.getMessage()));
    }

    /**
     * A request that never became a run: answered as JSON with its own status
     * instead of an SSE error frame.
     */
    static final class Refusal extends RuntimeException {

        private final HttpStatus status;

        Refusal(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        HttpStatus status() {
            return status;
        }
    }
}
