package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class ReportGeneratedEvent extends ApplicationEvent {

    private final String reportId;
    private final String title;
    private final String owner;

    public ReportGeneratedEvent(Object source, String reportId, String title, String owner) {
        super(source);
        this.reportId = reportId;
        this.title = title;
        this.owner = owner;
    }
}
