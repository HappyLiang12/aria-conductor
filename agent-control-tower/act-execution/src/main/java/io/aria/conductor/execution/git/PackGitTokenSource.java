package io.aria.conductor.execution.git;

import io.aria.conductor.execution.credential.PackCredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The git pack credential, resolved on every call (F8).
 *
 * <p>Precedence is unchanged from the eager bean it replaces: the canonical
 * {@code GITHUB_TOKEN} key is resolved first (including its own host-environment
 * fallback inside {@link PackCredentialService}), and the transitional
 * {@code GH_TOKEN} name is consulted only when that lookup yields nothing. The
 * difference is when: each {@link #token()} call reads the store, so a credential
 * written through the pack endpoint is used by the next branch operation without
 * a restart.
 *
 * <p>A row that cannot be decrypted (a blank or foreign value, or every row after
 * a {@code PACK_CREDENTIAL_KEY} rotation) is reported as "no credential" with a
 * warning that names the pack id and the key only. Letting it escape would turn a
 * plain client request into an unhandled failure, and the eagerly-created caller
 * would abort context startup instead of disabling git operations.
 */
final class PackGitTokenSource implements GitTokenSource {

    private static final Logger log = LoggerFactory.getLogger(PackGitTokenSource.class);

    private final PackCredentialService credentialService;
    private final AtomicBoolean canonicalFailureReported = new AtomicBoolean();
    private final AtomicBoolean aliasFailureReported = new AtomicBoolean();
    private final AtomicBoolean aliasInUseReported = new AtomicBoolean();

    PackGitTokenSource(PackCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @Override
    public String token() {
        String token = resolve(GitBranchConfig.GITHUB_TOKEN_KEY, canonicalFailureReported);
        if (token != null && !token.isBlank()) {
            return token;
        }
        String alias = resolve(GitBranchConfig.DEPRECATED_GH_TOKEN_KEY, aliasFailureReported);
        if (alias != null && !alias.isBlank()) {
            if (aliasInUseReported.compareAndSet(false, true)) {
                log.warn("GitHub credential resolved from the deprecated GH_TOKEN name; rename it to"
                        + " GITHUB_TOKEN");
            }
            return alias;
        }
        return null;
    }

    private String resolve(String key, AtomicBoolean failureReported) {
        try {
            return credentialService.resolve(GitBranchConfig.GIT_PACK_ID, null, key);
        } catch (RuntimeException e) {
            if (failureReported.compareAndSet(false, true)) {
                // Pack id and credential key only: the value never reaches the log.
                log.warn("Could not resolve the GitHub credential for pack {} (key {}) — {}: {}. "
                                + "Git branch operations will report the credential as unavailable until the"
                                + " stored row can be read",
                        GitBranchConfig.GIT_PACK_ID, key, e.getClass().getSimpleName(), e.getMessage());
            }
            return null;
        }
    }
}
