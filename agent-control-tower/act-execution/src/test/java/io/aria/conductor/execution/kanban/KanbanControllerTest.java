package io.aria.conductor.execution.kanban;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.exception.GlobalExceptionHandler;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.env.Environment;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KanbanControllerTest {

    private KanbanService kanbanService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Environment mockEnv = mock(Environment.class);
    {
        when(mockEnv.getActiveProfiles()).thenReturn(new String[0]);
    }

    @BeforeEach
    void setUp() {
        kanbanService = mock(KanbanService.class);
        KanbanController controller = new KanbanController(kanbanService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(mockEnv))
                .build();
    }

    private KanbanItem sampleItem(String id, KanbanStatus status) {
        return KanbanItem.builder()
                .id(id)
                .title("Sample")
                .status(status)
                .priority(KanbanPriority.MEDIUM)
                .build();
    }

    @Test
    void create_returns201_withBody() throws Exception {
        KanbanItem created = sampleItem("abc-123", KanbanStatus.TODO);
        when(kanbanService.create(any(CreateKanbanItemRequest.class))).thenReturn(created);

        CreateKanbanItemRequest body = CreateKanbanItemRequest.builder()
                .title("Sample")
                .build();

        mockMvc.perform(post("/api/v1/kanban/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("abc-123"))
                .andExpect(jsonPath("$.status").value("TODO"));
    }

    @Test
    void list_returns200_withItems() throws Exception {
        when(kanbanService.list(null)).thenReturn(List.of(
                sampleItem("a", KanbanStatus.TODO),
                sampleItem("b", KanbanStatus.IN_PROGRESS)
        ));

        mockMvc.perform(get("/api/v1/kanban/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("a"));
    }

    @Test
    void list_withStatusParam_filters() throws Exception {
        when(kanbanService.list(KanbanStatus.DONE)).thenReturn(List.of(sampleItem("done-1", KanbanStatus.DONE)));

        mockMvc.perform(get("/api/v1/kanban/items").param("status", "DONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("DONE"));

        verify(kanbanService).list(KanbanStatus.DONE);
    }

    @Test
    void transition_returns200() throws Exception {
        KanbanItem after = sampleItem("xyz", KanbanStatus.IN_PROGRESS);
        when(kanbanService.transition(eq("xyz"), eq(KanbanStatus.IN_PROGRESS), any()))
                .thenReturn(after);

        TransitionRequest body = TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS)
                .comment("starting")
                .build();

        mockMvc.perform(post("/api/v1/kanban/items/xyz/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void transition_invalid_returns400() throws Exception {
        when(kanbanService.transition(eq("xyz"), eq(KanbanStatus.IN_PROGRESS), any()))
                .thenThrow(new IllegalArgumentException("Invalid kanban transition: DONE -> IN_PROGRESS"));

        TransitionRequest body = TransitionRequest.builder()
                .status(KanbanStatus.IN_PROGRESS)
                .build();

        mockMvc.perform(post("/api/v1/kanban/items/xyz/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid kanban transition: DONE -> IN_PROGRESS"));
    }

    @Test
    void get_missing_returns404() throws Exception {
        when(kanbanService.get("missing"))
                .thenThrow(new ResourceNotFoundException("KanbanItem", "missing"));

        mockMvc.perform(get("/api/v1/kanban/items/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/kanban/items/abc"))
                .andExpect(status().isNoContent());

        verify(kanbanService).delete("abc");
    }
}
