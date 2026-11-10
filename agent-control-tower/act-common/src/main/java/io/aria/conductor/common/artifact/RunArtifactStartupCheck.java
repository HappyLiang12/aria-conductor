package io.aria.conductor.common.artifact;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Startup self-check that the capture side and the read side resolved the same
 * canonical run artifact root.
 *
 * <p>Both sides now obtain the root from {@link RunArtifactProperties} through
 * {@link RunArtifactLocator}, so this holds by construction. The assertion stays
 * so a future regression -- a module reintroducing its own root -- fails fast at
 * startup instead of silently reading a directory the writer never wrote.
 */
@Component
public class RunArtifactStartupCheck implements InitializingBean {

    private final RunArtifactProperties properties;
    private final RunArtifactLocator locator;

    public RunArtifactStartupCheck(RunArtifactProperties properties, RunArtifactLocator locator) {
        this.properties = properties;
        this.locator = locator;
    }

    @Override
    public void afterPropertiesSet() {
        Path captureRoot = properties.resolvedResultRoot().toAbsolutePath().normalize();
        Path readRoot = locator.resultRoot().toAbsolutePath().normalize();
        if (!captureRoot.equals(readRoot)) {
            throw new IllegalStateException("Run artifact root mismatch: property "
                    + RunArtifactProperties.PROPERTY_KEY + " resolves the capture root to " + captureRoot
                    + " but the read root is " + readRoot
                    + ". The capture and spec-review read must use the same canonical run result root.");
        }
    }
}
