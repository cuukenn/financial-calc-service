package io.github.cuukenn.fincalc.server.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Plan 2 Task 2: the {@code POST /api/v1/calculations} contract over MockMvc.
 *
 * <p>The request bodies mirror the engine golden fixtures (12 monthly periods, principal
 * 1 000 000 at 6% nominal on THIRTY_360) so the asserted values are the established goldens:
 * {@code TOTAL_RENT = 1 032 797.15} (Task-21 tail-adjusted reference). Money in responses is
 * serialized as JSON strings (financially safe: no double round-trip corruption).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class CalculationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void happy_path_equal_installment_answers_200_with_golden_metrics() throws Exception {
        mockMvc.perform(post("/api/v1/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(happyBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").isNotEmpty())
                .andExpect(jsonPath("$.schedule.header.periods").value(12))
                .andExpect(jsonPath("$.schedule.header.metrics.TOTAL_RENT").value("1032797.15"))
                .andExpect(jsonPath("$.schedule.installments", hasSize(12)))
                .andExpect(jsonPath("$.schedule.hasErrors").value(false));
    }

    @Test
    void segmented_rule_rows_run_end_to_end_with_segmented_solver() throws Exception {
        mockMvc.perform(post("/api/v1/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(segmentedBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedule.header.solver").value("SEGMENTED_RESOLVE"))
                .andExpect(jsonPath("$.schedule.installments", hasSize(12)))
                .andExpect(jsonPath("$.schedule.installments[0].appliedRate").value("0.03"))
                .andExpect(jsonPath("$.schedule.installments[6].appliedRate").value("0.06"))
                .andExpect(jsonPath("$.schedule.hasErrors").value(false));
    }

    @Test
    void explicit_rent_echoes_the_given_rents() throws Exception {
        mockMvc.perform(post("/api/v1/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(explicitRentBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedule.installments", hasSize(12)))
                .andExpect(jsonPath("$.schedule.installments[0].rentDue").value("86066.43"))
                .andExpect(jsonPath("$.schedule.installments[11].rentDue").value("86066.42"))
                .andExpect(jsonPath("$.schedule.hasErrors").value(false));
    }

    @Test
    void principal_zero_maps_to_400_fcs_dom_1001() throws Exception {
        mockMvc.perform(post("/api/v1/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(happyBody().replace("\"principal\": 1000000", "\"principal\": 0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FCS-DOM-1001"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void unknown_method_maps_to_400_fcs_eng_1002() throws Exception {
        mockMvc.perform(post("/api/v1/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(happyBody().replace("EQUAL_INSTALLMENT", "NO_SUCH_METHOD")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FCS-ENG-1002"));
    }

    @Test
    void malformed_json_maps_to_400_fcs_srv_1001() throws Exception {
        mockMvc.perform(post("/api/v1/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FCS-SRV-1001"));
    }

    @Test
    void rule_row_missing_mandatory_columns_maps_to_400_fcs_com_0001() throws Exception {
        // "ruleRows": [{}] — ruleId/principalPlan/interestTreatment/paymentTiming/specialAction
        // are mandatory; the assembler must fail loud with the unified body BEFORE RuleRow's
        // requireNonNull turns the client mistake into a NullPointerException/500.
        mockMvc.perform(post("/api/v1/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(happyBody().replace(
                                "\"rateChangePolicy\": \"RESOLVE\"",
                                "\"rateChangePolicy\": \"RESOLVE\","
                                        + "\"ruleRows\": [{}]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FCS-COM-0001"))
                .andExpect(jsonPath("$.message").value(containsString("ruleRows[0].ruleId")));
    }

    @Test
    void rule_driven_without_rule_set_maps_to_400_fcs_eng_1003() throws Exception {
        mockMvc.perform(post("/api/v1/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(happyBody().replace("EQUAL_INSTALLMENT", "RULE_DRIVEN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FCS-ENG-1003"));
    }

    // ---- request bodies (no text blocks: the build targets JDK 11) ----

    /** The Task-21 standard lease fixture as a flat JSON request. */
    private static String happyBody() {
        return "{"
                + "\"principal\": 1000000,"
                + "\"nominalRate\": 0.06,"
                + "\"commenceDate\": \"2024-01-01\","
                + "\"firstRentDate\": \"2024-02-01\","
                + "\"periods\": 12,"
                + "\"frequency\": \"MONTHLY\","
                + "\"timing\": \"IN_ARREARS\","
                + "\"dayCountCode\": \"THIRTY_360\","
                + "\"dateRollCode\": \"NONE\","
                + "\"residualValue\": 0,"
                + "\"residualHandling\": \"SEPARATE\","
                + "\"deposit\": 0,"
                + "\"vatRate\": 0.06,"
                + "\"method\": \"EQUAL_INSTALLMENT\","
                + "\"currency\": \"CNY\","
                + "\"rateChangePolicy\": \"RESOLVE\""
                + "}";
    }

    /** The standard lease plus two segmented rate rows: 1-6 at 3%, 7-12 at 6%. */
    private static String segmentedBody() {
        return happyBody().replace(
                "\"rateChangePolicy\": \"RESOLVE\"",
                "\"rateChangePolicy\": \"RESOLVE\","
                        + "\"ruleRows\": ["
                        + "{"
                        + "\"ruleId\": \"R1\", \"priority\": 10, \"fromPeriod\": 1, \"toPeriod\": 6,"
                        + "\"annualRate\": 0.03, \"principalPlan\": \"INHERIT\","
                        + "\"interestTreatment\": \"PAY_AS_INCURRED\","
                        + "\"paymentTiming\": \"IN_ARREARS\", \"specialAction\": \"NONE\""
                        + "},"
                        + "{"
                        + "\"ruleId\": \"R2\", \"priority\": 20, \"fromPeriod\": 7, \"toPeriod\": 12,"
                        + "\"annualRate\": 0.06, \"principalPlan\": \"INHERIT\","
                        + "\"interestTreatment\": \"PAY_AS_INCURRED\","
                        + "\"paymentTiming\": \"IN_ARREARS\", \"specialAction\": \"NONE\""
                        + "}"
                        + "]");
    }

    /** The CalculationFacadeTest annuity golden: 11 x 86 066.43 plus a tail of 86 066.42. */
    private static String explicitRentBody() {
        StringBuilder rents = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            rents.append("86066.43").append(i < 10 ? ", " : ", ");
        }
        rents.append("86066.42");
        return happyBody().replace("\"method\": \"EQUAL_INSTALLMENT\"",
                "\"method\": \"EXPLICIT_RENT\", \"explicitRents\": [" + rents + "]");
    }
}
