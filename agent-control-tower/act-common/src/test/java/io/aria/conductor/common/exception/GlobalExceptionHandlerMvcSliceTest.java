package io.aria.conductor.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC-slice proof of F5: the shared handler actually turns the three
 * framework exceptions Spring throws before a controller runs into 400/405
 * instead of letting the catch-all return 500. The reproduction from the live
 * QA round was:
 * <ul>
 *   <li>{@code GET /api/v1/runs/46650059} → 500 {@code MethodArgumentTypeMismatchException}</li>
 *   <li>{@code GET /api/v1/packs/pack-git-0001/credentials} → 500
 *       {@code HttpRequestMethodNotSupportedException}</li>
 *   <li>empty POST body → 500 {@code HttpMessageNotReadableException}</li>
 * </ul>
 * A standalone MockMvc slice keeps the test on the advice only: no database,
 * no security, no context.
 */
class GlobalExceptionHandlerMvcSliceTest {

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler(new MockEnvironment()))
            .build();

    @Test
    void malformedUuidPathVariable_returns400Not500() throws Exception {
        mvc.perform(get("/probe/runs/46650059"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("id")));
    }

    @Test
    void unsupportedMethod_returns405Not500() throws Exception {
        mvc.perform(delete("/probe/credentials"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void missingRequiredQueryParameter_returns400Not500() throws Exception {
        mvc.perform(get("/probe/credentials"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("status")));
    }

    @Test
    void unreadableBody_returns400Not500() throws Exception {
        mvc.perform(post("/probe/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    record ProbeBody(String value) {}

    @RestController
    static class ProbeController {

        @GetMapping("/probe/runs/{id}")
        String run(@PathVariable UUID id) {
            return id.toString();
        }

        @GetMapping("/probe/credentials")
        String credentials(@RequestParam String status) {
            return status;
        }

        @PostMapping("/probe/echo")
        String echo(@RequestBody ProbeBody body) {
            return body.value();
        }
    }
}
