package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class ReportAmendedEvent extends ApplicationEvent {

    private final String reportId;
    private final String instruction;

    public ReportAmendedEvent(Object source, String reportId, String instruction) {
        super(source);
        this.reportId = reportId;
        this.instruction = instruction;
    }
}
