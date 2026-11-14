package io.github.cuukenn.fincalc.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.CashFlowBasis;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.ProductProfile;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.engine.CalculationFacade;
import io.github.cuukenn.fincalc.engine.CalculationRequest;
import io.github.cuukenn.fincalc.engine.CalculationResult;
import org.junit.jupiter.api.Test;

/**
 * form-field-gaps issue 15 / A2：{@code ProductProfile.MORTGAGE} 装配 §2.3 阈值读数
 * （{@code MortgageThresholdValidator}，WARN，不进 hasErrors 的守恒语义）。
 *
 * <p>三条铁律各有一条钉桩：MORTGAGE + 首付 14% → WARN 3010；恰为 15%（含本数）→ 无；
 * LEASE / 缺省 → issues 与 v1 逐字节一致（缺省链不带阈值校验器）。
 */
class ProductProfileThresholdTest {

    private static final CalculationFacade FACADE = CalculationFacade.defaults();

    private static final BigDecimal ASSET_VALUE = new BigDecimal("1200000");
    private static final BigDecimal DOWN_14 = new BigDecimal("168000");
    private static final BigDecimal DOWN_15 = new BigDecimal("180000");

    /** CAS 21 链自洽的房贷要素：principal = assetValue − downPayment（产品身份走选项层）。 */
    private static LeaseElement mortgageElement(BigDecimal downPayment, int periods) {
        BigDecimal principal = ASSET_VALUE.subtract(downPayment);
        return Fixtures
                .leaseElement(principal, new BigDecimal("0.035"), periods,
                        RepaymentMethodId.EQUAL_INSTALLMENT.name(), "THIRTY_360")
                .toBuilder()
                .assetValue(ASSET_VALUE)
                .downPayment(downPayment)
                .build();
    }

    private static CalculationOptions options(ProductProfile product) {
        return new CalculationOptions(false, CashFlowBasis.INCLUDE_FEES, product);
    }

    private static List<String> issueCodes(CalculationResult run) {
        return run.schedule().issues().stream()
                .map(i -> i.code())
                .collect(Collectors.toList());
    }

    @Test
    void mortgage_with_14_percent_down_discloses_the_floor_as_a_warn_not_an_error() {
        CalculationResult run = FACADE.calculate(new CalculationRequest(
                mortgageElement(DOWN_14, 360), null,
                options(ProductProfile.MORTGAGE)));

        assertThat(issueCodes(run)).contains("FCS-CALC-3010");
        assertThat(run.schedule().issues())
                .filteredOn(i -> "FCS-CALC-3010".equals(i.code()))
                .allSatisfy(i -> {
                    assertThat(i.level()).as("合规读数是 WARN，不进 hasErrors 的守恒语义")
                            .isEqualTo(io.github.cuukenn.fincalc.domain.model.IssueLevel.WARN);
                    assertThat(i.message()).contains("14.00%");
                });
        assertThat(run.schedule().hasErrors()).isFalse();
        assertThat(run.schedule().installments()).hasSize(360);
    }

    @Test
    void exactly_15_percent_is_within_the_floor_containing_the_number() {
        CalculationResult run = FACADE.calculate(new CalculationRequest(
                mortgageElement(DOWN_15, 360), null,
                options(ProductProfile.MORTGAGE)));

        assertThat(issueCodes(run)).doesNotContain("FCS-CALC-3010");
    }

    @Test
    void a_term_over_480_months_discloses_the_limit() {
        CalculationResult run = FACADE.calculate(new CalculationRequest(
                mortgageElement(DOWN_15, 481), null,
                options(ProductProfile.MORTGAGE)));

        assertThat(issueCodes(run)).contains("FCS-CALC-3011");
        assertThat(run.schedule().hasErrors()).isFalse();
    }

    @Test
    void lease_and_undeclared_products_keep_the_v1_issue_list_bit_for_bit() {
        // 同一笔 14% 首付的要素：LEASE 视角没有房贷阈值概念（租赁首付不是房贷首付）。
        CalculationResult leaseRun = FACADE.calculate(new CalculationRequest(
                mortgageElement(DOWN_14, 360), null,
                options(ProductProfile.LEASE)));
        // 缺省（不声明 product）= 历史 payload 的逐字节路径。
        LeaseElement undeclared = Fixtures
                .leaseElement(ASSET_VALUE.subtract(DOWN_14), new BigDecimal("0.035"), 360,
                        RepaymentMethodId.EQUAL_INSTALLMENT.name(), "THIRTY_360")
                .toBuilder()
                .assetValue(ASSET_VALUE)
                .downPayment(DOWN_14)
                .build();
        CalculationResult defaultRun = FACADE.calculate(
                new CalculationRequest(undeclared, null, CalculationOptions.defaults()));

        assertThat(issueCodes(leaseRun)).doesNotContain("FCS-CALC-3010", "FCS-CALC-3011");
        assertThat(issueCodes(defaultRun)).doesNotContain("FCS-CALC-3010", "FCS-CALC-3011");
        assertThat(defaultRun.schedule().issues())
                .as("缺省路径与 LEASE 路径的 issues 一致（均为 v1 形态）")
                .usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(leaseRun.schedule().issues());
    }
}
