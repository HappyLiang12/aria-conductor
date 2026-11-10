package io.aria.conductor.execution.runtime.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.execution.mcp.RunMcpWiring;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.SecretBundle;
import io.aria.conductor.execution.security.ActorTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The OpenCode launch profile's governed configuration with and without the
 * restored platform-MCP wiring: the unwired document is byte-identical to the
 * document the core adapter ships (the permission policy plus the resolved
 * provider block), and a wired Aria run adds exactly the
 * {@code mcp.aria-conductor} block with the run-scoped bearer.
 */
class OpenCodeLaunchProfileTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The governed document every unwired run ships: the permission policy plus the provider block. */
    private static final String SHIPPED_GOVERNED_CONFIGURATION = """
            {
              "$schema": "https://opencode.ai/config.json",
              "permission": {
                "*": "deny",
                "read": "allow",
                "list": "allow",
                "glob": "allow",
                "grep": "allow",
                "webfetch": "allow",
                "websearch": "allow",
                "edit": "deny",
                "write": "deny",
                "patch": "deny",
                "bash": "deny",
                "task": "deny",
                "question": "deny",
                "external_directory": "deny",
                "aria-conductor*": "allow"
              },
              "model": "aria-deepseek/deepseek-chat",
              "provider": {
                "aria-deepseek": {
                  "npm": "@ai-sdk/openai-compatible",
                  "options": {
                    "apiKey": "{env:LLM_API_KEY}",
                    "baseURL": "https://api.deepseek.com/v1"
                  },
                  "models": {
                    "deepseek-chat": {}
                  }
                }
              }
            }
            """;

    /** The permission keys of the shipped document, in the order it renders them. */
    private static final List<String> SHIPPED_PERMISSION_KEYS = List.of(
            "*", "read", "list", "glob", "grep", "webfetch", "websearch",
            "edit", "write", "patch", "bash", "task", "question",
            "external_directory", "aria-conductor*");

    /** The four tools a SANDBOX worker may use: the only keys its policy flips. */
    private static final List<String> WORKSPACE_WRITE_TOOLS = List.of("edit", "write", "patch", "bash");

    private final McpProperties mcp = new McpProperties();
    private final ActorTokenService actorTokens = new ActorTokenService();
    private final RunMcpWiring wiring = new RunMcpWiring(mcp, actorTokens);

    private Path root;

    @AfterEach
    void tearDown() throws IOException {
        if (root != null) {
            deleteTree(root);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private record Fixture(Path root, Path workspace, Path configuration, UUID runId,
            ExecutionSpec spec, PreparedEnvironment environment) {
    }

    private Fixture fixture(UUID agentId, Instant deadline) throws IOException {
        root = Files.createTempDirectory("opencode-launch-profile");
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path configuration = Files.createDirectories(root.resolve("runtime").resolve("host"));
        UUID runId = UUID.randomUUID();
        ExecutionSpec spec = new ExecutionSpec(runId, agentId, "opencode", ExecutionMode.HOST,
                new AgentExecutionSettings("opencode", ExecutionMode.HOST, null, null, null),
                "opencode:provider-key", "rev-1", deadline);
        PreparedEnvironment environment = new PreparedEnvironment(runId, ExecutionMode.HOST, "host-" + runId,
                workspace.toString(), configuration.toString(), URI.create("http://127.0.0.1:46321/"));
        return new Fixture(root, workspace, configuration, runId, spec, environment);
    }

    /**
     * A SANDBOX placement: the working directory is a sandbox path and the
     * endpoint is the real proxy shape ({@code http://<host>:<published>/proxy/<inner>}),
     * which is what {@code SandboxBind} reads the placement from. Callers that
     * assert a run is <em>not</em> MCP-wired pass a real future deadline anyway:
     * {@code RunMcpWiring} mints its run bearer against that deadline, so passing
     * null would let a missing token pass for the wrong reason and leave the agent
     * gate untested.
     */
    private Fixture sandboxFixture(UUID agentId, Instant deadline) throws IOException {
        root = Files.createTempDirectory("opencode-launch-profile");
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path configuration = Files.createDirectories(root.resolve("runtime").resolve("sandbox"));
        UUID runId = UUID.randomUUID();
        ExecutionSpec spec = new ExecutionSpec(runId, agentId, "opencode", ExecutionMode.SANDBOX,
                new AgentExecutionSettings("opencode", ExecutionMode.SANDBOX, null, null, null),
                "opencode:provider-key", "rev-1", deadline);
        PreparedEnvironment environment = new PreparedEnvironment(runId, ExecutionMode.SANDBOX,
                "sandbox-" + runId, "/workspace", configuration.toString(),
                URI.create("http://localhost:40000/proxy/4096"));
        return new Fixture(root, workspace, configuration, runId, spec, environment);
    }

    private static OpenCodeCoreAdapter.OpenCodeProfile profile() {
        return new OpenCodeCoreAdapter.OpenCodeProfile("opencode", List.of(), Map.of(), "1.14.31", "efficient");
    }

    private static LaunchProfile launch(Fixture fixture, OpenCodeCoreAdapter adapter) {
        return adapter.launchProfile(fixture.spec(), fixture.environment(),
                new SecretBundle("opencode:provider-key", Map.of("DEEPSEEK_API_KEY", "sk-fixture")));
    }

    private static JsonNode governedConfig(Fixture fixture) throws IOException {
        return JSON.readTree(Files.readString(
                OpenCodeCoreAdapter.governedConfigurationFile(fixture.environment())));
    }

    // ------------------------------------------------------------------ the profile

    /**
     * A run without wiring (here: a non-Aria agent) writes exactly the bytes the
     * governed document ships with -- neither the MCP wiring nor the resolved
     * provider may drift it -- and no token rides the server environment.
     */
    @Test
    void anUnwiredRunWritesTheUnchangedGovernedConfigurationByteForByte() throws Exception {
        Fixture fixture = fixture(UUID.randomUUID(), null);

        LaunchProfile profile = launch(fixture, new OpenCodeCoreAdapter(profile(), wiring));

        assertThat(Files.readString(OpenCodeCoreAdapter.governedConfigurationFile(fixture.environment())))
                .isEqualTo(SHIPPED_GOVERNED_CONFIGURATION);
        // Golden copy held in this test (no separate shipped file): production output must match it byte for byte.
        assertThat(OpenCodeCoreAdapter.governedConfigurationJson())
                .as("the production no-wiring document is the shipped one, byte for byte")
                .isEqualTo(SHIPPED_GOVERNED_CONFIGURATION);
        assertThat(profile.env()).doesNotContainKey("ARIA_MCP_TOKEN");
    }

    /**
     * A SANDBOX worker (any non-Aria agent) gets the run's own workspace tools.
     * The run-owned container is the real boundary; the permission document is only
     * a guardrail inside it: {@code edit}, {@code write}, {@code patch} and
     * {@code bash} are allowed, everything the shipped document refuses stays
     * refused, the policy is structurally the shipped policy with exactly those four
     * flipped, the provider block is the same resolved one as every other placement,
     * and the worker still receives no platform MCP -- here with a configured
     * sandbox host and a real future deadline, so the Aria-only gate (not a blank
     * address and not a null deadline) is what keeps it off.
     */
    @Test
    void sandboxWorkerRunAllowsWorkspaceTools() throws Exception {
        // A configured sandbox host and a deadline the bearer could be minted
        // against, so an unwired document cannot pass merely because the address was
        // blank or the deadline absent: only the Aria-only gate refuses this run.
        mcp.setSandboxHostAddress("host.containers.internal");
        mcp.setPort(4815);
        Fixture fixture = sandboxFixture(UUID.randomUUID(), Instant.now().plusSeconds(2700));
        OpenCodeCoreAdapter.ModelProvider provider = new OpenCodeCoreAdapter.ModelProvider(
                "acme-gateway", "acme-flash", "https://gw.example.com/v1");

        LaunchProfile profile = launch(fixture, new OpenCodeCoreAdapter(profile(), wiring, () -> provider));

        JsonNode document = governedConfig(fixture);
        JsonNode permission = document.path("permission");

        List<String> keys = new ArrayList<>();
        permission.fieldNames().forEachRemaining(keys::add);
        assertThat(keys)
                .as("the worker policy keeps the shipped permission keys, in the shipped order")
                .containsExactlyElementsOf(SHIPPED_PERMISSION_KEYS);
        for (String tool : WORKSPACE_WRITE_TOOLS) {
            assertThat(permission.path(tool).asText())
                    .as("a sandbox worker must be able to use %s inside its container", tool)
                    .isEqualTo("allow");
        }
        for (String readTool : List.of("read", "list", "glob", "grep", "webfetch", "websearch", "aria-conductor*")) {
            assertThat(permission.path(readTool).asText())
                    .as("%s stays an automatic allowance for a worker too", readTool)
                    .isEqualTo("allow");
        }
        for (String tool : List.of("*", "task", "question", "external_directory")) {
            assertThat(permission.path(tool).asText()).as("%s stays refused for a worker", tool).isEqualTo("deny");
        }
        // Structural, not textual: the worker document IS the shipped document with
        // exactly the four workspace tools flipped, so any other drift fails here.
        ObjectNode expectedPermission = JSON.readTree(OpenCodeCoreAdapter.governedConfigurationJson())
                .path("permission").deepCopy();
        for (String tool : WORKSPACE_WRITE_TOOLS) {
            expectedPermission.put(tool, "allow");
        }
        assertThat(permission)
                .as("the worker policy equals the shipped policy with exactly %s allowed", WORKSPACE_WRITE_TOOLS)
                .isEqualTo(expectedPermission);

        assertThat(document.has("mcp"))
                .as("the Aria-only gate, not a blank sandbox address, keeps the platform MCP off a worker")
                .isFalse();
        assertThat(profile.env())
                .as("a worker is never handed the run bearer, even with a deadline to mint it against")
                .doesNotContainKey("ARIA_MCP_TOKEN");
        JsonNode providerBlock = document.path("provider").path("aria-acme-gateway");
        assertThat(document.path("model").asText()).isEqualTo("aria-acme-gateway/acme-flash");
        assertThat(providerBlock.path("npm").asText()).isEqualTo("@ai-sdk/openai-compatible");
        assertThat(providerBlock.path("options").path("apiKey").asText()).isEqualTo("{env:LLM_API_KEY}");
        assertThat(providerBlock.path("options").path("baseURL").asText()).isEqualTo("https://gw.example.com/v1");
        List<String> models = new ArrayList<>();
        providerBlock.path("models").fieldNames().forEachRemaining(models::add);
        assertThat(models).as("the provider block carries exactly the resolved model").containsExactly("acme-flash");
    }

    /**
     * The Aria assistant stays read-only in every placement: a sandbox Aria run
     * keeps the shipped refusals for the side-effecting tools.
     */
    @Test
    void sandboxAriaRunStaysReadOnly() throws Exception {
        Fixture fixture = sandboxFixture(AriaConstants.ARIA_AGENT_ID, null);

        launch(fixture, new OpenCodeCoreAdapter(profile(), wiring));

        JsonNode permission = governedConfig(fixture).path("permission");
        assertThat(permission.path("edit").asText()).isEqualTo("deny");
        assertThat(permission.path("write").asText()).isEqualTo("deny");
        assertThat(permission.path("patch").asText()).isEqualTo("deny");
        assertThat(permission.path("bash").asText()).isEqualTo("deny");
        assertThat(permission.path("*").asText()).isEqualTo("deny");
    }

    /**
     * The Aria gate, not a blank address, is what keeps the platform MCP off a
     * worker: with the very same configured sandbox host a sandbox Aria run does
     * receive the block, and it stays read-only while it does.
     */
    @Test
    void sandboxAriaRunGetsTheMcpBlockButStaysReadOnly() throws Exception {
        mcp.setSandboxHostAddress("host.containers.internal");
        mcp.setPort(4815);
        Fixture fixture = sandboxFixture(AriaConstants.ARIA_AGENT_ID, Instant.now().plusSeconds(2700));

        LaunchProfile profile = launch(fixture, new OpenCodeCoreAdapter(profile(), wiring));

        JsonNode document = governedConfig(fixture);
        JsonNode mcpBlock = document.path("mcp").path("aria-conductor");
        assertThat(mcpBlock.path("type").asText()).isEqualTo("remote");
        assertThat(mcpBlock.path("url").asText())
                .as("a sandbox run dials the operator's configured sandbox-reachable host")
                .isEqualTo("http://host.containers.internal:4815/mcp");
        assertThat(mcpBlock.path("headers").path("Authorization").asText())
                .isEqualTo("Bearer {env:ARIA_MCP_TOKEN}");
        JsonNode permission = document.path("permission");
        assertThat(permission.path("*").asText()).isEqualTo("deny");
        for (String tool : WORKSPACE_WRITE_TOOLS) {
            assertThat(permission.path(tool).asText())
                    .as("the wired Aria run stays read-only: %s", tool)
                    .isEqualTo("deny");
        }
        String token = profile.env().get("ARIA_MCP_TOKEN");
        assertThat(token).isNotNull();
        assertThat(actorTokens.resolveBearer("Bearer " + token))
                .as("the sandbox Aria run's environment carries the run-scoped bearer minted for this run")
                .get()
                .satisfies(actor -> assertThat(actor.runId()).isEqualTo(fixture.runId()));
    }

    /**
     * A HOST worker stays read-only: only the sandbox container is accepted as the
     * boundary that allows a worker's write and shell tools, so the sandbox
     * worker policy must never leak onto the operator's own host.
     */
    @Test
    void hostWorkerRunStaysReadOnly() throws Exception {
        Fixture fixture = fixture(UUID.randomUUID(), null);

        launch(fixture, new OpenCodeCoreAdapter(profile(), wiring));

        JsonNode permission = governedConfig(fixture).path("permission");
        assertThat(permission.path("edit").asText()).isEqualTo("deny");
        assertThat(permission.path("write").asText()).isEqualTo("deny");
        assertThat(permission.path("patch").asText()).isEqualTo("deny");
        assertThat(permission.path("bash").asText()).isEqualTo("deny");
        assertThat(permission.path("*").asText()).isEqualTo("deny");
    }

    /**
     * A wired Aria run in actor mode: the block names the platform endpoint, the
     * header references the server environment token, and that token is the
     * run-scoped bearer minted for this run — the only kind of token the actor mode
     * accepts.
     */
    @Test
    void theAriaActorModeRunCarriesTheMcpBlockWithTheRunBearer() throws Exception {
        mcp.setAuthMode("actor");
        mcp.setPort(4815);
        Fixture fixture = fixture(AriaConstants.ARIA_AGENT_ID, Instant.now().plusSeconds(2700));

        LaunchProfile profile = launch(fixture, new OpenCodeCoreAdapter(profile(), wiring));

        JsonNode document = governedConfig(fixture);
        JsonNode mcpBlock = document.path("mcp").path("aria-conductor");
        assertThat(mcpBlock.path("type").asText()).isEqualTo("remote");
        assertThat(mcpBlock.path("url").asText()).isEqualTo("http://127.0.0.1:4815/mcp");
        assertThat(mcpBlock.path("enabled").asBoolean()).isTrue();
        assertThat(mcpBlock.path("headers").path("Authorization").asText())
                .isEqualTo("Bearer {env:ARIA_MCP_TOKEN}");
        assertThat(document.path("permission").path("*").asText())
                .as("the governed policy survives the wiring")
                .isEqualTo("deny");
        assertThat(document.path("permission").path("aria-conductor*").asText())
                .as("the sanctioned Conductor surface stays visible to the run")
                .isEqualTo("allow");

        String token = profile.env().get("ARIA_MCP_TOKEN");
        assertThat(token).isNotNull();
        assertThat(actorTokens.resolveBearer("Bearer " + token))
                .as("the environment carries the run-scoped bearer minted for this run")
                .get()
                .satisfies(actor -> assertThat(actor.runId()).isEqualTo(fixture.runId()));
    }

    /**
     * Without an authenticating mode the block still carries the run bearer: the
     * endpoint ignores it in {@code none} mode, and one shape for every wired run
     * keeps the wiring from depending on the mode it was read under.
     */
    @Test
    void theAriaNoneModeRunCarriesTheMcpBlockWithTheRunBearer() throws Exception {
        mcp.setPort(4815);
        Fixture fixture = fixture(AriaConstants.ARIA_AGENT_ID, Instant.now().plusSeconds(2700));

        LaunchProfile profile = launch(fixture, new OpenCodeCoreAdapter(profile(), wiring));

        JsonNode mcpBlock = governedConfig(fixture).path("mcp").path("aria-conductor");
        assertThat(mcpBlock.path("url").asText()).isEqualTo("http://127.0.0.1:4815/mcp");
        assertThat(mcpBlock.path("enabled").asBoolean()).isTrue();
        assertThat(mcpBlock.path("headers").path("Authorization").asText())
                .isEqualTo("Bearer {env:ARIA_MCP_TOKEN}");
        String token = profile.env().get("ARIA_MCP_TOKEN");
        assertThat(token).isNotNull();
        assertThat(actorTokens.resolveBearer("Bearer " + token)).isPresent();
    }

    /**
     * The legacy static-token mode authenticates with the platform's long-lived
     * token, which is never handed to a run: the Aria run is left unwired instead of
     * carrying a bearer the endpoint must refuse.
     */
    @Test
    void theLegacyTokenModeLeavesTheAriaRunUnwired() throws Exception {
        mcp.setAuthMode("token");
        mcp.setToken("static-legacy-bearer");
        mcp.setPort(4815);
        Fixture fixture = fixture(AriaConstants.ARIA_AGENT_ID, Instant.now().plusSeconds(2700));

        LaunchProfile profile = launch(fixture, new OpenCodeCoreAdapter(profile(), wiring));

        assertThat(governedConfig(fixture).has("mcp")).isFalse();
        assertThat(profile.env()).doesNotContainKey("ARIA_MCP_TOKEN");
    }

    /**
     * The operator's provider environment ({@code opencode.sandbox-env}) is the
     * reviewed base environment of this profile, and a production run carries
     * {@code credentialRef=null}: its values are the credentials the launch
     * injects, so they are declared secrets of the launch and masked by value
     * whatever their name looks like. The name here is deliberately not
     * credential-shaped, so the name rule alone would write it.
     */
    @Test
    void theOperatorProviderEnvironmentValuesAreDeclaredSecrets() throws Exception {
        Fixture fixture = fixture(UUID.randomUUID(), null);
        String value = "synthetic-aws-access-key-id-not-a-credential";
        OpenCodeCoreAdapter adapter = new OpenCodeCoreAdapter(
                new OpenCodeCoreAdapter.OpenCodeProfile("opencode", List.of(),
                        Map.of("AWS_ACCESS_KEY_ID", value), "1.14.31", "efficient"), wiring);

        LaunchProfile profile = adapter.launchProfile(fixture.spec(), fixture.environment(),
                new SecretBundle(null, Map.of()));

        assertThat(profile.env()).containsEntry("AWS_ACCESS_KEY_ID", value);
        assertThat(profile.secretValues())
                .as("the provider environment value must be declared secret, not merely carried")
                .contains(value);
    }

    /**
     * The merge guard is enforced where the merge happens, so a profile that is
     * constructed directly (a test, a future wiring) cannot carry a
     * launcher-owned name past it. The spelling variants matter because the
     * ambient lookup it protects is case-insensitive.
     */
    @Test
    void aDirectlyConstructedProfileCannotSetALauncherOwnedVariable() throws Exception {
        Fixture fixture = fixture(UUID.randomUUID(), null);
        for (String name : List.of("HOME", "home", "Home", "XDG_config_home", "ARIA_MCP_TOKEN")) {
            OpenCodeCoreAdapter adapter = new OpenCodeCoreAdapter(
                    new OpenCodeCoreAdapter.OpenCodeProfile("opencode", List.of(),
                            Map.of(name, "synthetic-operator-value"), "1.14.31", "efficient"), wiring);

            assertThatThrownBy(() -> adapter.launchProfile(fixture.spec(), fixture.environment(),
                    new SecretBundle(null, Map.of())))
                    .as("a directly constructed profile must not carry %s into the launch", name)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(name)
                    .hasMessageContaining("launcher owns");
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
