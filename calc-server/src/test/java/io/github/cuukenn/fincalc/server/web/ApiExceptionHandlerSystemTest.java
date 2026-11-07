package io.github.cuukenn.fincalc.server.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.cuukenn.fincalc.common.error.SystemException;
import io.github.cuukenn.fincalc.server.dto.CalculationResponseDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * R77's 500 mappings: both the {@code SystemException} path and the catch-all for anything
 * uncaught surface as HTTP 500 with the unified error body {@code {code: "FCS-SRV-1002",
 * message}} — never Spring Boot's default {@code /error} body, and no internal details in the
 * message.
 *
 * <p>{@link CalculationFacade} is a final class (the engine stays framework-free), so the 500
 * paths are exercised through standalone MockMvc with the real {@link ApiExceptionHandler}
 * advice attached to controller stubs that raise the failures.
 */
class ApiExceptionHandlerSystemTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new SystemExceptionController(), new UncaughtExceptionController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void system_exception_maps_to_500_fcs_srv_1002() throws Exception {
        mockMvc.perform(post("/stub/system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("FCS-SRV-1002"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void uncaught_runtime_exception_maps_to_unified_500_fcs_srv_1002() throws Exception {
        // The catch-all: an IllegalStateException (like one from a deep layer) must answer the
        // unified {code, message} body with the FIXED generic message — proving neither the
        // Spring default /error body nor the exception's own text leaks.
        mockMvc.perform(post("/stub/uncaught")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("FCS-SRV-1002"))
                .andExpect(jsonPath("$.message")
                        .value("FCS-SRV-1002: 系统内部错误"));
    }

    /** Stub standing in for the facade raising the unexpected SystemException. */
    @RestController
    static class SystemExceptionController {

        @PostMapping("/stub/system")
        public CalculationResponseDto calculate() {
            throw new SystemException("boom", null);
        }
    }

    /** Stub raising a failure outside the R77 business/system taxonomy (catch-all path). */
    @RestController
    static class UncaughtExceptionController {

        @PostMapping("/stub/uncaught")
        public CalculationResponseDto calculate() {
            throw new IllegalStateException("secret internal state must not leak");
        }
    }
}
