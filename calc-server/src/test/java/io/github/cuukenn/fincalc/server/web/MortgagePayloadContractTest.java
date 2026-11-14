package io.github.cuukenn.fincalc.server.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Task 7 fix round 1 (R137): the 房贷 payload that the Web 房贷 Tab actually builds is proven
 * against the <em>real</em> engine here, not against a mock.
 *
 * <p>{@code MortgageForm.buildRequest} sends exactly 15 keys (14 + {@code product}, issue 15/A1);
 * three of them
 * ({@code residualValue} / {@code residualHandling} / {@code rateChangePolicy}) are mandatory
 * {@code LeaseElement} inputs that the form now sends explicitly instead of leaving the server to
 * default them — AGENTS.md 输入要素必须显式提供、缺失即报错、不得静默取默认值, and
 * {@code CalculationRequestAssembler} only defaults {@code rounding} / {@code gracePeriod}. The
 * bodies below are byte-for-byte the payload shape the form produces (money-as-string, the form's
 * own default 要素), so the values asserted are what the Tab will really show.
 *
 * <p>The asserted §3.4 facts are the two the mortgage shape must land exactly: 本金守恒
 * ({@code Σ principalDue = 合同本金}, read here through the last row's {@code cumulativePrincipal})
 * and 末期余额 = 0 ({@code SEPARATE} + 残值 0 → nothing is folded into the rents). A broken chain
 * would surface as an ERROR issue and {@code hasErrors = true}, so {@code issues} being empty is
 * part of the assertion rather than a formality.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class MortgagePayloadContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void mortgage_tab_equal_installment_payload_answers_200_and_closes_the_chain() throws Exception {
        mockMvc.perform(post("/api/v1/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(equalInstallmentBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").isNotEmpty())
                .andExpect(jsonPath("$.schedule.header.periods").value(360))
                .andExpect(jsonPath("$.schedule.installments", hasSize(360)))
                // §3.4 校验一：Σ本金 = 合同本金（残值单独收取且为 0）。
                .andExpect(jsonPath("$.schedule.installments[359].cumulativePrincipal")
                        .value("2380000.00"))
                .andExpect(jsonPath("$.schedule.header.metrics.TOTAL_PRINCIPAL").value("2380000.00"))
                // §3.4 校验二：末期余额 = 并入租金的残值（这里残值为 0）→ 0.00。
                .andExpect(jsonPath("$.schedule.installments[359].closingBalance").value("0.00"))
                // §3.4 校验四：Σ租金 = Σ本金 + Σ利息 + Σ费用 + Σ税（费用与税缺席 → 0）。
                .andExpect(jsonPath("$.schedule.header.metrics.TOTAL_INTEREST").value("1467416.03"))
                .andExpect(jsonPath("$.schedule.header.metrics.TOTAL_RENT").value("3847416.03"))
                .andExpect(jsonPath("$.schedule.installments[0].rentDue").value("10687.26"))
                .andExpect(jsonPath("$.schedule.installments[359].rentDue").value("10689.69"))
                .andExpect(jsonPath("$.schedule.hasErrors").value(false))
                .andExpect(jsonPath("$.schedule.issues", empty()));
    }

    @Test
    void mortgage_tab_bullet_payload_answers_200_and_settles_the_deferred_interest_at_maturity()
            throws Exception {
        mockMvc.perform(post("/api/v1/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulletBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedule.installments", hasSize(12)))
                // 递延期：每期计提、实付 0（R102 的引擎语义），所以末期一次性结清。
                .andExpect(jsonPath("$.schedule.installments[0].interestAccrued").value("6941.67"))
                .andExpect(jsonPath("$.schedule.installments[0].interestPaid").value("0.00"))
                .andExpect(jsonPath("$.schedule.installments[0].rentDue").value("0.00"))
                .andExpect(jsonPath("$.schedule.installments[0].closingBalance").value("2380000.00"))
                .andExpect(jsonPath("$.schedule.installments[11].interestPaid").value("83300.04"))
                .andExpect(jsonPath("$.schedule.installments[11].rentDue").value("2463300.04"))
                .andExpect(jsonPath("$.schedule.installments[11].closingBalance").value("0.00"))
                .andExpect(jsonPath("$.schedule.installments[11].cumulativePrincipal")
                        .value("2380000.00"))
                .andExpect(jsonPath("$.schedule.hasErrors").value(false))
                .andExpect(jsonPath("$.schedule.issues", empty()));
    }

    @Test
    void omitting_the_mandatory_elements_still_answers_400_no_default_is_invented() throws Exception {
        // R137 keeps the fix on the Web side: the contract still refuses a payload that leaves a
        // mandatory 输入要素 out, so a future 房贷 payload change cannot reintroduce the bug by
        // quietly expecting a server-side default.
        mockMvc.perform(post("/api/v1/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithoutMandatoryElements()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FCS-COM-0001"))
                .andExpect(jsonPath("$.message").value(containsString("residualHandling")));
    }

    @Test
    void algorithms_entry_describes_bullet_loan_as_deferred_accrual_settled_at_maturity()
            throws Exception {
        // R136: the one text-only fix. The description must agree with the engine and the README
        // table (interest accrues every period, deferred, settled at maturity with the principal).
        // Entry count and codes are untouched — see AlgorithmsApiTest for that contract.
        mockMvc.perform(get("/api/v1/algorithms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'BULLET_LOAN')].description",
                        everyItem(containsString("每期照常计提利息"))))
                .andExpect(jsonPath("$[?(@.code == 'BULLET_LOAN')].description",
                        everyItem(containsString("递延"))))
                .andExpect(jsonPath("$[?(@.code == 'BULLET_LOAN')].description",
                        everyItem(not(containsString("期内不付本息")))));
    }

    // ---- request bodies (no text blocks: the build targets JDK 11) ----

    /** The 房贷 Tab's default run: 2 380 000 @ LPR 3.5 + 加点 0 → 0.035, 360 months. */
    private static String equalInstallmentBody() {
        return mortgageBody("EQUAL_INSTALLMENT", 360);
    }

    /** The same payload with 到期一次还本付息 at 12 期 (the Tab only exposes it at ≤ 12 期, R109). */
    private static String bulletBody() {
        return mortgageBody("BULLET_LOAN", 12);
    }

    private static String mortgageBody(String method, int periods) {
        return "{"
                + "\"principal\": \"2380000\","
                + "\"nominalRate\": \"0.035\","
                + "\"commenceDate\": \"2026-11-01\","
                + "\"firstRentDate\": \"2026-12-01\","
                + "\"periods\": " + periods + ","
                + "\"frequency\": \"MONTHLY\","
                + "\"timing\": \"IN_ARREARS\","
                + "\"dayCountCode\": \"THIRTY_360\","
                + "\"dateRollCode\": \"NONE\","
                + "\"currency\": \"CNY\","
                + "\"method\": \"" + method + "\","
                + "\"residualValue\": \"0\","
                + "\"residualHandling\": \"SEPARATE\","
                + "\"rateChangePolicy\": \"RESOLVE\""
                + "}";
    }

    /** The 11-key payload the Tab used to send (Task 7 finding D-1), kept as a regression pin. */
    private static String bodyWithoutMandatoryElements() {
        return mortgageBody("EQUAL_INSTALLMENT", 360)
                .replace(",\"residualValue\": \"0\"", "")
                .replace(",\"residualHandling\": \"SEPARATE\"", "")
                .replace(",\"rateChangePolicy\": \"RESOLVE\"", "");
    }
}
