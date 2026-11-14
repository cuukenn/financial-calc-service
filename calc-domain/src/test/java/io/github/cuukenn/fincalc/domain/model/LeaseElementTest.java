package io.github.cuukenn.fincalc.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeaseElementTest {

    @Test
    void builder_rejects_non_positive_principal() {
        assertThatThrownBy(() -> baseBuilder().principal(BigDecimal.ZERO).build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM");
    }

    @Test
    void builder_rejects_rate_above_one() {
        assertThatThrownBy(() -> baseBuilder().nominalRate(new BigDecimal("1.5")).build())
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void irregular_requires_explicit_periods() {
        assertThatThrownBy(() -> baseBuilder()
                .frequency(PaymentFrequency.IRREGULAR)
                .explicitPeriods(Collections.emptyList())
                .build())
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void regular_derives_periods_per_year() {
        assertThat(PaymentFrequency.QUARTERLY.periodsPerYear()).isEqualTo(4);
        assertThat(PaymentFrequency.MONTHLY.monthsPerPeriod()).isEqualTo(1);
    }

    @Test
    void explicit_rent_requires_rents() {
        assertThatThrownBy(() -> baseBuilder().method(RepaymentMethodId.EXPLICIT_RENT).build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1008");
    }

    @Test
    void explicit_rent_rejects_wrong_rent_count() {
        assertThatThrownBy(() -> baseBuilder()
                .method(RepaymentMethodId.EXPLICIT_RENT)
                .explicitRents(twelveRents().subList(0, 11))
                .build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1008");
    }

    @Test
    void explicit_rent_irregular_rejects_wrong_rent_count() {
        assertThatThrownBy(() -> baseBuilder()
                .method(RepaymentMethodId.EXPLICIT_RENT)
                .frequency(PaymentFrequency.IRREGULAR)
                .explicitPeriods(java.util.Arrays.asList(
                        PeriodDate.builder().startDate(LocalDate.of(2024, 1, 1))
                                .endDate(LocalDate.of(2024, 2, 1)).build(),
                        PeriodDate.builder().startDate(LocalDate.of(2024, 2, 1))
                                .endDate(LocalDate.of(2024, 3, 1)).build()))
                .explicitRents(twelveRents())
                .build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1008");
    }

    @Test
    void explicit_rent_rejects_non_positive_rent() {
        java.util.List<BigDecimal> rents = twelveRents();
        rents.set(3, BigDecimal.ZERO);
        assertThatThrownBy(() -> baseBuilder()
                .method(RepaymentMethodId.EXPLICIT_RENT)
                .explicitRents(rents)
                .build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1009");
    }

    @Test
    void non_explicit_method_rejects_explicit_rents() {
        assertThatThrownBy(() -> baseBuilder().explicitRents(twelveRents()).build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1008");
    }

    @Test
    void builder_rejects_periods_above_hard_limit() {
        assertThatThrownBy(() -> baseBuilder().periods(1201).build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1010")
                .hasMessageContaining("1201");
    }

    @Test
    void builder_accepts_periods_at_hard_limit() {
        LeaseElement element = baseBuilder().periods(1200).build();
        assertThat(element.periods()).isEqualTo(1200);
    }

    @Test
    void builder_rejects_irregular_explicit_periods_above_hard_limit() {
        List<PeriodDate> periods = new ArrayList<>();
        for (int i = 0; i < 1201; i++) {
            periods.add(PeriodDate.builder()
                    .startDate(LocalDate.of(2024, 1, 1).plusDays(i * 30L))
                    .endDate(LocalDate.of(2024, 1, 1).plusDays(i * 30L + 1))
                    .build());
        }
        assertThatThrownBy(() -> baseBuilder()
                .frequency(PaymentFrequency.IRREGULAR)
                .explicitPeriods(periods)
                .build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1010")
                .hasMessageContaining("1201");
    }

    @Test
    void builder_rejects_negative_fee_amount() {
        assertThatThrownBy(() -> baseBuilder()
                .fees(Collections.singletonList(FeeItem.builder()
                        .type("HANDLING")
                        .amount(new BigDecimal("-1"))
                        .chargeMode(FeeChargeMode.UPFRONT)
                        .includeInIrr(true)
                        .build()))
                .build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1011")
                .hasMessageContaining("-1");
    }

    @Test
    void builder_rejects_null_fee_amount() {
        assertThatThrownBy(() -> baseBuilder()
                .fees(Collections.singletonList(FeeItem.builder()
                        .type("HANDLING")
                        .amount(null)
                        .chargeMode(FeeChargeMode.UPFRONT)
                        .includeInIrr(true)
                        .build()))
                .build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1011");
    }

    @Test
    void builder_rejects_negative_deposit() {
        assertThatThrownBy(() -> baseBuilder().deposit(new BigDecimal("-1")).build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1012")
                .hasMessageContaining("-1");
    }

    @Test
    void builder_accepts_zero_fee_amount() {
        LeaseElement element = baseBuilder()
                .fees(Collections.singletonList(FeeItem.builder()
                        .type("HANDLING")
                        .amount(BigDecimal.ZERO)
                        .chargeMode(FeeChargeMode.UPFRONT)
                        .includeInIrr(false)
                        .build()))
                .build();
        assertThat(element.fees()).hasSize(1);
    }

    @Test
    void builder_accepts_null_deposit() {
        LeaseElement element = baseBuilder().deposit(null).build();
        assertThat(element.deposit()).isNull();
    }

    @Test
    void explicit_rent_happy_path_builds_unmodifiable_rents() {
        LeaseElement element = baseBuilder()
                .method(RepaymentMethodId.EXPLICIT_RENT)
                .explicitRents(twelveRents())
                .build();
        assertThat(element.explicitRents()).hasSize(12);
        assertThat(element.explicitRents())
                .isUnmodifiable()
                .containsExactlyElementsOf(twelveRents());
        assertThat(element.toBuilder().build().explicitRents()).hasSize(12);
    }

    private static java.util.List<BigDecimal> twelveRents() {
        java.util.List<BigDecimal> rents = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            rents.add(new BigDecimal("90000"));
        }
        return rents;
    }

    /** CAS 21 chain: principal = asset value + initial direct costs - down payment. */
    @Test
    void standard_measurement_chain_must_balance() {
        LeaseElement ok = baseBuilder()
                .principal(new BigDecimal("2010000"))
                .assetValue(new BigDecimal("2400000"))
                .downPayment(new BigDecimal("400000"))
                .initialDirectCosts(new BigDecimal("10000"))
                .build();
        assertThat(ok.principal()).isEqualByComparingTo("2010000");

        assertThatThrownBy(() -> baseBuilder()
                .principal(new BigDecimal("1900000"))
                .assetValue(new BigDecimal("2400000"))
                .downPayment(new BigDecimal("400000"))
                .initialDirectCosts(new BigDecimal("10000"))
                .build())
                .isInstanceOf(BusinessException.class);
    }

    /**
     * M-01: 首付款 / IDC / 未担保余值 cannot move any number without the asset value (the chain
     * has no denominator), so a lone amount is rejected instead of being silently ignored.
     */
    @Test
    void chain_amounts_require_the_asset_value() {
        assertThatThrownBy(() -> baseBuilder()
                .downPayment(new BigDecimal("400000"))
                .build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1017");
        assertThatThrownBy(() -> baseBuilder()
                .initialDirectCosts(new BigDecimal("10000"))
                .build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1017");
        assertThatThrownBy(() -> baseBuilder()
                .unguaranteedResidual(new BigDecimal("70000"))
                .build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1017");

        // The guaranteed residual is a standalone receipt, so it needs no chain.
        assertThat(baseBuilder().guaranteedResidual(new BigDecimal("30000")).build()
                .guaranteedResidual()).isEqualByComparingTo("30000");
    }

    @Test
    void builder_defaults_deposit_calibers_to_the_v1_posture() {
        LeaseElement element = baseBuilder().deposit(new BigDecimal("50000")).build();
        assertThat(element.depositTiming()).isEqualTo(DepositTiming.UPFRONT);
        assertThat(element.depositSettlement()).isEqualTo(DepositSettlement.REFUND);
        assertThat(element.depositInterestRate()).isNull();
        assertThat(element.measuredPrincipal()).isEqualByComparingTo(element.principal());
    }

    @Test
    void builder_rejects_deposit_interest_rate_without_a_positive_deposit() {
        assertThatThrownBy(() -> baseBuilder().depositInterestRate(new BigDecimal("0.03")).build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1018");
        assertThatThrownBy(() -> baseBuilder()
                .deposit(BigDecimal.ZERO)
                .depositInterestRate(new BigDecimal("0.03"))
                .build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1018");
    }

    @Test
    void builder_rejects_deposit_interest_rate_under_offset_principal() {
        assertThatThrownBy(() -> baseBuilder()
                .deposit(new BigDecimal("50000"))
                .depositSettlement(DepositSettlement.OFFSET_PRINCIPAL)
                .depositInterestRate(new BigDecimal("0.03"))
                .build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1019");
    }

    @Test
    void builder_rejects_out_of_range_deposit_interest_rate() {
        assertThatThrownBy(() -> baseBuilder()
                .deposit(new BigDecimal("50000"))
                .depositInterestRate(new BigDecimal("1.5"))
                .build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1002");
    }

    @Test
    void measured_principal_nets_only_under_offset_principal() {
        LeaseElement refund = baseBuilder().deposit(new BigDecimal("100000")).build();
        assertThat(refund.measuredPrincipal()).isEqualByComparingTo(refund.principal());
        LeaseElement offset = baseBuilder()
                .deposit(new BigDecimal("100000"))
                .depositSettlement(DepositSettlement.OFFSET_PRINCIPAL)
                .build();
        assertThat(offset.measuredPrincipal()).isEqualByComparingTo("900000");
        assertThat(offset.toBuilder().build().measuredPrincipal()).isEqualByComparingTo("900000");
    }

    @Test
    void builder_defaults_first_period_stub_to_off() {
        assertThat(baseBuilder().build().firstPeriodStub()).isFalse();
    }

    @Test
    void builder_rejects_first_period_stub_for_irregular_and_rule_driven() {
        // IRREGULAR：显式期间没有整月锚点（显式期间本身合法，拒绝来自 stub 校验）。
        assertThatThrownBy(() -> baseBuilder()
                .frequency(PaymentFrequency.IRREGULAR)
                .explicitPeriods(Collections.singletonList(PeriodDate.builder()
                        .startDate(LocalDate.of(2024, 1, 1))
                        .endDate(LocalDate.of(2024, 2, 1))
                        .paymentDate(LocalDate.of(2024, 2, 1))
                        .build()))
                .firstPeriodStub(true)
                .build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1021");
        assertThatThrownBy(() -> baseBuilder()
                .method(RepaymentMethodId.RULE_DRIVEN)
                .firstPeriodStub(true)
                .build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1021");
    }

    @Test
    void measured_principal_nets_deduct_from_principal_fees() {
        FeeItem deducted = FeeItem.builder()
                .type("FILING")
                .amount(new BigDecimal("30000"))
                .chargeMode(FeeChargeMode.DEDUCT_FROM_PRINCIPAL)
                .build();
        LeaseElement element = baseBuilder()
                .fees(Collections.singletonList(deducted))
                .build();
        // 抵减本金的费用按合同扣减融资额——与 IRR 参与无关（includeInIrr 只管现金流腿）。
        assertThat(element.measuredPrincipal()).isEqualByComparingTo("970000");
        // 与保证金抵减叠加。
        LeaseElement stacked = baseBuilder()
                .fees(Collections.singletonList(deducted))
                .deposit(new BigDecimal("50000"))
                .depositSettlement(DepositSettlement.OFFSET_PRINCIPAL)
                .build();
        assertThat(stacked.measuredPrincipal()).isEqualByComparingTo("920000");
    }

    private LeaseElement.Builder baseBuilder() {
        return LeaseElement.builder()
                .principal(new BigDecimal("1000000"))
                .nominalRate(new BigDecimal("0.06"))
                .commenceDate(LocalDate.of(2024, 1, 1))
                .firstRentDate(LocalDate.of(2024, 2, 1))
                .periods(12)
                .frequency(PaymentFrequency.MONTHLY)
                .timing(PaymentTiming.IN_ARREARS)
                .dayCountCode("THIRTY_360")
                .dateRollCode("NONE")
                .rounding(RoundingPolicy.defaults())
                .residualValue(BigDecimal.ZERO)
                .residualHandling(ResidualHandling.SEPARATE)
                .deposit(BigDecimal.ZERO)
                .fees(Collections.emptyList())
                .method(RepaymentMethodId.EQUAL_INSTALLMENT)
                .gracePeriod(GracePeriod.none())
                .rateChangePolicy(RateChangePolicy.RESOLVE);
    }
}
