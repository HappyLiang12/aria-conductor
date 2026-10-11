package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.model.RunExecutionBinding;
import io.aria.conductor.common.port.RunBindingReaderPort;
import io.aria.conductor.execution.repository.RunExecutionBindingRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The run store's read-only binding view ({@link RunBindingReaderPort}), served
 * from the execution module's run-keyed binding store (Fleet Runtime
 * Governance): the dashboard run responses badge the core/mode each run was
 * dispatched with, read through this port so the run store (act-agent) never
 * depends on the execution module's repository -- the same module-direction
 * seam {@link CoordinatedRunRuntimeControl} serves for verified pause/resume.
 *
 * <p>Read-only by contract: the binding rows are written by the launcher before
 * a run is placed on its frozen core and are never mutated through this port.
 */
public class RepositoryRunBindingReader implements RunBindingReaderPort {

    private final RunExecutionBindingRepository bindings;

    public RepositoryRunBindingReader(RunExecutionBindingRepository bindings) {
        this.bindings = bindings;
    }

    @Override
    public Optional<RunExecutionBinding> findByRunId(UUID runId) {
        return runId == null ? Optional.empty() : bindings.findById(runId);
    }

    @Override
    public List<RunExecutionBinding> findByRunIdIn(Collection<UUID> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return List.of();
        }
        return bindings.findAllById(runIds);
    }
}
