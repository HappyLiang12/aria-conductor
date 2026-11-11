package io.aria.conductor.execution.engine;

import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.event.MemoryProposalCreatedEvent;
import io.aria.conductor.common.event.RunWaitingForInputEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.tool.ToolPolicy;
import io.aria.conductor.execution.pipeline.ToolRiskResolver;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The after-commit signal listener (spec §5.1 / decision A2): an ask is turned
 * into the SSE frame the panel renders, in order, on the turn's own stream.
 *
 * <p>Two properties are load-bearing and checked here: the listener runs only
 * after the ask's transaction committed (a panel that fetches the approval on
 * the event would otherwise read nothing), and the tool identity and tier come
 * from the persisted row, not from the event — an ACP ask is published through
 * the four-argument constructor, which labels every ask {@code LEGACY_GATE}.
 */
@ExtendWith(MockitoExtension.class)
class RunSignalSseListenerTest {

    @Mock RunStreamRegistry registry;
    @Mock ApprovalRepository approvals;
    @Mock ToolRiskResolver resolver;

    private RunSignalSseListener listener;

    @BeforeEach
    void setUp() {
        listener = new RunSignalSseListener(registry, approvals, resolver);
    }

    @Test
    void anAcpAskEmitsApprovalRequiredWithTheRowsToolIdentityAndPolicy() {
        UUID runId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-11-11T12:00:00Z");
        Approval row = Approval.builder()
                .id(approvalId)
                .runId(runId)
                .status(ApprovalStatus.PENDING)
                .approvalType(Approval.ApprovalType.TOOL_CALL)
                .askType(Approval.AskType.APPROVAL)
                .toolName("mcp__aria-conductor__remember_fact")
                .riskTier("WRITE_LOCAL")
                .optionsJson("[{\"optionId\":\"allow_once\",\"name\":\"Allow once\"}]")
                .expiresAt(expiresAt)
                .build();
        when(registry.hasStream(runId)).thenReturn(true);
        when(approvals.findById(approvalId)).thenReturn(Optional.of(row));
        when(resolver.policyOf("mcp__aria-conductor__remember_fact")).thenReturn(ToolPolicy.WORKER_WRITE);
        when(registry.emit(eq(runId), eq("approval_required"), any())).thenReturn(true);

        listener.onApprovalRequested(new ApprovalRequestedEvent(this, approvalId, runId, null));

        ArgumentCaptor<Map<String, Object>> payload = mapCaptor();
        verify(registry).emit(eq(runId), eq("approval_required"), payload.capture());
        assertThat(payload.getValue())
                .containsOnlyKeys("runId", "approvalId", "toolName", "riskTier", "toolPolicy",
                        "optionsJson", "expiresAt")
                .containsEntry("runId", runId.toString())
                .containsEntry("approvalId", approvalId.toString())
                .containsEntry("toolName", "mcp__aria-conductor__remember_fact")
                .containsEntry("riskTier", "WRITE_LOCAL")
                .containsEntry("toolPolicy", "WORKER_WRITE")
                .containsEntry("optionsJson", "[{\"optionId\":\"allow_once\",\"name\":\"Allow once\"}]")
                .containsEntry("expiresAt", "2026-11-11T12:00:00Z");
    }

    @Test
    void aToolTheCatalogDoesNotClassifyCarriesANullPolicyButStillEmits() {
        UUID runId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        Approval row = Approval.builder()
                .id(approvalId).runId(runId).status(ApprovalStatus.PENDING)
                .toolName("some-worker-tool").riskTier("READ").build();
        when(registry.hasStream(runId)).thenReturn(true);
        when(approvals.findById(approvalId)).thenReturn(Optional.of(row));
        when(resolver.policyOf("some-worker-tool")).thenReturn(null);
        when(registry.emit(eq(runId), eq("approval_required"), any())).thenReturn(true);

        listener.onApprovalRequested(new ApprovalRequestedEvent(this, approvalId, runId, null));

        ArgumentCaptor<Map<String, Object>> payload = mapCaptor();
        verify(registry).emit(eq(runId), eq("approval_required"), payload.capture());
        assertThat(payload.getValue()).containsEntry("toolPolicy", null);
    }

