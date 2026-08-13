package io.aria.conductor.knowledge.converter;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bidirectional converter between {@link WorkflowChain} / {@link WorkflowStep}
 * domain objects and human-readable Markdown / machine-readable YAML templates.
 */
@Slf4j
@Component
public class WorkflowTemplateConverter {

    /** Placeholder names reserved for the system — never user-substitutable or exposed. */
    private static final Set<String> SYSTEM_PLACEHOLDERS = Set.of("previousOutput", "specRef");

    /** Matches {@code {paramName}} placeholders but NOT system placeholders. */
    private static final Pattern PARAM_PATTERN =
            Pattern.compile("\\{(?!(?:" + String.join("|", SYSTEM_PLACEHOLDERS) + "))([a-zA-Z_][a-zA-Z0-9_]*)\\}");

    private final AgentRepository agentRepository;

    public WorkflowTemplateConverter(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    // ------------------------------------------------------------------
    // 1. WorkflowChain → Markdown
    // ------------------------------------------------------------------

    /**
     * Render a workflow chain and its steps as human-readable Markdown.
     */
    public String workflowChainToMarkdown(WorkflowChain chain, List<WorkflowStep> steps) {
        if (chain == null) return "";

        StringBuilder md = new StringBuilder();
        md.append("# ").append(chain.getName() != null ? chain.getName() : "Untitled Workflow").append("\n\n");
        md.append(chain.getDescription() != null ? chain.getDescription() : "Workflow template.").append("\n\n");

        // Parameters section
        Set<String> paramNames = extractParameterNames(steps);
        md.append("## Parameters\n");
        md.append("| Name | Type | Required | Default | Description |\n");
        md.append("|------|------|----------|---------|-------------|\n");
        for (String param : paramNames) {
            md.append("| ").append(param)
                    .append(" | string | yes | — | ")
                    .append(toHumanLabel(param))
                    .append(" |\n");
        }
        md.append("\n");

        // Steps section
        md.append("## Steps\n\n");
        if (steps != null) {
            for (int i = 0; i < steps.size(); i++) {
                WorkflowStep step = steps.get(i);
                String agentLabel = shortenUuid(step.getAgentId());
                md.append("### Step ").append(i + 1).append(": Agent ").append(agentLabel).append("\n");
                md.append("**Prompt:**\n```\n");
                md.append(step.getPromptTemplate() != null ? step.getPromptTemplate() : "").append("\n");
                md.append("```\n");
                md.append("**Max Iterations:** ").append(step.getMaxIterations()).append("\n\n");
            }
        }

        return md.toString();
    }

    // ------------------------------------------------------------------
    // 2. WorkflowChain → YAML
    // ------------------------------------------------------------------

    /**
     * Serialize a workflow chain to a YAML template string.
     *
     * @param parameterDefs optional parameter definitions; if {@code null} or empty,
     *                      parameters are auto-extracted from the step prompt templates.
     */
    public String workflowChainToYaml(WorkflowChain chain,
                                      List<WorkflowStep> steps,
                                      List<Map<String, Object>> parameterDefs) {
        if (chain == null) return "";

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema_version", "1.0");
        doc.put("name", chain.getName() != null ? chain.getName() : "Untitled Workflow");
        doc.put("description", chain.getDescription() != null ? chain.getDescription() : "");
        doc.put("version", "v0.1.0");

        // Parameters
        List<Map<String, Object>> params;
        if (parameterDefs != null && !parameterDefs.isEmpty()) {
            params = parameterDefs;
        } else {
            params = new ArrayList<>();
            for (String name : extractParameterNames(steps)) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("name", name);
                p.put("type", "string");
                p.put("required", true);
                params.add(p);
            }
        }
        doc.put("parameters", params);

        // Steps
        List<Map<String, Object>> yamlSteps = new ArrayList<>();
        if (steps != null) {
            for (WorkflowStep step : steps) {
                Map<String, Object> ys = new LinkedHashMap<>();
                String role = resolveAgentRole(step.getAgentId());
                ys.put("agent_role", role);
                ys.put("agent_id", step.getAgentId() != null ? step.getAgentId().toString() : "");
                ys.put("prompt_template", step.getPromptTemplate() != null ? step.getPromptTemplate() : "");
                ys.put("max_iterations", step.getMaxIterations());
                if (step.getKind() != null && step.getKind() != WorkflowStep.StepKind.GENERIC) {
                    ys.put("kind", step.getKind().name());
                }
                yamlSteps.add(ys);
            }
        }
        doc.put("steps", yamlSteps);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);
        return yaml.dump(doc);
    }

    // ------------------------------------------------------------------
    // 3. YAML → WorkflowSteps
    // ------------------------------------------------------------------

    /**
     * Parse a YAML template string and extract a list of {@link WorkflowStep}s.
     */
    @SuppressWarnings("unchecked")
    public List<WorkflowStep> yamlToWorkflowSteps(String yamlStr) {
        if (yamlStr == null || yamlStr.isBlank()) return Collections.emptyList();

        Yaml yaml = new Yaml();
        Map<String, Object> doc = yaml.load(yamlStr);
        if (doc == null) return Collections.emptyList();

        List<Map<String, Object>> rawSteps = (List<Map<String, Object>>) doc.get("steps");
        if (rawSteps == null || rawSteps.isEmpty()) return Collections.emptyList();

        List<WorkflowStep> result = new ArrayList<>();
        for (Map<String, Object> raw : rawSteps) {
            WorkflowStep step = new WorkflowStep();

            // Resolve agent ID
            String agentIdStr = getStringValue(raw, "agent_id");
            if (agentIdStr != null && !agentIdStr.isBlank()) {
                try {
                    step.setAgentId(UUID.fromString(agentIdStr));
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid agent_id '{}', attempting role resolution", agentIdStr);
                    step.setAgentId(resolveAgentIdByRole(getStringValue(raw, "agent_role")));
                }
            } else {
                // Fall back to role-based resolution
                String role = getStringValue(raw, "agent_role");
                step.setAgentId(resolveAgentIdByRole(role));
            }

            if (step.getAgentId() == null) {
                String role = getStringValue(raw, "agent_role");
                throw new IllegalArgumentException(
                    "Cannot resolve agent for YAML step: role='" + (role != null ? role : "unknown") + "'. " +
                    "Provide a valid agent_id or agent_role that matches an existing agent.");
            }

            step.setPromptTemplate(getStringValue(raw, "prompt_template"));

            // SDD step kind (case-normalised; unknown/missing -> GENERIC).
            step.setKind(parseKind(getStringValue(raw, "kind")));

            Object maxIter = raw.get("max_iterations");
            if (maxIter instanceof Number) {
                step.setMaxIterations(((Number) maxIter).intValue());
            } else {
                step.setMaxIterations(3);
            }

            step.setStatus(WorkflowStep.Status.PENDING);
            result.add(step);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 4. Parameter substitution
    // ------------------------------------------------------------------

    /**
     * Replace {@code {key}} placeholders in the template with values from the map.
     * System placeholders such as {@code {previousOutput}} and {@code {specRef}}
     * are left untouched (resolved at runtime by the framework).
     */
    public String substituteParameters(String template, Map<String, String> params) {
        if (template == null || params == null || params.isEmpty()) return template;

        String result = template;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (SYSTEM_PLACEHOLDERS.contains(entry.getKey())) continue;
            String placeholder = "{" + entry.getKey() + "}";
            result = result.replace(placeholder, entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 5. Merge YAML templates
    // ------------------------------------------------------------------

    /**
     * Merge multiple YAML template strings by concatenating their {@code steps} lists
     * into a single document named "merged-workflow".
     */
    @SuppressWarnings("unchecked")
    public String mergeYamlTemplates(List<String> yamls) {
        if (yamls == null || yamls.isEmpty()) return "";

        Yaml yamlParser = new Yaml();
        List<Map<String, Object>> mergedSteps = new ArrayList<>();

        for (String yamlStr : yamls) {
            if (yamlStr == null || yamlStr.isBlank()) continue;
            Map<String, Object> doc = yamlParser.load(yamlStr);
            if (doc == null) continue;
            List<Map<String, Object>> steps = (List<Map<String, Object>>) doc.get("steps");
            if (steps != null) {
                mergedSteps.addAll(steps);
            }
        }

        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("schema_version", "1.0");
        merged.put("name", "merged-workflow");
        merged.put("steps", mergedSteps);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yamlDumper = new Yaml(options);
        return yamlDumper.dump(merged);
    }

    // ------------------------------------------------------------------
    // 6. Extract parameter names (helper)
    // ------------------------------------------------------------------

    /**
     * Scan all step prompt templates for {@code {paramName}} patterns.
     *
     * @return a sorted set of unique parameter names (excludes system placeholders
     *         such as {@code previousOutput} and {@code specRef}).
     */
    public Set<String> extractParameterNames(List<WorkflowStep> steps) {
        if (steps == null || steps.isEmpty()) return Collections.emptySet();

        Set<String> names = new TreeSet<>();
        for (WorkflowStep step : steps) {
            String template = step.getPromptTemplate();
            if (template == null || template.isBlank()) continue;
            Matcher matcher = PARAM_PATTERN.matcher(template);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Look up an agent's role by its ID. Returns "unknown" if not found.
     */
    private String resolveAgentRole(UUID agentId) {
        if (agentId == null) return "unknown";
        return agentRepository.findById(agentId)
                .map(Agent::getRole)
                .orElseGet(() -> {
                    log.warn("Agent not found for id {}, using role 'unknown'", agentId);
                    return "unknown";
                });
    }

    /**
     * Resolve an agent ID from a role string. Returns {@code null} if no agent
     * with the given role exists.
     */
    private UUID resolveAgentIdByRole(String role) {
        if (role == null || role.isBlank()) {
            log.warn("No agent_role provided, cannot resolve agent ID");
            return null;
        }
        return agentRepository.findByRole(role).stream()
                .findFirst()
                .map(Agent::getId)
                .orElseGet(() -> {
                    log.warn("No agent found for role '{}'", role);
                    return null;
                });
    }

    /** Shorten a UUID to its first 8 characters for display. */
    private static String shortenUuid(UUID id) {
        if (id == null) return "unknown";
        String s = id.toString();
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    /** Convert a camelCase/snake_case param name to a human-readable label. */
    private static String toHumanLabel(String param) {
        if (param == null) return "";
        // Split on underscores or camelCase boundaries
        String spaced = param.replace('_', ' ');
        spaced = spaced.replaceAll("([a-z])([A-Z])", "$1 $2");
        return spaced.substring(0, 1).toUpperCase() + spaced.substring(1);
    }

    /** Safely extract a string value from a map. */
    private static String getStringValue(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    /** Parse a step kind with case normalisation; null/unknown -> GENERIC. */
    static WorkflowStep.StepKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) return WorkflowStep.StepKind.GENERIC;
        try {
            return WorkflowStep.StepKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown workflow step kind '{}', defaulting to GENERIC", raw);
            return WorkflowStep.StepKind.GENERIC;
        }
    }
}