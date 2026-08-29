package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Housekeeping S3: per-category progress while a cleanup batch executes.
 * Transient by design — broadcast over WS only, never persisted.
 */
@Getter
public class HousekeepingProgressEvent extends ApplicationEvent {

    private final String category;
    private final int cleared;
    private final int failed;
    private final long seq;

    public HousekeepingProgressEvent(Object source, String category, int cleared, int failed, long seq) {
        super(source);
        this.category = category;
        this.cleared = cleared;
        this.failed = failed;
        this.seq = seq;
    }
}
