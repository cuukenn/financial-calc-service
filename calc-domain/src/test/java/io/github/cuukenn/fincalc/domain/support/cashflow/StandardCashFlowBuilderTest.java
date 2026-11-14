package io.github.cuukenn.fincalc.domain.support.cashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static io.github.cuukenn.fincalc.domain.model.CashFlowDirection.INFLOW;
import static io.github.cuukenn.fincalc.domain.model.CashFlowDirection.OUTFLOW;
import static java.util.stream.Collectors.toList;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.common.time.SystemClockProvider;
import io.github.cuukenn.fincalc.domain.calc.CalculationContext;
import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.CashFlow;
import io.github.cuukenn.fincalc.domain.model.CashFlowBasis;
import io.github.cuukenn.fincalc.domain.model.CashFlowDirection;
import io.github.cuukenn.fincalc.domain.model.CashFlowPerspective;
import io.github.cuukenn.fincalc.domain.model.CashFlowType;
import io.github.cuukenn.fincalc.domain.model.DepositSettlement;
import io.github.cuukenn.fincalc.domain.model.DepositTiming;
import io.github.cuukenn.fincalc.domain.model.FeeChargeMode;
import io.github.cuukenn.fincalc.domain.model.FeeOwner;
import io.github.cuukenn.fincalc.domain.model.FeeItem;
import io.github.cuukenn.fincalc.domain.model.GracePeriod;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.PaymentFrequency;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.model.RateChangePolicy;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
import io.github.cuukenn.fincalc.domain.model.RoundingPolicy;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.model.ScheduleHeader;
import io.github.cuukenn.fincalc.domain.metric.FinancialMetrics;
import io.github.cuukenn.fincalc.domain.support.algo.EqualInstallmentGenerator;
import io.github.cuukenn.fincalc.domain.support.dateroll.NoRoll;
import io.github.cuukenn.fincalc.domain.support.daycount.Thirty360;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class StandardCashFlowBuilderTest {

    private static final LocalDate T0 = LocalDate.of(2024, 1, 1);
    private static final LocalDate LAST = LocalDate.of(2025, 1, 1);

    private final StandardCashFlowBuilder builder = new StandardCashFlowBuilder();

    @Test
    void exposes_extension_identity() {
        assertThat(builder.code()).isEqualTo("STANDARD_EVENT_TABLE");
        assertThat(builder.order()).isEqualTo(10);
    }

    @Test
    void lessor_flows_start_with_principal_outflow_and_end_with_rent_inflow() {
        List<CashFlow> flows = builder.build(schedule(), element(), CalculationOptions.defaults());

        assertThat(flows.get(0).type()).isEqualTo(CashFlowType.PRINCIPAL);
        assertThat(flows.get(0).direction()).isEqualTo(OUTFLOW);
        assertThat(flows.get(0).date()).isEqualTo(T0);
        assertThat(flows.get(0).amount()).isEqualByComparingTo("1000000.00");
        assertThat(flows).filteredOn(f -> f.type() == CashFlowType.RENT).hasSize(12);
        assertThat(flows.get(flows.size() - 1).type()).isEqualTo(CashFlowType.RENT);
        assertThat(flows.get(flows.size() - 1).date()).isEqualTo(LAST);
        assertThat(flows).allSatisfy(f -> assertThat(f.amount()).isGreaterThan(BigDecimal.ZERO));
        assertThat(flows).filteredOn(f -> f.type() == CashFlowType.RENT)
                .allSatisfy(f -> assertThat(f.direction()).isEqualTo(INFLOW));
    }

    @Test
    void tax_is_not_double_counted() {
        List<CashFlow> flows = builder.build(scheduleWithTax(), element(),
                CalculationOptions.defaults());

        BigDecimal inflow = MoneyUtils.sum(flows.stream()
                .filter(f -> f.direction() == CashFlowDirection.INFLOW)
                .map(CashFlow::amount).collect(toList()));
        assertThat(inflow).isEqualByComparingTo("1120000.00");
        assertThat(flows).noneMatch(f -> f.type() == CashFlowType.FEE
                || f.type() == CashFlowType.RESIDUAL);
    }

    @Test
    void balloon_residual_settles_at_maturity_as_the_outstanding_closing_balance() {
        LeaseElement balloon = element().toBuilder()
                .residualValue(new BigDecimal("100000"))
                .residualHandling(ResidualHandling.BALLOON_IN_RENT)
                .build();
        Schedule balloonSchedule = generate(balloon);

        List<CashFlow> flows = builder.build(balloonSchedule, balloon, CalculationOptions.defaults());

        // §9.6 leaves the balloon as the schedule's closing balance; the flow list must show it,
        // otherwise 100 000.00 of the lessor's money vanishes from every yield metric.
        assertThat(flows).filteredOn(f -> f.type() == CashFlowType.RESIDUAL).hasSize(1);
        CashFlow settlement = flows.get(flows.size() - 1);
        assertThat(settlement.type()).isEqualTo(CashFlowType.RESIDUAL);
        assertThat(settlement.date()).isEqualTo(LAST);
        assertThat(settlement.direction()).isEqualTo(INFLOW);
        assertThat(settlement.amount())
                .isEqualByComparingTo(balloonSchedule.installments().get(11).closingBalance());
        assertThat(settlement.amount()).isEqualByComparingTo("100000.00");
    }

    @Test
    void separate_residual_adds_a_final_inflow() {
        LeaseElement separate = element().toBuilder()
                .residualValue(new BigDecimal("100000"))
                .build();

        List<CashFlow> flows = builder.build(generate(separate), separate,
                CalculationOptions.defaults());

        assertThat(flows).filteredOn(f -> f.type() == CashFlowType.RESIDUAL).hasSize(1);
        assertThat(flows.get(flows.size() - 1).type()).isEqualTo(CashFlowType.RESIDUAL);
        assertThat(flows.get(flows.size() - 1).direction()).isEqualTo(INFLOW);
        assertThat(flows.get(flows.size() - 1).amount()).isEqualByComparingTo("100000.00");
    }

    @Test
    void guaranteed_residual_is_a_terminal_receipt_on_top_of_the_option_price() {
        LeaseElement withGuarantee = element().toBuilder()
                .residualValue(new BigDecimal("100000"))
                .guaranteedResidual(new BigDecimal("30000"))
                .build();

        List<CashFlow> flows = builder.build(generate(withGuarantee), withGuarantee,
                CalculationOptions.defaults());

        List<CashFlow> terminal = flows.stream()
                .filter(f -> f.type() == CashFlowType.RESIDUAL).collect(toList());
        assertThat(terminal).hasSize(2);
        assertThat(terminal.get(0).amount().add(terminal.get(1).amount()))
                .isEqualByComparingTo("130000.00");
    }

    @Test
    void guaranteed_residual_stacks_on_the_balloon_without_relisting_the_option_price() {
        LeaseElement balloon = element().toBuilder()
                .residualValue(new BigDecimal("100000"))
                .residualHandling(ResidualHandling.BALLOON_IN_RENT)
                .guaranteedResidual(new BigDecimal("30000"))
                .build();

        List<CashFlow> flows = builder.build(generate(balloon), balloon,
                CalculationOptions.defaults());

        // 100 000 of ballooned option price + 30 000 of guarantee, each exactly once.
        BigDecimal terminal = flows.stream()
                .filter(f -> f.type() == CashFlowType.RESIDUAL)
                .map(CashFlow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(terminal).isEqualByComparingTo("130000.00");
    }

    @Test
    void unguaranteed_residual_never_becomes_cash() {
        LeaseElement withUnguaranteed = element().toBuilder()
                .assetValue(new BigDecimal("1000000"))
                .unguaranteedResidual(new BigDecimal("70000"))
                .build();

        List<CashFlow> flows = builder.build(generate(withUnguaranteed), withUnguaranteed,
                CalculationOptions.defaults());

        assertThat(flows).noneMatch(f -> f.type() == CashFlowType.RESIDUAL);
    }


    @Test
    void deposit_only_appears_when_basis_includes_it() {
        LeaseElement withDeposit = element().toBuilder().deposit(new BigDecimal("50000")).build();

        List<CashFlow> none = builder.build(schedule(), withDeposit,
                new CalculationOptions(false, CashFlowBasis.INCLUDE_FEES));
        List<CashFlow> with = builder.build(schedule(), withDeposit,
                new CalculationOptions(false,
                        CashFlowBasis.INCLUDE_DEPOSIT));

        assertThat(none).noneMatch(f -> f.type() == CashFlowType.DEPOSIT);
        assertThat(with).filteredOn(f -> f.type() == CashFlowType.DEPOSIT).hasSize(2);
        assertThat(with).filteredOn(f -> f.type() == CashFlowType.DEPOSIT)
                .allSatisfy(f -> assertThat(f.amount()).isEqualByComparingTo("50000.00"));
        assertThat(with).anySatisfy(f -> {
            assertThat(f.type()).isEqualTo(CashFlowType.DEPOSIT);
            assertThat(f.date()).isEqualTo(T0);
            assertThat(f.direction()).isEqualTo(INFLOW);
        });
        assertThat(with).anySatisfy(f -> {
            assertThat(f.type()).isEqualTo(CashFlowType.DEPOSIT);
            assertThat(f.date()).isEqualTo(LAST);
            assertThat(f.direction()).isEqualTo(OUTFLOW);
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

        List<CashFlow> flows = builder.build(schedule(), withFees, CalculationOptions.defaults());

        assertThat(flows).filteredOn(f -> f.type() == CashFlowType.FEE).hasSize(1);
        assertThat(flows).anySatisfy(f -> {
            assertThat(f.type()).isEqualTo(CashFlowType.FEE);
            assertThat(f.date()).isEqualTo(T0);
            assertThat(f.direction()).isEqualTo(INFLOW);
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
        CalculationOptions options = new CalculationOptions(false,
                CashFlowBasis.INCLUDE_DEPOSIT);

        List<CashFlow> flows = builder.build(schedule(), busy, options);

        assertThat(flows.subList(0, 3)).extracting(CashFlow::type)
                .containsExactly(CashFlowType.PRINCIPAL, CashFlowType.FEE, CashFlowType.DEPOSIT);
        assertThat(flows.subList(0, 3)).extracting(CashFlow::date)
                .containsExactly(T0, T0, T0);
    }

    @Test
    void maturity_day_orders_deposit_before_rent_before_residual() {
        LeaseElement elementWithBoth = element().toBuilder()
                .deposit(new BigDecimal("50000"))
                .residualValue(new BigDecimal("100000"))
                .build();
        CalculationOptions options = new CalculationOptions(false,
                CashFlowBasis.INCLUDE_DEPOSIT);

        List<CashFlow> flows = builder.build(generate(elementWithBoth), elementWithBoth, options);

        List<CashFlow> maturityDay = flows.stream()
                .filter(f -> f.date().isEqual(LAST)).collect(toList());
        assertThat(maturityDay).extracting(CashFlow::type)
                .containsExactly(CashFlowType.DEPOSIT, CashFlowType.RENT, CashFlowType.RESIDUAL);
    }

    @Test
    void reorders_and_renumbers_regardless_of_insertion_order() {
        // Hand-built out-of-order rows: maturity is the LAST row's end (2024-02-01).
        List<Installment> rows = Arrays.asList(
                taxRow(1, "2024-01-01", "2024-03-01", "1000000.00", "500000.00", "500000.00"),
                taxRow(2, "2024-02-01", "2024-02-01", "500000.00", "500000.00", "0.00"));
        Schedule messy = scheduleOf(2, T0, rows);

        LeaseElement separate = element().toBuilder()
                .residualValue(new BigDecimal("100000"))
                .build();

        List<CashFlow> flows = builder.build(messy, separate, CalculationOptions.defaults());

        assertThat(flows).extracting(CashFlow::type).containsExactly(
                CashFlowType.PRINCIPAL, CashFlowType.RENT, CashFlowType.RESIDUAL, CashFlowType.RENT);
        assertThat(flows.get(0).sequence()).isZero();
        for (int i = 1; i < flows.size(); i++) {
            boolean sameDay = flows.get(i).date().equals(flows.get(i - 1).date());
            assertThat(flows.get(i).sequence())
                    .isEqualTo(flows.get(i - 1).sequence() + (sameDay ? 0 : 1));
        }
        for (int i = 1; i < flows.size(); i++) {
            assertThat(flows.get(i).date()).isAfterOrEqualTo(flows.get(i - 1).date());
        }
    }

    @Test
    void sequence_is_contiguous_and_ordering_is_stable() {
        List<CashFlow> flows = builder.build(schedule(), element(), CalculationOptions.defaults());

        assertThat(flows.get(0).sequence()).isZero();
        for (int i = 1; i < flows.size(); i++) {
            boolean sameDay = flows.get(i).date().equals(flows.get(i - 1).date());
            assertThat(flows.get(i).sequence())
                    .isEqualTo(flows.get(i - 1).sequence() + (sameDay ? 0 : 1));
        }
        for (int i = 1; i < flows.size(); i++) {
            assertThat(flows.get(i).date()).isAfterOrEqualTo(flows.get(i - 1).date());
        }
    }

    @Test
    void negative_rent_is_rejected_not_silently_flipped() {
        List<Installment> rows = Collections.singletonList(
                taxRow(1, "2024-01-01", "2024-02-01", "1000000.00", "500000.00", "500000.00",
                        new BigDecimal("-100.00")));

        assertThatThrownBy(() -> builder.build(scheduleOf(1, T0, rows), element(),
                CalculationOptions.defaults()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("negative cash flow amount");
    }

    @Test
    void empty_schedule_emits_only_the_t0_outflow_and_no_unbalanced_deposit() {
        LeaseElement withDeposit = element().toBuilder().deposit(new BigDecimal("50000")).build();
        CalculationOptions options = new CalculationOptions(false,
                CashFlowBasis.INCLUDE_DEPOSIT);
        Schedule empty = scheduleOf(0, T0, Collections.emptyList());

        List<CashFlow> flows = builder.build(empty, withDeposit, options);

        assertThat(flows).hasSize(1);
        assertThat(flows.get(0).type()).isEqualTo(CashFlowType.PRINCIPAL);
        assertThat(flows).noneMatch(f -> f.type() == CashFlowType.DEPOSIT
                || f.type() == CashFlowType.RESIDUAL);
    }

    @Test
    void vat_split_basis_is_rejected_not_silently_degraded() {
        CalculationOptions options = new CalculationOptions(false,
                CashFlowBasis.VAT_SPLIT);

        assertThatThrownBy(() -> builder.build(schedule(), element(), options))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-CALC-3005");
    }

    @Test
    void lessee_perspective_mirrors_every_direction() {
        List<CashFlow> lessor = builder.build(schedule(), element(), CalculationOptions.defaults());
        List<CashFlow> lessee = builder.build(schedule(), element(), CalculationOptions.defaults(),
                CashFlowPerspective.LESSEE);

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

    @Test
    void deposit_with_first_rent_collects_on_the_first_rent_date() {
        LeaseElement element = element().toBuilder()
                .deposit(new BigDecimal("50000"))
                .depositTiming(DepositTiming.WITH_FIRST_RENT)
                .build();

        List<CashFlow> flows = builder.build(schedule(), element,
                new CalculationOptions(false, CashFlowBasis.INCLUDE_DEPOSIT));

        List<CashFlow> depositIn = flows.stream()
                .filter(f -> f.type() == CashFlowType.DEPOSIT && f.direction() == INFLOW)
                .collect(toList());
        assertThat(depositIn).hasSize(1);
        // The same date the first rent row lands on, so same-day compression keeps the two on one
        // discount index.
        assertThat(depositIn.get(0).date()).isEqualTo(LocalDate.of(2024, 2, 1));
    }

    @Test
    void deposit_interest_is_its_own_maturity_outflow_at_act360_days() {
        LeaseElement element = element().toBuilder()
                .deposit(new BigDecimal("50000"))
                .depositInterestRate(new BigDecimal("0.03"))
                .build();

        List<CashFlow> flows = builder.build(schedule(), element,
                new CalculationOptions(false, CashFlowBasis.INCLUDE_DEPOSIT));

        List<CashFlow> interest = flows.stream()
                .filter(f -> f.type() == CashFlowType.DEPOSIT_INTEREST).collect(toList());
        assertThat(interest).hasSize(1);
        // 2024-01-01 → 2025-01-01 = 366 actual days, leap year: 50000 × 3% × 366/360 = 1525.00
        // (P-03 日利率 ACT/360、算头不算尾、单利；P-05/P-06 算至分）.
        assertThat(interest.get(0).amount()).isEqualByComparingTo("1525.00");
        assertThat(interest.get(0).date()).isEqualTo(LAST);
        assertThat(interest.get(0).direction()).isEqualTo(OUTFLOW);
        // The deposit principal leg stays its own row — the interest never folds into it.
        assertThat(flows).filteredOn(f -> f.type() == CashFlowType.DEPOSIT)
                .allSatisfy(f -> assertThat(f.amount()).isEqualByComparingTo("50000.00"));
    }

    @Test
    void offset_principal_amortizes_the_net_principal_and_keeps_both_t0_legs() {
        LeaseElement element = element().toBuilder()
                .deposit(new BigDecimal("100000"))
                .depositSettlement(DepositSettlement.OFFSET_PRINCIPAL)
                .build();
        Schedule schedule = generate(element);

        assertThat(schedule.installments().get(0).openingBalance())
                .as("the schedule amortizes 合同本金 − 保证金")
                .isEqualByComparingTo("900000.00");

        List<CashFlow> flows = builder.build(schedule, element,
                new CalculationOptions(false, CashFlowBasis.INCLUDE_DEPOSIT));

        assertThat(flows.get(0).type()).isEqualTo(CashFlowType.PRINCIPAL);
        assertThat(flows.get(0).amount()).as("the gross principal leg stays explicit")
                .isEqualByComparingTo("1000000.00");
        assertThat(flows).anySatisfy(f -> {
            assertThat(f.type()).isEqualTo(CashFlowType.DEPOSIT);
            assertThat(f.date()).isEqualTo(T0);
            assertThat(f.direction()).isEqualTo(INFLOW);
            assertThat(f.amount()).isEqualByComparingTo("100000.00");
        });
        // Consumed at collection: no settlement leg at maturity.
        assertThat(flows).noneMatch(f -> f.type() == CashFlowType.DEPOSIT
                && f.direction() == OUTFLOW);
    }

    @Test
    void offset_final_rent_sets_like_a_refund_and_never_nets_into_rent() {
        LeaseElement refund = element().toBuilder().deposit(new BigDecimal("50000")).build();
        LeaseElement offset = element().toBuilder()
                .deposit(new BigDecimal("50000"))
                .depositSettlement(DepositSettlement.OFFSET_FINAL_RENT)
                .build();

        List<CashFlow> refundFlows = builder.build(schedule(), refund,
                new CalculationOptions(false, CashFlowBasis.INCLUDE_DEPOSIT));
        List<CashFlow> offsetFlows = builder.build(generate(offset), offset,
                new CalculationOptions(false, CashFlowBasis.INCLUDE_DEPOSIT));

        // Same cash pair, same dates, same directions: offsetting the final rent is a refund
        // dated at maturity, so the schedule's rent column stays the amortization rent.
        assertThat(offsetFlows).hasSameSizeAs(refundFlows);
        for (int i = 0; i < offsetFlows.size(); i++) {
            assertThat(offsetFlows.get(i).date()).isEqualTo(refundFlows.get(i).date());
            assertThat(offsetFlows.get(i).type()).isEqualTo(refundFlows.get(i).type());
            assertThat(offsetFlows.get(i).direction()).isEqualTo(refundFlows.get(i).direction());
            assertThat(offsetFlows.get(i).amount())
                    .isEqualByComparingTo(refundFlows.get(i).amount());
        }
    }

    @Test
    void lessee_borne_upfront_fee_stays_an_inflow_the_v1_posture() {
        LeaseElement element = element().toBuilder()
                .fees(Collections.singletonList(
                        fee("HANDLING", "20000", FeeChargeMode.UPFRONT, true)))
                .build();

        List<CashFlow> flows = builder.build(schedule(), element, CalculationOptions.defaults());

        assertThat(flows).filteredOn(f -> f.type() == CashFlowType.FEE).hasSize(1);
        assertThat(flows).anySatisfy(f -> {
            assertThat(f.type()).isEqualTo(CashFlowType.FEE);
            assertThat(f.direction()).isEqualTo(INFLOW);
            assertThat(f.amount()).isEqualByComparingTo("20000.00");
        });
    }

    @Test
    void lessor_borne_upfront_fee_flips_to_an_outflow_and_mixed_fees_split_by_owner() {
        LeaseElement mixed = element().toBuilder()
                .fees(Arrays.asList(
                        fee("HANDLING", "20000", FeeChargeMode.UPFRONT, true),
                        lessorOwnedFee("INSURANCE", "3000", FeeChargeMode.UPFRONT, true),
                        fee("EXCLUDED", "5000", FeeChargeMode.UPFRONT, false)))
                .build();

        List<CashFlow> flows = builder.build(schedule(), mixed, CalculationOptions.defaults());

        List<CashFlow> feeRows = flows.stream()
                .filter(f -> f.type() == CashFlowType.FEE).collect(toList());
        // 两行（金额为 0 的行丢弃）：先收（承租人承担）后付（出租人承担），不入 IRR 的费用不出现。
        assertThat(feeRows).extracting(CashFlow::direction)
                .containsExactly(INFLOW, OUTFLOW);
        assertThat(feeRows).extracting(CashFlow::amount)
                .usingElementComparator(java.util.Comparator
                        .comparing(java.math.BigDecimal::intValueExact))
                .containsExactly(new BigDecimal("20000.00"), new BigDecimal("3000.00"));
        assertThat(feeRows).allSatisfy(f -> assertThat(f.date()).isEqualTo(T0));
    }

    @Test
    void lessee_perspective_mirrors_both_fee_directions() {
        LeaseElement mixed = element().toBuilder()
                .fees(Arrays.asList(
                        fee("HANDLING", "20000", FeeChargeMode.UPFRONT, true),
                        lessorOwnedFee("INSURANCE", "3000", FeeChargeMode.UPFRONT, true)))
                .build();

        List<CashFlow> lessor = builder.build(schedule(), mixed, CalculationOptions.defaults());
        List<CashFlow> lessee = builder.build(schedule(), mixed, CalculationOptions.defaults(),
                CashFlowPerspective.LESSEE);

        List<CashFlow> lessorFees = lessor.stream()
                .filter(f -> f.type() == CashFlowType.FEE).collect(toList());
        List<CashFlow> lesseeFees = lessee.stream()
                .filter(f -> f.type() == CashFlowType.FEE).collect(toList());
        // 金额守恒（R103 镜像）：方向全翻转、金额逐位一致。
        assertThat(lesseeFees).hasSameSizeAs(lessorFees);
        for (int i = 0; i < lessorFees.size(); i++) {
            assertThat(lesseeFees.get(i).amount())
                    .isEqualByComparingTo(lessorFees.get(i).amount());
            assertThat(lesseeFees.get(i).direction())
                    .isNotEqualTo(lessorFees.get(i).direction());
        }
    }

    /** 出租人承担的费用行（ticket 04 第一步）：同一形状，仅承担方不同。 */
    private static FeeItem lessorOwnedFee(String type, String amount, FeeChargeMode mode,
            boolean includeInIrr) {
        return FeeItem.builder()
                .type(type)
                .amount(new BigDecimal(amount))
                .chargeMode(mode)
                .owner(FeeOwner.LESSOR)
                .includeInIrr(includeInIrr)
                .build();
    }

    @Test
    void deduct_from_principal_fee_nets_the_financed_principal_and_keeps_both_legs() {
        FeeItem deducted = FeeItem.builder()
                .type("FILING")
                .amount(new BigDecimal("30000"))
                .chargeMode(FeeChargeMode.DEDUCT_FROM_PRINCIPAL)
                .includeInIrr(true)
                .build();
        LeaseElement element = element().toBuilder()
                .fees(Collections.singletonList(deducted))
                .build();
        Schedule schedule = generate(element);

        // 摊销本金吃净额：首期期初 = 1,000,000 − 30,000。
        assertThat(schedule.installments().get(0).openingBalance())
                .isEqualByComparingTo("970000.00");

        List<CashFlow> flows = builder.build(schedule, element, CalculationOptions.defaults());
        // 两腿显式：本金全额流出 + 费用流入，净额与摊销本金一致。
        assertThat(flows.get(0).type()).isEqualTo(CashFlowType.PRINCIPAL);
        assertThat(flows.get(0).amount()).isEqualByComparingTo("1000000.00");
        assertThat(flows).anySatisfy(f -> {
            assertThat(f.type()).isEqualTo(CashFlowType.FEE);
            assertThat(f.date()).isEqualTo(T0);
            assertThat(f.direction()).isEqualTo(INFLOW);
            assertThat(f.amount()).isEqualByComparingTo("30000.00");
        });
        assertThat(schedule.hasErrors()).isFalse();
    }

    @Test
    void a_deduct_fee_without_irr_participation_still_nets_but_has_no_leg() {
        FeeItem deducted = FeeItem.builder()
                .type("FILING")
                .amount(new BigDecimal("30000"))
                .chargeMode(FeeChargeMode.DEDUCT_FROM_PRINCIPAL)
                .includeInIrr(false)
                .build();
        LeaseElement element = element().toBuilder()
                .fees(Collections.singletonList(deducted))
                .build();
        Schedule schedule = generate(element);

        // 净额是合同事实（includeInIrr 只管现金流腿，不管摊销基数）。
        assertThat(schedule.installments().get(0).openingBalance())
                .isEqualByComparingTo("970000.00");
        List<CashFlow> flows = builder.build(schedule, element, CalculationOptions.defaults());
        assertThat(flows).noneMatch(f -> f.type() == CashFlowType.FEE);
        assertThat(schedule.hasErrors()).isFalse();
    }

    private static Schedule schedule() {
        return generate(element());
    }

    private static Schedule generate(LeaseElement element) {
        return new EqualInstallmentGenerator().generate(new CalculationContext(element, null,
                new Thirty360(), new NoRoll(), SystemClockProvider.INSTANCE,
                CalculationOptions.defaults()));
    }

    /** Two rents of 560 000.00 each: 500 000 principal + 50 000 interest + 10 000 tax inside the rent. */
    private static Schedule scheduleWithTax() {
        List<Installment> rows = Arrays.asList(
                taxRow(1, "2024-01-01", "2024-02-01", "1000000.00", "500000.00", "500000.00"),
                taxRow(2, "2024-02-01", "2024-03-01", "500000.00", "500000.00", "0.00"));
        return scheduleOf(2, LocalDate.of(2024, 3, 1), rows);
    }

    private static Schedule scheduleOf(int periodCount, LocalDate end, List<Installment> rows) {
        ScheduleHeader header = new ScheduleHeader(FinancialMetrics.empty(), periodCount, T0, end,
                "CLOSED_FORM", BigDecimal.ZERO);
        return new Schedule(header, rows, Collections.emptyList(), Collections.emptyList());
    }

    private static Installment taxRow(int periodNo, String start, String end, String opening,
            String principal, String closing) {
        return taxRow(periodNo, start, end, opening, principal, closing, new BigDecimal("560000.00"));
    }

    private static Installment taxRow(int periodNo, String start, String end, String opening,
            String principal, String closing, BigDecimal rentDue) {
        return new Installment(periodNo, LocalDate.parse(start), LocalDate.parse(end), 30,
                new BigDecimal(opening), new BigDecimal(principal),
                new BigDecimal("50000.00"), new BigDecimal("50000.00"), new BigDecimal("0.00"),
                new BigDecimal("10000.00"), rentDue, new BigDecimal(closing),
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
