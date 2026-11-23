package io.aria.conductor.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.AuditEventRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.AuditEvent;
import io.aria.conductor.common.model.HealthStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The live path end to end, without Qwen (spec §"Testing"): a real operator upgrade, a real
 * {@code hello}/{@code ready}, a scripted bridge child behind the production
 * {@code QwenLiveBridgeFactory}, and the phone's frames read back in order.
 *
 * <p>What this proves is the seam the unit tiers cannot. The scripted session emits the
 * contract's caption stream, one {@code get_time} call answered by the real tool executor,
 * two audio deltas, a barge-in and a trailing delta, then the turn end; the assertions are
 * the phone's ordered events, the one lazy run row with its audit row, the image-before-commit
 * order at the child, the {@code interrupted}-before-audio rule, the fatal close codes, and a
 * privacy scan of every log line and of the child's own receipt.
 *
 * <p>The child's switches cannot travel through its environment the way the real deployment
 * would: {@code QwenLiveBridgeProcess} clears the parent environment on purpose (spec L2), so
 * a generated driver sets the fake's {@code FAKE_BRIDGE_*} variables from a per-test config
 * file before importing it. Everything else — the factory, the spawn, the stdio JSON, the
 * redactor, the run store — is the production path.
 */
@Import(NoopLlmTestConfig.class)
class LiveEndToEndIntegrationTest extends BaseH2IntegrationTest {

    static final String OPERATOR_CREDENTIAL = "live-e2e-operator-credential-4b8f";
    /** Synthetic values: the privacy scan proves neither ever reaches a log line or receipt. */
    static final String BRIDGE_KEY = "sk-live-e2e-0123456789abcdef0123456789abcdef";
    static final String BRIDGE_WORKSPACE = "ws-live-e2e-singapore-0001";

    private static final String PATH = "/api/v1/aria/live";
    private static final String PROTOCOL = "aria-live.v1";
    private static final String CALL_ID = "call-scripted-1";
    /** A legal 200 KiB base64 payload (decodes cleanly; never a real JPEG). */
    private static final String IMAGE_200_KIB = "QUFB".repeat((200 * 1024) / 4);
    /** 300 KiB of base64: past the 256 KiB validator limit, inside the 320 KiB envelope. */
    private static final String IMAGE_300_KIB = "QUFB".repeat((300 * 1024) / 4);
    private static final int AUDIO_FILL_A = 0x11;
    private static final int AUDIO_FILL_B = 0x22;
    private static final int AUDIO_FILL_AFTER_INTERRUPT = 0x33;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path HARNESS = harnessDirectory();
    private static final Path FAKE_BRIDGE = locateFakeBridge();
    private static final Path DRIVER = writeDriver(HARNESS);
    private static final Path CREDENTIALS = writeCredentials(HARNESS);
    private static final Path CONFIG = HARNESS.resolve("bridge-config.json");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AgentRepository agentRepository;

    @Autowired
    AuditEventRepository auditRepository;

    private Logger liveLogger;
    private Level previousLevel;
    private LineCapture liveLines;

    @DynamicPropertySource
    static void liveProperties(DynamicPropertyRegistry registry) {
        registry.add("aria.live.enabled", () -> "true");
        registry.add("aria.live.bridge-script", DRIVER::toString);
        registry.add("aria.live.credentials-file", CREDENTIALS::toString);
        registry.add("aria.operator.bearer-token", () -> OPERATOR_CREDENTIAL);
        registry.add("aria.operator.trusted-proxies", () -> "127.0.0.1");
    }

