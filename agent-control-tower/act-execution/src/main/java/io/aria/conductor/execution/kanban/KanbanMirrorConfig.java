package io.aria.conductor.execution.kanban;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The executor every kanban mirroring listener hands its work to.
 *
 * <p>A single thread, on purpose. Mirroring one run is a create followed by a
 * couple of transitions, and the status matrix refuses the reverse order, so
 * the per-run publication order has to survive the hand-off: one queue drained
 * by one thread preserves it, and the work is milliseconds long.
 *
 * <p>The queue is unbounded (Spring's default capacity), so submitting never
 * blocks a publisher; a mirror that is still queued when the application stops
 * is flushed by the graceful shutdown.
 */
@Configuration
public class KanbanMirrorConfig {

    @Bean(name = "kanbanMirrorExecutor")
    public ThreadPoolTaskExecutor kanbanMirrorExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("kanban-mirror-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
