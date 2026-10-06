package io.aria.conductor.execution.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunExecutionBinding;
import io.aria.conductor.common.runtime.AgentExecutionPolicy;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.execution.credential.CoreCredentialService;
import io.aria.conductor.execution.repository.RunExecutionBindingRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * The production cutover path of one run (Task 18): resolve the agent's frozen
 * core/mode/workspace selection through the shared admission policy, freeze the
 * run's immutable execution binding (once -- an existing row is the run's own
 * record and is never re-resolved from the agent's mutable settings), and hand
 * the attempt to the run coordinator.
 *
 * <p>The supported production cores are exactly the registered adapters
 * ({@code opencode}, {@code qoder}); an unknown core is refused instead of
 * being substituted. The credential reference is per core: the Qoder core
 * consumes the operator-bound runtime credential, the OpenCode core carries no
 * platform credential (its model credentials are delivered through the
 * reviewed sandbox/core environment).
 */
@Slf4j
public class CoreRunLauncher {

    /** The runtime credential reference of the Qoder core. */
    public static final String QODER_CREDENTIAL_REFERENCE = CoreCredentialService.QODER_CREDENTIAL_REFERENCE;

    private final AgentExecutionPolicy policy;
    private final CoreAdapters adapters;
    private final CoreExecutionService coordinator;
    private final RunExecutionBindingRepository bindings;
    private final TaskDeadlineProperties deadlines;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public CoreRunLauncher(AgentExecutionPolicy policy, CoreAdapters adapters,
            CoreExecutionService coordinator, RunExecutionBindingRepository bindings,
            TaskDeadlineProperties deadlines, Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.adapters = Objects.requireNonNull(adapters, "adapters");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The normalized selection of the agent, before freezing. */
    public AgentExecutionSettings resolveSettings(Agent agent) {
        return policy.normalize(new AgentExecutionSettings(agent.getAdkProvider(),
                agent.getExecutionMode(), agent.getWorkspaceMode(), agent.getWorkspacePath(),
                agent.getWorkspaceBaseRef()));
    }

    /**
     * Whether the run is owned by the production core path.
     *
     * <p>Ownership is decided by the selected core alone, never by the whole
     * selection: a core in the production catalog is owned even when the rest of
     * the selection is invalid, because such a run must fail with the admission
     * refusal ({@link #execute} surfaces the exact message) and must never
     * execute through the pre-cutover provider path. Only a core outside the
     * catalog (unknown or removed) is not owned; the caller refuses it through
     * the provider registry's explicit no-fallback failure.
     *
     * <p>An agent that stores no core is owned as well: admission applies the
     * documented default core, which the cutover wiring registers.
     */
    public boolean owns(Agent agent) {
        if (agent == null) {
            return false;
        }
        String coreId = agent.getAdkProvider();
        return coreId == null || coreId.isBlank() || adapters.supports(coreId);
    }

    /**
     * Executes one run-owned attempt through the coordinator.
     *
     * <p>The binding is frozen on the first attempt and reused on a re-attempt
     * (a resume): the frozen row is the authority, so its settings, deadline and
     * configuration revision are the ones the coordinator re-validates.
     */
    public CoreResult execute(Run run, Agent agent, CoreTask task) {
        Objects.requireNonNull(task, "task");
        RunExecutionBinding binding = bindings.findById(run.getId()).orElse(null);
        AgentExecutionSettings settings;
        String coreId;
        io.aria.conductor.common.runtime.ExecutionMode mode;
        Instant deadline;
        String credentialRef;
        String configurationRevision;
        if (binding != null) {
            settings = frozenSettings(binding);
            coreId = binding.getCoreId();
            mode = binding.getExecutionMode();
            deadline = binding.getDeadline();
            credentialRef = binding.getCredentialRef();
            configurationRevision = binding.getConfigurationRevision();
        } else {
            settings = resolveSettings(agent);
            coreId = settings.coreId();
            mode = settings.executionMode();
            // The deadline is persisted in a TIMESTAMP column, so freeze it at the
            // persistence granularity (whole seconds, the coarsest supported engine:
            // a MariaDB `TIMESTAMP` carries no fractional seconds). A finer freeze
            // value cannot survive the round trip, and the coordinator's
            // requireFrozenMatch compares the frozen deadline exactly -- a
            // nanosecond value would make every coordinated run unstartable.
            deadline = clock.instant().plus(deadlines.deadline()).truncatedTo(ChronoUnit.SECONDS);
            credentialRef = credentialReference(coreId);
            configurationRevision = configurationRevision(agent);
            binding = RunExecutionBinding.builder()
                    .runId(run.getId())
                    .agentId(agent.getId())
                    .coreId(coreId)
                    .executionMode(mode)
                    .settingsJson(serializeSettings(settings))
                    .credentialRef(credentialRef)
                    .configurationRevision(configurationRevision)
                    .deadline(deadline)
                    .build();
            bindings.save(binding);
            log.info("Froze run {} to core {}/{}", run.getId(), coreId, mode);
        }

        ExecutionSpec spec = new ExecutionSpec(run.getId(), agent.getId(), coreId, mode, settings,
                credentialRef, configurationRevision, deadline);
        return coordinator.execute(spec, adapters.require(coreId), task);
    }

    private AgentExecutionSettings frozenSettings(RunExecutionBinding binding) {
        String json = binding.getSettingsJson();
        if (json == null || json.isBlank()) {
            // A frozen row without a settings snapshot is incomplete: refuse
            // instead of re-resolving the agent's mutable settings.
            throw new IllegalStateException("Run " + binding.getRunId()
                    + " carries no frozen settings snapshot; the run is not executable");
        }
        try {
            return mapper.readValue(json, AgentExecutionSettings.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Run " + binding.getRunId()
                    + " carries an unreadable frozen settings snapshot: " + e.getMessage(), e);
        }
    }

    private String serializeSettings(AgentExecutionSettings settings) {
        try {
            return mapper.writeValueAsString(settings);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize the frozen execution settings: "
                    + e.getMessage(), e);
        }
    }

    /** The per-core credential reference; the OpenCode core carries none. */
    static String credentialReference(String coreId) {
        return "qoder".equals(coreId) ? QODER_CREDENTIAL_REFERENCE : null;
    }

    private static String configurationRevision(Agent agent) {
        UUID id = agent.getId();
        Instant updatedAt = agent.getUpdatedAt();
        return id == null ? null : id + "@" + (updatedAt == null ? "unversioned" : updatedAt);
    }
}
