package io.aria.conductor.execution.runtime;

import java.util.Optional;

/**
 * The bind point between a run's owning workflow and the runtime's workspace
 * provisioning (F15): a run that names a repository starts with an isolated
 * shallow checkout of it instead of an empty scratch directory the agent then
 * fills by reading the operator's live worktree.
 *
 * <p>Which run gets which checkout is a workflow decision -- the BA step of an SDD
 * chain knows the repository and base ref, a plain operator chat does not -- so the
 * runtime asks this seam instead of guessing from the execution settings, where no
 * "scratch plus repository" combination exists ({@code WorkspaceMode} has no
 * SCRATCH value; a path-bearing setting is normalized to a worktree). The workflow
 * lane supplies one bean; with none wired, scratch provisioning is unchanged.
 *
 * <p>A returned binding is provisioned by {@link RunWorkspaceService} as a shallow
 * ({@code --depth 1 --single-branch}) clone at {@code <scratch>/repo}, authenticated
 * with the resolved git-pack credential when one exists. A propagation that cannot
 * be checked out refuses the run instead of starting an empty workspace.
 */
@FunctionalInterface
public interface ScratchWorkspaceProvisioner {

    /** No repository binding for this run; its scratch stays empty. */
    ScratchWorkspaceProvisioner NONE = spec -> Optional.empty();

    /**
     * The repository this run's scratch workspace must be checked out from, or
     * empty when the run needs no checkout.
     */
    Optional<RepositoryBinding> bindingFor(ExecutionSpec spec);

    /**
     * The repository to check out and the ref to check out from it.
     *
     * @param repository a cloneable git URL or local repository path
     * @param baseRef a branch or tag; blank means the repository's default branch
     */
    record RepositoryBinding(String repository, String baseRef) {
        public RepositoryBinding {
            if (repository == null || repository.isBlank()) {
                throw new IllegalArgumentException("A scratch checkout binding requires a repository");
            }
            baseRef = baseRef == null || baseRef.isBlank() ? null : baseRef;
        }
    }
}
