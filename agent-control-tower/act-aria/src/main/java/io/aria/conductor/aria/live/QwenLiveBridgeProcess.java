package io.aria.conductor.aria.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The production {@link LiveBridge}: one {@code node} child per socket, JSON lines over
 * stdio (spec L1, §"Bridge process").
 *
 * <p>Credentials arrive only through the child's environment: the environment is cleared
 * first, so the child has no inherited key, and only the two provided values (plus
 * {@code LIVE_SESSION_ID}, {@code PATH} and — when the deployment configured one —
 * {@code QWEN_REALTIME_URL}) reach it. Nothing the phone sends or the provider returns is
 * ever written to a log line: stdout is dispatch metadata, stderr goes through
 * {@link LiveLogRedactor} before it is surfaced.
 */
public final class QwenLiveBridgeProcess implements LiveBridge {

    static final String NODE_EXECUTABLE = "node";
    static final String SESSION_ENV = "LIVE_SESSION_ID";
    /** The child's Qwen endpoint; absent means the child derives it (Task 5). Not a secret. */
    static final String REALTIME_URL_ENV = "QWEN_REALTIME_URL";
    static final String CLOSE_FRAME = "{\"type\":\"close\"}";

    private static final Logger log = LoggerFactory.getLogger(QwenLiveBridgeProcess.class);
    private static final String PATH_ENV = "PATH";

    private final Process process;
    private final BufferedWriter stdin;
    private final ObjectMapper json;
    private final LiveBridgeListener listener;
    private final LiveLogRedactor redactor;
    private final AtomicBoolean closing = new AtomicBoolean();

    /**
     * Starts the child and its two readers.
     *
     * @param sessionId  Aria's per-socket id, handed to the child as {@code LIVE_SESSION_ID}
     * @param script     the bridge entry point ({@code index.js})
     * @param credentials exactly the variables the child may see; the parent environment is not inherited
     * @param stderrSink receives already-redacted stderr lines (null → the metadata logger)
     */
    static QwenLiveBridgeProcess spawn(String sessionId, Path script, Map<String, String> credentials,
            ObjectMapper json, LiveBridgeListener listener, Consumer<String> stderrSink) {
        return spawn(sessionId, script, credentials, null, json, listener, stderrSink);
    }

