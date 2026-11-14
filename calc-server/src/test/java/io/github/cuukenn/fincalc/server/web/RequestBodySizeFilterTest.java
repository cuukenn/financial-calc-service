package io.github.cuukenn.fincalc.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.assertj.core.api.Assertions;

/**
 * §15 的 strict 1MB JSON 上限（form-field-gaps 收口轮 2026-11-14）：超过
 * {@link RequestBodySizeFilter#MAX_BODY_BYTES} 的请求体以统一错误体
 * {@code FCS-SRV-1006} 返回 HTTP 413；限内的正常请求不受影响。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class RequestBodySizeFilterTest {

    @Autowired
    private MockMvc mockMvc;


    @Test
    void an_oversized_body_answers_413_with_the_unified_error_shape() throws Exception {
        String oversized = "{\"principal\":\"" + "9".repeat((int) RequestBodySizeFilter.MAX_BODY_BYTES)
                + "\"}";

        mockMvc.perform(post("/api/v1/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FCS-SRV-1006"));
    }

    @Test
    void a_body_within_the_limit_passes_the_filter_and_reaches_the_api() throws Exception {
        String body = "{\"principal\":\"1000000\",\"nominalRate\":\"0.06\","
                + "\"commenceDate\":\"2024-01-01\",\"firstRentDate\":\"2024-02-01\","
                + "\"periods\":12,\"frequency\":\"MONTHLY\",\"timing\":\"IN_ARREARS\","
                + "\"dayCountCode\":\"THIRTY_360\",\"dateRollCode\":\"NONE\","
                + "\"currency\":\"CNY\",\"method\":\"EQUAL_INSTALLMENT\"}";

        // 限内的体穿过过滤器到达控制器：要么 200 完整测算，要么业务校验的 400——
        // 都不是 413，说明过滤器没有误伤。
        String result = mockMvc.perform(post("/api/v1/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result).doesNotContain("FCS-SRV-1006");
    }
}