    /** {@code runs.agent_id} is a foreign key; the class cleanup truncates the Aria row. */
    @BeforeEach
    void ensureAriaAgentAndCaptureLogs() {
        if (agentRepository.findById(AriaConstants.ARIA_AGENT_ID).isEmpty()) {
            agentRepository.save(Agent.builder()
                    .id(AriaConstants.ARIA_AGENT_ID).name("Aria").description("live end-to-end test")
                    .agentType(AgentType.ADK).role("assistant").model("gpt-4o-mini")
                    .provider("openai").config("{}").healthStatus(HealthStatus.HEALTHY)
                    .createdAt(Instant.now()).build());
        }
        liveLogger = (Logger) LoggerFactory.getLogger("io.aria.conductor.aria.live");
        previousLevel = liveLogger.getLevel();
        // The child's stderr reaches this logger at debug, so the scan must see debug lines.
        liveLogger.setLevel(Level.DEBUG);
        liveLines = new LineCapture();
        liveLines.start();
        liveLogger.addAppender(liveLines);
    }

    @AfterEach
    void releaseLogs() {
        if (liveLogger != null && liveLines != null) {
            liveLogger.detachAppender(liveLines);
            liveLines.stop();
            liveLogger.setLevel(previousLevel);
        }
    }

    // --- the scripted turn ------------------------------------------------------------------

    @Test
    void aScriptedTurnCrossesTheWholeSeam() throws Exception {
        String conversationId = "live-e2e-" + UUID.randomUUID().toString().substring(0, 8);
        Path receipt = configureBridge("scripted", Map.of());
        Recorder recorder = new Recorder();
        WebSocket socket = connect(recorder);
        try {
            socket.sendText(hello(conversationId), true).get(5, TimeUnit.SECONDS);
            await("ready", () -> firstEvent(recorder, "ready") != null);
            await("the bridge to report its environment", () -> firstEvent(recorder, "env") != null);

            socket.sendBinary(ByteBuffer.wrap(new byte[640]), true).get(5, TimeUnit.SECONDS);
            socket.sendText(frameMessage(IMAGE_200_KIB), true).get(5, TimeUnit.SECONDS);
            socket.sendText("{\"type\":\"commit\",\"image\":true}", true).get(5, TimeUnit.SECONDS);
            socket.sendText("{\"type\":\"text\",\"text\":\"what is the time?\"}", true).get(5, TimeUnit.SECONDS);

            await("the turn's run to finish", () -> firstEvent(recorder, "run_event", event ->
                    "finished".equals(event.path("state").asText())) != null);

            assertReady(recorder);
            assertSpeechEnd(recorder);
            assertTranscripts(recorder);
            assertToolAndRunEvents(recorder);
            assertBargeInOrder(recorder);

            // The child rewrites its receipt after every line; wait for the turn end to be in it
            // rather than racing the last write.
            await("the child's receipt to record the turn end",
                    () -> "turn_done".equals(lastSentLabel(readReceipt(receipt))));
            JsonNode receiptJson = readReceipt(receipt);
            assertImagePrecededCommit(receiptJson);
            assertTurnDoneIsTheBridgesLastLine(receiptJson);

            assertRunAndAudit(conversationId);

            assertNoSecrets(liveLines.lines);
            assertReceiptHasNoPayload(receipt);
        } finally {
            socket.abort();
        }
    }

    @Test
    void anOversizedFrameIsRefusedAndTheSocketStaysUsable() throws Exception {
        String conversationId = "live-e2e-" + UUID.randomUUID().toString().substring(0, 8);
        Path receipt = configureBridge("plain", Map.of());
        Recorder recorder = new Recorder();
        WebSocket socket = connect(recorder);
        try {
            socket.sendText(hello(conversationId), true).get(5, TimeUnit.SECONDS);
            await("ready", () -> firstEvent(recorder, "ready") != null);
            await("the bridge to report its environment", () -> firstEvent(recorder, "env") != null);

            socket.sendText(frameMessage(IMAGE_300_KIB), true).get(5, TimeUnit.SECONDS);

            await("the frame refusal", () -> firstEvent(recorder, "error") != null);
            JsonNode error = firstEvent(recorder, "error");
            assertThat(error.path("v").asInt()).isEqualTo(1);
            assertThat(error.path("code").asText()).isEqualTo("frame_too_large");
            assertThat(error.path("retryable").asBoolean(true)).isFalse();

            // The socket is still the phone's: the next line reaches the child and is answered.
            socket.sendText("{\"type\":\"text\",\"text\":\"still here\"}", true).get(5, TimeUnit.SECONDS);
            await("the child to answer the next line", () -> events(recorder, "ack").stream()
                    .anyMatch(event -> "text".equals(event.path("of").asText())));

            assertThat(recorder.closeCode()).as("a refused frame never tears the socket down").isNull();
            List<JsonNode> received = new ArrayList<>();
            MAPPER.readTree(Files.readString(receipt)).path("received").forEach(received::add);
            assertThat(received).noneMatch(entry ->
                    "image".equals(entry.path("type").asText()) || "commit".equals(entry.path("type").asText()));
        } finally {
            socket.abort();
        }
    }