    /**
     * As {@link #spawn(String, Path, Map, ObjectMapper, LiveBridgeListener, Consumer)}, plus
     * the Qwen endpoint: a non-blank {@code realtimeUrl} becomes {@code QWEN_REALTIME_URL},
     * a blank one leaves the child to derive it. It is not a credential, so it is not one
     * of the redactor's secrets.
     */
    static QwenLiveBridgeProcess spawn(String sessionId, Path script, Map<String, String> credentials,
            String realtimeUrl, ObjectMapper json, LiveBridgeListener listener, Consumer<String> stderrSink) {
        Map<String, String> env = new LinkedHashMap<>();
        String path = System.getenv(PATH_ENV);
        if (path != null) {
            env.put(PATH_ENV, path);
        }
        if (credentials != null) {
            env.putAll(credentials);
        }
        if (realtimeUrl != null && !realtimeUrl.isBlank()) {
            env.put(REALTIME_URL_ENV, realtimeUrl.trim());
        }
        if (sessionId != null && !sessionId.isBlank()) {
            env.put(SESSION_ENV, sessionId);
        }
        ProcessBuilder builder = new ProcessBuilder(NODE_EXECUTABLE, script.toString()).redirectErrorStream(false);
        builder.environment().clear();
        builder.environment().putAll(env);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new LiveBridgeUnavailableException("bridge process failed to start");
        }
        try {
            return new QwenLiveBridgeProcess(process, json, listener, credentials, stderrSink);
        } catch (IOException e) {
            process.destroyForcibly();
            throw new LiveBridgeUnavailableException("bridge process streams unavailable");
        }
    }

    private QwenLiveBridgeProcess(Process process, ObjectMapper json, LiveBridgeListener listener,
            Map<String, String> credentials, Consumer<String> stderrSink) throws IOException {
        this.process = process;
        this.json = json;
        this.listener = listener;
        this.redactor = new LiveLogRedactor(credentials == null ? null : credentials.values());
        this.stdin = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Consumer<String> sink = stderrSink != null ? stderrSink
                : line -> log.debug("qwen live bridge stderr: {}", line);
        start("aria-live-bridge-stdout", this::readStdout);
        start("aria-live-bridge-stderr", () -> readStderr(sink));
        log.debug("qwen live bridge started");
    }

    @Override
    public void send(String json) {
        if (json != null) {
            write(json);
        }
    }

    @Override
    public void sendBinary(byte[] pcm) {
        if (pcm == null || pcm.length == 0) {
            return;
        }
        write("{\"type\":\"audio\",\"dir\":\"in\",\"dataBase64\":\""
                + Base64.getEncoder().encodeToString(pcm) + "\"}");
    }

    @Override
    public void close() {
        if (closing.get()) {
            return;
        }
        write(CLOSE_FRAME);
        closing.set(true);
        try {
            stdin.close();
        } catch (IOException e) {
            log.debug("qwen live bridge stdin close failed");
        }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        log.debug("qwen live bridge closed");
    }

    /** @return whether the child is still running; the close contract leaves this false */
    boolean isAlive() {
        return process.isAlive();
    }

    private synchronized void write(String line) {
        if (closing.get()) {
            return;
        }
        try {
            stdin.write(line);
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            // Never the line itself: it may carry audio or an image.
            log.debug("qwen live bridge write failed: chars={}", line.length());
        }
    }

    // --- child output ---------------------------------------------------------------------

    private void readStdout() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                dispatch(line);
            }
        } catch (IOException e) {
            log.debug("qwen live bridge stdout unreadable");
        }
        int code = waitForExit();
        if (!closing.get() && code != Integer.MIN_VALUE) {
            listener.onExit(code);
        }
    }

    private void dispatch(String line) {
        JsonNode node;
        try {
            node = json.readTree(line);
        } catch (IOException e) {
            log.debug("qwen live bridge line unparseable: chars={}", line.length());
            return;
        }
        if (node == null || !node.isObject()) {
            log.debug("qwen live bridge line dropped: chars={}", line.length());
            return;
        }
        switch (node.path("type").asText("")) {
            case "audio" -> outboundAudio(node);
            case "control" -> control(node);
            case "tool_call" -> listener.onToolCall(line);
            case "turn_done" -> listener.onTurnDone();
            case "log" -> log.debug("qwen live bridge log: kind={} count={} bytes={} ms={}",
                    node.path("event").asText(""),
                    node.path("count").asInt(),
                    node.path("bytes").asInt(),
                    node.path("ms").asLong());
            default -> log.debug("qwen live bridge line dropped: chars={}", line.length());
        }
    }

    private void outboundAudio(JsonNode node) {
        if (!"out".equals(node.path("dir").asText(""))) {
            log.debug("qwen live bridge audio dropped: dir_not_out");
            return;
        }
        String dataBase64 = node.path("dataBase64").asText("");
        if (dataBase64.isEmpty()) {
            log.debug("qwen live bridge audio dropped: empty");
            return;
        }
        try {
            listener.onAudio(Base64.getDecoder().decode(dataBase64));
        } catch (IllegalArgumentException e) {
            log.debug("qwen live bridge audio undecodable: chars={}", dataBase64.length());
        }
    }

    private void control(JsonNode node) {
        JsonNode event = node.path("event");
        if (!event.isObject()) {
            log.debug("qwen live bridge control dropped: event_absent");
            return;
        }
        try {
            listener.onControl(json.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            log.debug("qwen live bridge control dropped: unserializable");
        }
    }

    private void readStderr(Consumer<String> sink) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sink.accept(redactor.redact(line));
            }
        } catch (IOException e) {
            log.debug("qwen live bridge stderr unreadable");
        }
    }

    private int waitForExit() {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Integer.MIN_VALUE;
        }
    }

    private static void start(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
    }
}
