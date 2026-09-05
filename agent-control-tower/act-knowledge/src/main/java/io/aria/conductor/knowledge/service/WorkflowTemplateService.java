package io.aria.conductor.knowledge.service;

import io.aria.conductor.agent.dto.CreateWorkflowRequest;
import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.KnowledgeVersion;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import io.aria.conductor.execution.adk.opencode.OpenCodeProperties;
import io.aria.conductor.execution.dod.DoDService;
import io.aria.conductor.execution.git.GitHandoffMetadata;
import io.aria.conductor.execution.git.GitHubIssue;
import io.aria.conductor.execution.git.GitHubIssueClient;
import io.aria.conductor.execution.kanban.CreateKanbanItemRequest;
import io.aria.conductor.execution.kanban.KanbanService;
import io.aria.conductor.knowledge.converter.WorkflowTemplateConverter;
import io.aria.conductor.knowledge.dto.KnowledgeItemResponse;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.repository.KnowledgeVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Service for discovering and instantiating APPROVED workflow templates
 * stored as knowledge items of type {@link KnowledgeType#WORKFLOW}.
 */
@Service
public class WorkflowTemplateService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTemplateService.class);

    private final KnowledgeItemRepository itemRepository;
    private final KnowledgeVersionRepository versionRepository;
    private final WorkflowTemplateConverter templateConverter;
    private final WorkflowService workflowService;
    private final WorkflowChainRepository chainRepository;
    private final KnowledgeService knowledgeService;
    private final DoDService dodService;
    private final KanbanService kanbanService;
    private final OpenCodeProperties openCodeProperties;
    private final GitHubIssueClient gitHubIssueClient;

    public WorkflowTemplateService(KnowledgeItemRepository itemRepository,
                                   KnowledgeVersionRepository versionRepository,
                                   WorkflowTemplateConverter templateConverter,
                                   WorkflowService workflowService,
                                   WorkflowChainRepository chainRepository,
                                   KnowledgeService knowledgeService,
                                   DoDService dodService,
                                   KanbanService kanbanService,
                                   OpenCodeProperties openCodeProperties,
                                   GitHubIssueClient gitHubIssueClient) {
        this.itemRepository = itemRepository;
        this.versionRepository = versionRepository;
        this.templateConverter = templateConverter;
        this.workflowService = workflowService;
        this.chainRepository = chainRepository;
        this.knowledgeService = knowledgeService;
        this.dodService = dodService;
        this.kanbanService = kanbanService;
        this.openCodeProperties = openCodeProperties;
        this.gitHubIssueClient = gitHubIssueClient;
    }

    /**
     * Find APPROVED workflow templates matching the given intent keywords.
     * If {@code userIntent} is {@code null} or blank, all APPROVED workflow templates are returned.
     */
    @Transactional(readOnly = true)
    public List<KnowledgeItemResponse> findMatchingTemplates(String userIntent) {
        List<KnowledgeItem> allWorkflowTemplates = itemRepository
                .findByTypeAndStatus(KnowledgeType.WORKFLOW, KnowledgeStatus.APPROVED);

        if (userIntent == null || userIntent.isBlank()) {
            return allWorkflowTemplates.stream()
                    .map(knowledgeService::toResponseWithLatestVersion)
                    .toList();
        }

        String lowerIntent = userIntent.toLowerCase();
        return allWorkflowTemplates.stream()
                .filter(item -> item.getName().toLowerCase().contains(lowerIntent)
                        || (item.getDescription() != null
                            && item.getDescription().toLowerCase().contains(lowerIntent)))
                .map(knowledgeService::toResponseWithLatestVersion)
                .toList();
    }

    /**
     * Instantiate an APPROVED workflow template with the given parameters.
     * <p>
     * Reads the YAML content from the template's current version, parses it into steps,
     * substitutes parameters, creates a new workflow chain, and links the chain back
     * to the source knowledge item.
     *
     * @param templateItemId the knowledge item ID of the APPROVED workflow template
     * @param parameters     parameter key-value pairs to substitute into prompt templates
     * @return the newly created workflow response
     */
    @Transactional
    public WorkflowResponse instantiateTemplate(UUID templateItemId, Map<String, String> parameters) {
        KnowledgeItem item = itemRepository.findById(templateItemId)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeItem", templateItemId));

        if (item.getType() != KnowledgeType.WORKFLOW) {
            throw new IllegalArgumentException("Knowledge item is not a WORKFLOW template");
        }
        if (item.getStatus() != KnowledgeStatus.APPROVED) {
            throw new IllegalArgumentException("Template is not APPROVED");
        }

        // Read YAML from KnowledgeVersion
        KnowledgeVersion version = versionRepository
                .findByKnowledgeItemIdAndVersion(item.getId(), item.getCurrentVersion())
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeVersion", item.getId()));

        String yamlContent = version.getYamlContent();
        if (yamlContent == null || yamlContent.isBlank()) {
            // Legacy WORKFLOW items (created before yaml_content existed) store the YAML
            // as the version content — derive it instead of failing instantiation.
            yamlContent = version.getContent();
        }
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new IllegalArgumentException("Template has no YAML content");
        }

        // Parse YAML to steps
        List<WorkflowStep> steps = templateConverter.yamlToWorkflowSteps(yamlContent);

        // Validate that all parameter keys are declared in the template and build
        // the resolved parameter map used for substitution and persistence.
        Set<String> declaredParams = templateConverter.extractParameterNames(steps);
        Map<String, String> resolvedParams = parameters;
        if (parameters != null) {
            for (String key : parameters.keySet()) {
                if (!declaredParams.contains(key)) {
                    throw new IllegalArgumentException(
                            "Unknown parameter '" + key + "': not declared in template " + templateItemId
                            + ". Declared: " + declaredParams);
                }
            }
        }

        // R8-F1: resolve repoUrl. Caller-supplied values win; otherwise fall back to
        // the system-configured default (opencode.repo-url). Fail fast when the
        // template declares {repoUrl} but neither source provides one — without this,
        // the Dev prompt keeps a literal {repoUrl} and the spec-approval coordinator
        // skips branch creation (Dev then "guesses" the repo).
        if (declaredParams.contains(GitHandoffMetadata.KEY_REPO_URL)) {
            String repoUrl = resolvedParams == null ? null : resolvedParams.get(GitHandoffMetadata.KEY_REPO_URL);
            if (repoUrl == null || repoUrl.isBlank()) {
                String sysRepoUrl = openCodeProperties.getRepoUrl();
                if (sysRepoUrl != null && !sysRepoUrl.isBlank()) {
                    resolvedParams = resolvedParams == null
                            ? new LinkedHashMap<>()
                            : new LinkedHashMap<>(resolvedParams);
                    resolvedParams.put(GitHandoffMetadata.KEY_REPO_URL, sysRepoUrl);
                } else {
                    throw new IllegalArgumentException(
                            "Template requires repoUrl parameter; pass it or set opencode.repo-url");
                }
            }
        }

        // R9-F2: ground the issue reference of a spec-authoring (BA) sub-task before
        // dispatch. When the template's BA step declares {issueRepo}/{issueRef} (or an
        // explicit `gh issue view` instruction), the orchestrator resolves the reference
        // to a real issue in the authoritative repository and inlines the full title/body/
        // labels into the task message. Dispatch aborts (fail-fast) when {issueRepo} is
        // un-substituted or the issue reference cannot be resolved, so a BA agent is never
        // told to fetch an issue the orchestrator did not ground.
        if (isSddSpecTask(steps)) {
            resolvedParams = groundSpecTaskIssue(templateItemId, steps, resolvedParams);
        }

        // Substitute parameters
        if (resolvedParams != null && !resolvedParams.isEmpty()) {
            for (WorkflowStep step : steps) {
                step.setPromptTemplate(
                        templateConverter.substituteParameters(step.getPromptTemplate(), resolvedParams));
            }
        }

        // Build CreateWorkflowRequest
        List<CreateWorkflowRequest.StepDef> stepDefs = steps.stream()
                .map(s -> CreateWorkflowRequest.StepDef.builder()
                        .agentId(s.getAgentId())
                        .promptTemplate(s.getPromptTemplate())
                        .maxIterations(s.getMaxIterations())
                        .kind(s.getKind())
                        .build())
                .toList();

        CreateWorkflowRequest request = CreateWorkflowRequest.builder()
                .name(item.getName() + "-instance")
                .description("Instantiated from template: " + item.getName())
                .steps(stepDefs)
                .allowSddSteps(true)
                .build();

        WorkflowResponse response = workflowService.createAndStart(request);

        // SDD wiring: templates carrying BA/DEV/QA step kinds initialise a DoD
        // record (custom stages [dev, qa], taskId = chainId) and a chain-level
        // kanban item without a linked run, so RunKanbanAutoCreator does not
        // auto-transition it.
        boolean isSdd = steps.stream().anyMatch(s ->
                s.getKind() == WorkflowStep.StepKind.BA
                        || s.getKind() == WorkflowStep.StepKind.DEV
                        || s.getKind() == WorkflowStep.StepKind.QA);
        if (isSdd) {
            dodService.init(response.getId().toString(), "SDD", List.of("dev", "qa"));
        }

        // Link source knowledge item to the newly created chain and inject the
        // system-derived branchName (sdd/<chainId>) into any {branchName} placeholders.
        // branchName is reserved (SYSTEM_PLACEHOLDERS) so callers cannot supply it.
        WorkflowChain newChain = chainRepository.findById(response.getId())
                .orElse(null);
        if (newChain != null) {
            newChain.setSourceKnowledgeItemId(templateItemId);
            // Persist the instantiation parameters (e.g. repoUrl) on the chain so the
            // spec-approval coordinator and Dev-completion fallback can resolve the
            // target repository without re-querying the template (T5 D-A).
            if (resolvedParams != null && !resolvedParams.isEmpty()) {
                newChain.setTemplateParams(GitHandoffMetadata.toJson(resolvedParams));
            }
            String branchName = "sdd/" + newChain.getId();
            List<WorkflowStep> instantiatedSteps = workflowService.deserializeSteps(newChain.getStepsJson());
            boolean branchInjected = false;
            for (WorkflowStep step : instantiatedSteps) {
                String prompt = step.getPromptTemplate();
                if (prompt != null && prompt.contains("{branchName}")) {
                    step.setPromptTemplate(prompt.replace("{branchName}", branchName));
                    branchInjected = true;
                }
            }
            if (branchInjected) {
                newChain.setStepsJson(workflowService.serializeSteps(instantiatedSteps));
            }
            chainRepository.save(newChain);

            // R9-F2 observability: emit a structured audit record (task ID, resolved
            // issueRepo, resolved issue number, body hash) so a downstream QA/audit step
            // can verify every spec task carried a grounded issue reference.
            if (resolvedParams != null && resolvedParams.containsKey(GitHandoffMetadata.KEY_ISSUE_NUMBER)) {
                log.info("SDD spec task dispatch grounded: taskId={} issueRepo={} issueNumber={} issueBodySha={}",
                        newChain.getId(),
                        resolvedParams.get(GitHandoffMetadata.KEY_ISSUE_REPO),
                        resolvedParams.get(GitHandoffMetadata.KEY_ISSUE_NUMBER),
                        resolvedParams.get(GitHandoffMetadata.KEY_ISSUE_BODY_SHA));
            }
        }

        log.info("Instantiated workflow template {} as chain {}", templateItemId, response.getId());
        return response;
    }

    // ---- SDD spec-task issue grounding helpers (R9-F2) ----------------------

    /** Clause a seeded spec-authoring prompt uses to tell the BA to fetch the issue itself. */
    private static final Pattern GH_FETCH_GUARDED_CLAUSE = Pattern.compile(
            "(?i)\\s*If the issue body is not already in your prompt, fetch it first with: "
                    + "gh issue view\\s*\\{[^}]*\\}\\s*-R\\s*\\{[^}]*\\}[^.]*\\.");
    /** Bare fallback for templates that instruct a fetch without the "not already in your prompt" guard. */
    private static final Pattern GH_FETCH_BARE_CLAUSE = Pattern.compile(
            "(?i)\\s*fetch it first with: gh issue view\\s*\\{[^}]*\\}\\s*-R\\s*\\{[^}]*\\}[^.]*\\.");

    /**
     * True when the workflow carries a spec-authoring (BA) step that instructs the agent
     * to fetch an issue ({@code gh issue view}) or names the {@code {issueRepo}} parameter.
     */
    private static boolean isSddSpecTask(List<WorkflowStep> steps) {
        for (WorkflowStep step : steps) {
            if (step.getKind() == WorkflowStep.StepKind.BA && referencesIssueRepo(step.getPromptTemplate())) {
                return true;
            }
        }
        return false;
    }

    private static boolean referencesIssueRepo(String promptTemplate) {
        if (promptTemplate == null || promptTemplate.isBlank()) {
            return false;
        }
        return promptTemplate.contains("gh issue view")
                || promptTemplate.contains("{" + GitHandoffMetadata.KEY_ISSUE_REPO + "}");
    }

    /**
     * Resolve the issue reference and inline the authoritative issue into every BA step.
     * Fails fast (before any task is emitted) when {@code issueRepo} is un-substituted,
     * {@code issueRef} is missing, or the key cannot be resolved in the repository.
     */
    private Map<String, String> groundSpecTaskIssue(UUID templateItemId, List<WorkflowStep> steps,
                                                    Map<String, String> resolvedParams) {
        Map<String, String> params = resolvedParams == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(resolvedParams);

        String issueRepo = params.get(GitHandoffMetadata.KEY_ISSUE_REPO);
        if (issueRepo == null || issueRepo.isBlank()) {
            throw new IllegalArgumentException(
                    "SDD spec task template " + templateItemId + " references {issueRepo} but no "
                            + "issueRepo was supplied; pass issueRepo=<owner/repo> so the BA task message "
                            + "can name its authoritative repository");
        }
        String issueRef = params.get(GitHandoffMetadata.KEY_ISSUE_REF);
        if (issueRef == null || issueRef.isBlank()) {
            throw new IllegalArgumentException(
                    "SDD spec task template " + templateItemId + " references {issueRef} but no "
                            + "issueRef was supplied; pass issueRef=<number | #number | issue URL | slug>");
        }

        // Normalize/validate issueRepo (owner/repo or github URL) before any lookup.
        String repo = GitHubIssueClient.parseRepository(issueRepo);

        // Resolve + fetch. IssueReferenceException propagates to the caller boundary as a
        // 4xx/5xx structured failure; the chain is never created with an ungrounded issue.
        GitHubIssue issue = gitHubIssueClient.resolveIssue(repo, issueRef);

        for (WorkflowStep step : steps) {
            if (step.getKind() == WorkflowStep.StepKind.BA
                    && referencesIssueRepo(step.getPromptTemplate())) {
                String prompt = neutralizeGhFetchClause(step.getPromptTemplate());
                step.setPromptTemplate(prompt + issueContextBlock(issue));
            }
        }

        // Canonicalize the reference and record the grounding audit trail on the chain.
        params.put(GitHandoffMetadata.KEY_ISSUE_REF, "#" + issue.number());
        params.put(GitHandoffMetadata.KEY_ISSUE_REPO, issue.ownerRepo());
        params.put(GitHandoffMetadata.KEY_ISSUE_NUMBER, String.valueOf(issue.number()));
        params.put(GitHandoffMetadata.KEY_ISSUE_BODY_SHA, issue.bodySha256());
        return params;
    }

    /** Strip any {@code gh issue view} fetch instruction so the BA relies on the inlined context. */
    private static String neutralizeGhFetchClause(String promptTemplate) {
        if (promptTemplate == null || promptTemplate.isBlank()) {
            return promptTemplate;
        }
        String cleaned = GH_FETCH_GUARDED_CLAUSE.matcher(promptTemplate).replaceAll(" ");
        cleaned = GH_FETCH_BARE_CLAUSE.matcher(cleaned).replaceAll(" ");
        return cleaned.replaceAll("[ ]{2,}", " ").trim();
    }

    /** Render the inline, authoritative issue context appended to the BA task message. */
    private static String issueContextBlock(GitHubIssue issue) {
        String body = issue.body() == null ? "" : issue.body().strip();
        String labels = issue.labels() == null || issue.labels().isEmpty()
                ? "(none)" : String.join(", ", issue.labels());
        StringBuilder block = new StringBuilder();
        block.append("\n\n--- Issue Context (inlined by the orchestrator; authoritative — ")
                .append("do not fetch the issue yourself) ---\n")
                .append("Repository: ").append(issue.ownerRepo()).append('\n')
                .append("Issue: #").append(issue.number()).append('\n')
                .append("Title: ").append(issue.title() == null ? "" : issue.title()).append('\n')
                .append("Labels: ").append(labels).append('\n')
                .append("Body:\n");
        if (body.isEmpty()) {
            block.append("(empty)").append('\n')
                    .append("The issue body is empty: record every missing fact in the spec's ")
                    .append("## Questions section and do not invent product behavior.\n");
        } else {
            block.append(body).append('\n');
        }
        return block.append("---").toString();
    }
}
