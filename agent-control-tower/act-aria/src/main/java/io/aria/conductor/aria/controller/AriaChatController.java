package io.aria.conductor.aria.controller;

import io.aria.conductor.agent.service.SystemConfigService;
import io.aria.conductor.aria.dto.AriaChatRequest;
import io.aria.conductor.aria.dto.AriaChatResponse;
import io.aria.conductor.aria.service.AriaChatAttachmentValidator;
import io.aria.conductor.aria.service.AriaService;
import io.aria.conductor.aria.service.AriaStreamService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/aria")
public class AriaChatController {

    private final AriaService ariaService;
    private final AriaStreamService ariaStreamService;
    private final SystemConfigService systemConfigService;

    public AriaChatController(AriaService ariaService, AriaStreamService ariaStreamService, SystemConfigService systemConfigService) {
        this.ariaService = ariaService;
        this.ariaStreamService = ariaStreamService;
        this.systemConfigService = systemConfigService;
    }

    @PostMapping("/chat")
    public ResponseEntity<AriaChatResponse> chat(@Valid @RequestBody AriaChatRequest request,
                                                 HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ariaService.chat(request, validated(request, httpRequest.getContentLengthLong())));
    }

    /**
     * Server-Sent Events streaming variant of {@link #chat}. Emits incremental
     * progress events ({@code thinking}, {@code tool_call}, {@code tool_result},
     * {@code message}, {@code done}, {@code error}) as the LLM tool-calling
     * loop progresses. The non-streaming endpoint above is preserved as a
     * fallback for clients that cannot consume SSE.
     *
     * <p>An attachment the validator refuses is answered before the stream
     * starts — a JSON {@code {"error": ...}} body with its status, never an
     * SSE frame, on both endpoints.
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chatStream(@Valid @RequestBody AriaChatRequest request,
                                                 HttpServletRequest httpRequest) {
        AriaChatAttachmentValidator.Validated attachments =
                validated(request, httpRequest.getContentLengthLong());
        long sseTimeoutMs = systemConfigService.getLong("aria.sse.timeout.ms", 600_000L, 30_000L, 3_600_000L);
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        ariaStreamService.streamChat(request, attachments, emitter);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    private static AriaChatAttachmentValidator.Validated validated(AriaChatRequest request, long contentLength) {
        AriaChatAttachmentValidator.Result result =
                AriaChatAttachmentValidator.validate(request.getAttachments(), contentLength);
        if (result.rejection() != null) {
            throw new AttachmentRejection(result.rejection());
        }
        return result.validated();
    }

    /** A pre-stream attachment refusal; answered as JSON, never as an SSE frame. */
    static final class AttachmentRejection extends RuntimeException {
        private final AriaChatAttachmentValidator.Rejection rejection;

        AttachmentRejection(AriaChatAttachmentValidator.Rejection rejection) {
            super(rejection.message());
            this.rejection = rejection;
        }

        AriaChatAttachmentValidator.Rejection rejection() {
            return rejection;
        }
    }

    @ExceptionHandler(AttachmentRejection.class)
    public ResponseEntity<Map<String, String>> rejected(AttachmentRejection rejection) {
        return ResponseEntity.status(rejection.rejection().status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", rejection.rejection().message()));
    }
}
