package io.github.cuukenn.fincalc.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.PaymentFrequency;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.engine.CalculationFacade;
import io.github.cuukenn.fincalc.engine.CalculationRequest;
import io.github.cuukenn.fincalc.engine.CalculationResult;
import org.junit.jupiter.api.Test;

/**
 * 输入维度端到端覆盖（AGENTS.md 纪律：引擎输入输出需被单元测试完全覆盖）。
 *
 * <p>四个此前只有约定/生成器级单测、无门面级触达的输入维度，各经
 * {@code CalculationFacade} 全链路跑一遍并断言真实输出：
 *
 * <ul>
 *   <li>付租频率 QUARTERLY / SEMI_ANNUAL / ANNUAL —— 等额本金跑通（本金计划与费率解耦）；
 *       等额本息被「各期利率一致」守卫拒绝（PMT 闭式解只认月频），拒绝本身也是契约；</li>
 *   <li>付租时点 IN_ADVANCE —— 全部内置算法显式拒绝（FCS-DOM-2004），拒绝而非静默错价；</li>
 *   <li>计息基准 ACT_365F / ACT_ACT / ACT_365_25 / THIRTY_E_360 —— 天数列按基准取值
 *       （实际天数 vs 30），全程守恒；</li>
 *   <li>日期调整 FOLLOWING / MODIFIED_FOLLOWING / PRECEDING / MODIFIED_PRECEDING ——
 *       周末名义期日的顺延 / 前移落在工作日，且方向正确。</li>
 * </ul>
 */
class InputDimensionCoverageTest {

    private static final CalculationFacade FACADE = CalculationFacade.defaults();
    private static final BigDecimal PRINCIPAL = new BigDecimal("1000000");
    private static final BigDecimal RATE = new BigDecimal("0.06");

    // ---- 付租频率：QUARTERLY / SEMI_ANNUAL / ANNUAL ----

    @Test
    void quarterly_equal_principal_runs_clean_and_annualizes_by_four() {
        CalculationResult r = run(element(PaymentFrequency.QUARTERLY, 8, "EQUAL_PRINCIPAL"));

        assertThat(r.schedule().installments()).hasSize(8);
        assertThat(r.schedule().hasErrors()).isFalse();
        assertThat(r.schedule().header().metrics().find(MetricCodes.TOTAL_PRINCIPAL))
                .isEqualByComparingTo(PRINCIPAL);
        assertThat(r.schedule().installments().get(7).closingBalance())
                .isEqualByComparingTo("0.00");
        assertThat(r.schedule().header().metrics().find(MetricCodes.IRR_ANNUAL)).isNotNull();
        // 年化关系：(1 + IRR_PERIOD)^4 − 1 = IRR_ANNUAL（periodsPerYear = 4）。
        BigDecimal periodIrr = r.schedule().header().metrics().find(MetricCodes.IRR_PERIOD);
        BigDecimal annualIrr = r.schedule().header().metrics().find(MetricCodes.IRR_ANNUAL);
        BigDecimal compounded = periodIrr.add(BigDecimal.ONE).pow(4).subtract(BigDecimal.ONE);
        assertThat(annualIrr).isCloseTo(compounded, within(new BigDecimal("0.0000001")));
    }

    @Test
    void semi_annual_equal_principal_runs_clean_and_annualizes_by_two() {
        CalculationResult r = run(element(PaymentFrequency.SEMI_ANNUAL, 6, "EQUAL_PRINCIPAL"));

        assertThat(r.schedule().installments()).hasSize(6);
        assertThat(r.schedule().hasErrors()).isFalse();
        assertThat(r.schedule().header().metrics().find(MetricCodes.TOTAL_PRINCIPAL))
                .isEqualByComparingTo(PRINCIPAL);
        assertThat(r.schedule().installments().get(5).closingBalance())
                .isEqualByComparingTo("0.00");
        BigDecimal periodIrr = r.schedule().header().metrics().find(MetricCodes.IRR_PERIOD);
        BigDecimal annualIrr = r.schedule().header().metrics().find(MetricCodes.IRR_ANNUAL);
        BigDecimal compounded = periodIrr.add(BigDecimal.ONE).pow(2).subtract(BigDecimal.ONE);
        assertThat(annualIrr).isCloseTo(compounded, within(new BigDecimal("0.0000001")));
    }

