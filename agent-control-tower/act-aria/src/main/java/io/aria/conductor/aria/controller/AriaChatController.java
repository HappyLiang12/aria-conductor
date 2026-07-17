package io.aria.conductor.aria.controller;

import io.aria.conductor.agent.service.SystemConfigService;
import io.aria.conductor.aria.dto.AriaChatRequest;
import io.aria.conductor.aria.dto.AriaChatResponse;
import io.aria.conductor.aria.service.AriaService;
import io.aria.conductor.aria.service.AriaStreamService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    public ResponseEntity<AriaChatResponse> chat(@Valid @RequestBody AriaChatRequest request) {
        AriaChatResponse response = ariaService.chat(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Server-Sent Events streaming variant of {@link #chat}. Emits incremental
     * progress events ({@code thinking}, {@code tool_call}, {@code tool_result},
     * {@code message}, {@code done}, {@code error}) as the LLM tool-calling
     * loop progresses. The non-streaming endpoint above is preserved as a
     * fallback for clients that cannot consume SSE.
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody AriaChatRequest request) {
        long sseTimeoutMs = systemConfigService.getLong("aria.sse.timeout.ms", 600_000L, 30_000L, 3_600_000L);
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        ariaStreamService.streamChat(request, emitter);
        return emitter;
    }
}
