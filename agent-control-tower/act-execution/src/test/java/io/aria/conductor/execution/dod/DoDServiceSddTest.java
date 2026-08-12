package io.aria.conductor.execution.dod;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SDD workflow (Task 3): custom stage lists via {@code init(taskId, taskType, stages)}
 * and optional verdict recording via the 7-arg {@code review(...)} overload.
 */
@ExtendWith(MockitoExtension.class)
class DoDServiceSddTest {

    @Mock private DoDRepository dodRepository;
    @Mock private DoDStageReviewRepository reviewRepository;
    @Mock private EvidenceItemRepository evidenceRepository;

    @InjectMocks private DoDService dodService;

    /** Stub helper: persist returns the same record (with assigned id). */
    @BeforeEach
    void wireRepositoryEcho() {
        lenient().when(dodRepository.save(any(DoDRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(reviewRepository.save(any(DoDStageReview.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void init_withCustomStages_setsCurrentStageToFirstCustomStage() {
        when(dodRepository.findByTaskId("chain-1")).thenReturn(Optional.empty());

        DoDRecord record = dodService.init("chain-1", "SDD", List.of("dev", "qa"));

        assertThat(record.getCurrentStage()).isEqualTo("dev");
    }

    @Test
    void init_withoutStages_keepsDefaultStages() {
        when(dodRepository.findByTaskId("chain-2")).thenReturn(Optional.empty());

        DoDRecord record = dodService.init("chain-2", "TASK");

        assertThat(record.getStagesJson()).isNull();
        assertThat(record.getCurrentStage()).isEqualTo("dev");
    }

    @Test
    void review_recordsVerdictAndAdvancesThroughCustomStages() {
        when(dodRepository.findByTaskId("chain-3")).thenReturn(Optional.empty());
        DoDRecord record = dodService.init("chain-3", "SDD", List.of("dev", "qa"));
        stubFind(record);

        dodService.review("chain-3", "eng", "Engine", true, null, null, null); // dev passed
        assertThat(record.getCurrentStage()).isEqualTo("qa");
        dodService.review("chain-3", "qa", "QA", true, null, null, "PASS");   // qa passed
        assertThat(record.getOverallStatus()).isEqualTo(DoDService.STATUS_PASSED);
    }

    @Test
    void review_withNullVerdict_isStillRecorded() {
        when(dodRepository.findByTaskId("chain-4")).thenReturn(Optional.empty());
        DoDRecord record = dodService.init("chain-4", "TASK");
        stubFind(record);

        dodService.review("chain-4", "u", "U", true, null, "c", null);

        verify(reviewRepository).save(argThat(rv -> rv.getVerdict() == null));
    }

    // -------- helpers --------

    /** Stub the find used by review(); the record must already exist in the repo mock. */
    private void stubFind(DoDRecord record) {
        when(dodRepository.findByTaskId(record.getTaskId())).thenReturn(Optional.of(record));
    }
}
