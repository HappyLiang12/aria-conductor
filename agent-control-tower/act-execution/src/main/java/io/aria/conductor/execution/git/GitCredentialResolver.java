package io.aria.conductor.execution.git;

import io.aria.conductor.execution.credential.PackCredentialService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Resolves the GitHub token used for git operations. The resolution order is:
 * <ol>
 *   <li>Pack credential store under {@code GITHUB_TOKEN}</li>
 *   <li>Pack credential store under the deprecated {@code GH_TOKEN} alias</li>
 *   <li>Host environment variable {@code GITHUB_TOKEN}</li>
 *   <li>Host environment variable {@code GH_TOKEN}</li>
 * </ol>
 *
 * <p>A credential row that cannot be decrypted (e.g. after a key rotation) is
 * skipped with a warning; the resolver falls through to the next source.
 *
 * <p>This service is shared by {@link GitBranchConfig} (for REST API calls) and
 * the workspace preparation step (for agent-core git operations via credential helper).
 */
@Slf4j
@Service
public class GitCredentialResolver {

    static final String GIT_PACK_ID = "pack-git-0001";
    static final String GITHUB_TOKEN_KEY = "GITHUB_TOKEN";
    static final String DEPRECATED_GH_TOKEN_KEY = "GH_TOKEN";

    private final PackCredentialService credentialService;

    public GitCredentialResolver(PackCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    /**
     * Resolves the GitHub token. Returns an empty optional when no token is
     * available from any source.
     *
     * <p>{@link PackCredentialService#resolve} already falls back to
     * {@code System.getenv(credKey)}, so the canonical key lookup covers the
     * host-environment case for {@code GITHUB_TOKEN}; the deprecated alias
     * lookup covers {@code GH_TOKEN}.
     */
    public Optional<String> resolveToken() {
        String token;
        try {
            token = credentialService.resolve(GIT_PACK_ID, null, GITHUB_TOKEN_KEY);
            if (token == null || token.isBlank()) {
                token = credentialService.resolve(GIT_PACK_ID, null, DEPRECATED_GH_TOKEN_KEY);
                if (token != null && !token.isBlank()) {
                    log.warn("GitHub credential resolved from the deprecated GH_TOKEN name; "
                            + "rename it to GITHUB_TOKEN");
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not resolve the GitHub credential for pack {} — {}: {}. "
                    + "Git credential helper will not be configured",
                    GIT_PACK_ID, e.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(token);
    }

    /**
     * Returns true when a token is available from any source.
     */
    public boolean isTokenAvailable() {
        return resolveToken().isPresent();
    }
}
