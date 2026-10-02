package io.aria.conductor.execution.runtime.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
                "edit": "deny",
                "write": "deny",
                "patch": "deny",
                "bash": "deny",
                "webfetch": "deny",
                "task": "deny",
                "question": "deny",
                "external_directory": "deny",
                "aria-conductor*": "allow"
              },
              "model": "deepseek/deepseek-chat",
              "provider": {
                "deepseek": {
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
        assertThat(OpenCodeCoreAdapter.governedConfigurationJson())
                .as("the production no-wiring document is the shipped one, byte for byte")
                .isEqualTo(SHIPPED_GOVERNED_CONFIGURATION);
        assertThat(profile.env()).doesNotContainKey("ARIA_MCP_TOKEN");
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
