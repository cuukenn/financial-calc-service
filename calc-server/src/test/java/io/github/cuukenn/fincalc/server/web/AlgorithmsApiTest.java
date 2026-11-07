package io.github.cuukenn.fincalc.server.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Plan 2 Task 3: the {@code GET /api/v1/algorithms} contract — every {@code RepaymentMethodId}
 * code answers with a non-blank Chinese description (the five original entries plus
 * EXPLICIT_RENT from R68), so clients can drive {@code POST /api/v1/calculations}'s
 * {@code method} field without hard-coding it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AlgorithmsApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void lists_all_method_codes_with_descriptions() throws Exception {
        mockMvc.perform(get("/api/v1/algorithms"))
                .andExpect(status().isOk())
                // The five original codes...
                .andExpect(jsonPath("$[?(@.code == 'EQUAL_INSTALLMENT')].description",
                        not(emptyOrNullString())))
                .andExpect(jsonPath("$[?(@.code == 'EQUAL_PRINCIPAL')].description",
                        not(emptyOrNullString())))
                .andExpect(jsonPath("$[?(@.code == 'INTEREST_FIRST')].description",
                        not(emptyOrNullString())))
                .andExpect(jsonPath("$[?(@.code == 'PRINCIPAL_FIRST')].description",
                        not(emptyOrNullString())))
                .andExpect(jsonPath("$[?(@.code == 'RULE_DRIVEN')].description",
                        not(emptyOrNullString())))
                // ...plus EXPLICIT_RENT (R68, Plan 1.5).
                .andExpect(jsonPath("$[?(@.code == 'EXPLICIT_RENT')].description",
                        not(emptyOrNullString())));
    }

    @Test
    void entry_count_tracks_the_domain_enum_exactly() throws Exception {
        mockMvc.perform(get("/api/v1/algorithms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(RepaymentMethodId.values().length)));
    }

    @Test
    void explicit_rent_entry_is_present() throws Exception {
        mockMvc.perform(get("/api/v1/algorithms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'EXPLICIT_RENT')]").isNotEmpty());
    }
}