    // --- bridge fatal close codes -------------------------------------------------------------

    @Test
    void aFailedReopenCloses4012WithoutASecondError() throws Exception {
        Recorder recorder = fatalTurn(4012, true);

        await("close 4012", () -> recorder.closeCode() != null);
        assertThat(recorder.closeCode()).isEqualTo(4012);
        List<JsonNode> errors = events(recorder, "error");
        assertThat(errors).as("the bridge sent session_expired itself; one error per failure").hasSize(1);
        assertThat(errors.get(0).path("code").asText()).isEqualTo("session_expired");
        assertThat(errors.get(0).path("retryable").asBoolean()).isTrue();
    }

    @Test
    void aFatalWithoutAPriorErrorIsAnnouncedThenClosed1011() throws Exception {
        Recorder recorder = fatalTurn(1011, false);

        await("close 1011", () -> recorder.closeCode() != null);
        assertThat(recorder.closeCode()).isEqualTo(1011);
        List<JsonNode> errors = events(recorder, "error");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).path("code").asText()).isEqualTo("bridge_internal");
        assertThat(errors.get(0).path("retryable").asBoolean()).isTrue();
    }

    @Test
    void aFatalAuthRefusalIsAnnouncedThenClosed4001() throws Exception {
        Recorder recorder = fatalTurn(4001, false);

        await("close 4001", () -> recorder.closeCode() != null);
        assertThat(recorder.closeCode()).isEqualTo(4001);
        List<JsonNode> errors = events(recorder, "error");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).path("code").asText()).isEqualTo("auth_failed");
        assertThat(errors.get(0).path("retryable").asBoolean(true)).isFalse();
    }

    @Test
    void aFatalCapabilityRefusalIsAnnouncedThenClosed4002() throws Exception {
        Recorder recorder = fatalTurn(4002, false);

        await("close 4002", () -> recorder.closeCode() != null);
        assertThat(recorder.closeCode()).isEqualTo(4002);
        List<JsonNode> errors = events(recorder, "error");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).path("code").asText()).isEqualTo("capability_mismatch");
        assertThat(errors.get(0).path("retryable").asBoolean()).isFalse();
    }

    // --- assertions ---------------------------------------------------------------------------

    private void assertReady(Recorder recorder) {
        JsonNode ready = firstEvent(recorder, "ready");
        assertThat(ready.path("v").asInt()).isEqualTo(1);
        assertThat(ready.path("maxSessionSec").asInt()).isEqualTo(7200);
        assertThat(ready.path("sessionId").asText()).isNotBlank();
        assertThat(ready.path("model").asText()).isEqualTo("qwen3.8-omni-flash-realtime");
    }

    private void assertSpeechEnd(Recorder recorder) {
        JsonNode speechEnd = firstEvent(recorder, "speech_end");
        assertThat(speechEnd).as("the child's speech_end reaches the phone").isNotNull();
        long tMs = speechEnd.path("tMs").asLong();
        // A plausible epoch: after 2020 and within two minutes of this machine's clock.
        assertThat(tMs).isGreaterThan(1_600_000_000_000L);
        assertThat(Math.abs(System.currentTimeMillis() - tMs)).isLessThan(120_000L);
    }

    private void assertTranscripts(Recorder recorder) {
        List<JsonNode> input = events(recorder, "input_transcript");
        assertThat(input).hasSizeGreaterThanOrEqualTo(2);
        assertThat(input.stream().map(event -> event.path("seq").asInt()).toList()).containsExactly(1, 2);
        assertThat(input.get(input.size() - 1).path("final").asBoolean()).isTrue();

        List<JsonNode> output = events(recorder, "output_transcript");
        assertThat(output).hasSizeGreaterThanOrEqualTo(3);
        assertThat(output.stream().map(event -> event.path("seq").asInt()).toList()).containsExactly(1, 2, 3);
        assertThat(output).anyMatch(event -> event.path("final").asBoolean());
    }

    private void assertToolAndRunEvents(Recorder recorder) {
        List<JsonNode> statuses = events(recorder, "tool_status");
        assertThat(statuses).anyMatch(event -> CALL_ID.equals(event.path("id").asText())
                && "get_time".equals(event.path("toolName").asText())
                && "running".equals(event.path("status").asText()));
        assertThat(statuses).anyMatch(event -> CALL_ID.equals(event.path("id").asText())
                && "done".equals(event.path("status").asText()));

        List<JsonNode> runEvents = events(recorder, "run_event");
        JsonNode started = runEvents.stream()
                .filter(event -> "started".equals(event.path("state").asText())).findFirst().orElse(null);
        JsonNode finished = runEvents.stream()
                .filter(event -> "finished".equals(event.path("state").asText())).findFirst().orElse(null);
        assertThat(started).as("the lazy run is announced before the tool runs").isNotNull();
        assertThat(finished).as("the turn end settles the run as finished").isNotNull();
        assertThat(started.path("runId").asText()).isNotBlank();
        assertThat(finished.path("runId").asText()).isEqualTo(started.path("runId").asText());
        // The turn end is consumed by Aria, so the phone must not see the bridge literal.
        assertThat(events(recorder, "turn_done")).isEmpty();
    }

    private void assertBargeInOrder(Recorder recorder) {
        List<Frame> frames = List.copyOf(recorder.frames);
        int interruptedIndex = indexOfTyped(frames, frame -> frame.event() != null
                && "interrupted".equals(frame.event().path("type").asText()));
        assertThat(interruptedIndex).as("the barge-in reaches the phone").isGreaterThanOrEqualTo(0);
        assertThat(frames.get(interruptedIndex).event().path("reason").asText()).isEqualTo("user_speech");

        int firstAnswerAudio = indexOfTyped(frames, frame -> isFill(frame, AUDIO_FILL_A));
        int secondAnswerAudio = indexOfTyped(frames, frame -> isFill(frame, AUDIO_FILL_B));
        int trailingAudio = indexOfTyped(frames, frame -> isFill(frame, AUDIO_FILL_AFTER_INTERRUPT));
        assertThat(firstAnswerAudio).as("the answer's first delta").isGreaterThanOrEqualTo(0);
        assertThat(secondAnswerAudio).as("the answer's second delta").isGreaterThanOrEqualTo(0);
        assertThat(firstAnswerAudio).isLessThan(interruptedIndex);
        assertThat(secondAnswerAudio).isLessThan(interruptedIndex);
        // Spec X6: no audio delta may be delivered before `interrupted`. The child sends one
        // after it on purpose; the phone drops it, but its place in the order is the assertion.
        assertThat(trailingAudio).as("the post-barge-in delta still follows the interrupt")
                .isGreaterThan(interruptedIndex);
    }

    private void assertImagePrecededCommit(JsonNode receipt) {
        List<JsonNode> received = new ArrayList<>();
        receipt.path("received").forEach(received::add);
        int imageIndex = indexOfTyped(received, entry -> "image".equals(entry.path("type").asText()));
        int commitIndex = indexOfTyped(received, entry -> "commit".equals(entry.path("type").asText()));
        assertThat(imageIndex).as("the image reached the bridge").isGreaterThanOrEqualTo(0);
        assertThat(commitIndex).as("the commit reached the bridge").isGreaterThan(imageIndex);
        assertThat(received.get(commitIndex).path("image").asBoolean()).isTrue();
        assertThat(received.get(commitIndex).path("imagePreceded").asBoolean())
                .as("spec X10: audio -> image -> commit").isTrue();
        assertThat(received.get(imageIndex).path("chars").asInt()).isEqualTo(IMAGE_200_KIB.length());
        assertThat(received).anyMatch(entry -> "tool_result".equals(entry.path("type").asText())
                && entry.path("ok").asBoolean());
    }

    private void assertTurnDoneIsTheBridgesLastLine(JsonNode receipt) {
        List<JsonNode> sent = new ArrayList<>();
        receipt.path("sent").forEach(sent::add);
        List<String> labels = sent.stream().map(LiveEndToEndIntegrationTest::label).toList();
        assertThat(labels).contains("speech_end", "input_transcript", "output_transcript", "tool_call");
        int toolCall = labels.indexOf("tool_call");
        assertThat(toolCall).isLessThan(labels.lastIndexOf("audio"));
        assertThat(labels.get(labels.size() - 1))
                .as("the child's last line is the turn end Java consumes").isEqualTo("turn_done");
        // Two deltas, the barge-in, then the deliberately trailing delta.
        int interrupted = labels.indexOf("interrupted");
        assertThat(interrupted).isGreaterThan(toolCall);
        assertThat(labels.stream().filter("audio"::equals).count()).isGreaterThanOrEqualTo(2);
        assertThat(labels.subList(interrupted, labels.size())).contains("audio");
    }

    private void assertRunAndAudit(String conversationId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, prompt_seed, status from runs where conversation_id = ?", conversationId);
        assertThat(rows).as("one lazy run row for the tool-using turn").hasSize(1);
        assertThat(rows.get(0).get("prompt_seed")).isEqualTo("[live] tool: get_time");
        assertThat(rows.get(0).get("status")).isEqualTo("COMPLETED");
        String runId = rows.get(0).get("id").toString();

        List<AuditEvent> audit = auditRepository.findByResourceTypeAndResourceId("Run", runId);
        assertThat(audit).anyMatch(event -> "START".equals(event.getAction())
                && "Run started (live tool turn)".equals(event.getDetails()));
    }

    private void assertNoSecrets(List<String> lines) {
        assertThat(lines).as("the live logger is actually captured").isNotEmpty();
        String joined = String.join("\n", lines);
        assertThat(joined).doesNotContain("dataBase64");
        assertThat(joined).doesNotContain(OPERATOR_CREDENTIAL);
        assertThat(joined).doesNotContain(BRIDGE_KEY);
        assertThat(joined).doesNotContain(BRIDGE_WORKSPACE);
        assertThat(joined).doesNotContain(IMAGE_200_KIB.substring(0, 64));
    }

    private void assertReceiptHasNoPayload(Path receipt) {
        String text = read(receipt);
        assertThat(text).doesNotContain("dataBase64");
        assertThat(text).doesNotContain(OPERATOR_CREDENTIAL);
        assertThat(text).doesNotContain(BRIDGE_KEY);
        assertThat(text).doesNotContain(BRIDGE_WORKSPACE);
        assertThat(text).doesNotContain(IMAGE_200_KIB.substring(0, 64));
    }

    // --- harness ------------------------------------------------------------------------------

    private Recorder fatalTurn(int fatalCode, boolean priorError) throws Exception {
        String conversationId = "live-e2e-" + UUID.randomUUID().toString().substring(0, 8);
        configureBridge("fatal", Map.of(
                "FAKE_BRIDGE_FATAL_CODE", Integer.toString(fatalCode),
                "FAKE_BRIDGE_FATAL_PRIOR_ERROR", priorError ? "1" : "0"));
        Recorder recorder = new Recorder();
        WebSocket socket = connect(recorder);
        try {
            socket.sendText(hello(conversationId), true).get(5, TimeUnit.SECONDS);
            await("the bridge to go fatal", () -> recorder.closeCode() != null);
        } finally {
            socket.abort();
        }
        return recorder;
    }

    /** Writes the driver's per-test config; {@code env} carries every {@code FAKE_BRIDGE_*} switch. */
    private Path configureBridge(String mode, Map<String, String> extraEnv) {
        Path receipt = HARNESS.resolve("receipt-" + UUID.randomUUID() + ".json");
        Map<String, String> env = new LinkedHashMap<>();
        env.put("FAKE_BRIDGE_MODE", mode);
        env.put("FAKE_BRIDGE_RECEIPT", receipt.toString());
        env.putAll(extraEnv);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("script", FAKE_BRIDGE.toUri().toString());
        config.put("env", env);
        try {
            String body = MAPPER.writeValueAsString(config);
            Path aside = CONFIG.resolveSibling(CONFIG.getFileName() + ".tmp");
            Files.writeString(aside, body);
            Files.move(aside, CONFIG, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return receipt;
    }

    private WebSocket connect(Recorder recorder) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .newWebSocketBuilder()
                .header("Authorization", "Bearer " + OPERATOR_CREDENTIAL)
                .subprotocols(PROTOCOL)
                .buildAsync(URI.create("ws://127.0.0.1:" + port + PATH), recorder)
                .get(5, TimeUnit.SECONDS);
    }

    private static String hello(String conversationId) {
        return "{\"type\":\"hello\",\"v\":1,\"conversationId\":\"" + conversationId + "\","
                + "\"audio\":{\"inRate\":16000,\"outRate\":24000,\"format\":\"pcm16le\",\"channels\":1},"
                + "\"mic\":true,\"ar\":false}";
    }

    private static String frameMessage(String base64) {
        return "{\"type\":\"frame\",\"v\":1,\"mime\":\"image/jpeg\",\"dataBase64\":\"" + base64
                + "\",\"capturedAtMs\":0}";
    }

    private static JsonNode firstEvent(Recorder recorder, String type) {
        return firstEvent(recorder, type, event -> true);
    }

    private static JsonNode firstEvent(Recorder recorder, String type, Predicate<JsonNode> filter) {
        return events(recorder, type).stream().filter(filter).findFirst().orElse(null);
    }

    private static List<JsonNode> events(Recorder recorder, String type) {
        return recorder.frames.stream()
                .map(Frame::event)
                .filter(event -> event != null && type.equals(event.path("type").asText()))
                .toList();
    }

    private static boolean isFill(Frame frame, int fill) {
        byte[] audio = frame.audio();
        return audio != null && audio.length == 128
                && audio[0] == (byte) fill && audio[audio.length - 1] == (byte) fill;
    }

    private static <T> int indexOfTyped(List<T> list, Predicate<? super T> test) {
        for (int index = 0; index < list.size(); index++) {
            if (test.test(list.get(index))) {
                return index;
            }
        }
        return -1;
    }

    /** The label a receipt entry contributes: its phone-event type, else its line type. */
    private static String label(JsonNode entry) {
        String event = entry.path("event").asText("");
        return event.isEmpty() ? entry.path("type").asText("") : event;
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!condition.getAsBoolean()) {
            fail("timed out waiting for " + what);
        }
    }

    // --- static harness files ----------------------------------------------------------------

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The receipt the child is currently publishing, or null while it is mid-rename. */
    private static JsonNode readReceipt(Path receipt) {
        try {
            return MAPPER.readTree(Files.readString(receipt));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String lastSentLabel(JsonNode receipt) {
        if (receipt == null) {
            return "";
        }
        JsonNode sent = receipt.path("sent");
        if (!sent.isArray() || sent.isEmpty()) {
            return "";
        }
        return label(sent.get(sent.size() - 1));
    }

    private static void write(Path file, String body) {
        try {
            Files.writeString(file, body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path harnessDirectory() {
        try {
            return Files.createTempDirectory("aria-live-e2e");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The fake lives in act-aria's test resources, which are not on act-app's classpath; the
     * source tree is the only copy, so it is found from the working directory (the module
     * directory under Maven, or the reactor root when the lane is run from there).
     */
    private static Path locateFakeBridge() {
        Path cwd = Path.of("").toAbsolutePath();
        List<Path> candidates = List.of(
                cwd.resolve("../act-aria/src/test/resources/live/fake-bridge.mjs").normalize(),
                cwd.resolve("act-aria/src/test/resources/live/fake-bridge.mjs").normalize(),
                cwd.resolve("agent-control-tower/act-aria/src/test/resources/live/fake-bridge.mjs").normalize());
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("fake bridge not found from " + cwd + "; tried " + candidates);
    }

    /**
     * The production spawn clears the child's environment (spec L2), so the shared fake's
     * switches are set here, from the per-test config file, before it is imported.
     */
    private static Path writeDriver(Path directory) {
        String driver = """
                // Generated by LiveEndToEndIntegrationTest.
                import { readFileSync } from 'node:fs';
                const config = JSON.parse(readFileSync(new URL('./bridge-config.json', import.meta.url), 'utf8'));
                for (const [name, value] of Object.entries(config.env ?? {})) {
                  process.env[name] = String(value);
                }
                await import(config.script);
                """;
        Path file = directory.resolve("fake-bridge-driver.mjs");
        write(file, driver);
        return file;
    }

    private static Path writeCredentials(Path directory) {
        Path file = directory.resolve("qwen-live.env");
        write(file, "DASHSCOPE_API_KEY=" + BRIDGE_KEY + "\nDASHSCOPE_WORKSPACE=" + BRIDGE_WORKSPACE + "\n");
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // A volume without POSIX attributes (Windows): the loader logs the check as
            // unverifiable and reads the file anyway.
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    // --- recording ----------------------------------------------------------------------------

    /** One received frame: a phone event, one binary delta, or the close code. */
    record Frame(JsonNode event, byte[] audio, Integer closeCode) {

        static Frame text(JsonNode event) {
            return new Frame(event, null, null);
        }

        static Frame audio(byte[] pcm) {
            return new Frame(null, pcm, null);
        }

        static Frame close(int code) {
            return new Frame(null, null, code);
        }
    }

    /** Collects the live package's log lines, formatted, from whatever thread writes them. */
    private static final class LineCapture extends AppenderBase<ILoggingEvent> {

        private final List<String> lines = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            lines.add(event.getFormattedMessage());
        }
    }

    /** The phone side of the socket: ordered text events, binary deltas and the close code. */
    private final class Recorder implements WebSocket.Listener {

        private final List<Frame> frames = new CopyOnWriteArrayList<>();
        private final StringBuilder pendingText = new StringBuilder();
        private final ByteArrayOutputStream pendingBinary = new ByteArrayOutputStream();

        Integer closeCode() {
            return frames.stream().map(Frame::closeCode).filter(code -> code != null)
                    .findFirst().orElse(null);
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            pendingText.append(data);
            if (last) {
                String payload = pendingText.toString();
                pendingText.setLength(0);
                try {
                    frames.add(Frame.text(MAPPER.readTree(payload)));
                } catch (IOException e) {
                    // A frame that is not JSON is not a phone event; keep the recorder honest
                    // rather than failing the reader thread.
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            pendingBinary.write(chunk, 0, chunk.length);
            if (last) {
                frames.add(Frame.audio(pendingBinary.toByteArray()));
                pendingBinary.reset();
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            frames.add(Frame.close(statusCode));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            // Recorded as text so an await() failure names the cause instead of timing out blind.
            frames.add(Frame.text(MAPPER.createObjectNode()
                    .put("type", "transport_error")
                    .put("detail", error.getClass().getSimpleName())));
        }
    }
}
