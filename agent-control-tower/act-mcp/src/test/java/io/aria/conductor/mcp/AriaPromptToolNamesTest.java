package io.aria.conductor.mcp;

import io.aria.conductor.aria.prompt.AriaPromptContributor;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.context.ContextAdapter;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.mcp.catalog.ToolCatalog;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T43 Item 2 (Major): the Aria policy told the model to "always use {@code instantiate_template}"
 * and "NEVER use {@code create_workflow}". Neither name is on the surface Aria calls: the only
 * registered workflow-instantiation tool is {@code instantiate_workflow_template}
 * ({@code WorkflowTools.java:42}), and {@code create_workflow} lives only in the TypeScript MCP
 * package, which a run is not wired to ({@code RunMcpWiring} wires the backend endpoint).
 *
 * <p>The pins in {@code AriaPromptContributorTest} were moved to the real name, but a content pin
 * can only hold the sentence it was written against. This test holds the <em>rule</em>: every
 * snake_case token the policy names must be a registered tool name, so the next drift fails here
 * instead of degrading a live turn into an unknown-tool call. It lives in act-mcp because only this
 * module sees both the prompt (act-aria) and the catalog.
 */
class AriaPromptToolNamesTest {

    /**
     * A snake_case identifier as the policy writes tool names. Deliberately lowercase-only: the
     * policy's non-tool tokens in this shape would have to be a tool name, which is the point.
     */
    private static final Pattern TOOL_SHAPED = Pattern.compile("\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b");

    private final ToolCatalog catalog = new ToolCatalog();

    @Test
    void everyToolNameThePolicyMentionsIsRegistered() {
        Set<String> mentioned = toolShapedTokens(policyContent());

        assertThat(mentioned)
                .as("the extractor must find the workflow tool the policy instructs Aria to use")
                .isNotEmpty()
                .contains("instantiate_workflow_template", "list_workflow_templates");
        assertThat(unregistered(mentioned))
                .as("the policy names a tool that is not on the platform MCP surface")
                .isEmpty();
    }

    /**
     * Positive control: the same extractor and the same registration check must reject a name that
     * is misspelled by one character, while the real name beside it stays accepted. Without this,
     * an extractor that silently found nothing would pass the rule above.
     */
    @Test
    void theCheckRejectsAMisspelledToolNameAndAcceptsARealOneBesideIt() {
        Set<String> mentioned = toolShapedTokens("use instantiate_templete first, then list_agents");

        assertThat(mentioned).containsExactlyInAnyOrder("instantiate_templete", "list_agents");
        assertThat(unregistered(mentioned)).containsExactly("instantiate_templete");
    }

    private Set<String> toolShapedTokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TOOL_SHAPED.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private Set<String> unregistered(Set<String> mention) {
        Set<String> registered = catalog.names();
        Set<String> unknown = new LinkedHashSet<>(mention);
        unknown.removeAll(registered);
        return unknown;
    }

    private static String policyContent() {
        ContextRequest request = new ContextRequest(UUID.randomUUID(), "conv-1", AriaConstants.ARIA_AGENT_ID,
                ContextAdapter.SYNC, 1, "list my agents", null, Sensitivity.INTERNAL, 24_000, null, "", List.of());
        List<ContextSection> sections = new AriaPromptContributor().contribute(request);
        assertThat(sections).hasSize(1);
        return sections.get(0).content();
    }
}
