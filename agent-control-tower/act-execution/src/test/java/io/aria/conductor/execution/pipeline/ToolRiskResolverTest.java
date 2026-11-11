package io.aria.conductor.execution.pipeline;

import io.aria.conductor.common.model.RiskTier;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import io.aria.conductor.common.tool.ToolCatalogProvider;
import io.aria.conductor.common.tool.ToolDescriptor;
import io.aria.conductor.common.tool.ToolPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolRiskResolverTest {

    @Mock
    private ToolDefinitionRepository toolRepo;

    private ToolRiskResolver resolver;

    @BeforeEach
    void setUp() {
        // No catalog wired: every pre-B1 expectation is about the configured row or the native map.
        resolver = new ToolRiskResolver(toolRepo, List::of);
    }

    @Test
    void resolveReturnsReadForUnknownTool() {
        when(toolRepo.findByName("nonexistent")).thenReturn(Optional.empty());
        assertThat(resolver.resolve("nonexistent")).isEqualTo(RiskTier.READ);
    }

    @Test
    void resolveReturnsReadForNullRiskTier() {
        ToolDefinition tool = ToolDefinition.builder().name("legacy_tool").build();
        when(toolRepo.findByName("legacy_tool")).thenReturn(Optional.of(tool));
        // null riskTier maps to READ (backward compatible with legacy tools)
        assertThat(resolver.resolve("legacy_tool")).isEqualTo(RiskTier.READ);
    }

    @Test
    void resolveReturnsPushTier() {
        ToolDefinition tool = ToolDefinition.builder().name("git_push").riskTier(RiskTier.PUSH).build();
        when(toolRepo.findByName("git_push")).thenReturn(Optional.of(tool));
        assertThat(resolver.resolve("git_push")).isEqualTo(RiskTier.PUSH);
    }

    @Test
    void requiresApprovalTrueForPush() {
        ToolDefinition tool = ToolDefinition.builder().name("git_push").riskTier(RiskTier.PUSH).build();
        when(toolRepo.findByName("git_push")).thenReturn(Optional.of(tool));
        assertThat(resolver.requiresApproval("git_push")).isTrue();
    }

    @Test
    void requiresApprovalTrueForDestructive() {
        ToolDefinition tool = ToolDefinition.builder().name("git_reset_hard").riskTier(RiskTier.DESTRUCTIVE).build();
        when(toolRepo.findByName("git_reset_hard")).thenReturn(Optional.of(tool));
        assertThat(resolver.requiresApproval("git_reset_hard")).isTrue();
    }

    @Test
    void requiresApprovalFalseForRead() {
        ToolDefinition tool = ToolDefinition.builder().name("git_status").riskTier(RiskTier.READ).build();
        when(toolRepo.findByName("git_status")).thenReturn(Optional.of(tool));
        assertThat(resolver.requiresApproval("git_status")).isFalse();
    }

    @Test
    void requiresApprovalFalseForWriteLocal() {
        ToolDefinition tool = ToolDefinition.builder().name("git_commit").riskTier(RiskTier.WRITE_LOCAL).build();
        when(toolRepo.findByName("git_commit")).thenReturn(Optional.of(tool));
        assertThat(resolver.requiresApproval("git_commit")).isFalse();
    }

    @Test
    void requiresApprovalFalseForUnknownTool() {
        when(toolRepo.findByName("unknown")).thenReturn(Optional.empty());
        assertThat(resolver.requiresApproval("unknown")).isFalse();
    }

    // ------------------------------------------------- native ACP tools (F28)

    @Test
    void resolveReturnsDestructiveForNativeBashRegardlessOfCase() {
        when(toolRepo.findByName("Bash")).thenReturn(Optional.empty());
        when(toolRepo.findByName("bash")).thenReturn(Optional.empty());

        assertThat(resolver.resolve("Bash")).isEqualTo(RiskTier.DESTRUCTIVE);
        assertThat(resolver.resolve("bash")).isEqualTo(RiskTier.DESTRUCTIVE);
    }

    @Test
    void resolveReturnsPushForNativeWriteAndEdit() {
        when(toolRepo.findByName("Write")).thenReturn(Optional.empty());
        when(toolRepo.findByName("Edit")).thenReturn(Optional.empty());

        assertThat(resolver.resolve("Write")).isEqualTo(RiskTier.PUSH);
        assertThat(resolver.resolve("Edit")).isEqualTo(RiskTier.PUSH);
    }

    @Test
    void resolveReturnsReadForNativeReadOnlyTools() {
        for (String tool : List.of("Read", "Glob", "Grep", "LS", "WebFetch", "WebSearch")) {
            when(toolRepo.findByName(tool)).thenReturn(Optional.empty());
            assertThat(resolver.resolve(tool)).as(tool).isEqualTo(RiskTier.READ);
        }
    }

    @Test
    void nativeToolNamesAreCaseInsensitive() {
        when(toolRepo.findByName("BASH")).thenReturn(Optional.empty());
        when(toolRepo.findByName("wEbFeTcH")).thenReturn(Optional.empty());

        assertThat(resolver.resolve("BASH")).isEqualTo(RiskTier.DESTRUCTIVE);
        assertThat(resolver.resolve("wEbFeTcH")).isEqualTo(RiskTier.READ);
    }

    @Test
    void configuredRowStillOverridesTheNativeTier() {
        ToolDefinition tool = ToolDefinition.builder().name("bash").riskTier(RiskTier.READ).build();
        when(toolRepo.findByName("bash")).thenReturn(Optional.of(tool));

        assertThat(resolver.resolve("bash")).isEqualTo(RiskTier.READ);
    }

    /**
     * F28: the configured row wins over the native map even when its tier is null (the documented
     * legacy-row READ). Only an absent row may consult the native map, so a null-tier {@code bash}
     * row must not be re-classified DESTRUCTIVE by the fallback.
     */
    @Test
    void aConfiguredNullTierRowWinsOverTheNativeTier() {
        ToolDefinition legacy = ToolDefinition.builder().name("bash").build();
        when(toolRepo.findByName("bash")).thenReturn(Optional.of(legacy));

        assertThat(resolver.resolve("bash"))
                .as("a configured null-tier row resolves READ; the native map is only for absent rows")
                .isEqualTo(RiskTier.READ);
        assertThat(resolver.requiresApproval("bash"))
                .as("the null-tier row never inherits the native DESTRUCTIVE approval requirement")
                .isFalse();
    }

    @Test
    void requiresApprovalTrueForNativeDestructiveAndPushTools() {
        when(toolRepo.findByName("Bash")).thenReturn(Optional.empty());
        when(toolRepo.findByName("Write")).thenReturn(Optional.empty());

        assertThat(resolver.requiresApproval("Bash")).isTrue();
        assertThat(resolver.requiresApproval("Write")).isTrue();
    }

    // ------------------------------------------- platform MCP catalog (B1, spec §5.3)

    /**
     * B1: the platform MCP tools are stamped READ today because the resolver only knows native
     * names, so {@code remember_fact} (a worker write) and {@code decide_memory_proposal} (an
     * operator-only action) both show as read-only on the approval card. The reviewed catalog is
     * the authority: the {@code mcp__aria-conductor__} prefix is stripped and the bare name is
     * looked up in act-common's one catalog.
     */
    @Test
    void platformMcpNamesResolveThroughTheCatalog() {
        ToolCatalogProvider catalog = () -> List.of(
                new ToolDescriptor("remember_fact", "d", ToolPolicy.WORKER_WRITE, false),
                new ToolDescriptor("decide_memory_proposal", "d", ToolPolicy.OPERATOR_ONLY, false),
                new ToolDescriptor("list_agents", "d", ToolPolicy.WORKER_READ, true));
        ToolRiskResolver resolver = new ToolRiskResolver(toolRepo, catalog);

        assertThat(resolver.resolve("mcp__aria-conductor__remember_fact")).isEqualTo(RiskTier.WRITE_LOCAL);
        assertThat(resolver.resolve("mcp__aria-conductor__decide_memory_proposal")).isEqualTo(RiskTier.PUSH);
        assertThat(resolver.resolve("mcp__aria-conductor__list_agents")).isEqualTo(RiskTier.READ);
        assertThat(resolver.policyOf("mcp__aria-conductor__remember_fact")).isEqualTo(ToolPolicy.WORKER_WRITE);
        assertThat(resolver.policyOf("mcp__aria-conductor__decide_memory_proposal")).isEqualTo(ToolPolicy.OPERATOR_ONLY);
    }

    /** A configured row is the operator's explicit override, so it still outranks the catalog. */
    @Test
    void aConfiguredRowStillWinsOverTheCatalog() {
        ToolDefinition row = new ToolDefinition();
        row.setName("mcp__aria-conductor__remember_fact");
        row.setRiskTier(RiskTier.DESTRUCTIVE);
        when(toolRepo.findByName("mcp__aria-conductor__remember_fact")).thenReturn(Optional.of(row));
        ToolCatalogProvider catalog = () -> List.of(
                new ToolDescriptor("remember_fact", "d", ToolPolicy.WORKER_WRITE, false));
        ToolRiskResolver resolver = new ToolRiskResolver(toolRepo, catalog);

        assertThat(resolver.resolve("mcp__aria-conductor__remember_fact")).isEqualTo(RiskTier.DESTRUCTIVE);
    }

    /** Fail closed: an unknown name keeps the READ default and is never given an invented policy. */
    @Test
    void anUnclassifiedNameStaysReadAndHasNoPolicy() {
        ToolRiskResolver resolver = new ToolRiskResolver(toolRepo, List::of);
        assertThat(resolver.resolve("mcp__aria-conductor__unknown_tool")).isEqualTo(RiskTier.READ);
        assertThat(resolver.policyOf("mcp__aria-conductor__unknown_tool")).isNull();
    }
}
