package io.aria.conductor.agent.service;

import io.aria.conductor.agent.dto.AgentResponse;
import io.aria.conductor.agent.dto.AgentTemplateDTO;
import io.aria.conductor.agent.dto.CreateAgentRequest;
import io.aria.conductor.common.model.AgentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTemplateServiceTest {

    @Mock AgentService agentService;
    @InjectMocks AgentTemplateService service;

    @Test
    void listTemplates_exposesTheThreeBuiltInRoles() {
        List<AgentTemplateDTO> templates = service.listTemplates();

        assertThat(templates).extracting(AgentTemplateDTO::getId)
                .containsExactlyInAnyOrder("ba", "dev", "qa");
        assertThat(templates).allSatisfy(t -> {
            assertThat(t.getAgentType()).isEqualTo(AgentType.ADK);
            assertThat(t.getProvider()).isEqualTo("alibaba");
            assertThat(t.getAdkProvider()).isEqualTo("langchain");
            assertThat(t.getLabel()).isNotBlank();
            assertThat(t.getDescription()).isNotBlank();
        });
    }

    @Test
    void listTemplates_devTemplateCarriesRoleSpecificMetadata() {
        AgentTemplateDTO dev = service.listTemplates().stream()
                .filter(t -> t.getId().equals("dev")).findFirst().orElseThrow();

        assertThat(dev.getRole()).isEqualTo("dev");
        assertThat(dev.getLabel()).isEqualTo("Developer Agent");
        assertThat(dev.getModel()).isEqualTo("ali-copilot");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "DEV", "Dev", "ba", "qa", "QA"})
    void createFromTemplate_isCaseInsensitiveAndDelegatesMatchingTemplate(String name) {
        UUID createdId = UUID.randomUUID();
        when(agentService.createAgent(org.mockito.ArgumentMatchers.any()))
                .thenReturn(AgentResponse.builder().id(createdId).build());

        AgentResponse response = service.createFromTemplate(name);

        assertThat(response.getId()).isEqualTo(createdId);

        ArgumentCaptor<CreateAgentRequest> captor = ArgumentCaptor.forClass(CreateAgentRequest.class);
        verify(agentService).createAgent(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(name.toLowerCase());
        assertThat(captor.getValue().getAgentType()).isEqualTo(AgentType.ADK);
    }

    @Test
    void createFromTemplate_unknownName_isRejectedWithoutCreatingAgent() {
        assertThatThrownBy(() -> service.createFromTemplate("architect"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("architect");
        verify(agentService, never()).createAgent(org.mockito.ArgumentMatchers.any());
    }
}
