package io.aria.conductor.execution.maintenance;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.AuditEventRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.event.AuditLogEvent;
import io.aria.conductor.common.model.AcpPermissionRequest;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentSession;
import io.aria.conductor.common.model.AgentSkillId;
import io.aria.conductor.common.model.AgentToolId;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.AuditEvent;
import io.aria.conductor.common.model.PromptCall;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import io.aria.conductor.common.repository.AcpPermissionRequestRepository;
import io.aria.conductor.common.repository.AgentSkillRepository;
import io.aria.conductor.common.repository.AgentToolRepository;
import io.aria.conductor.common.repository.AuditEventBulkRepository;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanRepository;
import io.aria.conductor.execution.repository.AgentSessionRepository;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.PromptCallRepository;
import io.aria.conductor.execution.repository.RunExecutionBindingRepository;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.execution.runtime.RuntimeActivity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Preview-first retirement of the legacy LangChain agents (spec 7.2, plan
 * section 2.1). The scope is exactly the agents whose <em>current</em>
 * {@code adk_provider} is {@code langchain} and the run-owned records of those
 * agents; nothing is widened by name, role, substring or configured default,
 * and other-core data, shared business records (except the approved link
 * removal), settings, credentials and user repositories are never touched.
 *
 * <p>Contract:
 * <ul>
 *   <li>operator authority: every entry point calls
 *       {@link ActorPrincipal#requireOperator()}; a worker principal is
 *       refused with {@code SecurityException} before any read;</li>
 *   <li>preview-first: {@link #preview(ActorPrincipal)} is read-only, freezes
 *       the exact ID sets plus a SHA-256 digest and an expiry, and never
 *       deletes anything;</li>
 *   <li>{@link #execute(UUID, String, ActorPrincipal)} requires the reviewed
 *       digest, re-derives the selection and refuses when the digest moved,
 *       when the preview expired or when quiescence does not hold;</li>
 *   <li>quiescence over force: an active target run, a runtime this process
 *       still owns, or a pending approval on a target run refuses the
 *       operation instead of killing live work. When the run-quiescence view is
 *       not deployed the entry points fail closed: retirement never proceeds
 *       without proving quiescence (Task 18 supplies the view);</li>
 *   <li>{@link #execute(UUID, String, ActorPrincipal)} re-derives the selection,
 *       verifies the digest and verifies quiescence in the same transaction and
 *       before the deletion, so a selection that moved since the preview is a
 *       clean refusal, not a half-applied delete;</li>
 *   <li>one transaction, children before parents, set-based deletes, and
 *       link removal only for target references;</li>
 *   <li>no startup hook, scheduler, health path or event listener calls this
 *       service: the only triggers are the explicit operator endpoints.</li>
 * </ul>
 */
@Slf4j
@Service
public class LegacyRetirementService {

    /** The legacy provider value this retirement is scoped to; matched exactly. */
    public static final String LEGACY_PROVIDER = "langchain";

    /**
     * How long a produced preview stays executable. This is the fixed default of
     * the production wiring; no configuration property overrides it (tests inject
     * their own TTL through the full-seam constructor).
     */
    static final Duration DEFAULT_PREVIEW_TTL = Duration.ofMinutes(15);

    /** Run states that mean work is still live and must not be deleted under. */
    static final Set<RunStatus> ACTIVE_RUN_STATUSES = Set.of(
            RunStatus.PENDING, RunStatus.INITIALIZING, RunStatus.RUNNING, RunStatus.PAUSED);

    /**
     * The only audit resource types retirement may delete by.
     * {@code Action} rows carry a tool-call id as their resource id, so the
     * frozen tool-call set is that type's target set.
     */
    static final List<String> AUDIT_RESOURCE_TYPES = List.of("Agent", "Run", "Action");

    static final String IN_FLIGHT_MESSAGE = "Retirement operation already in flight";

    /** Fail-closed refusal while no run-quiescence view is deployed (Task 18 wires it). */
    static final String RUNTIME_ACTIVITY_MISSING_MESSAGE = "Retirement refused: the RuntimeActivity"
            + " run-quiescence view is not deployed; quiescence cannot be proven";

    private final AgentRepository agentRepository;
    private final RunRepository runRepository;
    private final ApprovalRepository approvalRepository;
    private final PromptCallRepository promptCallRepository;
    private final AcpPermissionRequestRepository permissionRequestRepository;
    private final RunExecutionBindingRepository runExecutionBindingRepository;
    private final SessionTrajectoryRepository trajectoryRepository;
    private final ToolCallRepository toolCallRepository;
    private final AgentSessionRepository agentSessionRepository;
    private final AgentToolRepository agentToolRepository;
    private final AgentSkillRepository agentSkillRepository;
    private final KanbanRepository kanbanRepository;
    private final WorkflowChainRepository workflowChainRepository;
    private final AuditEventRepository auditEventRepository;
    private final AuditEventBulkRepository auditEventBulkRepository;
    private final WorkflowService workflowService;
    /**
     * Optional run-quiescence view: absent until Task 18 wires the run
     * coordinator's registry, so the entry points fail closed while it is not
     * deployed instead of deleting without proving quiescence.
     */
    private final RuntimeActivity runtimeActivity;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Duration previewTtl;

    /** Preview manifests of this process only; a preview never outlives the process. */
    private final ConcurrentHashMap<UUID, RetirementManifest> manifests = new ConcurrentHashMap<>();
    /** Single-flight: one preview/execute at a time, so two executions cannot interleave. */
    private final AtomicBoolean operationInFlight = new AtomicBoolean();

    /**
     * Production wiring: system clock, the fixed preview TTL and whatever
     * {@link RuntimeActivity} bean exists (none until Task 18, which is a legal
     * state the entry points refuse).
     */
    @Autowired
    public LegacyRetirementService(AgentRepository agentRepository, RunRepository runRepository,
            ApprovalRepository approvalRepository, PromptCallRepository promptCallRepository,
            AcpPermissionRequestRepository permissionRequestRepository,
            RunExecutionBindingRepository runExecutionBindingRepository,
            SessionTrajectoryRepository trajectoryRepository, ToolCallRepository toolCallRepository,
            AgentSessionRepository agentSessionRepository, AgentToolRepository agentToolRepository,
            AgentSkillRepository agentSkillRepository, KanbanRepository kanbanRepository,
            WorkflowChainRepository workflowChainRepository, AuditEventRepository auditEventRepository,
            AuditEventBulkRepository auditEventBulkRepository, WorkflowService workflowService,
            ObjectProvider<RuntimeActivity> runtimeActivity, ApplicationEventPublisher eventPublisher,
            TransactionTemplate transactionTemplate) {
        this(agentRepository, runRepository, approvalRepository, promptCallRepository,
                permissionRequestRepository, runExecutionBindingRepository, trajectoryRepository,
                toolCallRepository, agentSessionRepository, agentToolRepository, agentSkillRepository,
                kanbanRepository, workflowChainRepository, auditEventRepository, auditEventBulkRepository,
                workflowService, runtimeActivity.getIfAvailable(), eventPublisher, transactionTemplate,
                Clock.systemUTC(), DEFAULT_PREVIEW_TTL);
    }

    /**
     * Full seam: explicit clock and preview TTL (tests exercise expiry without
     * sleeping) and an explicit runtime view; a {@code null} view is legal here
     * (the missing-view refusal is part of the contract), while the clock and
     * TTL are required.
     */
    public LegacyRetirementService(AgentRepository agentRepository, RunRepository runRepository,
            ApprovalRepository approvalRepository, PromptCallRepository promptCallRepository,
            AcpPermissionRequestRepository permissionRequestRepository,
            RunExecutionBindingRepository runExecutionBindingRepository,
            SessionTrajectoryRepository trajectoryRepository, ToolCallRepository toolCallRepository,
            AgentSessionRepository agentSessionRepository, AgentToolRepository agentToolRepository,
            AgentSkillRepository agentSkillRepository, KanbanRepository kanbanRepository,
            WorkflowChainRepository workflowChainRepository, AuditEventRepository auditEventRepository,
            AuditEventBulkRepository auditEventBulkRepository, WorkflowService workflowService,
            RuntimeActivity runtimeActivity, ApplicationEventPublisher eventPublisher,
            TransactionTemplate transactionTemplate, Clock clock, Duration previewTtl) {
        this.agentRepository = agentRepository;
        this.runRepository = runRepository;
        this.approvalRepository = approvalRepository;
        this.promptCallRepository = promptCallRepository;
        this.permissionRequestRepository = permissionRequestRepository;
        this.runExecutionBindingRepository = runExecutionBindingRepository;
        this.trajectoryRepository = trajectoryRepository;
        this.toolCallRepository = toolCallRepository;
        this.agentSessionRepository = agentSessionRepository;
        this.agentToolRepository = agentToolRepository;
        this.agentSkillRepository = agentSkillRepository;
        this.kanbanRepository = kanbanRepository;
        this.workflowChainRepository = workflowChainRepository;
        this.auditEventRepository = auditEventRepository;
        this.auditEventBulkRepository = auditEventBulkRepository;
        this.workflowService = workflowService;
        this.runtimeActivity = runtimeActivity;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.previewTtl = Objects.requireNonNull(previewTtl, "Preview TTL is required");
    }

    /* ------------------------------------------------------------------ */
    /* Preview (read-only)                                                  */
    /* ------------------------------------------------------------------ */

    /**
     * Takes the exact, expiring preview of the current legacy scope. Read-only:
     * it derives the target sets, refuses non-quiescent targets and stores a
     * manifest the caller must present (with its digest) to
     * {@link #execute(UUID, String, ActorPrincipal)}.
     *
     * @throws SecurityException when the caller is not the operator
     * @throws IllegalStateException when no run-quiescence view is deployed, a
     *         target run is active or a target run has a pending approval
     */
    public RetirementManifest preview(ActorPrincipal actor) {
        Objects.requireNonNull(actor, "ActorPrincipal is required").requireOperator();
        requireRuntimeActivity();
        if (!operationInFlight.compareAndSet(false, true)) {
            throw new IllegalStateException(IN_FLIGHT_MESSAGE);
        }
        try {
            TargetView view = deriveTargets();
            requireQuiescence(view);
            Instant now = clock.instant();
            // Expired previews are dropped here: a manifest that can no longer be
            // executed must not accumulate in this process's memory.
            manifests.values().removeIf(manifest -> manifest.isExpired(now));
            RetirementManifest manifest = view.toManifest(UUID.randomUUID(), now, now.plus(previewTtl));
            manifests.put(manifest.previewId(), manifest);
            log.info("Legacy retirement previewed: previewId={} agents={} runs={} approvals={} auditEvents={}",
                    manifest.previewId(), manifest.agentIds().size(), manifest.runIds().size(),
                    manifest.approvalIds().size(), manifest.auditEventIds().size());
            return manifest;
        } finally {
            operationInFlight.set(false);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Execute (explicit, bounded, transactional)                           */
    /* ------------------------------------------------------------------ */

    /**
     * Hard-deletes exactly the previewed scope in one transaction.
     *
     * <p>The transaction covers the re-derivation, the digest comparison and the
     * quiescence proof as well as the deletion: the selection the digest is
     * verified against and the rows deleted are read under the same transaction
     * (immediately before the delete), so a selection that moved since the
     * preview is refused before anything is deleted instead of surfacing as a
     * half-applied delete or a raw constraint failure.
     *
     * <p>Refusals are deliberate and total: unknown preview, expired preview,
     * digest mismatch (including a selection that changed since the preview)
     * and any quiescence violation delete nothing and leave the manifest
     * consumed only on success.
     *
     * @param previewId      the preview the operator reviewed
     * @param expectedDigest the digest shown in that preview
     * @param actor          the authenticated operator
     */
    public RetirementReceipt execute(UUID previewId, String expectedDigest, ActorPrincipal actor) {
        Objects.requireNonNull(actor, "ActorPrincipal is required").requireOperator();
        requireRuntimeActivity();
        Objects.requireNonNull(previewId, "previewId is required");
        if (expectedDigest == null || expectedDigest.isBlank()) {
            throw new IllegalArgumentException("Retirement execute requires the previewed digest");
        }
        if (!operationInFlight.compareAndSet(false, true)) {
            throw new IllegalStateException(IN_FLIGHT_MESSAGE);
        }
        try {
            RetirementManifest manifest = manifests.get(previewId);
            if (manifest == null) {
                throw new IllegalArgumentException("Unknown retirement preview: " + previewId);
            }
            Instant now = clock.instant();
            if (manifest.isExpired(now)) {
                throw new IllegalStateException(
                        "Retirement preview " + previewId + " expired at " + manifest.expiresAt());
            }
            if (!manifest.digest().equals(expectedDigest)) {
                throw new IllegalArgumentException("Retirement preview " + previewId + " digest mismatch");
            }

            AtomicReference<RetirementReceipt> executed = new AtomicReference<>();
            transactionTemplate.executeWithoutResult(status -> {
                TargetView view = deriveTargets();
                if (!manifest.digest().equals(view.digest())) {
                    throw new IllegalStateException(
                            "Retirement preview " + previewId + " selection changed since preview");
                }
                requireQuiescence(view);
                executed.set(delete(view, previewId));
            });
            RetirementReceipt receipt = executed.get();
            manifests.remove(previewId);
            publishAudit(receipt);
            log.warn("Legacy retirement executed: {} ({} agents, {} runs)",
                    describe(receipt), receipt.deletedAgents(), receipt.deletedRuns());
            return receipt;
        } finally {
            operationInFlight.set(false);
        }
    }

    /**
     * Retirement must prove quiescence before it deletes anything: without the
     * run-quiescence view, an active run could be deleted under. The view is not
     * deployed until Task 18 wires it, so this fails closed. It is deliberately
     * not a startup failure -- the application context must boot without the
     * view -- and not a warning that lets the deletion proceed.
     */
    private void requireRuntimeActivity() {
        if (runtimeActivity == null) {
            throw new IllegalStateException(RUNTIME_ACTIVITY_MISSING_MESSAGE);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Target derivation                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Re-derives the exact current scope from the database. Called by both
     * preview and execute (never trusted from a snapshot), and the digest of
     * its result is what execute compares against the manifest.
     */
    private TargetView deriveTargets() {
        List<Agent> legacyAgents = agentRepository.findAll().stream()
                .filter(agent -> LEGACY_PROVIDER.equals(agent.getAdkProvider()))
                .sorted(Comparator.comparing(Agent::getId))
                .toList();
        Set<UUID> agentIds = legacyAgents.stream().map(Agent::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<Run> targetRuns = new ArrayList<>();
        for (UUID agentId : agentIds) {
            targetRuns.addAll(runRepository.findByAgentId(agentId));
        }
        targetRuns.sort(Comparator.comparing(Run::getId));
        Set<UUID> runIds = targetRuns.stream().map(Run::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<Approval> targetApprovals = new ArrayList<>();
        for (UUID runId : runIds) {
            targetApprovals.addAll(approvalRepository.findByRunId(runId));
        }
        targetApprovals.sort(Comparator.comparing(Approval::getId));

        List<AcpPermissionRequest> permissionRequests = new ArrayList<>();
        List<PromptCall> runPromptCalls = new ArrayList<>();
        List<SessionTrajectory> trajectoryRows = new ArrayList<>();
        List<ToolCall> toolCallRows = new ArrayList<>();
        List<UUID> agentSessionRunIds = new ArrayList<>();
        for (UUID runId : runIds) {
            permissionRequests.addAll(permissionRequestRepository.findByRunId(runId));
            runPromptCalls.addAll(promptCallRepository.findByRunId(runId));
            trajectoryRows.addAll(trajectoryRepository.findByRunIdOrderByTurnNumberAsc(runId));
            toolCallRows.addAll(toolCallRepository.findByRunId(runId));
            agentSessionRepository.findById(runId).map(AgentSession::getRunId).ifPresent(agentSessionRunIds::add);
        }
        permissionRequests.sort(Comparator.comparing(AcpPermissionRequest::getId));
        trajectoryRows.sort(Comparator.comparing(SessionTrajectory::getId));
        toolCallRows.sort(Comparator.comparing(ToolCall::getId));
        runPromptCalls.sort(Comparator.comparing(PromptCall::getId));
        agentSessionRunIds.sort(Comparator.naturalOrder());

        List<Long> runPromptCallIds = runPromptCalls.stream().map(PromptCall::getId).toList();
        // Direct agent-owned prompt rows: agent-scoped rows that are not owned by
        // one of the target runs (already purged with the runs).
        List<Long> agentPromptCallIds = new ArrayList<>();
        for (UUID agentId : agentIds) {
            for (PromptCall call : promptCallRepository.findByAgentId(agentId)) {
                if (call.getRunId() == null || !runIds.contains(call.getRunId())) {
                    agentPromptCallIds.add(call.getId());
                }
            }
        }
        agentPromptCallIds.sort(Comparator.naturalOrder());

        List<AgentToolId> agentToolBindings = new ArrayList<>();
        List<AgentSkillId> agentSkillBindings = new ArrayList<>();
        for (UUID agentId : agentIds) {
            for (String toolId : agentToolRepository.findToolIdsByAgentId(agentId.toString())) {
                agentToolBindings.add(new AgentToolId(agentId.toString(), toolId));
            }
            for (String skillId : agentSkillRepository.findSkillIdsByAgentId(agentId.toString())) {
                agentSkillBindings.add(new AgentSkillId(agentId.toString(), skillId));
            }
        }

        // Audit history: only rows of the frozen (resourceType, resourceId) pairs.
        // The type list is exactly AUDIT_RESOURCE_TYPES, so a type may not be
        // deleted by without also being frozen by this derivation.
        List<AuditEvent> auditRows = new ArrayList<>();
        for (String resourceType : AUDIT_RESOURCE_TYPES) {
            auditRows.addAll(auditEventsOfType(resourceType,
                    auditResourceIdsOfType(resourceType, agentIds, runIds, toolCallRows)));
        }
        auditRows.sort(Comparator.comparing(AuditEvent::getId));

        // Shared records that merely link into the deleted scope.
        Set<String> kanbanCardIds = new LinkedHashSet<>();
        for (KanbanItem card : kanbanRepository.findAll()) {
            if (isTargetLink(card.getLinkedAgentId(), agentIds)
                    || isTargetLink(card.getLinkedRunId(), runIds)) {
                kanbanCardIds.add(card.getId());
            }
        }
        Set<UUID> workflowChainIds = new LinkedHashSet<>();
        for (WorkflowChain chain : workflowChainRepository.findAll()) {
            if (chainReferencesTargets(chain, agentIds, runIds)) {
                workflowChainIds.add(chain.getId());
            }
        }

        Set<UUID> activeRunIds = new TreeSet<>();
        Set<UUID> pendingApprovalRunIds = new TreeSet<>();
        for (Run run : targetRuns) {
            if (ACTIVE_RUN_STATUSES.contains(run.getStatus())) {
                activeRunIds.add(run.getId());
            }
        }
        for (UUID agentId : agentIds) {
            activeRunIds.addAll(runtimeActivity.activeRuns(agentId));
        }
        for (Approval approval : targetApprovals) {
            if (approval.getStatus() == ApprovalStatus.PENDING) {
                pendingApprovalRunIds.add(approval.getRunId());
            }
        }

        return new TargetView(legacyAgents, targetRuns, targetApprovals, permissionRequests,
                trajectoryRows, toolCallRows, runPromptCallIds, agentPromptCallIds, agentSessionRunIds,
                agentToolBindings, agentSkillBindings, auditRows, kanbanCardIds, workflowChainIds,
                new ArrayList<>(activeRunIds), new ArrayList<>(pendingApprovalRunIds));
    }

    private List<AuditEvent> auditEventsOfType(String resourceType, List<String> resourceIds) {
        List<AuditEvent> rows = new ArrayList<>();
        for (String resourceId : resourceIds) {
            rows.addAll(auditEventRepository.findByResourceTypeAndResourceId(resourceType, resourceId));
        }
        return rows;
    }

    /**
     * The frozen resource-id set of one audit resource type: {@code Agent} rows
     * carry the agent id, {@code Run} rows the run id, and {@code Action} rows the
     * tool-call id (what {@code AuditRecorder} writes for a tool call). A type the
     * deletion knows but the derivation does not would be unfrozen and therefore
     * undeletable, so it is refused loudly instead.
     */
    private static List<String> auditResourceIdsOfType(String resourceType, Set<UUID> agentIds,
            Set<UUID> runIds, List<ToolCall> toolCalls) {
        return switch (resourceType) {
            case "Agent" -> agentIds.stream().map(UUID::toString).toList();
            case "Run" -> runIds.stream().map(UUID::toString).toList();
            case "Action" -> toolCalls.stream().map(call -> call.getId().toString()).toList();
            default -> throw new IllegalArgumentException(
                    "Audit resource type not declared for retirement: " + resourceType);
        };
    }

    private static boolean isTargetLink(String linkId, Set<UUID> targetIds) {
        if (linkId == null || linkId.isBlank()) {
            return false;
        }
        try {
            return targetIds.contains(UUID.fromString(linkId));
        } catch (IllegalArgumentException e) {
            return false; // a non-UUID link can never point at a target id
        }
    }

    private boolean chainReferencesTargets(WorkflowChain chain, Set<UUID> agentIds, Set<UUID> runIds) {
        for (WorkflowStep step : workflowService.deserializeSteps(chain.getStepsJson())) {
            if ((step.getAgentId() != null && agentIds.contains(step.getAgentId()))
                    || (step.getRunId() != null && runIds.contains(step.getRunId()))) {
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /* Quiescence                                                           */
    /* ------------------------------------------------------------------ */

    /** Refuses non-quiescent targets instead of force-killing owned work. */
    private void requireQuiescence(TargetView view) {
        if (!view.activeRunIds().isEmpty()) {
            throw new IllegalStateException("Retirement refused: target run(s) "
                    + new ArrayList<>(new TreeSet<>(view.activeRunIds())) + " are still active");
        }
        if (!view.pendingApprovalRunIds().isEmpty()) {
            throw new IllegalStateException("Retirement refused: target run(s) "
                    + new ArrayList<>(new TreeSet<>(view.pendingApprovalRunIds()))
                    + " have pending approval(s)");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Deletion (one transaction, children before parents)                  */
    /* ------------------------------------------------------------------ */

    private RetirementReceipt delete(TargetView view, UUID previewId) {
        List<UUID> runIds = new ArrayList<>(view.runIds());
        int deletedTrajectories = bulkDelete(runIds, trajectoryRepository::deleteByRunIdInBulk);
        int deletedToolCalls = bulkDelete(runIds, toolCallRepository::deleteByRunIdInBulk);
        int deletedRunPromptCalls = bulkDelete(runIds, promptCallRepository::deleteByRunIdInBulk);
        int deletedPermissionRequests = bulkDelete(runIds, permissionRequestRepository::deleteByRunIdInBulk);
        int deletedRunBindings = bulkDelete(runIds, runExecutionBindingRepository::deleteByRunIdInBulk);
        int deletedApprovals = bulkDelete(runIds, approvalRepository::deleteByRunIdInBulk);
        int deletedAgentSessions = bulkDelete(runIds, agentSessionRepository::deleteByRunIdInBulk);
        int deletedRuns = bulkDelete(runIds, runRepository::deleteByIdInBulk);

        // Direct agent-owned prompt rows survive their runs' purge, so they are
        // deleted explicitly before the agents themselves. The removal API
        // returns no count: the frozen (digest-verified) set size is the exact
        // count of rows this statement removed.
        int deletedAgentPromptCalls = 0;
        if (!view.agentPromptCallIds().isEmpty()) {
            deletedAgentPromptCalls = view.agentPromptCallIds().size();
            promptCallRepository.deleteAllById(view.agentPromptCallIds());
        }

        // The frozen binding sets are re-derived and digest-verified above and
        // removed by per-agent void modifies, which return no count: their sizes
        // are the exact deletion counts.
        int deletedAgentToolBindings = view.agentToolBindingIds().size();
        int deletedAgentSkillBindings = view.agentSkillBindingIds().size();
        for (Agent agent : view.agents()) {
            agentToolRepository.deleteByAgentId(agent.getId().toString());
            agentSkillRepository.deleteByAgentId(agent.getId().toString());
        }

        // JpaRepository's entity-removal API returns no count either: the frozen
        // (digest-verified) agent set size is the exact deletion count.
        int deletedAgents = 0;
        if (!view.agentIds().isEmpty()) {
            deletedAgents = view.agentIds().size();
            agentRepository.deleteAllById(view.agentIds());
        }

        int deletedAuditEvents = deleteAuditRows(view);

        int unlinkedKanbanCards = unlinkKanbanCards(view);
        int unlinkedWorkflowSteps = unlinkWorkflowSteps(view);

        return new RetirementReceipt(previewId, clock.instant(), deletedAgents, deletedRuns,
                deletedApprovals, deletedAuditEvents, deletedPermissionRequests, deletedTrajectories,
                deletedToolCalls, deletedRunPromptCalls, deletedAgentPromptCalls, deletedRunBindings,
                deletedAgentSessions, deletedAgentToolBindings, deletedAgentSkillBindings,
                unlinkedKanbanCards, unlinkedWorkflowSteps);
    }

    private int bulkDelete(List<UUID> ids, java.util.function.Function<List<UUID>, Integer> delete) {
        return ids.isEmpty() ? 0 : delete.apply(ids);
    }

    /**
     * Deletes the frozen audit rows, grouped by the frozen resource types. The
     * predicate is both the verified {@code (resourceType, resourceId)} pair and
     * the frozen row ids, so a row written for a target resource after the
     * re-derivation (a new row id that is not in the manifest's
     * {@code auditEventIds}) survives instead of being swept up by the pair.
     */
    private int deleteAuditRows(TargetView view) {
        int deleted = 0;
        for (String resourceType : AUDIT_RESOURCE_TYPES) {
            List<AuditEvent> frozenRows = view.auditEvents().stream()
                    .filter(row -> resourceType.equals(row.getResourceType()))
                    .toList();
            if (frozenRows.isEmpty()) {
                continue;
            }
            deleted += auditEventBulkRepository.deleteByResourceTypeAndResourceIdIn(resourceType,
                    frozenRows.stream().map(AuditEvent::getResourceId).distinct().toList(),
                    frozenRows.stream().map(AuditEvent::getId).toList());
        }
        return deleted;
    }

    private int unlinkKanbanCards(TargetView view) {
        int unlinked = 0;
        if (view.kanbanCardIds().isEmpty()) {
            return 0;
        }
        for (KanbanItem card : kanbanRepository.findAllById(view.kanbanCardIds())) {
            boolean changed = false;
            if (isTargetLink(card.getLinkedAgentId(), view.agentIds())) {
                card.setLinkedAgentId(null);
                changed = true;
            }
            if (isTargetLink(card.getLinkedRunId(), view.runIds())) {
                card.setLinkedRunId(null);
                changed = true;
            }
            if (changed) {
                kanbanRepository.save(card);
                unlinked++;
            }
        }
        return unlinked;
    }

    /**
     * Removes only the target agent/run references from the chain JSON through
     * the workflow serializer round-trip, preserving every other step and all
     * outputs.
     */
    private int unlinkWorkflowSteps(TargetView view) {
        int unlinked = 0;
        if (view.workflowChainIds().isEmpty()) {
            return 0;
        }
        for (WorkflowChain chain : workflowChainRepository.findAllById(view.workflowChainIds())) {
            List<WorkflowStep> steps = workflowService.deserializeSteps(chain.getStepsJson());
            boolean chainChanged = false;
            for (WorkflowStep step : steps) {
                boolean stepChanged = false;
                if (step.getAgentId() != null && view.agentIds().contains(step.getAgentId())) {
                    step.setAgentId(null);
                    stepChanged = true;
                }
                if (step.getRunId() != null && view.runIds().contains(step.getRunId())) {
                    step.setRunId(null);
                    stepChanged = true;
                }
                if (stepChanged) {
                    chainChanged = true;
                    unlinked++;
                }
            }
            if (chainChanged) {
                chain.setStepsJson(workflowService.serializeSteps(steps));
                workflowChainRepository.save(chain);
            }
        }
        return unlinked;
    }

    private void publishAudit(RetirementReceipt receipt) {
        eventPublisher.publishEvent(new AuditLogEvent(this, "LEGACY_RETIREMENT_EXECUTED",
                "Maintenance", receipt.previewId().toString(), "DELETE", describe(receipt), null));
    }

    private static String describe(RetirementReceipt receipt) {
        return "preview=" + receipt.previewId()
                + " deletedAgents=" + receipt.deletedAgents()
                + " deletedRuns=" + receipt.deletedRuns()
                + " deletedApprovals=" + receipt.deletedApprovals()
                + " deletedAuditEvents=" + receipt.deletedAuditEvents()
                + " unlinkedKanbanCards=" + receipt.unlinkedKanbanCards()
                + " unlinkedWorkflowSteps=" + receipt.unlinkedWorkflowSteps();
    }

    /* ------------------------------------------------------------------ */
    /* Target view: the exact frozen sets, in one place                     */
    /* ------------------------------------------------------------------ */

    /**
     * One consistent derivation of the scope. The digest of this view is what
     * the manifest freezes and what execute re-verifies, so preview and execute
     * can never disagree about which rows are in scope.
     */
    private record TargetView(
            List<Agent> agents,
            List<Run> runs,
            List<Approval> approvals,
            List<AcpPermissionRequest> permissionRequests,
            List<SessionTrajectory> trajectories,
            List<ToolCall> toolCalls,
            List<Long> runPromptCallIds,
            List<Long> agentPromptCallIds,
            List<UUID> agentSessionRunIds,
            List<AgentToolId> agentToolBindings,
            List<AgentSkillId> agentSkillBindings,
            List<AuditEvent> auditEvents,
            Set<String> kanbanCardIds,
            Set<UUID> workflowChainIds,
            List<UUID> activeRunIds,
            List<UUID> pendingApprovalRunIds) {

        Set<UUID> agentIds() {
            return agents.stream().map(Agent::getId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        Set<UUID> runIds() {
            return runs.stream().map(Run::getId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        Set<UUID> approvalIds() {
            return approvals.stream().map(Approval::getId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        Set<Long> auditEventIds() {
            return auditEvents.stream().map(AuditEvent::getId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        Set<UUID> permissionRequestIds() {
            return permissionRequests.stream().map(AcpPermissionRequest::getId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        Set<UUID> trajectoryIds() {
            return trajectories.stream().map(SessionTrajectory::getId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        Set<UUID> toolCallIds() {
            return toolCalls.stream().map(ToolCall::getId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        Set<Long> promptCallIds() {
            Set<Long> ids = new LinkedHashSet<>(runPromptCallIds);
            ids.addAll(agentPromptCallIds);
            return ids;
        }

        Set<UUID> agentSessionIds() {
            return new LinkedHashSet<>(agentSessionRunIds);
        }

        Set<String> agentToolBindingIds() {
            return agentToolBindings.stream()
                    .map(binding -> binding.getAgentId() + ":" + binding.getToolId())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        Set<String> agentSkillBindingIds() {
            return agentSkillBindings.stream()
                    .map(binding -> binding.getAgentId() + ":" + binding.getSkillId())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        String digest() {
            return RetirementManifest.digestOf(agentIds(), runIds(), approvalIds(), auditEventIds(),
                    permissionRequestIds(), trajectoryIds(), toolCallIds(), promptCallIds(),
                    agentSessionIds(), agentToolBindingIds(), agentSkillBindingIds(), kanbanCardIds,
                    workflowChainIds);
        }

        RetirementManifest toManifest(UUID previewId, Instant createdAt, Instant expiresAt) {
            return new RetirementManifest(previewId, digest(), createdAt, expiresAt, agentIds(),
                    runIds(), approvalIds(), auditEventIds(), permissionRequestIds(), trajectoryIds(),
                    toolCallIds(), promptCallIds(), agentSessionIds(), agentToolBindingIds(),
                    agentSkillBindingIds(), kanbanCardIds, workflowChainIds);
        }
    }
}
