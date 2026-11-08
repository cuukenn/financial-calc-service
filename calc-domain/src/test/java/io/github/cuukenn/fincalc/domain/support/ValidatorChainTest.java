package io.github.cuukenn.fincalc.domain.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cuukenn.fincalc.common.time.SystemClockProvider;
import io.github.cuukenn.fincalc.domain.calc.CalcErrorCode;
import io.github.cuukenn.fincalc.domain.calc.CalculationContext;
import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.GracePeriod;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.IssueLevel;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.PaymentFrequency;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.model.RateChangePolicy;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
import io.github.cuukenn.fincalc.domain.model.RoundingPolicy;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.model.ScheduleHeader;
import io.github.cuukenn.fincalc.domain.model.ValidationIssue;
import io.github.cuukenn.fincalc.domain.metric.FinancialMetrics;
import io.github.cuukenn.fincalc.domain.support.algo.EqualInstallmentGenerator;
import io.github.cuukenn.fincalc.domain.support.algo.EqualPrincipalGenerator;
import io.github.cuukenn.fincalc.domain.support.algo.ExplicitRentGenerator;
import io.github.cuukenn.fincalc.domain.support.daycount.Thirty360;
import io.github.cuukenn.fincalc.domain.support.dateroll.NoRoll;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidatorChainTest {

    @Test
    void detects_principal_conservation_violation() {
        Schedule broken = scheduleWithPrincipalSum("999999.99");
        List<ValidationIssue> issues = ValidatorChain.defaults().validateAll(broken, baseElement());
        assertThat(issues).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("FCS-CALC-3001");
            assertThat(issue.level()).isEqualTo(IssueLevel.ERROR);
        });
    }

    @Test
    void detects_non_zero_tail_balance() {
        Schedule broken = scheduleWithLastClosing("0.01");
        assertThat(ValidatorChain.defaults().validateAll(broken, baseElement()))
                .anySatisfy(issue -> assertThat(issue.code()).isEqualTo("FCS-CALC-3002"));
    }

    @Test
    void passes_on_valid_schedule() {
        Schedule ok = new EqualInstallmentGenerator().generate(context(baseElement()));
        assertThat(ValidatorChain.defaults().validateAll(ok, baseElement()))
                .noneMatch(issue -> issue.level() == IssueLevel.ERROR);
    }

    @Test
    void tail_balance_validator_accepts_balloon_residual() {
        LeaseElement balloon = baseElement().toBuilder()
                .residualValue(new BigDecimal("100000"))
                .residualHandling(ResidualHandling.BALLOON_IN_RENT)
                .build();
        Schedule schedule = new EqualInstallmentGenerator().generate(context(balloon));

        assertThat(ValidatorChain.defaults().validateAll(schedule, balloon))
                .noneMatch(issue -> issue.code().equals("FCS-CALC-3002"));
    }

    @Test
    void detects_overlapping_or_reversed_period_dates() {
        LeaseElement element = baseElement();
        List<Installment> rows = new ArrayList<>();
        rows.add(installment(1, "2024-01-01", "2024-02-01", 31, "1000000.00", "83333.33", "916666.67"));
        // 第 2 期开始日早于第 1 期结束日：区间重叠。
        rows.add(installment(2, "2024-01-15", "2024-02-15", 31, "916666.67", "83333.33", "833333.34"));
        rows.add(installment(3, "2024-02-15", "2024-03-15", 29, "833333.34", "833333.34", "0.00"));

        List<ValidationIssue> issues = ValidatorChain.defaults()
                .validateAll(schedule(rows), element);

        assertThat(issues).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("FCS-CALC-3004");
            assertThat(issue.level()).isEqualTo(IssueLevel.ERROR);
            assertThat(issue.periodNo()).isEqualTo(2);
        });
    }

    @Test
    void does_not_report_an_issue_the_schedule_already_carries() {
        Schedule broken = scheduleWithPrincipalSum("999999.99");
        Schedule carrying = new Schedule(broken.header(), broken.installments(),
                Collections.emptyList(),
                Collections.singletonList(new ValidationIssue(IssueLevel.ERROR, "FCS-CALC-3001",
                        "生成器已经报出的本金守恒告警", null)));

        List<ValidationIssue> found = ValidatorChain.defaults().validateAll(carrying, baseElement());
        assertThat(found).filteredOn(issue -> issue.code().equals("FCS-CALC-3001")).isEmpty();

        Schedule merged = ValidatorChain.defaults().validate(carrying, baseElement());
        assertThat(merged.issues())
                .filteredOn(issue -> issue.code().equals("FCS-CALC-3001")).hasSize(1);
    }

    @Test
    void validate_returns_a_new_schedule_carrying_the_issues() {
        Schedule broken = scheduleWithPrincipalSum("999999.99");
        Schedule validated = ValidatorChain.defaults().validate(broken, baseElement());

        assertThat(validated).isNotSameAs(broken);
        assertThat(broken.issues()).isEmpty();
        assertThat(validated.issues()).anySatisfy(issue -> issue.code().equals("FCS-CALC-3001"));
        assertThat(validated.installments()).isEqualTo(broken.installments());
        assertThat(validated.header()).isSameAs(broken.header());
    }

    @Test
    void registers_the_default_validators_in_code_number_order() {
        // AGENTS.md §3.4: 3001 本金守恒 → 3002 末期余额 → 3008 逐期恒等式 → 3009 租金构成 →
        // 3004 日期单调殿后。
        assertThat(ValidatorChain.defaults().codes()).containsExactly(
                "PRINCIPAL_CONSERVATION", "TAIL_BALANCE", "PERIOD_IDENTITY",
                "RENT_COMPOSITION", "DATE_MONOTONIC");
    }

    @Test
    void schedule_validation_findings_carry_the_calc_error_code_constants() {
        // The emitted code and the enum constant cannot drift apart: the validator reads the
        // constant instead of holding a literal of its own.
        assertThat(ValidatorChain.defaults()
                .validateAll(scheduleWithPrincipalSum("999999.99"), baseElement()))
                .anySatisfy(issue -> assertThat(issue.code())
                        .isEqualTo(CalcErrorCode.PRINCIPAL_CONSERVATION_BROKEN.code()));
        assertThat(ValidatorChain.defaults()
                .validateAll(scheduleWithLastClosing("0.01"), baseElement()))
                .anySatisfy(issue -> assertThat(issue.code())
                        .isEqualTo(CalcErrorCode.TAIL_BALANCE_MISMATCH.code()));
    }

    @Test
    void detects_broken_period_identity() {
        // 第 2 期期末余额被篡改多 0.05 元：期初 − 本金 ≠ 期末，误差 0.05 ≥ 0.01 元容差。
        Schedule broken = scheduleWithTamperedClosing(1, new BigDecimal("0.05"));

        List<ValidationIssue> issues = ValidatorChain.defaults().validateAll(broken, baseElement());

        assertThat(issues).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("FCS-CALC-3008");
            assertThat(issue.level()).isEqualTo(IssueLevel.ERROR);
            assertThat(issue.periodNo()).isEqualTo(2);
        });
    }

    @Test
    void period_identity_within_tolerance_passes() {
        // 第 2 期期末余额多 0.004 元：小于 0.01 元容差，按吸收舍入处理，不报错。
        Schedule ok = scheduleWithTamperedClosing(1, new BigDecimal("0.004"));

        assertThat(ValidatorChain.defaults().validateAll(ok, baseElement()))
                .noneMatch(issue -> issue.code().equals("FCS-CALC-3008"));
    }

    @Test
    void detects_broken_rent_composition() {
        // 第 3 期租金被篡改多 0.05 元：Σ租金 ≠ Σ本金 + Σ利息 + Σ费用 + Σ税。
        Schedule broken = scheduleWithTamperedRent(2, new BigDecimal("0.05"));

        List<ValidationIssue> issues = ValidatorChain.defaults().validateAll(broken, baseElement());

        assertThat(issues).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("FCS-CALC-3009");
            assertThat(issue.level()).isEqualTo(IssueLevel.ERROR);
        });
    }

    @Test
    void rent_composition_within_tolerance_passes() {
        Schedule ok = scheduleWithTamperedRent(2, new BigDecimal("0.004"));

        assertThat(ValidatorChain.defaults().validateAll(ok, baseElement()))
                .noneMatch(issue -> issue.code().equals("FCS-CALC-3009"));
    }

    @Test
    void period_identity_and_rent_composition_accept_balloon_residual() {
        LeaseElement balloon = baseElement().toBuilder()
                .residualValue(new BigDecimal("100000"))
                .residualHandling(ResidualHandling.BALLOON_IN_RENT)
                .build();
        Schedule schedule = new EqualInstallmentGenerator().generate(context(balloon));

        assertThat(ValidatorChain.defaults().validateAll(schedule, balloon))
                .noneMatch(issue -> issue.code().equals("FCS-CALC-3008"))
                .noneMatch(issue -> issue.code().equals("FCS-CALC-3009"));
    }

    @Test
    void period_identity_and_rent_composition_accept_explicit_rent_schedule() {
        LeaseElement element = LeaseElement.builder()
                .principal(new BigDecimal("90000"))
                .nominalRate(new BigDecimal("0.06"))
                .commenceDate(LocalDate.of(2024, 1, 1))
                .firstRentDate(LocalDate.of(2024, 2, 1))
                .periods(3)
                .frequency(PaymentFrequency.MONTHLY)
                .timing(PaymentTiming.IN_ARREARS)
                .dayCountCode("THIRTY_360")
                .dateRollCode("NONE")
                .rounding(RoundingPolicy.defaults())
                .residualValue(BigDecimal.ZERO)
                .residualHandling(ResidualHandling.SEPARATE)
                .deposit(BigDecimal.ZERO)
                .vatRate(new BigDecimal("0.06"))
                .method(RepaymentMethodId.EXPLICIT_RENT)
                .explicitRents(List.of(
                        new BigDecimal("30200.00"), new BigDecimal("30200.00"),
                        new BigDecimal("30503.01")))
                .gracePeriod(GracePeriod.none())
                .currency("CNY")
                .rateChangePolicy(RateChangePolicy.RESOLVE)
                .build();
        Schedule schedule = new ExplicitRentGenerator().generate(context(element));

        assertThat(schedule.hasErrors()).isFalse();
        assertThat(ValidatorChain.defaults().validateAll(schedule, element))
                .noneMatch(issue -> issue.code().equals("FCS-CALC-3008"))
                .noneMatch(issue -> issue.code().equals("FCS-CALC-3009"));
    }

    @Test
    void reports_the_tail_finding_a_generator_already_flagged_only_once() {
        Schedule flagged = new ShortPayStub().generate(context(baseElement()));

        // The generator sees the tail itself, and the finding belongs to the last period so that the
        // chain can recognise it as the same finding its own tail check would raise.
        assertThat(flagged.issues()).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("FCS-CALC-3002");
            assertThat(issue.periodNo()).isEqualTo(12);
        });

        Schedule merged = ValidatorChain.defaults().validate(flagged, baseElement());

        assertThat(merged.issues()).filteredOn(issue -> issue.code().equals("FCS-CALC-3002"))
                .hasSize(1);
        assertThat(merged.issues()).filteredOn(issue -> issue.code().equals("FCS-CALC-3001"))
                .hasSize(1);
    }

    @Test
    void names_every_date_rule_a_period_breaks() {
        List<Installment> rows = new ArrayList<>();
        rows.add(installment(1, "2024-01-01", "2024-02-01", 31, "1000000.00", "83333.33", "916666.67"));
        // 第 2 期同时违反两条规则：区间为空，且与上一期结束日不连续。
        rows.add(installment(2, "2024-02-15", "2024-02-15", 0, "916666.67", "83333.33", "833333.34"));
        rows.add(installment(3, "2024-02-15", "2024-03-15", 28, "833333.34", "833333.34", "0.00"));

        List<ValidationIssue> issues = ValidatorChain.defaults()
                .validateAll(schedule(rows), baseElement());

        assertThat(issues).filteredOn(issue -> issue.code().equals("FCS-CALC-3004")).hasSize(1);
        assertThat(issues).anySatisfy(issue -> {
            assertThat(issue.message()).contains("不早于结束日");
            assertThat(issue.message()).contains("不连续");
        });
    }

    @Test
    void accepts_a_schedule_built_at_a_coarser_rounding_scale() {
        LeaseElement wholeYuan = baseElement().toBuilder()
                .principal(new BigDecimal("1000000.40"))
                .rounding(RoundingPolicy.builder().scale(0).build())
                .build();
        Schedule schedule = new EqualPrincipalGenerator().generate(context(wholeYuan));

        assertThat(schedule.hasErrors()).isFalse();
        assertThat(ValidatorChain.defaults().validateAll(schedule, wholeYuan)).isEmpty();
    }

    /** Test double: underpays 100.00 of principal in the final rent. */
    private static final class ShortPayStub extends AbstractAmortizationGenerator {

        @Override
        public String code() {
            return "SHORT_PAY_STUB";
        }

        @Override
        public boolean supports(CalculationContext context) {
            return true;
        }

        @Override
        protected Allocation allocate(AllocationInput input, AmortizationState state) {
            BigDecimal principal = input.lastPeriod()
                    ? input.remainingPrincipal().subtract(new BigDecimal("100.00"))
                    : new BigDecimal("83333.33");
            return new Allocation(principal, input.accruedInterest());
        }
    }

    private static Schedule scheduleWithPrincipalSum(String total) {
        List<Installment> rows = amortizingRows();
        // 把末期本金压小，使合计比合同本金少一分。
        Installment last = rows.get(rows.size() - 1);
        rows.set(rows.size() - 1, installment(last.periodNo(), last.startDate().toString(),
                last.endDate().toString(), last.days(), last.openingBalance().toString(),
                last.principalDue().subtract(new BigDecimal("0.01")).toString(),
                last.closingBalance().add(new BigDecimal("0.01")).toString()));
        return schedule(rows);
    }

    private static Schedule scheduleWithLastClosing(String closing) {
        List<Installment> rows = amortizingRows();
        Installment last = rows.get(rows.size() - 1);
        rows.set(rows.size() - 1, installment(last.periodNo(), last.startDate().toString(),
                last.endDate().toString(), last.days(), last.openingBalance().toString(),
                last.openingBalance().subtract(new BigDecimal(closing)).toString(),
                closing));
        return schedule(rows);
    }

    /** 把第 {@code index} 期（0 起）的期末余额抬高 {@code delta}，其余不变。 */
    private static Schedule scheduleWithTamperedClosing(int index, BigDecimal delta) {
        List<Installment> rows = amortizingRows();
        Installment tampered = rows.get(index);
        rows.set(index, new Installment(tampered.periodNo(), tampered.startDate(),
                tampered.endDate(), tampered.days(), tampered.openingBalance(),
                tampered.principalDue(), tampered.interestAccrued(), tampered.interestPaid(),
                tampered.fee(), tampered.tax(), tampered.rentDue(),
                tampered.closingBalance().add(delta), tampered.cumulativePrincipal(),
                tampered.cumulativeInterest(), tampered.appliedRate(), tampered.memo()));
        return schedule(rows);
    }

    /** 把第 {@code index} 期（0 起）的租金抬高 {@code delta}，其余不变。 */
    private static Schedule scheduleWithTamperedRent(int index, BigDecimal delta) {
        List<Installment> rows = amortizingRows();
        Installment tampered = rows.get(index);
        rows.set(index, new Installment(tampered.periodNo(), tampered.startDate(),
                tampered.endDate(), tampered.days(), tampered.openingBalance(),
                tampered.principalDue(), tampered.interestAccrued(), tampered.interestPaid(),
                tampered.fee(), tampered.tax(), tampered.rentDue().add(delta),
                tampered.closingBalance(), tampered.cumulativePrincipal(),
                tampered.cumulativeInterest(), tampered.appliedRate(), tampered.memo()));
        return schedule(rows);
    }

    /** 12 期等额本金形状：合计正好 1 000 000.00，末期归零。 */
    private static List<Installment> amortizingRows() {
        List<Installment> rows = new ArrayList<>();
        BigDecimal opening = new BigDecimal("1000000.00");
        BigDecimal repaid = new BigDecimal("0.00");
        LocalDate start = LocalDate.of(2024, 1, 1);
        for (int period = 1; period <= 12; period++) {
            LocalDate end = start.plusMonths(1);
            int days = 30;
            BigDecimal principal = period == 12
                    ? opening
                    : new BigDecimal("83333.33");
            BigDecimal closing = opening.subtract(principal);
            repaid = repaid.add(principal);
            rows.add(new Installment(period, start, end, days, opening, principal,
                    new BigDecimal("5000.00"), new BigDecimal("5000.00"), new BigDecimal("0.00"),
                    null, principal.add(new BigDecimal("5000.00")), closing, repaid,
                    new BigDecimal("5000.00").multiply(BigDecimal.valueOf(period)),
                    new BigDecimal("0.06"), null));
            opening = closing;
            start = end;
        }
        return rows;
    }

    private static Installment installment(int periodNo, String start, String end, int days,
            String opening, String principal, String closing) {
        BigDecimal openingBalance = new BigDecimal(opening);
        BigDecimal principalDue = new BigDecimal(principal);
        return new Installment(periodNo, LocalDate.parse(start), LocalDate.parse(end), days,
                openingBalance, principalDue, new BigDecimal("5000.00"), new BigDecimal("5000.00"),
                new BigDecimal("0.00"), null, principalDue.add(new BigDecimal("5000.00")),
                new BigDecimal(closing), principalDue, new BigDecimal("5000.00"),
                new BigDecimal("0.06"), null);
    }

    private static Schedule schedule(List<Installment> rows) {
        ScheduleHeader header = new ScheduleHeader(FinancialMetrics.empty(), rows.size(),
                rows.get(0).startDate(), rows.get(rows.size() - 1).endDate(), "FIXTURE",
                new BigDecimal("0.00"));
        return new Schedule(header, rows, Collections.emptyList(), Collections.emptyList());
    }

    private static CalculationContext context(LeaseElement element) {
        return new CalculationContext(element, null, new Thirty360(), new NoRoll(),
                SystemClockProvider.INSTANCE, CalculationOptions.defaults());
    }

    private static LeaseElement baseElement() {
        return LeaseElement.builder()
                .principal(new BigDecimal("1000000.00"))
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
                .vatRate(new BigDecimal("0.06"))
                .method(RepaymentMethodId.EQUAL_INSTALLMENT)
                .gracePeriod(GracePeriod.none())
                .currency("CNY")
                .rateChangePolicy(RateChangePolicy.RESOLVE)
                .build();
    }
}
