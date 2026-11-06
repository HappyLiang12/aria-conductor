package io.aria.conductor.execution.approval;

import io.aria.conductor.common.event.ApprovalDecidedEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.runtime.RunInputCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Guards of {@link ApprovalAnswerService} (R20.6 + 2026-10-05 spec §5): free-text answers must
 * not bypass the ACP permission policy — an ACP ask is refused outright and only legacy rows
 * stay answerable. A CLARIFICATION ask, by contrast, IS answerable: the answer settles the ask
 * APPROVED, records the operator's turn on the trajectory, and wakes the parked run.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalAnswerServiceTest {

    private static final UUID RUN_ID = UUID.randomUUID();

    @Mock ApprovalRepository approvalRepository;
    @Mock RunInputCoordinator inputs;
    @Mock SessionTrajectoryRepository trajectoryRepository;
    @Mock ApplicationEventPublisher events;

    ApprovalAnswerService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalAnswerService(approvalRepository, inputs, trajectoryRepository, events);
    }

    @Test
    void answer_refusesAcpPermissionRows_andNamesTheCoordinator() {
        UUID id = UUID.randomUUID();
        Approval acpAsk = Approval.builder()
                .id(id)
                .runId(UUID.randomUUID())
                .status(ApprovalStatus.PENDING)
                .askType(Approval.AskType.APPROVAL)
                .source(ApprovalSource.ACP_PERMISSION)
                .build();
        when(approvalRepository.findById(id)).thenReturn(Optional.of(acpAsk));

        assertThatThrownBy(() -> service.answer(id, "free-text bypass", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ACP permission coordinator");
        verify(approvalRepository, never()).save(any(Approval.class));
    }

    @Test
    void answer_freeTextOnLegacyPendingAsk_isStillRecorded() {
        UUID id = UUID.randomUUID();
        Approval legacy = Approval.builder()
                .id(id)
                .runId(UUID.randomUUID())
                .status(ApprovalStatus.PENDING)
                .source(ApprovalSource.LEGACY_GATE)
                .build();
        when(approvalRepository.findById(id)).thenReturn(Optional.of(legacy));
        when(approvalRepository.save(any(Approval.class))).thenAnswer(inv -> inv.getArgument(0));

        Approval answered = service.answer(id, "use semicolons", null, null);

        assertThat(answered.getAnswer()).isEqualTo("use semicolons");
        assertThat(answered.getStatus()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void answeringAClarificationSettlesTheAskAndWakesTheRun() {
        Approval ask = Approval.builder()
                .id(UUID.randomUUID())
                .runId(RUN_ID)
                .status(ApprovalStatus.PENDING)
                .askType(Approval.AskType.QUESTION)
                .source(ApprovalSource.CLARIFICATION)
                .build();
        when(approvalRepository.findById(ask.getId())).thenReturn(Optional.of(ask));
        when(approvalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(trajectoryRepository.findMaxTurnNumberByRunId(RUN_ID)).thenReturn(3);

        service.answer(ask.getId(), "postgres", true, null);

        ArgumentCaptor<SessionTrajectory> row = ArgumentCaptor.forClass(SessionTrajectory.class);
        verify(trajectoryRepository).save(row.capture());
        assertThat(row.getValue().getRunId()).isEqualTo(RUN_ID);
        assertThat(row.getValue().getRole()).isEqualTo("user");
        assertThat(row.getValue().getTurnNumber()).isEqualTo(4);
        assertThat(row.getValue().getContent()).isEqualTo("postgres");
        verify(inputs).submitAnswer(RUN_ID, "postgres");
        ArgumentCaptor<Approval> saved = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(saved.getValue().getReason()).isEqualTo("postgres");
        assertThat(saved.getValue().getAnswer()).isEqualTo("postgres");
        // Ruling 2: the settlement is announced exactly like every other decide path
        // (ApprovalGate.decideApproval), so the dashboard/notification listeners see it.
        ArgumentCaptor<org.springframework.context.ApplicationEvent> published =
                ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOf(ApprovalDecidedEvent.class);
        ApprovalDecidedEvent decided = (ApprovalDecidedEvent) published.getValue();
        assertThat(decided.getApprovalId()).isEqualTo(ask.getId());
        assertThat(decided.getDecision()).isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    void denyAnswerToAClarificationIsRefused() {
        Approval ask = Approval.builder().id(UUID.randomUUID()).runId(RUN_ID).status(ApprovalStatus.PENDING)
                .askType(Approval.AskType.QUESTION).source(ApprovalSource.CLARIFICATION).build();
        when(approvalRepository.findById(ask.getId())).thenReturn(Optional.of(ask));

        assertThatThrownBy(() -> service.answer(ask.getId(), "no", false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finalize");
        verifyNoInteractions(inputs);
    }

    @Test
    void blankAnswerToAClarificationIsRefused() {
        Approval ask = Approval.builder().id(UUID.randomUUID()).runId(RUN_ID).status(ApprovalStatus.PENDING)
                .askType(Approval.AskType.QUESTION).source(ApprovalSource.CLARIFICATION).build();
        when(approvalRepository.findById(ask.getId())).thenReturn(Optional.of(ask));

        assertThatThrownBy(() -> service.answer(ask.getId(), "  ", true, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(inputs);
    }

    /**
     * Ruling 1 (answer race): the loser of two concurrent /answer calls finds the pending
     * entry already consumed by the winner and surfaces {@link IllegalStateException} from
     * {@code submitAnswer}. In production the whole {@code @Transactional} settlement (ask
     * save + trajectory row) rolls back with it and the controller maps the ISE to 409.
     */
    @Test
    void wakingANonWaitingRunSurfacesIllegalState() {
        Approval ask = Approval.builder().id(UUID.randomUUID()).runId(RUN_ID).status(ApprovalStatus.PENDING)
                .askType(Approval.AskType.QUESTION).source(ApprovalSource.CLARIFICATION).build();
        when(approvalRepository.findById(ask.getId())).thenReturn(Optional.of(ask));
        doThrow(new IllegalStateException("Run " + RUN_ID + " is not waiting for operator input"))
                .when(inputs).submitAnswer(RUN_ID, "postgres");

        assertThatThrownBy(() -> service.answer(ask.getId(), "postgres", true, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not waiting for operator input");
    }
}
