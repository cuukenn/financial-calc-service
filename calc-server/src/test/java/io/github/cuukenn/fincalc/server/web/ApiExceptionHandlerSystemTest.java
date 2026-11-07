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
 * R77's SystemException mapping: an unexpected engine failure surfaces as HTTP 500 with the
 * unified error body (server-side code, no internal details leaked).
 *
 * <p>{@link CalculationFacade} is a final class (the engine stays framework-free), so the 500
 * path is exercised through standalone MockMvc with the real {@link ApiExceptionHandler}
 * advice attached to a controller stub that raises the {@code SystemException}.
 */
class ApiExceptionHandlerSystemTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void system_exception_maps_to_500_fcs_srv_1002() throws Exception {
        mockMvc.perform(post("/api/v1/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("FCS-SRV-1002"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    /** Stub standing in for the facade raising the unexpected failure. */
    @RestController
    static class ThrowingController {

        @PostMapping("/api/v1/calculations")
        public CalculationResponseDto calculate() {
            throw new SystemException("boom", null);
        }
    }
}
