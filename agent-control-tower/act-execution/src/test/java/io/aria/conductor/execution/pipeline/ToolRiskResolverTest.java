package io.aria.conductor.execution.pipeline;

import io.aria.conductor.common.model.RiskTier;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
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
        resolver = new ToolRiskResolver(toolRepo);
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
}
