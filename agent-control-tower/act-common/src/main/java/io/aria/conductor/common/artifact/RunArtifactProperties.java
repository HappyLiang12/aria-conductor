package io.aria.conductor.common.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single canonical definition of the run artifact (result) root.
 *
 * <p>Both the execution side (which captures a run's artifacts) and the
 * knowledge side (which reads a run's exported {@code spec.md}) obtain the root
 * from this bean. The property key is the existing
 * {@value #PROPERTY_KEY}; no module keeps its own {@code @Value} default for it.
 *
 * <p>{@link #resolvedResultRoot()} is the only place where placeholder and
 * relative-path normalization happens for this root. The effective value is
 * resolved once at startup and reused by every consumer, so a trailing
 * separator or a {@code .}/{@code ..} segment cannot make the write and read
 * sides disagree.
 */
@Component
@ConfigurationProperties(prefix = "aria.workspaces")
public class RunArtifactProperties implements InitializingBean {

    /** The operator-facing property key this bean binds to. */
    public static final String PROPERTY_KEY = "aria.workspaces.result-root";

    private static final Logger log = LoggerFactory.getLogger(RunArtifactProperties.class);

    /** A Windows-style {@code %NAME%} token (F21). */
    private static final Pattern PERCENT_TOKEN = Pattern.compile("%([^%\\s]+)%");

    /** The raw configured value; bound from {@value #PROPERTY_KEY}. */
    private String resultRoot;

    /** The normalized absolute root, resolved once. */
    private volatile Path resolved;

    public String getResultRoot() {
        return resultRoot;
    }

    public void setResultRoot(String resultRoot) {
        this.resultRoot = resultRoot;
        this.resolved = null;
    }

    /**
     * Startup validation: resolve the configured root once, log it in a stable
     * greppable form, and warn (without failing) when the directory does not
     * exist yet -- an operator may mount storage after boot.
     */
    @Override
    public void afterPropertiesSet() {
        Path root = resolvedResultRoot();
        log.info("run result root resolved: path={} property={}", root, PROPERTY_KEY);
        if (!Files.exists(root)) {
            log.warn("run result root does not exist: path={} property={}", root, PROPERTY_KEY);
        }
    }

    /**
     * The configured root as a normalized absolute path. The value is resolved
     * once and cached; a blank value fails fast naming the property.
     */
    public Path resolvedResultRoot() {
        Path current = resolved;
        if (current == null) {
            synchronized (this) {
                current = resolved;
                if (current == null) {
                    current = resolve(resultRoot);
                    resolved = current;
                }
            }
        }
        return current;
    }

    /**
     * A properties instance whose root is already resolved (the test seam used
     * by callers that construct workspace services directly).
     */
    public static RunArtifactProperties forResolvedRoot(Path root) {
        if (root == null) {
            throw new IllegalArgumentException(PROPERTY_KEY + " must be configured: the run result root is required");
        }
        RunArtifactProperties properties = new RunArtifactProperties();
        Path normalized = root.toAbsolutePath().normalize();
        properties.resultRoot = normalized.toString();
        properties.resolved = normalized;
        return properties;
    }

    /**
     * Normalizes a configured value: blank is refused by name, {@code %NAME%}
     * tokens are expanded from the environment (or refused when unknown), and a
     * relative value is anchored at the process working directory.
     */
    static Path resolve(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(PROPERTY_KEY + " must be configured: the run result root is required"
                    + " (a blank value would silently resolve to the process working directory)");
        }
        String expanded = expand(configured, System.getenv());
        return Path.of(expanded).toAbsolutePath().normalize();
    }

    static Path resolve(String configured, Map<String, String> environment) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(PROPERTY_KEY + " must be configured: the run result root is required"
                    + " (a blank value would silently resolve to the process working directory)");
        }
        return Path.of(expand(configured, environment)).toAbsolutePath().normalize();
    }

    private static String expand(String configured, Map<String, String> environment) {
        Matcher matcher = PERCENT_TOKEN.matcher(configured);
        StringBuilder expanded = new StringBuilder();
        List<String> unknown = new ArrayList<>();
        int last = 0;
        while (matcher.find()) {
            expanded.append(configured, last, matcher.start());
            String name = matcher.group(1);
            String value = environmentValue(environment, name);
            if (value == null) {
                unknown.add(name);
                expanded.append(matcher.group());
            } else {
                expanded.append(value);
            }
            last = matcher.end();
        }
        expanded.append(configured.substring(last));
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(PROPERTY_KEY + " carries an unexpanded Windows variable token: "
                    + unknown + ". Set the variable in the environment that starts the backend, or replace the"
                    + " token with its absolute path; a literal %NAME% directory is never created.");
        }
        return expanded.toString();
    }

    private static String environmentValue(Map<String, String> environment, String name) {
        if (environment == null || environment.isEmpty()) {
            return null;
        }
        String exact = environment.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
