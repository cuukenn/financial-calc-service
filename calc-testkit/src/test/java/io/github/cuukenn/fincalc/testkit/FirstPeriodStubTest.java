package io.github.cuukenn.fincalc.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.model.RoundingPolicy;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.model.TailAdjustPolicy;
import io.github.cuukenn.fincalc.engine.CalculationFacade;
import io.github.cuukenn.fincalc.engine.CalculationRequest;
import io.github.cuukenn.fincalc.engine.CalculationResult;
import org.junit.jupiter.api.Test;

/**
 * AGENTS.md §3.1 第 9 条「首末期规则」（form-field-gaps ticket 05）：
 *
 * <ul>
 *   <li>首期 stub（129号① 整月+零头）：默认关（129号② 一段计）；THIRTY_360 的对齐月频窗口
 *       两种算法逐位相等，ACT_360 上分叉；首期行 memo 标注口径。</li>
 *   <li>尾差吸收位置：v1 只有末期吸收（前向舍入漂移只能在末行闭合），非 LAST_PERIOD 的
 *       请求显式拒绝而非静默忽略——这正是「尾差去向不静默」的验收形态。</li>
 * </ul>
 */
class FirstPeriodStubTest {

    private static final CalculationFacade FACADE = CalculationFacade.defaults();

    private static LeaseElement element(String dayCount, boolean stub) {
        return Fixtures
                .leaseElement(new BigDecimal("1000000"), new BigDecimal("0.06"), 12,
                        RepaymentMethodId.EQUAL_INSTALLMENT.name(), dayCount)
                .toBuilder()
                .firstPeriodStub(stub)
                .build();
    }

    @Test
    void stub_on_and_off_agree_bit_for_bit_on_aligned_thirty_360_windows() {
        CalculationResult off = FACADE.calculate(
                new CalculationRequest(element("THIRTY_360", false), null,
                        io.github.cuukenn.fincalc.domain.model.CalculationOptions.defaults()));
        CalculationResult on = FACADE.calculate(
                new CalculationRequest(element("THIRTY_360", true), null,
                        io.github.cuukenn.fincalc.domain.model.CalculationOptions.defaults()));

        List<Installment> offRows = off.schedule().installments();
        List<Installment> onRows = on.schedule().installments();
        assertThat(onRows).hasSameSizeAs(offRows);
        // 对齐月频窗口：1/12 == 30/360，①② 在这个窗口上逐位相等。
        for (int i = 0; i < offRows.size(); i++) {
            assertThat(onRows.get(i).rentDue())
                    .isEqualByComparingTo(offRows.get(i).rentDue());
            assertThat(onRows.get(i).interestPaid())
                    .isEqualByComparingTo(offRows.get(i).interestPaid());
        }
        assertThat(on.schedule().header().metrics().find(MetricCodes.IRR_PERIOD))
                .isEqualByComparingTo(off.schedule().header().metrics()
                        .find(MetricCodes.IRR_PERIOD));
        assertThat(on.schedule().hasErrors()).isFalse();
    }

    @Test
    void stub_on_prices_the_first_window_by_full_months_plus_days_on_act_360() {
        // 2024-01-01 → 2024-02-01：ACT_360 下实际 31 天，② = 31/360；① = 1 个整月 = 1/12。
        // 首期利息因此更小，租金（由同一条率表解出）随之变化。
        LeaseElement off = element("ACT_360", false).toBuilder()
                .commenceDate(java.time.LocalDate.of(2024, 1, 1))
                .firstRentDate(java.time.LocalDate.of(2024, 2, 1))
                .build();
        LeaseElement on = element("ACT_360", true).toBuilder()
                .commenceDate(java.time.LocalDate.of(2024, 1, 1))
                .firstRentDate(java.time.LocalDate.of(2024, 2, 1))
                .build();

        CalculationResult offRun = FACADE.calculate(
                new CalculationRequest(off, null,
                        io.github.cuukenn.fincalc.domain.model.CalculationOptions.defaults()));
        CalculationResult onRun = FACADE.calculate(
                new CalculationRequest(on, null,
                        io.github.cuukenn.fincalc.domain.model.CalculationOptions.defaults()));

        Installment firstOn = onRun.schedule().installments().get(0);
        Installment firstOff = offRun.schedule().installments().get(0);
        assertThat(firstOn.interestPaid())
                .as("① 的首月 = 月利率（1/12），小于 ② 的 31/360")
                .isLessThan(firstOff.interestPaid());
        assertThat(firstOn.memo()).contains("129号①");
        // 无 stub 的单子不落口径标注（memo 为 null 也是「未标注」）。
        assertThat(firstOff.memo() == null || !firstOff.memo().contains("129号①")).isTrue();
        // 两种算法各自闭合：四项校验全绿（末期余额、本金守恒、行恒等、合计恒等）。
        assertThat(onRun.schedule().hasErrors()).isFalse();
        assertThat(offRun.schedule().hasErrors()).isFalse();
        // 差异只许出现在首期——后续各期是它们自己的整期，两种算法等价……
        // 但首期还本变了会让后续各期的期初余额平移，租金也重解，所以只钉首期利息与全表绿。
        assertThat(onRun.schedule().installments().get(1).startDate())
                .isEqualTo(offRun.schedule().installments().get(1).startDate());
    }

    @Test
    void a_non_last_tail_policy_is_refused_loudly_not_silently_ignored() {
        LeaseElement firstPeriodAbsorption = element("THIRTY_360", false).toBuilder()
                .rounding(RoundingPolicy.builder()
                        .tailAdjustPolicy(TailAdjustPolicy.FIRST_PERIOD)
                        .build())
                .build();

        assertThatThrownBy(() -> FACADE.calculate(
                new CalculationRequest(firstPeriodAbsorption, null,
                        io.github.cuukenn.fincalc.domain.model.CalculationOptions.defaults())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1020");
    }
}
