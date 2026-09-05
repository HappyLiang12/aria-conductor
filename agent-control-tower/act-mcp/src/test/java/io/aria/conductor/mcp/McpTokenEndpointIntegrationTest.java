package io.aria.conductor.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Token-mode contract over real HTTP: both the SSE handshake and the message
 * endpoint require a valid Bearer (spec §6 — the 1.0.9 SSE handshake was the
 * gap the unit quartet could not catch). context = auth-mode=token.
 */
@SpringBootTest(classes = McpTestBootstrap.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"aria.mcp.enabled=true", "aria.mcp.auth-mode=token", "aria.mcp.token=test-token-1"})
class McpTokenEndpointIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    WebApplicationContext context;

    @MockitoBean io.aria.conductor.knowledge.service.WorkflowTemplateService workflowTemplateService;
    @MockitoBean io.aria.conductor.agent.service.WorkflowService workflowService;
    @MockitoBean io.aria.conductor.execution.mcp.McpProperties mcpProperties;
    @MockitoBean io.aria.conductor.knowledge.service.KnowledgeService knowledgeService;
    @MockitoBean io.aria.conductor.execution.approval.ApprovalQueryService approvalQueryService;
    @MockitoBean io.aria.conductor.execution.approval.ApprovalGate approvalGate;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(mcpProperties.isTokenMode()).thenReturn(true);
        when(mcpProperties.getToken()).thenReturn("test-token-1");
        // webAppContextSetup does NOT auto-register Filter beans — the token filter
        // must be added explicitly to sit in front of the DispatcherServlet.
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(McpTokenFilter.class))
                .build();
    }

    @Test
    void sseHandshake_withoutBearer_401() throws Exception {
        mvc.perform(get("/sse")).andExpect(status().isUnauthorized());
    }

    @Test
    void messageEndpoint_withoutBearer_401() throws Exception {
        mvc.perform(post("/mcp/message")).andExpect(status().isUnauthorized());
    }

    @Test
    void sseHandshake_withValidBearer_opens() throws Exception {
        // ServerResponse.sse() starts the SSE stream asynchronously (status 200 +
        // text/event-stream written before the async start) and stays open — assert
        // the initial result only (asyncStarted proves the handshake really opened
        // the stream) so MockMvc never waits on the never-completing SSE body.
        MvcResult result = mvc.perform(get("/sse").header("Authorization", "Bearer test-token-1"))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
    }
}
