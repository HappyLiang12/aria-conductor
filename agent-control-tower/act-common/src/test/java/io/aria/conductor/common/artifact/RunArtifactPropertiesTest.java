package io.aria.conductor.common.artifact;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunArtifactPropertiesTest {

    @TempDir
    Path temp;

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(RunArtifactProperties.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void aBlankRootFailsFastNamingTheProperty() {
        RunArtifactProperties properties = new RunArtifactProperties();
        properties.setResultRoot("   ");

        assertThatThrownBy(properties::resolvedResultRoot)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RunArtifactProperties.PROPERTY_KEY)
                .hasMessageContaining("must be configured");
    }

    @Test
    void anUnsetRootFailsFastNamingTheProperty() {
        RunArtifactProperties properties = new RunArtifactProperties();

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RunArtifactProperties.PROPERTY_KEY);
    }

    @Test
    void aRelativeRootIsAnchoredAtTheWorkingDirectory() {
        RunArtifactProperties properties = new RunArtifactProperties();
        properties.setResultRoot("./data/custom-results");

        assertThat(properties.resolvedResultRoot())
                .isEqualTo(Path.of("./data/custom-results").toAbsolutePath().normalize());
    }

    @Test
    void aPercentTokenIsExpanded() {
        Path root = RunArtifactProperties.resolve("%OUT%/results", Map.of("OUT", temp.toString()));

        assertThat(root).isEqualTo(temp.resolve("results").toAbsolutePath().normalize());
    }

    @Test
    void anUnknownPercentTokenIsRefusedByNameAndProperty() {
        assertThatThrownBy(() -> RunArtifactProperties.resolve("%NOT_REAL%/x", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT_REAL")
                .hasMessageContaining(RunArtifactProperties.PROPERTY_KEY);
    }

    @Test
    void startupLogsTheResolvedAbsoluteRootOnce() {
        RunArtifactProperties properties = new RunArtifactProperties();
        properties.setResultRoot(temp.resolve("resolved-root").toString());

        properties.afterPropertiesSet();

        assertThat(logs.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.INFO);
                    assertThat(event.getFormattedMessage())
                            .contains("run result root resolved: path=")
                            .contains(properties.resolvedResultRoot().toString())
                            .contains(RunArtifactProperties.PROPERTY_KEY);
                });
    }

    @Test
    void aMissingRootLogsAWarnButDoesNotFailStartup() {
        RunArtifactProperties properties = new RunArtifactProperties();
        Path missing = temp.resolve("not-mounted-yet");
        properties.setResultRoot(missing.toString());

        properties.afterPropertiesSet();

        assertThat(logs.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains(missing.toString());
                });
    }

    @Test
    void theResolvedRootIsNormalizedAbsoluteAndStable() {
        RunArtifactProperties properties = new RunArtifactProperties();
        properties.setResultRoot(temp.resolve("a/../b/./c").toString());

        assertThat(properties.resolvedResultRoot()).isAbsolute();
        assertThat(properties.resolvedResultRoot()).isEqualTo(temp.resolve("b/c").toAbsolutePath().normalize());
        assertThat(properties.resolvedResultRoot()).isSameAs(properties.resolvedResultRoot());
    }
}
