package io.aria.conductor.agent.service;

import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.HarnessProfile;
import io.aria.conductor.common.model.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HarnessProfileServiceTest {

    @Mock private SystemConfigService systemConfig;
    private HarnessProfileService service;

    private static final String WEAK = "{\"name\":\"weak-model-safe\",\"toolDenylist\":[\"shell_exec\"],"
            + "\"steering\":{\"shellExecToGitPack\":true},"
            + "\"selfVerify\":{\"enabled\":true,\"escalateTiers\":[\"PUSH\"],\"maxResponseTokens\":150,\"promptOverride\":null},"
            + "\"maxToolCallRounds\":25,\"maxToolOutputChars\":8000}";

    @BeforeEach
    void setUp() {
        service = new HarnessProfileService(systemConfig);
        lenient().when(systemConfig.get(eq("harness.default.profile"), any())).thenReturn("default");
    }

    @Test
    void resolve_noConfig_usesDefaultNoOpProfile() {
        Agent agent = Agent.builder().role("dev").config(null).build();
        HarnessProfile p = service.resolve(agent);
        assertThat(p.name()).isEqualTo("default");
        assertThat(p.toolDenylist()).isEmpty();
        assertThat(p.steering().shellExecToGitPack()).isFalse();
        assertThat(p.selfVerify().escalateTiers()).isEmpty();
        assertThat(p.maxToolCallRounds()).isZero();
    }

    @Test
    void resolve_agentConfigProfile_loadsAndParses() {
        Agent agent = Agent.builder().role("dev").config("{\"harnessProfile\":\"weak-model-safe\"}").build();
        when(systemConfig.get(eq("harness.profile.weak-model-safe"), any())).thenReturn(WEAK);

        HarnessProfile p = service.resolve(agent);

        assertThat(p.name()).isEqualTo("weak-model-safe");
        assertThat(p.denies("shell_exec")).isTrue();
        assertThat(p.denies("SHELL_EXEC")).isTrue(); // case-insensitive
        assertThat(p.steering().shellExecToGitPack()).isTrue();
        assertThat(p.canEscalateTier("PUSH")).isTrue();
        assertThat(p.canEscalateTier("READ")).isFalse();
        assertThat(p.maxToolCallRounds()).isEqualTo(25);
        assertThat(p.effectiveOutputCap(16000)).isEqualTo(8000);
        assertThat(p.selfVerify().maxResponseTokens()).isEqualTo(150);
    }

    @Test
    void resolve_malformedJson_fallsBackToDefaults() {
        Agent agent = Agent.builder().role("dev").config("{\"harnessProfile\":\"broken\"}").build();
        when(systemConfig.get(eq("harness.profile.broken"), any())).thenReturn("{not valid json");

        HarnessProfile p = service.resolve(agent);
        assertThat(p.name()).isEqualTo("default");
    }

    @Test
    void parse_partialProfile_mergesOverDefaults() throws Exception {
        // Only steering supplied; everything else must fall back to defaults().
        HarnessProfile p = service.parse("partial", "{\"steering\":{\"shellExecToGitPack\":true}}");
        assertThat(p.steering().shellExecToGitPack()).isTrue();
        assertThat(p.toolDenylist()).isEmpty();
        assertThat(p.selfVerify().enabled()).isTrue();
        assertThat(p.maxToolOutputChars()).isEqualTo(16000);
    }

    @Test
    void applyDenylist_removesDeniedTools() {
        HarnessProfile weak = new HarnessProfile("w", List.of("shell_exec"),
                new HarnessProfile.Steering(true),
                new HarnessProfile.SelfVerify(true, List.of(), 200, null), 0, 16000);
        ToolDefinition shell = ToolDefinition.builder().id("1").name("shell_exec").build();
        ToolDefinition git = ToolDefinition.builder().id("2").name("git_clone").build();

        List<ToolDefinition> filtered = service.applyDenylist(List.of(shell, git), weak);

        assertThat(filtered).extracting(ToolDefinition::getName).containsExactly("git_clone");
    }

    @Test
    void applyDenylist_defaultProfile_isNoOp() {
        ToolDefinition shell = ToolDefinition.builder().id("1").name("shell_exec").build();
        List<ToolDefinition> tools = List.of(shell);
        assertThat(service.applyDenylist(tools, HarnessProfile.defaults())).isEqualTo(tools);
    }
}
