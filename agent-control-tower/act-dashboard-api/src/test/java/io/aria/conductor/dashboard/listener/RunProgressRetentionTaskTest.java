package io.aria.conductor.dashboard.listener;

import io.aria.conductor.common.repository.RunProgressEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

class RunProgressRetentionTaskTest {

    @Test
    void cleanup_deletesOlderThanRetentionDays() {
        RunProgressEventRepository repo = org.mockito.Mockito.mock(RunProgressEventRepository.class);
        RunProgressRetentionTask task = new RunProgressRetentionTask(repo, 7);

        task.cleanup();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repo).deleteByCreatedAtBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isBefore(Instant.now().minusSeconds(86400 * 6));
    }
}
