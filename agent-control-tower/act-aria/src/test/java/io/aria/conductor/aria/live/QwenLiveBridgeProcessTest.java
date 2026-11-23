package io.aria.conductor.aria.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The stdio seam of spec §"Bridge process", driven by {@code src/test/resources/live/
 * fake-bridge.mjs}: the child learns the key only from the env map (never argv), stdout
 * control JSON is parsed and dispatched, stderr reaches the log redacted, and
 * {@code close()} reaps the child without reporting an unexpected exit.
 */
class QwenLiveBridgeProcessTest {

    private static final String KEY = "sk-live-test-0123456789abcdef";
    private static final String WORKSPACE = "ws-test-singapore";
    private static final String REALTIME_URL = "wss://dashscope-intl.aliyuncs.com/api-ws/v1/realtime";
    private static final Path SCRIPT = Path.of("src/test/resources/live/fake-bridge.mjs").toAbsolutePath();

    /**
     * macOS injects {@code __CF_USER_TEXT_ENCODING} into every child it execs, even with an
     * empty environment ({@code env -i PATH=… node} shows it too), so it is the one key that
     * can appear without the Java side putting it there. Everything else is chosen: PATH,
     * the two credentials and LIVE_SESSION_ID.
     */
    private static final int EXPECTED_ENV_KEYS =
            4 + (System.getenv("__CF_USER_TEXT_ENCODING") == null ? 0 : 1);

    private final ObjectMapper json = new ObjectMapper();
    private final RecordingListener listener = new RecordingListener();
    private final List<String> stderrLines = new CopyOnWriteArrayList<>();
    private QwenLiveBridgeProcess bridge;

    @AfterEach
    void reap() {
        if (bridge != null) {
            bridge.close();
            assertThat(bridge.isAlive()).as("the child must be reaped by close()").isFalse();
        }
    }

    @Test
    void theKeyArrivesThroughTheEnvironmentAndNeverThroughArgv() throws Exception {
        bridge = spawn(credentials(), Map.of());

        JsonNode env = awaitEnvReport();

        assertThat(env.path("keyPresent").asBoolean()).isTrue();
        assertThat(env.path("workspacePresent").asBoolean()).isTrue();
        assertThat(env.path("keyLength").asInt()).isEqualTo(KEY.length());
        assertThat(env.path("workspaceLength").asInt()).isEqualTo(WORKSPACE.length());
        assertThat(env.path("liveSessionIdPresent").asBoolean()).isTrue();
        assertThat(env.path("argvLeaks").asBoolean()).isFalse();
        // The parent environment is cleared, not filtered: HOME is gone and the child
        // holds exactly PATH, the two credentials and LIVE_SESSION_ID.
        assertThat(env.path("homePresent").asBoolean())
                .as("the parent environment must not reach the child").isFalse();
        assertThat(env.path("envKeyCount").asInt())
                .as("PATH + DASHSCOPE_API_KEY + DASHSCOPE_WORKSPACE + LIVE_SESSION_ID")
                .isEqualTo(EXPECTED_ENV_KEYS);
        assertThat(env.path("realtimeUrlPresent").asBoolean()).isFalse();
    }

    @Test
    void stdoutControlIsParsedAndForwarded() throws Exception {
        bridge = spawn(credentials(), Map.of());
        awaitEnvReport();

        bridge.send("{\"type\":\"text\",\"text\":\"hello qwen\"}");

        String control = listener.controls.poll(10, TimeUnit.SECONDS);
        assertThat(control).isNotNull();
        assertThat(json.readTree(control).path("type").asText()).isEqualTo("ack");
        assertThat(json.readTree(control).path("of").asText()).isEqualTo("text");
    }

    @Test
    void anInboundBinaryFrameComesBackAsPcm() throws Exception {
        bridge = spawn(credentials(), Map.of());
        awaitEnvReport();
        byte[] pcm = new byte[] {1, -2, 3, -4, 5, 6, 7, 8};

        bridge.sendBinary(pcm);

        byte[] returned = listener.audio.poll(10, TimeUnit.SECONDS);
        assertThat(returned).isEqualTo(pcm);
    }

    @Test
    void stderrIsRedactedBeforeItIsSurfaced() {
        bridge = spawn(credentials(), Map.of());

        await(() -> !stderrLines.isEmpty(), "a stderr line");
        String surfaced = String.join("\n", stderrLines);

        assertThat(surfaced).contains("fake-bridge stderr");
        assertThat(surfaced).doesNotContain(KEY);
        assertThat(surfaced).doesNotContain(WORKSPACE);
        assertThat(surfaced).doesNotContain("AAAAAAAAAAAAAAAA");
    }

    @Test
    void closeReapsTheChildAndDoesNotReportAnExit() throws Exception {
        bridge = spawn(credentials(), Map.of());
        awaitEnvReport();

        bridge.close();

        assertThat(bridge.isAlive()).isFalse();
        assertThat(listener.exits.poll(500, TimeUnit.MILLISECONDS)).as("a phone-initiated close is not an exit").isNull();
        bridge.close();
        assertThat(bridge.isAlive()).isFalse();
    }

    @Test
    void anUnexpectedChildExitIsReported() throws Exception {
        bridge = spawn(credentials(), Map.of("FAKE_BRIDGE_EXIT_MS", "50"));

        Integer code = listener.exits.poll(10, TimeUnit.SECONDS);

        assertThat(code).isEqualTo(2);
    }

