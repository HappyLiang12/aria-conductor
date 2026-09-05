package io.aria.conductor.app.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Attaches a programmatic logback {@link ListAppender} to a logger for assertions. */
final class CaptureLogAppender {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private CaptureLogAppender(Class<?> type) {
        this.logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type);
        appender.start();
        logger.addAppender(appender);
    }

    static CaptureLogAppender attach(Class<?> type) {
        return new CaptureLogAppender(type);
    }

    List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    void detach() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
