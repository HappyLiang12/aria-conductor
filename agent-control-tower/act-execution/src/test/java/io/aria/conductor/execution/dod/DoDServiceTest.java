package io.aria.conductor.execution.dod;

import io.aria.conductor.execution.dod.dto.CreateEvidenceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DoDServiceTest {

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
        lenient().when(evidenceRepository.save(any(EvidenceItem.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void init_createsRecordWithDevAsFirstStage() {
        when(dodRepository.findByTaskId("T1")).thenReturn(Optional.empty());

        DoDRecord record = dodService.init("T1", "story");

        assertThat(record.getTaskId()).isEqualTo("T1");
        assertThat(record.getTaskType()).isEqualTo("story");
        assertThat(record.getCurrentStage()).isEqualTo("dev");
        assertThat(record.getOverallStatus()).isEqualTo(DoDService.STATUS_IN_PROGRESS);
        assertThat(record.getId()).isNotNull();
    }

    @Test
    void init_isIdempotent() {
        DoDRecord existing = DoDRecord.builder()
                .id("X").taskId("T1").currentStage("qa")
                .overallStatus(DoDService.STATUS_IN_PROGRESS).build();
        when(dodRepository.findByTaskId("T1")).thenReturn(Optional.of(existing));

        DoDRecord result = dodService.init("T1", "story");

        assertThat(result).isSameAs(existing);
        verify(dodRepository, never()).save(any());
    }

    @Test
    void review_passOnDev_advancesToQa() {
        DoDRecord record = givenRecordAtStage("T1", "dev");

        DoDRecord after = dodService.review("T1", "u1", "Alice", true, null, "ok");

        assertThat(after.getCurrentStage()).isEqualTo("qa");
        assertThat(after.getOverallStatus()).isEqualTo(DoDService.STATUS_IN_PROGRESS);
        verifyReviewSaved(record.getId(), "dev", true);
    }

    @Test
    void review_passOnQa_advancesToBa() {
        givenRecordAtStage("T1", "qa");

        DoDRecord after = dodService.review("T1", "u1", "QA", true, null, null);

        assertThat(after.getCurrentStage()).isEqualTo("ba");
    }

    @Test
    void review_failOnRequiredStage_blocksAdvancement() {
        DoDRecord record = givenRecordAtStage("T1", "dev");

        DoDRecord after = dodService.review("T1", "u1", "Alice", false, null, "missing tests");

        assertThat(after.getCurrentStage()).isEqualTo("dev");
        assertThat(after.getOverallStatus()).isEqualTo(DoDService.STATUS_IN_PROGRESS);
        verifyReviewSaved(record.getId(), "dev", false);
    }

    @Test
    void review_failOnOptionalStage_skipsAndAdvances() {
        givenRecordAtStage("T1", "ba");

        DoDRecord after = dodService.review("T1", "u1", "BA", false, null, "n/a");

        assertThat(after.getCurrentStage()).isEqualTo("pm");
        assertThat(after.getOverallStatus()).isEqualTo(DoDService.STATUS_IN_PROGRESS);
    }

    @Test
    void review_passOnLastStage_marksOverallPassed() {
        givenRecordAtStage("T1", "pm");

        DoDRecord after = dodService.review("T1", "u1", "PM", true, null, null);

        assertThat(after.getCurrentStage()).isEqualTo(DoDService.COMPLETED_STAGE);
        assertThat(after.getOverallStatus()).isEqualTo(DoDService.STATUS_PASSED);
    }

    @Test
    void review_allStagesPassedSequentially_endsPassed() {
        DoDRecord record = givenRecordAtStage("T1", "dev");

        dodService.review("T1", "u1", null, true, null, null); // dev→qa
        record.setCurrentStage("qa");
        dodService.review("T1", "u1", null, true, null, null); // qa→ba
        record.setCurrentStage("ba");
        dodService.review("T1", "u1", null, true, null, null); // ba→pm
        record.setCurrentStage("pm");
        DoDRecord after = dodService.review("T1", "u1", null, true, null, null); // pm→completed

        assertThat(after.getOverallStatus()).isEqualTo(DoDService.STATUS_PASSED);
        assertThat(after.getCurrentStage()).isEqualTo(DoDService.COMPLETED_STAGE);
    }

    @Test
    void review_onCompletedRecord_throws() {
        DoDRecord done = DoDRecord.builder()
                .id("X").taskId("T1")
                .currentStage(DoDService.COMPLETED_STAGE)
                .overallStatus(DoDService.STATUS_PASSED).build();
        when(dodRepository.findByTaskId("T1")).thenReturn(Optional.of(done));

        assertThatThrownBy(() -> dodService.review("T1", "u1", null, true, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already completed");
    }

    @Test
    void review_unknownTask_throws() {
        when(dodRepository.findByTaskId("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dodService.review("nope", "u", null, true, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No DoD record");
    }

    @Test
    void getEvidence_returnsRepositoryResult() {
        EvidenceItem item = EvidenceItem.builder().id("e1").taskId("T1").type("LOG").build();
        when(evidenceRepository.findByTaskIdOrderByCreatedAtDesc("T1")).thenReturn(List.of(item));

        List<EvidenceItem> result = dodService.getEvidence("T1");

        assertThat(result).containsExactly(item);
    }

    @Test
    void addEvidence_persistsLinkedToDodRecord() {
        DoDRecord record = givenRecordAtStage("T1", "dev");
        CreateEvidenceRequest request = new CreateEvidenceRequest(
                "LOG", "Build log", "lots of text", "/tmp/x.log", "run-9");

        EvidenceItem saved = dodService.addEvidence("T1", request);

        assertThat(saved.getDodId()).isEqualTo(record.getId());
        assertThat(saved.getTaskId()).isEqualTo("T1");
        assertThat(saved.getType()).isEqualTo("LOG");
        assertThat(saved.getTitle()).isEqualTo("Build log");
        assertThat(saved.getArtifactPath()).isEqualTo("/tmp/x.log");
    }

    @Test
    void buildStatusResponse_exposesStageRollup() {
        DoDRecord record = givenRecordAtStage("T1", "qa");
        DoDStageReview devPass = DoDStageReview.builder()
                .id("r1").dodId(record.getId()).stage("dev").reviewerId("u1")
                .passed(true).build();
        when(reviewRepository.findByDodIdOrderByReviewedAtAsc(record.getId()))
                .thenReturn(List.of(devPass));
        when(evidenceRepository.findByDodId(record.getId())).thenReturn(List.of());

        var response = dodService.buildStatusResponse("T1");

        assertThat(response.currentStage()).isEqualTo("qa");
        assertThat(response.stages()).hasSize(4);
        assertThat(response.stages().get(0).stage()).isEqualTo("dev");
        assertThat(response.stages().get(0).status()).isEqualTo(DoDService.STAGE_PASSED);
        assertThat(response.stages().get(0).required()).isTrue();
        assertThat(response.stages().get(1).stage()).isEqualTo("qa");
        assertThat(response.stages().get(1).status()).isEqualTo(DoDService.STAGE_PENDING);
        assertThat(response.stages().get(2).required()).isFalse();
        assertThat(response.evidenceCount()).isZero();
    }

    // -------- helpers --------

    private DoDRecord givenRecordAtStage(String taskId, String stage) {
        DoDRecord record = DoDRecord.builder()
                .id(UUID.randomUUID().toString())
                .taskId(taskId)
                .currentStage(stage)
                .overallStatus(DoDService.STATUS_IN_PROGRESS)
                .build();
        when(dodRepository.findByTaskId(taskId)).thenReturn(Optional.of(record));
        return record;
    }

    private void verifyReviewSaved(String dodId, String stage, boolean passed) {
        ArgumentCaptor<DoDStageReview> captor = ArgumentCaptor.forClass(DoDStageReview.class);
        verify(reviewRepository, atLeastOnce()).save(captor.capture());
        List<DoDStageReview> matches = new ArrayList<>();
        for (DoDStageReview r : captor.getAllValues()) {
            if (r.getDodId().equals(dodId) && r.getStage().equals(stage) && r.isPassed() == passed) {
                matches.add(r);
            }
        }
        assertThat(matches).as("review saved for stage=%s passed=%s", stage, passed).isNotEmpty();
    }
}
