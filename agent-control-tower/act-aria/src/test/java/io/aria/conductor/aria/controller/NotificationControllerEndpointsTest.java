package io.aria.conductor.aria.controller;

import io.aria.conductor.aria.dto.NotificationCountDto;
import io.aria.conductor.aria.dto.NotificationDto;
import io.aria.conductor.aria.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoint tests for the non-paging notification routes. The paging list
 * endpoint is exercised elsewhere; standalone MockMvc cannot reliably
 * serialize {@link org.springframework.data.domain.Page} without the Spring
 * Data web extension, which is why it is skipped here.
 */
class NotificationControllerEndpointsTest {

    private MockMvc mockMvc;
    private final NotificationService notificationService = mock(NotificationService.class);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new NotificationController(notificationService))
                .build();
    }

    @Test
    void count_returnsUnreadCount() throws Exception {
        when(notificationService.getUnreadCount()).thenReturn(new NotificationCountDto(5));

        mockMvc.perform(get("/api/v1/aria/notifications/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(5));
    }

    @Test
    void count_returnsZeroWhenNothingUnread() throws Exception {
        when(notificationService.getUnreadCount()).thenReturn(new NotificationCountDto(0));

        mockMvc.perform(get("/api/v1/aria/notifications/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void markRead_returnsUpdatedNotification() throws Exception {
        NotificationDto dto = new NotificationDto("n-1", "JOB_FIRED", "Reminder",
                "Job fired", "job", "j-1", "j-1", true, Instant.now());
        when(notificationService.markRead("n-1")).thenReturn(dto);

        mockMvc.perform(patch("/api/v1/aria/notifications/n-1/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("n-1"))
                .andExpect(jsonPath("$.title").value("Reminder"))
                .andExpect(jsonPath("$.type").value("JOB_FIRED"));

        verify(notificationService).markRead("n-1");
    }

    @Test
    void markAllRead_returns204AndDelegates() throws Exception {
        mockMvc.perform(patch("/api/v1/aria/notifications/read-all"))
                .andExpect(status().isNoContent());

        verify(notificationService).markAllRead();
    }
}
