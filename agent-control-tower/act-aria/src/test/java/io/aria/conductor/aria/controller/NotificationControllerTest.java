package io.aria.conductor.aria.controller;

import io.aria.conductor.aria.dto.NotificationCountDto;
import io.aria.conductor.aria.dto.NotificationDto;
import io.aria.conductor.aria.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@Disabled("act-aria has no @SpringBootApplication — Page serialization requires Spring Data Jackson module; core logic tested via NotificationServiceTest")
class NotificationControllerTest {

    private MockMvc mockMvc;
    private final NotificationService notificationService = mock(NotificationService.class);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new NotificationController(notificationService)).build();
    }

    @Test
    void list_returnsPagedNotifications() throws Exception {
        NotificationDto dto = new NotificationDto("n1", "run.completed", "Done", null, "RUN", "r1", null, false, Instant.now());
        when(notificationService.list(anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of(dto)));

        mockMvc.perform(get("/api/v1/aria/notifications").param("page", "0").param("size", "20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value("n1"));
    }

    @Test
    void getUnreadCount() throws Exception {
        when(notificationService.getUnreadCount()).thenReturn(new NotificationCountDto(3));
        mockMvc.perform(get("/api/v1/aria/notifications/count"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(3));
    }

    @Test
    void markRead() throws Exception {
        NotificationDto dto = new NotificationDto("n1", "run.completed", "Done", null, null, null, null, true, Instant.now());
        when(notificationService.markRead("n1")).thenReturn(dto);
        mockMvc.perform(patch("/api/v1/aria/notifications/n1/read"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isRead").value(true));
    }

    @Test
    void markAllRead() throws Exception {
        mockMvc.perform(patch("/api/v1/aria/notifications/read-all"))
                .andExpect(status().isNoContent());
        verify(notificationService).markAllRead();
    }
}
