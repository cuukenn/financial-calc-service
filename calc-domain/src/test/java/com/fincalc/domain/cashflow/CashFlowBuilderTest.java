package com.fincalc.domain.cashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static java.util.stream.Collectors.toList;

import com.fincalc.common.error.BusinessException;
import com.fincalc.common.money.MoneyUtils;
import com.fincalc.common.time.SystemClockProvider;
import com.fincalc.domain.calc.CalculationContext;
import com.fincalc.domain.model.CalculationOptions;
import com.fincalc.domain.model.CashFlow;
import com.fincalc.domain.model.CashFlowBasis;
import com.fincalc.domain.model.CashFlowDirection;
import com.fincalc.domain.model.CashFlowPerspective;
import com.fincalc.domain.model.CashFlowType;
import com.fincalc.domain.model.FeeChargeMode;
import com.fincalc.domain.model.FeeItem;
import com.fincalc.domain.model.GracePeriod;
import com.fincalc.domain.model.Installment;
import com.fincalc.domain.model.LeaseElement;
import com.fincalc.domain.model.PaymentFrequency;
import com.fincalc.domain.model.PaymentTiming;
import com.fincalc.domain.model.RateChangePolicy;
import com.fincalc.domain.model.RepaymentMethodId;
import com.fincalc.domain.model.ResidualHandling;
import com.fincalc.domain.model.RoundingPolicy;
import com.fincalc.domain.model.Schedule;
import com.fincalc.domain.model.ScheduleHeader;
import com.fincalc.domain.metric.FinancialMetrics;
import com.fincalc.domain.support.algo.EqualInstallmentGenerator;
import com.fincalc.domain.support.dateroll.NoRoll;
import com.fincalc.domain.support.daycount.Thirty360;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class CashFlowBuilderTest {

    private static final LocalDate T0 = LocalDate.of(2024, 1, 1);
    private static final LocalDate LAST = LocalDate.of(2025, 1, 1);

    @Test
    void lessor_flows_start_with_principal_outflow_and_end_with_rent_inflow() {
        List<CashFlow> flows = CashFlowBuilder.build(schedule(), element(),
                CalculationOptions.defaults());

        assertThat(flows.get(0).type()).isEqualTo(CashFlowType.PRINCIPAL);
        assertThat(flows.get(0).direction()).isEqualTo(CashFlowDirection.OUTFLOW);
        assertThat(flows.get(0).date()).isEqualTo(T0);
        assertThat(flows.get(0).amount()).isEqualByComparingTo("1000000.00");
        assertThat(flows).filteredOn(f -> f.type() == CashFlowType.RENT).hasSize(12);
        assertThat(flows.get(flows.size() - 1).type()).isEqualTo(CashFlowType.RENT);
        assertThat(flows.get(flows.size() - 1).date()).isEqualTo(LAST);
        assertThat(flows).allSatisfy(f -> assertThat(f.amount()).isGreaterThan(BigDecimal.ZERO));
        assertThat(flows).filteredOn(f -> f.type() == CashFlowType.RENT)
                .allSatisfy(f -> assertThat(f.direction()).isEqualTo(CashFlowDirection.INFLOW));
    }

    @Test
    void tax_is_not_double_counted() {
        List<CashFlow> flows = CashFlowBuilder.build(scheduleWithTax(), element(),
                CalculationOptions.defaults());

        BigDecimal inflow = MoneyUtils.sum(flows.stream()
                .filter(f -> f.direction() == CashFlowDirection.INFLOW)
                .map(CashFlow::amount).collect(toList()));
        assertThat(inflow).isEqualByComparingTo("1100000.00");
        assertThat(flows).noneMatch(f -> f.type() == CashFlowType.FEE
                || f.type() == CashFlowType.RESIDUAL);
    }

    @Test
    void balloon_residual_is_not_added_twice() {
        LeaseElement balloon = element().toBuilder()
                .residualValue(new BigDecimal("100000"))
                .residualHandling(ResidualHandling.BALLOON_IN_RENT)
                .build();

        List<CashFlow> flows = CashFlowBuilder.build(generate(balloon), balloon,
                CalculationOptions.defaults());

        assertThat(flows).noneMatch(f -> f.type() == CashFlowType.RESIDUAL);
    }

    @Test
    void separate_residual_adds_a_final_inflow() {
        LeaseElement separate = element().toBuilder()
                .residualValue(new BigDecimal("100000"))
                .build();

        List<CashFlow> flows = CashFlowBuilder.build(generate(separate), separate,
                CalculationOptions.defaults());

        assertThat(flows).filteredOn(f -> f.type() == CashFlowType.RESIDUAL).hasSize(1);
        assertThat(flows.get(flows.size() - 1).type()).isEqualTo(CashFlowType.RESIDUAL);
        assertThat(flows.get(flows.size() - 1).direction()).isEqualTo(CashFlowDirection.INFLOW);
        assertThat(flows.get(flows.size() - 1).amount()).isEqualByComparingTo("100000.00");
    }

    @Test
    void deposit_only_appears_when_basis_includes_it() {
        LeaseElement withDeposit = element().toBuilder().deposit(new BigDecimal("50000")).build();

        List<CashFlow> none = CashFlowBuilder.build(schedule(), withDeposit,
                new CalculationOptions(false, RateChangePolicy.RESOLVE, CashFlowBasis.INCLUDE_FEES));
        List<CashFlow> with = CashFlowBuilder.build(schedule(), withDeposit,
                new CalculationOptions(false, RateChangePolicy.RESOLVE, CashFlowBasis.INCLUDE_DEPOSIT));

        assertThat(none).noneMatch(f -> f.type() == CashFlowType.DEPOSIT);
        assertThat(with).filteredOn(f -> f.type() == CashFlowType.DEPOSIT).hasSize(2);
        assertThat(with).filteredOn(f -> f.type() == CashFlowType.DEPOSIT)
                .allSatisfy(f -> assertThat(f.amount()).isEqualByComparingTo("50000.00"));
        assertThat(with).anySatisfy(f -> {
            assertThat(f.type()).isEqualTo(CashFlowType.DEPOSIT);
            assertThat(f.date()).isEqualTo(T0);
            assertThat(f.direction()).isEqualTo(CashFlowDirection.INFLOW);
        });
        assertThat(with).anySatisfy(f -> {
            assertThat(f.type()).isEqualTo(CashFlowType.DEPOSIT);
            assertThat(f.date()).isEqualTo(LAST);
            assertThat(f.direction()).isEqualTo(CashFlowDirection.OUTFLOW);
        });
    }

    @Test
    void upfront_fees_inflow_at_t0_and_non_irr_fees_are_excluded() {
        LeaseElement withFees = element().toBuilder()
                .fees(Arrays.asList(
                        fee("HANDLING", "20000", FeeChargeMode.UPFRONT, true),
                        fee("FILING", "1000", FeeChargeMode.UPFRONT, false),
                        fee("MANAGEMENT", "2000", FeeChargeMode.AMORTIZED, true)))
                .build();

        List<CashFlow> flows = CashFlowBuilder.build(schedule(), withFees,
                CalculationOptions.defaults());

        assertThat(flows).filteredOn(f -> f.type() == CashFlowType.FEE).hasSize(1);
        assertThat(flows).anySatisfy(f -> {
            assertThat(f.type()).isEqualTo(CashFlowType.FEE);
            assertThat(f.date()).isEqualTo(T0);
            assertThat(f.direction()).isEqualTo(CashFlowDirection.INFLOW);
            assertThat(f.amount()).isEqualByComparingTo("20000.00");
        });
    }

    @Test
    void same_day_flows_follow_the_fixed_type_order() {
        LeaseElement busy = element().toBuilder()
                .deposit(new BigDecimal("50000"))
                .fees(Collections.singletonList(
                        fee("HANDLING", "20000", FeeChargeMode.UPFRONT, true)))
                .build();
        CalculationOptions options = new CalculationOptions(false, RateChangePolicy.RESOLVE,
                CashFlowBasis.INCLUDE_DEPOSIT);

        List<CashFlow> flows = CashFlowBuilder.build(schedule(), busy, options);

        assertThat(flows.subList(0, 3)).extracting(CashFlow::type)
                .containsExactly(CashFlowType.PRINCIPAL, CashFlowType.FEE, CashFlowType.DEPOSIT);
        assertThat(flows.subList(0, 3)).extracting(CashFlow::date)
                .containsExactly(T0, T0, T0);
    }

    @Test
    void sequence_is_contiguous_and_ordering_is_stable() {
        List<CashFlow> flows = CashFlowBuilder.build(schedule(), element(),
                CalculationOptions.defaults());

        for (int i = 0; i < flows.size(); i++) {
            assertThat(flows.get(i).sequence()).isEqualTo(i);
        }
        for (int i = 1; i < flows.size(); i++) {
            assertThat(flows.get(i).date()).isAfterOrEqualTo(flows.get(i - 1).date());
        }
    }

    @Test
    void vat_split_basis_is_rejected_not_silently_degraded() {
        CalculationOptions options = new CalculationOptions(false, RateChangePolicy.RESOLVE,
                CashFlowBasis.VAT_SPLIT);

        assertThatThrownBy(() -> CashFlowBuilder.build(schedule(), element(), options))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-CALC-3005");
    }

    @Test
    void lessee_perspective_mirrors_every_direction() {
        List<CashFlow> lessor = CashFlowBuilder.build(schedule(), element(),
                CalculationOptions.defaults());
        List<CashFlow> lessee = CashFlowBuilder.build(schedule(), element(),
                CalculationOptions.defaults(), CashFlowPerspective.LESSEE);

        assertThat(lessee).hasSize(lessor.size());
        for (int i = 0; i < lessor.size(); i++) {
            assertThat(lessee.get(i).date()).isEqualTo(lessor.get(i).date());
            assertThat(lessee.get(i).type()).isEqualTo(lessor.get(i).type());
            assertThat(lessee.get(i).amount()).isEqualByComparingTo(lessor.get(i).amount());
            assertThat(lessee.get(i).direction())
                    .isEqualTo(lessor.get(i).direction() == CashFlowDirection.INFLOW
                            ? CashFlowDirection.OUTFLOW : CashFlowDirection.INFLOW);
        }
    }

    private static Schedule schedule() {
        return generate(element());
    }

    private static Schedule generate(LeaseElement element) {
        return new EqualInstallmentGenerator().generate(new CalculationContext(element, null,
                new Thirty360(), new NoRoll(), SystemClockProvider.INSTANCE,
                CalculationOptions.defaults()));
    }

    /** Two rents of 550 000.00, each carrying a 10 000.00 tax already inside the rent. */
    private static Schedule scheduleWithTax() {
        List<Installment> rows = Arrays.asList(
                taxRow(1, "2024-01-01", "2024-02-01", "1000000.00", "500000.00", "500000.00"),
                taxRow(2, "2024-02-01", "2024-03-01", "500000.00", "500000.00", "0.00"));
        ScheduleHeader header = new ScheduleHeader(FinancialMetrics.empty(), 2, T0,
                LocalDate.of(2024, 3, 1), "CLOSED_FORM", BigDecimal.ZERO);
        return new Schedule(header, rows, Collections.emptyList(), Collections.emptyList());
    }

    private static Installment taxRow(int periodNo, String start, String end, String opening,
            String principal, String closing) {
        return new Installment(periodNo, LocalDate.parse(start), LocalDate.parse(end), 30,
                new BigDecimal(opening), new BigDecimal(principal),
                new BigDecimal("50000.00"), new BigDecimal("50000.00"), new BigDecimal("0.00"),
                new BigDecimal("10000.00"), new BigDecimal("550000.00"), new BigDecimal(closing),
                new BigDecimal(principal), new BigDecimal("50000.00"), new BigDecimal("0.06"),
                null);
    }

    private static FeeItem fee(String type, String amount, FeeChargeMode mode, boolean includeInIrr) {
        return FeeItem.builder()
                .type(type)
                .amount(new BigDecimal(amount))
                .chargeMode(mode)
                .vatRate(new BigDecimal("0.06"))
                .includeInIrr(includeInIrr)
                .build();
    }

    private static LeaseElement element() {
        return LeaseElement.builder()
                .principal(new BigDecimal("1000000"))
                .nominalRate(new BigDecimal("0.06"))
                .commenceDate(T0)
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
                .vatRate(new BigDecimal("0.06"))
                .method(RepaymentMethodId.EQUAL_INSTALLMENT)
                .gracePeriod(GracePeriod.none())
                .currency("CNY")
                .rateChangePolicy(RateChangePolicy.RESOLVE)
                .build();
    }
}
