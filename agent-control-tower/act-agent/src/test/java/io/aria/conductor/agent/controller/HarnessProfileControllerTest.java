package io.aria.conductor.agent.controller;

import io.aria.conductor.agent.service.HarnessProfileService;
import io.aria.conductor.common.model.HarnessProfile;
import io.aria.conductor.test.WebMvcTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HarnessProfileControllerTest extends WebMvcTestBase {

    private final HarnessProfileService service = mock(HarnessProfileService.class);
    private final MockMvc mvc = mockMvcFor(new HarnessProfileController(service));

    private HarnessProfile weak() {
        return new HarnessProfile("weak-model-safe", List.of("shell_exec"),
                new HarnessProfile.Steering(true),
                new HarnessProfile.SelfVerify(true, List.of("PUSH"), 150, null), 25, 8000);
    }

    @Test
    void list_returnsConfiguredProfiles() throws Exception {
        when(service.listProfiles()).thenReturn(List.of(HarnessProfile.defaults(), weak()));

        mvc.perform(get("/api/v1/harness-profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("default"))
                .andExpect(jsonPath("$[1].name").value("weak-model-safe"))
                .andExpect(jsonPath("$[1].toolDenylist[0]").value("shell_exec"))
                .andExpect(jsonPath("$[1].maxToolCallRounds").value(25));
    }

    @Test
    void getByName_returnsResolvedProfile() throws Exception {
        when(service.resolveByName("weak-model-safe")).thenReturn(weak());

        mvc.perform(get("/api/v1/harness-profiles/weak-model-safe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("weak-model-safe"))
                .andExpect(jsonPath("$.steering.shellExecToGitPack").value(true))
                .andExpect(jsonPath("$.maxToolOutputChars").value(8000));
    }

    @Test
    void getByName_unknownName_fallsBackToDefaults() throws Exception {
        when(service.resolveByName("ghost")).thenReturn(HarnessProfile.defaults());

        mvc.perform(get("/api/v1/harness-profiles/ghost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("default"))
                .andExpect(jsonPath("$.maxToolCallRounds").value(0));
    }
}
