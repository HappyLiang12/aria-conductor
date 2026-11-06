package io.aria.conductor.common.git;

/**
 * Single source of truth for the GitHub credential guidance shown to operators.
 * The credential is resolved once when the Spring context starts, so a credential
 * written at runtime does not reach the running process — hence the restart step.
 */
public final class GitCredentialGuidance {

    private GitCredentialGuidance() {}

    public static final String REQUIRED_MESSAGE =
            "No GitHub credential is configured. Set the GITHUB_TOKEN environment variable and "
                    + "restart the backend; the credential is resolved once at startup. An operator "
                    + "with API access can instead store it in the git tool pack "
                    + "(POST /api/v1/packs/pack-git-0001/credentials) and restart.";
}
