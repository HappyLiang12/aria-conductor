package io.aria.conductor.common.port;

import io.aria.conductor.common.model.RunExecutionBinding;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only view of the run's frozen execution binding for the run store
 * (Fleet Runtime Governance): the run store (act-agent) maps each run to its
 * dashboard response carrying the core/mode the run was dispatched with, but
 * the binding repository lives in the execution module and the run store must
 * not depend on it -- the same module-direction problem {@link
 * RunRuntimeControlPort} solves, resolved the same way: a port in act-common,
 * served by the execution module, consumed as an optional dependency (a run
 * without a binding row -- legacy runs, or a deployment that does not serve the
 * port -- carries null runtime fields, never an invented snapshot).
 */
public interface RunBindingReaderPort {

    /** The binding frozen for the run, or empty when the run has none. */
    Optional<RunExecutionBinding> findByRunId(UUID runId);

    /** Batched by-run bindings for run-list mappings; absent runs simply have no entry. */
    List<RunExecutionBinding> findByRunIdIn(Collection<UUID> runIds);
}
