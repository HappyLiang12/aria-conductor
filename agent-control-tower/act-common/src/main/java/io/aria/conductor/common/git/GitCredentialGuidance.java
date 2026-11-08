package io.aria.conductor.common.git;

/**
 * Single source of truth for the GitHub credential guidance shown to operators.
 * The credential is resolved per operation through the git pack credential
 * source, so a credential stored at runtime is used by the next branch
 * operation without restarting the backend (F8).
 */
public final class GitCredentialGuidance {

    private GitCredentialGuidance() {}

    public static final String REQUIRED_MESSAGE =
            "No GitHub credential is configured. Set the GITHUB_TOKEN environment variable before "
                    + "starting the backend, or store it in the git tool pack "
                    + "(POST /api/v1/packs/pack-git-0001/credentials); a stored credential is used "
                    + "by the next operation without a restart.";
}