    @Test
    void anUnregisteredRunIsANoOp() {
        // No live stream (a sync turn, or a panel that already closed): nothing is read
        // and nothing is emitted.
        UUID runId = UUID.randomUUID();
        when(registry.hasStream(runId)).thenReturn(false);

        listener.onApprovalRequested(new ApprovalRequestedEvent(this, UUID.randomUUID(), runId, null));

        verifyNoInteractions(approvals);
        verify(registry, never()).emit(any(), any(), any());
    }

    @Test
    void anAskWithoutAPersistedRowEmitsNothing() {
        UUID runId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        when(registry.hasStream(runId)).thenReturn(true);
        when(approvals.findById(approvalId)).thenReturn(Optional.empty());

        listener.onApprovalRequested(new ApprovalRequestedEvent(this, approvalId, runId, null));

        verify(registry, never()).emit(any(), any(), any());
    }

    @Test
    void aParkedRunEmitsWaitingInputNamingThePendingClarificationAsk() {
        UUID runId = UUID.randomUUID();
        UUID askId = UUID.randomUUID();
        Approval ask = Approval.builder()
                .id(askId).runId(runId).status(ApprovalStatus.PENDING)
                .approvalType(Approval.ApprovalType.TOOL_CALL).askType(Approval.AskType.QUESTION)
                .source(ApprovalSource.CLARIFICATION)
                .content("Which database should the migration target?")
                .build();
        when(registry.hasStream(runId)).thenReturn(true);
        when(approvals.findByRunIdAndStatusAndSource(runId, ApprovalStatus.PENDING,
                ApprovalSource.CLARIFICATION)).thenReturn(List.of(ask));
        when(registry.emit(eq(runId), eq("waiting_input"), any())).thenReturn(true);

        listener.onRunWaitingForInput(new RunWaitingForInputEvent(this, runId, "Which database?"));

        ArgumentCaptor<Map<String, Object>> payload = mapCaptor();
        verify(registry).emit(eq(runId), eq("waiting_input"), payload.capture());
        assertThat(payload.getValue())
                .containsOnlyKeys("runId", "approvalId", "question")
                .containsEntry("runId", runId.toString())
                .containsEntry("approvalId", askId.toString())
                .containsEntry("question", "Which database should the migration target?");
    }

    @Test
    void aParkedRunWithoutAPendingAskEmitsNothing() {
        UUID runId = UUID.randomUUID();
        when(registry.hasStream(runId)).thenReturn(true);
        when(approvals.findByRunIdAndStatusAndSource(runId, ApprovalStatus.PENDING,
                ApprovalSource.CLARIFICATION)).thenReturn(List.of());

        listener.onRunWaitingForInput(new RunWaitingForInputEvent(this, runId, "Which database?"));

        verify(registry, never()).emit(any(), any(), any());
    }

