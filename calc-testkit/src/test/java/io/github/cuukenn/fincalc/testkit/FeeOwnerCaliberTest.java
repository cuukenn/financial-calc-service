package io.github.cuukenn.fincalc.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.CashFlowPerspective;
import io.github.cuukenn.fincalc.domain.model.FeeChargeMode;
import io.github.cuukenn.fincalc.domain.model.FeeItem;
import io.github.cuukenn.fincalc.domain.model.FeeOwner;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.engine.CalculationFacade;
import io.github.cuukenn.fincalc.engine.CalculationRequest;
import io.github.cuukenn.fincalc.engine.CalculationResult;
import java.math.BigDecimal;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * AGENTS.md §3.1 第 12 条「费用承担方」（form-field-gaps ticket 04 第一步，验收标准 1）：
 * 同一笔费用换承担方，出租方 IRR 与承租人视角指标按方向变化，且金额守恒。
 *
 * <p>经济含义：出租人承担的费用是出租人的真实现金流出，压低出租方收益；同时承租人
 * 少付 2 万、租金不变，承租人的成本率同向下降——「换承担方」移动的是**谁的钱包**，
 * 两侧 IRR 同向移动、幅度由镜像不变性（R103）钉死：金额不动、只动方向。
 */
class FeeOwnerCaliberTest {

    private static final CalculationFacade FACADE = CalculationFacade.defaults();

    private static LeaseElement elementWithFee(FeeOwner owner) {
        return Fixtures
                .leaseElement(new BigDecimal("1000000"), new BigDecimal("0.06"), 12,
                        RepaymentMethodId.EQUAL_INSTALLMENT.name(), "THIRTY_360")
                .toBuilder()
                .fees(Collections.singletonList(FeeItem.builder()
                        .type("HANDLING")
                        .amount(new BigDecimal("20000"))
                        .chargeMode(FeeChargeMode.UPFRONT)
                        .owner(owner)
                        .includeInIrr(true)
                        .build()))
                .build();
    }

    @Test
    void flipping_the_fee_owner_moves_the_irrs_in_opposite_directions_and_conserves_money() {
        CalculationResult lesseeBorne = FACADE.calculate(
                new CalculationRequest(elementWithFee(FeeOwner.LESSEE), null,
                        CalculationOptions.defaults()));
        CalculationResult lessorBorne = FACADE.calculate(
                new CalculationRequest(elementWithFee(FeeOwner.LESSOR), null,
                        CalculationOptions.defaults()));

        BigDecimal lesseeView = lesseeBorne.schedule().header().metrics()
                .find(MetricCodes.IRR_PERIOD);
        BigDecimal lessorView = lessorBorne.schedule().header().metrics()
                .find(MetricCodes.IRR_PERIOD);
        // 出租人承担 → 出租人流出 → 出租方 IRR 更低。
        assertThat(lessorView).isLessThan(lesseeView);

        // 承租人视角（R103 镜像）恰好互换：出租人承担的费用在承租人账上是少付的 2 万，
        // 所以 LESSEE run 的 IRR_PERIOD 与 LESSOR run 的镜像方向相反。
        CalculationResult lesseeBorneLesseeView = FACADE.calculate(new CalculationRequest(
                elementWithFee(FeeOwner.LESSEE), null, CalculationOptions.defaults(),
                CashFlowPerspective.LESSEE));
        CalculationResult lessorBorneLesseeView = FACADE.calculate(new CalculationRequest(
                elementWithFee(FeeOwner.LESSOR), null, CalculationOptions.defaults(),
                CashFlowPerspective.LESSEE));
        BigDecimal lesseeBorneBorrowerIrr = lesseeBorneLesseeView.schedule().header().metrics()
                .find(MetricCodes.BORROWER_IRR_PERIOD);
        BigDecimal lessorBorneBorrowerIrr = lessorBorneLesseeView.schedule().header().metrics()
                .find(MetricCodes.BORROWER_IRR_PERIOD);
        assertThat(lesseeBorneBorrowerIrr).isNotNull();
        assertThat(lessorBorneBorrowerIrr).isNotNull();
        // 承租人视角（R103 镜像，IRR 族逐位 = 出租方视图）：出租人承担 = 承租人实际到手
        // 多 2 万、租金不变 → 承租人的成本率同样**下降**——两侧同向、方向由经济含义决定
        // （IRR 的符号翻转不变性：镜像流的根与原流相同，所谓「互换」不成立）。
        assertThat(lessorBorneBorrowerIrr).isLessThan(lesseeBorneBorrowerIrr);
        // 金额守恒：摊销表与租金列完全一致——承担方只动事件表方向，不动租金。
        assertThat(lessorBorne.schedule().installments())
                .hasSameSizeAs(lesseeBorne.schedule().installments());
        for (int i = 0; i < lesseeBorne.schedule().installments().size(); i++) {
            assertThat(lessorBorne.schedule().installments().get(i).rentDue())
                    .isEqualByComparingTo(lesseeBorne.schedule().installments().get(i).rentDue());
            assertThat(lessorBorne.schedule().installments().get(i).interestPaid())
                    .isEqualByComparingTo(lesseeBorne.schedule().installments().get(i).interestPaid());
        }
    }

    @Test
    void a_fee_without_owner_keeps_the_v1_inflow_numbers() {
        FeeItem legacy = FeeItem.builder()
                .type("HANDLING")
                .amount(new BigDecimal("20000"))
                .chargeMode(FeeChargeMode.UPFRONT)
                .includeInIrr(true)
                .build();
        LeaseElement legacyElement = Fixtures
                .leaseElement(new BigDecimal("1000000"), new BigDecimal("0.06"), 12,
                        RepaymentMethodId.EQUAL_INSTALLMENT.name(), "THIRTY_360")
                .toBuilder()
                .fees(Collections.singletonList(legacy))
                .build();
        LeaseElement explicit = elementWithFee(FeeOwner.LESSEE);

        CalculationResult legacyRun = FACADE.calculate(
                new CalculationRequest(legacyElement, null, CalculationOptions.defaults()));
        CalculationResult explicitRun = FACADE.calculate(
                new CalculationRequest(explicit, null, CalculationOptions.defaults()));

        assertThat(legacyElement.fees().get(0).owner()).isEqualTo(FeeOwner.LESSEE);
        assertThat(legacyRun.schedule().header().metrics().find(MetricCodes.IRR_PERIOD))
                .isEqualByComparingTo(explicitRun.schedule().header().metrics()
                        .find(MetricCodes.IRR_PERIOD));
    }
}
