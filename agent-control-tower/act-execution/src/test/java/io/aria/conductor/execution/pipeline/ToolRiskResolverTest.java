package io.aria.conductor.execution.pipeline;

import io.aria.conductor.common.model.RiskTier;
import io.aria.conductor.common.model.ToolDefinition;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
}
