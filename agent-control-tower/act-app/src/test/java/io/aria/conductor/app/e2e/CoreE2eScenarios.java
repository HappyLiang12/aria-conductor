package io.aria.conductor.app.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Harness-only peer-scenario selection of the deterministic core E2E
 * distribution (Task 19 wiring): the agent -&gt; scenario choice the harness
 * forwards to the mock peer process that serves that agent's core at launch
 * time.
 *
 * <p>Selection is memory-only: it is never written to the database, so the
 * scenario control cannot fabricate a run, an approval or a binding row. Every
 * selected id must be declared for at least one peer in the committed scenario
 * manifest, and a run whose agent has no selection is refused loudly at launch
 * ({@link #requireScenario(UUID)}) instead of being started without a peer
 * fixture -- the harness never invents model or core behavior. The only
 * exceptions are the harness's documented built-in defaults
 * ({@link #DEFAULT_SCENARIO}, registered by {@link #registerDefaults(List)}):
 * the built-in agents the specs cannot select a scenario for, because their runs
 * are driven by the chat or workflow routes rather than the scenario control.
 *
 * <p>The peer control token ({@code ARIA_PEER_CONTROL_TOKEN}) is part of the
 * harness environment: the peers refuse to start without it, so the harness
 * refuses to run without it as well.
 */
public final class CoreE2eScenarios {

    /** Environment variable the mock peers require on every launch. */
    public static final String PEER_CONTROL_TOKEN_ENV = "ARIA_PEER_CONTROL_TOKEN";

    /** The minimum length the peers accept (peer-actions.mjs bootPeer). */
    static final int MIN_TOKEN_LENGTH = 16;

    /**
     * The harness default scenario of the built-in agents (Aria and the SDD
     * role agents seeded by {@code V42__seed_sdd_role_agents.sql}): the exact
     * deterministic completion fixture those agents' runs boot on (see
     * {@link #registerDefaults(java.util.List)}), so a built-in chat or workflow
     * step completes deterministically instead of reaching a real core or model
     * -- or failing closed on the scenario gate. An explicit selection through
     * the control route always wins.
     */
    static final String DEFAULT_SCENARIO = "reported-usage";

    /**
     * The deterministic fixture of the SDD QA built-in: the chain's QA step is
     * routed on the {@code VERDICT=} marker its run's output carries
     * ({@code WorkflowAutoChainer.routeOnQaVerdict}), so a QA run booted on the
     * read-only default would complete the chain with "no verdict submitted".
     * This fixture's completion is exactly {@code fixture-qa-report\nVERDICT=PASS}
     * (the recorded QA report convention plus the router's PASS marker).
     */
    static final String QA_SCENARIO = "sdd-qa-pass";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Set<String> knownScenarios;
    private final String peerControlToken;
    private final ConcurrentMap<UUID, String> selections = new ConcurrentHashMap<>();

    CoreE2eScenarios(Path scenarioManifest, String peerControlToken) {
        this.knownScenarios = readScenarioIds(scenarioManifest);
        String token = peerControlToken == null ? "" : peerControlToken.trim();
        if (token.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalStateException("The core E2E harness requires " + PEER_CONTROL_TOKEN_ENV
                    + " (>= " + MIN_TOKEN_LENGTH + " characters): the mock protocol peers refuse to start"
                    + " without it, so a run without it could only fail");
        }
        this.peerControlToken = token;
    }

    /** The exact scenario ids the shipped manifest declares, in manifest order. */
    Set<String> knownScenarios() {
        return knownScenarios;
    }

    /** The token every launched peer receives; never logged. */
    String peerControlToken() {
        return peerControlToken;
    }

    /**
     * Records one selection. An unknown scenario id is refused (the manifest is
     * the authority, never an invented fixture), and re-selecting the same
     * agent is allowed: the latest selection is what the next launch forwards.
     */
    public void select(UUID agentId, String scenario) {
        Objects.requireNonNull(agentId, "agentId");
        if (scenario == null || !knownScenarios.contains(scenario)) {
            throw new IllegalArgumentException("Unknown peer scenario '" + scenario
                    + "'; the committed manifest declares " + knownScenarios);
        }
        selections.put(agentId, scenario);
    }

    /** The selection of one agent, or null when none was ever recorded. */
    public String selection(UUID agentId) {
        return selections.get(Objects.requireNonNull(agentId, "agentId"));
    }

    /**
     * The scenario of the agent's next run. A missing selection is refused: the
     * harness never launches a peer without a declared fixture.
     */
    public String requireScenario(UUID agentId) {
        String scenario = selection(agentId);
        if (scenario == null) {
            throw new IllegalStateException("No deterministic peer scenario was selected for agent "
                    + agentId + "; the harness never starts a core without a declared fixture."
                    + " Select one through POST /api/v1/maintenance/core-e2e/scenario first");
        }
        return scenario;
    }

    /**
     * The built-in agents whose runs must work without an explicit selection:
     * Aria (its conversation spec and the workflow lanes drive the run route,
     * not the scenario control) and the SDD role agents (a workflow chain
     * resolves its steps by agent role, so the specs cannot select a scenario
     * for the built-in that will be picked). Registered once at harness startup;
     * each entry is a selection like any other and an explicit
     * {@link #select(UUID, String)} overrides it.
     */
    void registerDefaults(List<UUID> builtinAgentIds) {
        for (UUID agentId : builtinAgentIds) {
            registerDefault(agentId, DEFAULT_SCENARIO);
        }
    }

    /**
     * Registers one built-in agent's default scenario (same precedence as
     * {@link #registerDefaults(java.util.List)}: an explicit
     * {@link #select(UUID, String)} always wins). The scenario must be declared
     * in the shipped manifest — an invented fixture id is refused here, not at
     * launch time.
     */
    void registerDefault(UUID agentId, String scenario) {
        Objects.requireNonNull(agentId, "agentId");
        if (scenario == null || !knownScenarios.contains(scenario)) {
            throw new IllegalArgumentException("Unknown peer scenario '" + scenario
                    + "'; the committed manifest declares " + knownScenarios);
        }
        selections.putIfAbsent(agentId, scenario);
    }

    private static Set<String> readScenarioIds(Path manifest) {
        if (manifest == null || !Files.isRegularFile(manifest)) {
            throw new IllegalStateException("The core E2E scenario manifest is missing: " + manifest);
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(manifest));
            Set<String> ids = new LinkedHashSet<>();
            for (JsonNode scenario : root.path("scenarios")) {
                String id = scenario.path("id").asText("");
                if (id.isBlank()) {
                    throw new IllegalStateException("A scenario without an id is declared in " + manifest);
                }
                ids.add(id);
            }
            if (ids.isEmpty()) {
                throw new IllegalStateException("The core E2E scenario manifest declares no scenarios: " + manifest);
            }
            return Set.copyOf(ids);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read the core E2E scenario manifest " + manifest, e);
        }
    }
}
