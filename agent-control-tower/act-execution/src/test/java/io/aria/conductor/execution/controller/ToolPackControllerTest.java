package io.aria.conductor.execution.controller;

import io.aria.conductor.common.model.PackKind;
import io.aria.conductor.common.model.ToolPack;
import io.aria.conductor.common.model.VersionStatus;
import io.aria.conductor.common.repository.ToolPackRepository;
import io.aria.conductor.execution.credential.PackCredentialService;
import io.aria.conductor.test.WebMvcTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static io.aria.conductor.test.TestDataBuilder.aToolPack;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ToolPackControllerTest extends WebMvcTestBase {

    private final ToolPackRepository packRepository = mock(ToolPackRepository.class);
    private final PackCredentialService credentialService = mock(PackCredentialService.class);
    private final MockMvc mvc = mockMvcFor(new ToolPackController(packRepository, credentialService));

    @Test
    void listPacks_returnsAllPacks() throws Exception {
        when(packRepository.findAll()).thenReturn(List.of(
                aToolPack().withId("pack-1").withName("git-pack").withStatus(VersionStatus.APPROVED).build(),
                aToolPack().withId("pack-2").withName("web-pack").withStatus(VersionStatus.PENDING).build()));

        mvc.perform(get("/api/v1/packs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("git-pack"))
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[1].status").value("PENDING"));
    }

    @Test
    void getPack_returns200WithPack() throws Exception {
        when(packRepository.findById("pack-1")).thenReturn(Optional.of(
                aToolPack().withId("pack-1").withName("git-pack").withKind(PackKind.MCP).build()));

        mvc.perform(get("/api/v1/packs/pack-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("pack-1"))
                .andExpect(jsonPath("$.kind").value("MCP"));
    }

    @Test
    void getPack_unknownId_returns404() throws Exception {
        when(packRepository.findById("missing")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/packs/missing"))
                .andExpect(status().isNotFound());
        verify(packRepository).findById("missing");
    }

    @Test
    void registerPack_withoutIdAndStatus_defaultsToGeneratedIdAndPending() throws Exception {
        when(packRepository.save(any(ToolPack.class))).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/v1/packs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "new-pack", "kind", "HANDLER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("new-pack"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.id").isNotEmpty());

        // Governance: registration must persist as PENDING, never pre-approved.
        ArgumentCaptor<ToolPack> saved = ArgumentCaptor.forClass(ToolPack.class);
        verify(packRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(VersionStatus.PENDING);
        assertThat(saved.getValue().getId()).isNotBlank();
    }

    @Test
    void registerPack_withExplicitIdAndStatus_preservesClientValues() throws Exception {
        when(packRepository.save(any(ToolPack.class))).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/v1/packs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("id", "custom-id", "name", "seeded", "status", "REJECTED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("custom-id"))
                .andExpect(jsonPath("$.status").value("REJECTED"));

        ArgumentCaptor<ToolPack> saved = ArgumentCaptor.forClass(ToolPack.class);
        verify(packRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo("custom-id");
    }

    @Test
    void approvePack_transitionsPendingToApprovedAndEnables() throws Exception {
        ToolPack pending = aToolPack().withId("pack-1")
                .withStatus(VersionStatus.PENDING).withEnabled(false).build();
        when(packRepository.findById("pack-1")).thenReturn(Optional.of(pending));
        when(packRepository.save(any(ToolPack.class))).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/v1/packs/pack-1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("pack-1"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.enabled").value(true));

        ArgumentCaptor<ToolPack> saved = ArgumentCaptor.forClass(ToolPack.class);
        verify(packRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(VersionStatus.APPROVED);
        assertThat(saved.getValue().isEnabled()).isTrue();
    }

    @Test
    void rejectPack_transitionsToRejectedAndDisables() throws Exception {
        ToolPack pending = aToolPack().withId("pack-1")
                .withStatus(VersionStatus.PENDING).withEnabled(true).build();
        when(packRepository.findById("pack-1")).thenReturn(Optional.of(pending));
        when(packRepository.save(any(ToolPack.class))).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/v1/packs/pack-1/reject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.enabled").value(false));

        ArgumentCaptor<ToolPack> saved = ArgumentCaptor.forClass(ToolPack.class);
        verify(packRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(VersionStatus.REJECTED);
        assertThat(saved.getValue().isEnabled()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject"})
    void decisionEndpoints_unknownPack_return404WithoutSaving(String action) throws Exception {
        when(packRepository.findById("missing")).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/packs/missing/" + action))
                .andExpect(status().isNotFound());
        verify(packRepository).findById("missing");
        verify(packRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void storeCredential_agentScoped_delegatesAllFieldsToService() throws Exception {
        mvc.perform(post("/api/v1/packs/pack-1/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("key", "API_TOKEN", "value", "s3cret", "agentId", "agent-9"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("stored"))
                .andExpect(jsonPath("$.key").value("API_TOKEN"));

        verify(credentialService).store("pack-1", "agent-9", "API_TOKEN", "s3cret");
    }

    @Test
    void storeCredential_withoutAgentId_storesGlobally() throws Exception {
        mvc.perform(post("/api/v1/packs/pack-1/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("key", "API_TOKEN", "value", "s3cret"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("API_TOKEN"));

        ArgumentCaptor<String> agentIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(credentialService).store(
                org.mockito.ArgumentMatchers.eq("pack-1"), agentIdCaptor.capture(),
                org.mockito.ArgumentMatchers.eq("API_TOKEN"), org.mockito.ArgumentMatchers.eq("s3cret"));
        assertThat(agentIdCaptor.getValue()).isNull();
    }

    @ParameterizedTest
    @MethodSource("incompleteCredentialBodies")
    void storeCredential_missingKeyOrValue_returns400WithoutStoring(Map<String, String> body)
            throws Exception {
        mvc.perform(post("/api/v1/packs/pack-1/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("key and value are required"));
        verifyNoInteractions(credentialService);
    }

    static Stream<Map<String, String>> incompleteCredentialBodies() {
        return Stream.of(
                Map.of("value", "s3cret"),          // key missing
                Map.of("key", "API_TOKEN"),         // value missing
                new HashMap<>());                    // both missing
    }
}