    @Test
    void twoPendingAsksNameTheNewestOneNotWhicheverRowTheQueryReturnedFirst() {
        // A run can briefly own two PENDING CLARIFICATION rows (the terminal-race ask of the
        // recorded residual plus a live park, or a duplicate waiting event). The derived
        // finder carries no ORDER BY, so the database may hand them back in any order and
        // findFirst() names an arbitrary one. The card must name the ask the operator is
        // being asked now: this fixture returns the STALE row first.
        UUID runId = UUID.randomUUID();
        Approval stale = clarification(runId, Instant.parse("2026-11-11T10:00:00Z"), "superseded question");
        Approval live = clarification(runId, Instant.parse("2026-11-11T10:05:00Z"),
                "Which database should the migration target?");
        when(registry.hasStream(runId)).thenReturn(true);
        when(approvals.findByRunIdAndStatusAndSource(runId, ApprovalStatus.PENDING,
                ApprovalSource.CLARIFICATION)).thenReturn(List.of(stale, live));
        when(registry.emit(eq(runId), eq("waiting_input"), any())).thenReturn(true);

        listener.onRunWaitingForInput(new RunWaitingForInputEvent(this, runId, "Which database?"));

        ArgumentCaptor<Map<String, Object>> payload = mapCaptor();
        verify(registry).emit(eq(runId), eq("waiting_input"), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("approvalId", live.getId().toString())
                .containsEntry("question", "Which database should the migration target?");
    }

    @Test
    void twoAsksRequestedAtTheSameInstantStillResolveTheSameWayWhateverTheRowOrder() {
        // The tie-break must be a property of the rows, not of the list order: the same two
        // rows in the opposite order must name the same ask, or the card is nondeterministic.
        UUID runId = UUID.randomUUID();
        Instant sameInstant = Instant.parse("2026-11-11T10:00:00Z");
        Approval lowerId = clarification(UUID.fromString("00000000-0000-4000-8000-000000000001"),
                runId, sameInstant, "first question");
        Approval higherId = clarification(UUID.fromString("00000000-0000-4000-8000-000000000002"),
                runId, sameInstant, "second question");
        when(registry.hasStream(runId)).thenReturn(true);
        when(approvals.findByRunIdAndStatusAndSource(runId, ApprovalStatus.PENDING,
                ApprovalSource.CLARIFICATION))
                .thenReturn(List.of(lowerId, higherId), List.of(higherId, lowerId));
        when(registry.emit(eq(runId), eq("waiting_input"), any())).thenReturn(true);

        listener.onRunWaitingForInput(new RunWaitingForInputEvent(this, runId, "Which database?"));
        listener.onRunWaitingForInput(new RunWaitingForInputEvent(this, runId, "Which database?"));

        ArgumentCaptor<Map<String, Object>> payload = mapCaptor();
        verify(registry, times(2)).emit(eq(runId), eq("waiting_input"), payload.capture());
        assertThat(payload.getAllValues())
                .extracting(emitted -> emitted.get("approvalId"))
                .containsExactly(higherId.getId().toString(), higherId.getId().toString());
    }

    @Test
    void aRepositoryFailureNeverEscapesIntoThePublisher() {
        // The ask is published from PermissionCoordinator.register inside its own
        // transaction and from the engine's waiting-input bookkeeping; an exception
        // escaping an after-commit listener would propagate into those callers.
        UUID runId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        when(registry.hasStream(runId)).thenReturn(true);
        when(approvals.findById(approvalId)).thenThrow(new IllegalStateException("db is gone"));
        when(approvals.findByRunIdAndStatusAndSource(runId, ApprovalStatus.PENDING,
                ApprovalSource.CLARIFICATION)).thenThrow(new IllegalStateException("db is gone"));

        assertThatCode(() -> {
            listener.onApprovalRequested(new ApprovalRequestedEvent(this, approvalId, runId, null));
            listener.onRunWaitingForInput(new RunWaitingForInputEvent(this, runId, "Which database?"));
        }).doesNotThrowAnyException();
    }

    @Test
    void aClarificationAskProducesOnlyTheWaitingInputSignal() {
        // The engine publishes ApprovalRequestedEvent for the CLARIFICATION ask it has just
        // persisted AND the run parks on RunWaitingForInputEvent. Both name the same approvalId,
        // so a second card would render one decision twice: exactly one signal per approval.
        UUID runId = UUID.randomUUID();
        UUID askId = UUID.randomUUID();
        Approval ask = Approval.builder()
                .id(askId).runId(runId).status(ApprovalStatus.PENDING)
                .approvalType(Approval.ApprovalType.TOOL_CALL).askType(Approval.AskType.QUESTION)
                .source(ApprovalSource.CLARIFICATION)
                .content("Which database should the migration target?")
                .build();
        when(registry.hasStream(runId)).thenReturn(true);
        when(approvals.findById(askId)).thenReturn(Optional.of(ask));
        when(approvals.findByRunIdAndStatusAndSource(runId, ApprovalStatus.PENDING,
                ApprovalSource.CLARIFICATION)).thenReturn(List.of(ask));
        when(registry.emit(eq(runId), eq("waiting_input"), any())).thenReturn(true);
        // Stubbed leniently on purpose: the pre-fix producer also emits approval_required here,
        // and Mockito's strict stubbing would fail that call before the never() assertion below
        // can. The fixed producer must not reach this stub at all.
        lenient().when(registry.emit(eq(runId), eq("approval_required"), any())).thenReturn(true);

        listener.onApprovalRequested(new ApprovalRequestedEvent(this, askId, runId, null));
        listener.onRunWaitingForInput(new RunWaitingForInputEvent(this, runId, "Which database?"));

        // Positive control: the clarification branch does reach the stream.
        verify(registry).emit(eq(runId), eq("waiting_input"), any());
        // Negative: and approval_required is never a second card for this same approval.
        verify(registry, never()).emit(eq(runId), eq("approval_required"), any());
        verify(registry, times(1)).emit(any(), any(), any());
    }

    @Test
    void aNonClarificationAskProducesOnlyTheApprovalRequiredSignal() {
        // The positive control for the branch the clarification filter must not touch.
        UUID runId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        Approval row = Approval.builder()
                .id(approvalId).runId(runId).status(ApprovalStatus.PENDING)
                .approvalType(Approval.ApprovalType.TOOL_CALL).askType(Approval.AskType.APPROVAL)
                .source(ApprovalSource.ACP_PERMISSION)
                .toolName("mcp__aria-conductor__remember_fact").riskTier("WRITE_LOCAL").build();
        when(registry.hasStream(runId)).thenReturn(true);
        when(approvals.findById(approvalId)).thenReturn(Optional.of(row));
        when(resolver.policyOf("mcp__aria-conductor__remember_fact")).thenReturn(ToolPolicy.WORKER_WRITE);
        when(registry.emit(eq(runId), eq("approval_required"), any())).thenReturn(true);

        listener.onApprovalRequested(new ApprovalRequestedEvent(this, approvalId, runId, null));

        verify(registry).emit(eq(runId), eq("approval_required"), any());
        verify(registry, never()).emit(eq(runId), eq("waiting_input"), any());
        verify(registry, times(1)).emit(any(), any(), any());
    }

    @Test
    void bothSignalsRunOnlyAfterCommitSoThePanelCannotReadAnUncommittedAsk() {
        assertAfterCommit(annotatedListener("onApprovalRequested", ApprovalRequestedEvent.class));
        assertAfterCommit(annotatedListener("onRunWaitingForInput", RunWaitingForInputEvent.class));
    }

    // ------------------------------------------------------------------ memory

    @Test
    void aMemoryProposalForARegisteredRunEmitsTheProposalCard() {
        // Task 10's MemoryProposalCreatedEvent is the operator's in-conversation
        // card: the panel must learn the id it decides and what kind of subject it
        // is WITHOUT a REST round-trip (spec C1/C2).
        UUID runId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        when(registry.hasStream(runId)).thenReturn(true);
        when(registry.emit(eq(runId), eq("memory_proposal"), any())).thenReturn(true);

        listener.onMemoryProposalCreated(new MemoryProposalCreatedEvent(this, proposalId,
                "ENTITY", entityId, "person:happy", "Happy", "INTERNAL", runId));

        ArgumentCaptor<Map<String, Object>> payload = mapCaptor();
        verify(registry).emit(eq(runId), eq("memory_proposal"), payload.capture());
        assertThat(payload.getValue())
                .containsOnlyKeys("runId", "proposalId", "subjectType", "entityId", "factKey",
                        "valueText", "sensitivity")
                .containsEntry("runId", runId.toString())
                .containsEntry("proposalId", proposalId.toString())
                .containsEntry("subjectType", "ENTITY")
                .containsEntry("entityId", entityId.toString())
                .containsEntry("factKey", "person:happy")
                .containsEntry("valueText", "Happy")
                .containsEntry("sensitivity", "INTERNAL");
    }

    @Test
    void aMemoryProposalForAnUnregisteredRunIsANoOp() {
        // A sync chat turn has no live stream: the proposal still exists in the
        // database and the REST inbox carries it, but there is no frame to push.
        UUID runId = UUID.randomUUID();
        when(registry.hasStream(runId)).thenReturn(false);

        listener.onMemoryProposalCreated(new MemoryProposalCreatedEvent(this, UUID.randomUUID(),
                "ENTITY", UUID.randomUUID(), "person:happy", "Happy", "INTERNAL", runId));

        verify(registry, never()).emit(any(), any(), any());
    }

    @Test
    void aMemoryProposalWithoutADecidableIdEmitsNothing() {
        // A card the operator cannot decide is worse than no card: the emitted
        // proposalId is the decision address, so an absent one is not emitted.
        UUID runId = UUID.randomUUID();

        listener.onMemoryProposalCreated(new MemoryProposalCreatedEvent(this, null,
                "ENTITY", null, "person:happy", "Happy", "INTERNAL", runId));

        verify(registry, never()).emit(any(), any(), any());
    }

    @Test
    void theMemoryProposalSignalRunsOnlyAfterCommit() {
        // The proposal row is written in MemoryCommandService's own transaction; a
        // panel that fetched it on the event would otherwise read nothing.
        assertAfterCommit(annotatedListener("onMemoryProposalCreated",
                MemoryProposalCreatedEvent.class));
    }

    @Test
    void theSignalIsEmittedAfterTheEngineHasPersistedTheClarificationAsk() {
        // RunWaitingForInputEvent is published before the ask exists: the engine's own
        // listener creates it. The signal must therefore be ordered strictly after that
        // bookkeeping, or the panel's clarification card can never name an approvalId.
        int bookkeeping = orderOf(AgentLoopEngine.class, "onRunWaitingForInput");
        int signal = orderOf(RunSignalSseListener.class, "onRunWaitingForInput");
        assertThat(bookkeeping)
                .as("the ask is persisted before any reader reacts to the event")
                .isLessThan(signal);
    }

    private static TransactionalEventListener annotatedListener(String name, Class<?> eventType) {
        try {
            Method method = RunSignalSseListener.class.getMethod(name, eventType);
            return AnnotatedElementUtils.findMergedAnnotation(method, TransactionalEventListener.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("listener method " + name + " is missing", e);
        }
    }

    private static void assertAfterCommit(TransactionalEventListener annotation) {
        assertThat(annotation).as("the listener must be registered transaction-aware").isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution())
                .as("a publish outside a transaction (the waiting-input path) still signals")
                .isTrue();
    }

    private static int orderOf(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                Order order = AnnotatedElementUtils.findMergedAnnotation(method, Order.class);
                return order == null ? Ordered.LOWEST_PRECEDENCE : order.value();
            }
        }
        throw new AssertionError("no " + name + " on " + type.getSimpleName());
    }

    private static Approval clarification(UUID runId, Instant requestedAt, String question) {
        return clarification(UUID.randomUUID(), runId, requestedAt, question);
    }

    private static Approval clarification(UUID id, UUID runId, Instant requestedAt, String question) {
        return Approval.builder()
                .id(id).runId(runId).status(ApprovalStatus.PENDING)
                .approvalType(Approval.ApprovalType.TOOL_CALL).askType(Approval.AskType.QUESTION)
                .source(ApprovalSource.CLARIFICATION)
                .requestedAt(requestedAt)
                .content(question)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
    }
}
