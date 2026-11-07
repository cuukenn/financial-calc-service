package io.github.cuukenn.fincalc.domain.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cuukenn.fincalc.domain.metric.FinancialMetrics;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FinancialMetricsTest {

    @Test
    void financial_metrics_returns_null_for_absent_code() {
        FinancialMetrics m = FinancialMetrics.of(Map.of("NPV", new BigDecimal("12.34")));
        assertThat(m.find("NPV")).isEqualByComparingTo("12.34");
        assertThat(m.find("XIRR")).isNull();
        assertThat(m.codes()).containsExactly("NPV");
    }

    @Test
    void empty_has_no_codes() {
        assertThat(FinancialMetrics.empty().codes()).isEmpty();
        assertThat(FinancialMetrics.empty().asMap()).isEmpty();
    }

    @Test
    void null_value_means_not_computable_and_is_preserved() {
        Map<String, BigDecimal> src = new LinkedHashMap<>();
        src.put("IRR_PERIOD", new BigDecimal("0.005"));
        src.put("MIRR", null);

        FinancialMetrics m = FinancialMetrics.of(src);

        assertThat(m.find("MIRR")).isNull();
        assertThat(m.codes()).containsExactly("IRR_PERIOD", "MIRR");
        assertThat(m.asMap()).containsKey("MIRR");
    }

    @Test
    void snapshot_is_decoupled_from_source_map() {
        Map<String, BigDecimal> src = new LinkedHashMap<>();
        src.put("NPV", new BigDecimal("1.00"));

        FinancialMetrics m = FinancialMetrics.of(src);
        src.put("NPV", new BigDecimal("9.99"));

        assertThat(m.find("NPV")).isEqualByComparingTo("1.00");
    }

    @Test
    void as_map_is_unmodifiable() {
        FinancialMetrics m = FinancialMetrics.of(Map.of("NPV", new BigDecimal("1.00")));
        assertThatThrownBy(() -> m.asMap().put("XIRR", BigDecimal.ONE))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
