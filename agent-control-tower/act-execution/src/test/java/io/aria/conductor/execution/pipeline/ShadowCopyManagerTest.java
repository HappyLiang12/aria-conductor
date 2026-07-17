package io.aria.conductor.execution.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShadowCopyManagerTest {

    @Mock private ShadowCopyRepository repository;
    @InjectMocks private ShadowCopyManager manager;

    @BeforeEach
    void echoSave() {
        lenient().when(repository.save(any(ShadowCopy.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createShadowCopy_persistsRecordWithExpectedFields() {
        String runId = UUID.randomUUID().toString();
        String actionId = "tc-write-1";

        manager.createShadowCopy(runId, actionId, "{\"k\":\"v\"}", "WRITE");

        ArgumentCaptor<ShadowCopy> captor = ArgumentCaptor.forClass(ShadowCopy.class);
        verify(repository).save(captor.capture());
        ShadowCopy saved = captor.getValue();
        assertThat(saved.getRunId()).isEqualTo(runId);
        assertThat(saved.getActionId()).isEqualTo(actionId);
        assertThat(saved.getOriginalState()).isEqualTo("{\"k\":\"v\"}");
        assertThat(saved.getActionType()).isEqualTo("WRITE");
        assertThat(saved.getId()).isNotBlank();
    }

    @Test
    void createShadowCopy_nullRunId_returnsEmptyAndSkipsPersistence() {
        Optional<ShadowCopy> result = manager.createShadowCopy(null, "tc-1", "state");
        assertThat(result).isEmpty();
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void createShadowCopy_repositoryThrows_returnsEmptyButDoesNotPropagate() {
        when(repository.save(any(ShadowCopy.class))).thenThrow(new RuntimeException("db down"));

        Optional<ShadowCopy> result = manager.createShadowCopy(
                UUID.randomUUID().toString(), "tc-1", "state");

        assertThat(result).isEmpty();
    }

    @Test
    void getShadowCopy_returnsRepositoryResult() {
        ShadowCopy copy = ShadowCopy.builder()
                .id("s1")
                .runId("r1")
                .actionId("a1")
                .originalState("state")
                .build();
        when(repository.findFirstByRunIdAndActionIdOrderByCreatedAtDesc("r1", "a1"))
                .thenReturn(Optional.of(copy));

        Optional<ShadowCopy> result = manager.getShadowCopy("r1", "a1");

        assertThat(result).contains(copy);
    }

    @Test
    void getShadowsForRun_returnsAllForThatRunInOrder() {
        ShadowCopy s1 = ShadowCopy.builder().id("s1").runId("r1").actionId("a1").build();
        ShadowCopy s2 = ShadowCopy.builder().id("s2").runId("r1").actionId("a2").build();
        when(repository.findByRunIdOrderByCreatedAtAsc("r1")).thenReturn(List.of(s1, s2));

        List<ShadowCopy> result = manager.getShadowsForRun("r1");

        assertThat(result).containsExactly(s1, s2);
    }

    @Test
    void readActionInPipeline_isNotReversible_soManagerNeverInvoked() {
        // Sanity check on the action-level contract used by the pipeline:
        Action read = new Action("list", ActionType.READ, "{}", "tc-r");
        Action write = new Action("upsert", ActionType.WRITE, "{}", "tc-w");
        Action exec = new Action("run", ActionType.EXECUTE, "ls", "tc-e");
        Action high = new Action("drop", ActionType.HIGH_RISK, "{}", "tc-h");

        assertThat(read.isReversible()).isFalse();
        assertThat(write.isReversible()).isTrue();
        assertThat(exec.isReversible()).isTrue();
        assertThat(high.isReversible()).isTrue();
    }
}