    @Test
    void theConfiguredRealtimeUrlIsTheOnlyExtraVariable() throws Exception {
        bridge = QwenLiveBridgeProcess.spawn("session-under-test", SCRIPT, credentials(),
                REALTIME_URL, json, listener, stderrLines::add);

        JsonNode env = awaitEnvReport();

        assertThat(env.path("realtimeUrlPresent").asBoolean()).isTrue();
        assertThat(env.path("realtimeUrlLength").asInt()).isEqualTo(REALTIME_URL.length());
        assertThat(env.path("envKeyCount").asInt()).isEqualTo(EXPECTED_ENV_KEYS + 1);
        assertThat(env.path("homePresent").asBoolean()).isFalse();
    }

    @Test
    void aBlankRealtimeUrlAddsNoVariable() throws Exception {
        bridge = QwenLiveBridgeProcess.spawn("session-under-test", SCRIPT, credentials(),
                "  ", json, listener, stderrLines::add);

        JsonNode env = awaitEnvReport();

        assertThat(env.path("realtimeUrlPresent").asBoolean()).isFalse();
        assertThat(env.path("envKeyCount").asInt()).isEqualTo(EXPECTED_ENV_KEYS);
    }

    @Test
    void aWriteAfterCloseIsDroppedInsteadOfThrowing() {
        bridge = spawn(credentials(), Map.of());
        bridge.close();

        bridge.send("{\"type\":\"text\",\"text\":\"late\"}");
        bridge.sendBinary(new byte[] {1, 2, 3});
    }

    @Test
    void linesTheJavaSideCannotUseAreDroppedWithoutLosingTheChild() throws Exception {
        bridge = spawn(credentials(), Map.of());
        awaitEnvReport();

        bridge.send("{\"type\":\"malformed\"}");
        // The ack for "malformed" is the last control: none of the malformed lines may
        // have reached the listener as a control event.
        assertThat(awaitAckFor("malformed")).isTrue();
        assertThat(listener.audio).isEmpty();

        bridge.send("{\"type\":\"text\",\"text\":\"still here\"}");

        assertThat(awaitAckFor("text")).isTrue();
        assertThat(bridge.isAlive()).isTrue();
    }

    @Test
    void theTurnDoneLineReachesTheListenerAsTheTurnEnd() throws Exception {
        bridge = spawn(credentials(), Map.of());
        awaitEnvReport();

        bridge.send("{\"type\":\"turn\"}");

        assertThat(listener.turnEnds.poll(10, TimeUnit.SECONDS)).isEqualTo(1);
    }

    @Test
    void nullAndEmptyOutboundFramesAreIgnored() throws Exception {
        bridge = spawn(credentials(), Map.of());
        awaitEnvReport();

        bridge.send(null);
        bridge.sendBinary(null);
        bridge.sendBinary(new byte[0]);
        bridge.sendBinary(new byte[] {7});

        assertThat(listener.audio.poll(10, TimeUnit.SECONDS)).isEqualTo(new byte[] {7});
    }

    @Test
    void theDefaultStderrSinkIsTheMetadataLogger() {
        // No sink supplied: the redacted line goes to the debug logger, not to the test.
        bridge = QwenLiveBridgeProcess.spawn("session-under-test", SCRIPT, credentials(), json,
                listener, null);

        assertThat(bridge.isAlive()).isTrue();
    }

    // --- harness -------------------------------------------------------------------------

    private QwenLiveBridgeProcess spawn(Map<String, String> env, Map<String, String> extraEnv) {
        Map<String, String> childEnv = new LinkedHashMap<>(env);
        childEnv.putAll(extraEnv);
        assertThat(SCRIPT).as("the fake bridge fixture").exists();
        return QwenLiveBridgeProcess.spawn("session-under-test", SCRIPT, childEnv, json, listener, stderrLines::add);
    }

    private static Map<String, String> credentials() {
        return Map.of("DASHSCOPE_API_KEY", KEY, "DASHSCOPE_WORKSPACE", WORKSPACE);
    }

    /** Drains controls until the fake's ack for {@code type} arrives. */
    private boolean awaitAckFor(String type) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String control = listener.controls.poll(50, TimeUnit.MILLISECONDS);
            if (control == null) {
                continue;
            }
            JsonNode node = json.readTree(control);
            if ("ack".equals(node.path("type").asText()) && type.equals(node.path("of").asText())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode awaitEnvReport() throws Exception {        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String control = listener.controls.poll(50, TimeUnit.MILLISECONDS);
            if (control != null && "env".equals(json.readTree(control).path("type").asText())) {
                return json.readTree(control);
            }
        }
        return fail("timed out waiting for the fake bridge's environment report");
    }

    private static void await(BooleanSupplier condition, String what) {        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(25);
        }
        fail("timed out waiting for " + what);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Everything the bridge hands back to Java, on the threads it hands it back on. */
    private static final class RecordingListener implements LiveBridgeListener {

        final BlockingQueue<String> controls = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> audio = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> exits = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> turnEnds = new LinkedBlockingQueue<>();

        @Override
        public void onControl(String json) {
            controls.add(json);
        }

        @Override
        public void onAudio(byte[] pcm) {
            audio.add(pcm);
        }

        @Override
        public void onTurnDone() {
            turnEnds.add(1);
        }

        @Override
        public void onExit(int code) {
            exits.add(code);
        }
    }
}
