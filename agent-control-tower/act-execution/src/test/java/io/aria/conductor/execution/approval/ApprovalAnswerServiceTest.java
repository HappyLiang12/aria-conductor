package io.aria.conductor.execution.approval;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards of {@link ApprovalAnswerService} (R20.6): free-text answers must not bypass the ACP
 * permission policy — an ACP ask is refused outright and only legacy rows stay answerable.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalAnswerServiceTest {

    @Mock ApprovalRepository approvalRepository;

    ApprovalAnswerService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalAnswerService(approvalRepository);
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
}