    @Test
    void annual_equal_principal_annualizes_by_one() {
        CalculationResult r = run(element(PaymentFrequency.ANNUAL, 3, "EQUAL_PRINCIPAL"));

        assertThat(r.schedule().installments()).hasSize(3);
        assertThat(r.schedule().hasErrors()).isFalse();
        // periodsPerYear = 1：年化系数为 1，两族一致。
        assertThat(r.schedule().header().metrics().find(MetricCodes.IRR_ANNUAL))
                .isCloseTo(r.schedule().header().metrics().find(MetricCodes.IRR_PERIOD),
                        within(new BigDecimal("0.0000001")));
        assertThat(r.schedule().installments().get(2).closingBalance())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void equal_installment_with_non_monthly_frequency_is_refused_by_the_rate_guard() {
        // 等额本息的 PMT 闭式解只认月频：非月频窗口的期利率与解算利率不一致，
        // 「各期利率一致」守卫显式拒绝（FCS-DOM-2002）——拒绝是契约，不是缺陷。
        for (PaymentFrequency frequency : new PaymentFrequency[] {
                PaymentFrequency.QUARTERLY, PaymentFrequency.SEMI_ANNUAL, PaymentFrequency.ANNUAL}) {
            assertThatThrownBy(() -> run(element(frequency, 8, "EQUAL_INSTALLMENT")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("FCS-DOM-2002")
                    .hasMessageContaining("各期利率一致");
        }
    }

    // ---- 付租时点：IN_ADVANCE 全算法显式拒绝 ----

    @Test
    void in_advance_is_rejected_by_every_builtin_method_instead_of_silent_mispricing() {
        for (String method : new String[] {"EQUAL_INSTALLMENT", "EQUAL_PRINCIPAL",
                "BULLET_LOAN", "INTEREST_FIRST", "PRINCIPAL_FIRST", "EXPLICIT_RENT"}) {
            final LeaseElement element;
            if ("EXPLICIT_RENT".equals(method)) {
                // EXPLICIT_RENT 有自己的必填前置（显式租金清单）：先以等额本息构建、
                // 再在 builder 上改 method 并补租金，一次 build 通过构造器校验。
                element = Fixtures
                        .leaseElement(PRINCIPAL, RATE, 12, "EQUAL_INSTALLMENT", "THIRTY_360")
                        .toBuilder()
                        .method(RepaymentMethodId.EXPLICIT_RENT)
                        .explicitRents(Fixtures.explicitAnnuityRents())
                        .timing(PaymentTiming.IN_ADVANCE)
                        .build();
            } else {
                element = Fixtures
                        .leaseElement(PRINCIPAL, RATE, 12, method, "THIRTY_360")
                        .toBuilder()
                        .timing(PaymentTiming.IN_ADVANCE)
                        .build();
            }
            System.out.println("DBG method=" + method
                    + " rents=" + element.explicitRents().size()
                    + " timing=" + element.timing());
            assertThatThrownBy(() -> run(element))
                    .as("method %s must refuse IN_ADVANCE", method)
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("FCS-DOM-2004")
                    .hasMessageContaining("IN_ADVANCE");
        }
    }

    // ---- 计息基准：ACT_365F / ACT_ACT / ACT_365_25 / THIRTY_E_360 ----

    @Test
    void act_family_uses_actual_days_thirty_e_360_uses_thirty() {
        // 2024-01-01 → 2024-02-01：算头不算尾 = 31 个实际天数；30E/360 = 30。
        assertThat(daysOfFirstPeriod("ACT_365F")).isEqualTo(31);
        assertThat(daysOfFirstPeriod("ACT_ACT")).isEqualTo(31);
        assertThat(daysOfFirstPeriod("ACT_365_25")).isEqualTo(31);
        assertThat(daysOfFirstPeriod("THIRTY_E_360")).isEqualTo(30);
        // 对照组：30/360 同窗口也是 30。
        assertThat(daysOfFirstPeriod("THIRTY_360")).isEqualTo(30);
    }

    @Test
    void each_day_count_basis_produces_a_conservation_clean_run() {
        for (String basis : new String[] {"ACT_365F", "ACT_ACT", "ACT_365_25", "THIRTY_E_360"}) {
            CalculationResult r = run(element(PaymentFrequency.MONTHLY, 12,
                    "EQUAL_PRINCIPAL", basis));
            assertThat(r.schedule().hasErrors())
                    .as("basis %s must produce an error-free schedule", basis)
                    .isFalse();
            assertThat(r.schedule().header().metrics().find(MetricCodes.TOTAL_PRINCIPAL))
                    .as("basis %s must conserve principal", basis)
                    .isEqualByComparingTo(PRINCIPAL);
            assertThat(r.schedule().installments().get(11).closingBalance())
                    .as("basis %s must close at zero", basis)
                    .isEqualByComparingTo("0.00");
        }
    }

    // ---- 日期调整：周末名义期日的顺延 / 前移 ----

    @Test
    void following_rolls_weekend_period_end_forward_to_monday() {
        // 名义末期 2024-03-31 是周日：FOLLOWING 顺延到周一 2024-04-01（不跨月）。
        assertThat(firstPeriodEndOf("FOLLOWING")).isEqualTo(LocalDate.of(2024, 4, 1));
    }

    @Test
    void modified_following_falls_back_when_forward_crosses_the_month() {
        // 顺延到 04-01 跨入四月：MODIFIED_FOLLOWING 回落到周五 2024-03-29。
        assertThat(firstPeriodEndOf("MODIFIED_FOLLOWING")).isEqualTo(LocalDate.of(2024, 3, 29));
    }

    @Test
    void preceding_rolls_weekend_period_end_backward_to_friday() {
        // 名义末期 2024-03-31 是周日：PRECEDING 前移到周五 2024-03-29。
        assertThat(firstPeriodEndOf("PRECEDING")).isEqualTo(LocalDate.of(2024, 3, 29));
        assertThat(firstPeriodEndOf("MODIFIED_PRECEDING")).isEqualTo(LocalDate.of(2024, 3, 29));
    }

    // ---- 夹具 ----

    private static CalculationResult run(LeaseElement element) {
        return FACADE.calculate(
                new CalculationRequest(element, null, CalculationOptions.defaults()));
    }

    private static LeaseElement element(PaymentFrequency frequency, int periods, String method) {
        return Fixtures
                .leaseElement(PRINCIPAL, RATE, periods, method, "THIRTY_360")
                .toBuilder()
                .frequency(frequency)
                .timing(PaymentTiming.IN_ARREARS)
                .build();
    }

    private static LeaseElement element(PaymentFrequency frequency, int periods, String method,
            String dayCount) {
        return Fixtures
                .leaseElement(PRINCIPAL, RATE, periods, method, dayCount)
                .toBuilder()
                .frequency(frequency)
                .timing(PaymentTiming.IN_ARREARS)
                .build();
    }

    private static int daysOfFirstPeriod(String basis) {
        CalculationResult r = run(element(PaymentFrequency.MONTHLY, 12,
                "EQUAL_PRINCIPAL", basis));
        return r.schedule().installments().get(0).days();
    }

    private static LocalDate firstPeriodEndOf(String dateRollCode) {
        // 起租 2024-03-03，首期租金日 2024-03-31（周日）→ 首期名义末期落在周末。
        LeaseElement element = Fixtures
                .leaseElement(PRINCIPAL, RATE, 3, "EQUAL_PRINCIPAL", "ACT_360")
                .toBuilder()
                .commenceDate(LocalDate.of(2024, 3, 3))
                .firstRentDate(LocalDate.of(2024, 3, 31))
                .dateRollCode(dateRollCode)
                .build();
        return run(element).schedule().installments().get(0).endDate();
    }
}
