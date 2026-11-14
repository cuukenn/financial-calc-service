package io.github.cuukenn.fincalc.server.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.FeeChargeMode;
import io.github.cuukenn.fincalc.domain.model.FeeItem;
import io.github.cuukenn.fincalc.domain.model.GracePeriod;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.PaymentFrequency;
import io.github.cuukenn.fincalc.domain.model.PeriodDate;
import io.github.cuukenn.fincalc.domain.model.RoundingPolicy;
import io.github.cuukenn.fincalc.engine.CalculationRequest;
import io.github.cuukenn.fincalc.engine.EngineErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Plan 2 Task 2 requirement (g): unit tests of the hand-written request assembler — the R76
 * DTO boundary where every field is mapped by hand onto {@link LeaseElement.Builder}.
 */
class CalculationRequestAssemblerTest {

    private final CalculationRequestAssembler assembler = new CalculationRequestAssembler();

    /** CAS 21: the five measurement-chain fields reach the element untouched. */
    @Test
    void maps_the_standard_measurement_chain() {
        CalculationRequestDto dto = new CalculationRequestDto(
                new BigDecimal("2010000"), new BigDecimal("0.06"),
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 1),
                12, null, PaymentFrequency.MONTHLY,
                io.github.cuukenn.fincalc.domain.model.PaymentTiming.IN_ARREARS,
                "THIRTY_360", "NONE", null,
                BigDecimal.ZERO, io.github.cuukenn.fincalc.domain.model.ResidualHandling.SEPARATE,
                BigDecimal.ZERO, null, null, null, new BigDecimal("0.06"), "CNY", "EQUAL_INSTALLMENT",
                null, io.github.cuukenn.fincalc.domain.model.RateChangePolicy.RESOLVE,
                null, null, null, null,
                new BigDecimal("2400000"), new BigDecimal("400000"),
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO, null);

        var element = assembler.assemble(dto).element();
        assertThat(element.assetValue()).isEqualByComparingTo("2400000");
        assertThat(element.downPayment()).isEqualByComparingTo("400000");
        assertThat(element.initialDirectCosts()).isEqualByComparingTo("10000");
    }

    @Test
    void maps_irregular_frequency_with_explicit_periods() {
        CalculationRequestDto dto = baseDto();
        dto = withFrequency(dto, PaymentFrequency.IRREGULAR);
        List<CalculationRequestDto.PeriodDateDto> periods = Arrays.asList(
                new CalculationRequestDto.PeriodDateDto(LocalDate.of(2024, 1, 1),
                        LocalDate.of(2024, 2, 10), LocalDate.of(2024, 2, 10)),
                new CalculationRequestDto.PeriodDateDto(LocalDate.of(2024, 2, 10),
                        LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 1)));
        dto = withExplicitPeriods(dto, periods);

        LeaseElement element = assembler.assemble(dto).element();

        assertThat(element.frequency()).isEqualTo(PaymentFrequency.IRREGULAR);
        assertThat(element.explicitPeriods()).hasSize(2);
        PeriodDate second = element.explicitPeriods().get(1);
        assertThat(second.startDate()).isEqualTo(LocalDate.of(2024, 2, 10));
        assertThat(second.endDate()).isEqualTo(LocalDate.of(2024, 5, 1));
        assertThat(second.paymentDate()).isEqualTo(LocalDate.of(2024, 5, 1));
    }

    @Test
    void maps_fees() {
        CalculationRequestDto.FeeDto fee = new CalculationRequestDto.FeeDto("HANDLING",
                new BigDecimal("10000"), FeeChargeMode.UPFRONT, null, new BigDecimal("0.06"), true);
        CalculationRequestDto.FeeDto lessorOwned = new CalculationRequestDto.FeeDto("INSURANCE",
                new BigDecimal("3000"), FeeChargeMode.UPFRONT,
                io.github.cuukenn.fincalc.domain.model.FeeOwner.LESSOR,
                new BigDecimal("0.06"), true);
        CalculationRequestDto dto = withFees(baseDto(), Arrays.asList(fee, lessorOwned));

        List<FeeItem> fees = assembler.assemble(dto).element().fees();

        assertThat(fees).hasSize(2);
        assertThat(fees.get(0).type()).isEqualTo("HANDLING");
        assertThat(fees.get(0).amount()).isEqualByComparingTo("10000");
        assertThat(fees.get(0).chargeMode()).isEqualTo(FeeChargeMode.UPFRONT);
        // 缺省 = 承租人承担（v1 姿态）。
        assertThat(fees.get(0).owner())
                .isEqualTo(io.github.cuukenn.fincalc.domain.model.FeeOwner.LESSEE);
        assertThat(fees.get(0).vatRate()).isEqualByComparingTo("0.06");
        assertThat(fees.get(0).includeInIrr()).isTrue();
        assertThat(fees.get(1).owner())
                .isEqualTo(io.github.cuukenn.fincalc.domain.model.FeeOwner.LESSOR);
    }

    @Test
    void maps_grace_period() {
        CalculationRequestDto.GracePeriodDto grace =
                new CalculationRequestDto.GracePeriodDto(2, 1);
        CalculationRequestDto dto = withGracePeriod(baseDto(), grace);

        GracePeriod mapped = assembler.assemble(dto).element().gracePeriod();

        assertThat(mapped.interestOnlyPeriods()).isEqualTo(2);
        assertThat(mapped.fullGracePeriods()).isEqualTo(1);
    }

    @Test
    void absent_optional_fields_fall_back_to_model_defaults() {
        LeaseElement element = assembler.assemble(baseDto()).element();

        // rounding absent -> RoundingPolicy.defaults(); gracePeriod absent -> GracePeriod.none()
        assertThat(element.rounding().scale())
                .isEqualTo(RoundingPolicy.defaults().scale());
        assertThat(element.rounding().mode()).isEqualTo(RoundingPolicy.defaults().mode());
        assertThat(element.gracePeriod().interestOnlyPeriods()).isZero();
        assertThat(element.gracePeriod().fullGracePeriods()).isZero();
        assertThat(element.fees()).isEmpty();
        assertThat(element.explicitRents()).isEmpty();
    }

    @Test
    void allow_partial_flag_defaults_to_false_and_is_mapped_when_true() {
        assertThat(assembler.assemble(baseDto()).options().allowPartialRuleSet()).isFalse();

        CalculationRequestDto partial = withAllowPartial(baseDto(), true);
        CalculationOptions options = assembler.assemble(partial).options();
        assertThat(options.allowPartialRuleSet()).isTrue();
    }

    @Test
    void rule_rows_are_compiled_into_the_request_rule_set() {
        CalculationRequestDto.RuleRowDto row = new CalculationRequestDto.RuleRowDto(
                "R1", 10, 1, 12, new BigDecimal("0.06"), null, "INHERIT", null, null,
                "PAY_AS_INCURRED", "IN_ARREARS", "NONE", null, null, null, null, null, null);
        CalculationRequestDto dto = withRuleRows(baseDto(), Collections.singletonList(row));

        CalculationRequest request = assembler.assemble(dto);

        assertThat(request.ruleSet()).isNotNull();
    }

    @Test
    void unknown_method_raises_fcs_eng_1002() {
        CalculationRequestDto dto = withMethod(baseDto(), "NO_SUCH_METHOD");

        assertThatThrownBy(() -> assembler.assemble(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(EngineErrorCode.GENERATOR_NOT_REGISTERED.code());
    }

    @Test
    void rule_row_missing_mandatory_columns_raise_fcs_com_0001_before_rule_row_construction() {
        // ruleId et al. are requireNonNull-mandatory in RuleRow; without the pre-check the
        // constructor NPEs and the API would answer Spring's default 500 body.
        CalculationRequestDto emptyRow = withRuleRows(baseDto(),
                Collections.singletonList(emptyRuleRow()));

        assertThatThrownBy(() -> assembler.assemble(emptyRow))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-COM-0001")
                .hasMessageContaining("ruleRows[0].ruleId");
    }

    /** Ticket 03: the deposit calibers map through, and a positive deposit flips the run's basis. */
    @Test
    void maps_deposit_calibers_and_runs_a_positive_deposit_on_include_deposit() {
        CalculationRequestDto dto = withDepositCalibers(baseDto(),
                new BigDecimal("50000"),
                io.github.cuukenn.fincalc.domain.model.DepositTiming.WITH_FIRST_RENT,
                io.github.cuukenn.fincalc.domain.model.DepositSettlement.OFFSET_FINAL_RENT,
                new BigDecimal("0.03"));

        CalculationRequest request = assembler.assemble(dto);
        assertThat(request.element().deposit()).isEqualByComparingTo("50000");
        assertThat(request.element().depositTiming())
                .isEqualTo(io.github.cuukenn.fincalc.domain.model.DepositTiming.WITH_FIRST_RENT);
        assertThat(request.element().depositSettlement())
                .isEqualTo(io.github.cuukenn.fincalc.domain.model.DepositSettlement.OFFSET_FINAL_RENT);
        assertThat(request.element().depositInterestRate()).isEqualByComparingTo("0.03");
        assertThat(request.options().cashFlowBasis())
                .isEqualTo(io.github.cuukenn.fincalc.domain.model.CashFlowBasis.INCLUDE_DEPOSIT);
    }

    @Test
    void a_deposit_free_request_keeps_the_include_fees_basis() {
        CalculationRequest request = assembler.assemble(baseDto());
        assertThat(request.options().cashFlowBasis())
                .isEqualTo(io.github.cuukenn.fincalc.domain.model.CashFlowBasis.INCLUDE_FEES);
        assertThat(request.element().depositTiming())
                .isEqualTo(io.github.cuukenn.fincalc.domain.model.DepositTiming.UPFRONT);
        assertThat(request.element().depositSettlement())
                .isEqualTo(io.github.cuukenn.fincalc.domain.model.DepositSettlement.REFUND);
    }

    private static CalculationRequestDto withDepositCalibers(CalculationRequestDto source,
            BigDecimal deposit,
            io.github.cuukenn.fincalc.domain.model.DepositTiming depositTiming,
            io.github.cuukenn.fincalc.domain.model.DepositSettlement depositSettlement,
            BigDecimal depositInterestRate) {
        return new CalculationRequestDto(source.principal(), source.nominalRate(),
                source.commenceDate(), source.firstRentDate(), source.periods(),
                source.explicitPeriods(), source.frequency(), source.timing(),
                source.dayCountCode(), source.dateRollCode(), source.rounding(),
                source.residualValue(), source.residualHandling(), deposit,
                depositTiming, depositSettlement, depositInterestRate,
                source.vatRate(), source.currency(), source.method(), source.gracePeriod(),
                source.rateChangePolicy(), source.explicitRents(), source.fees(),
                source.ruleRows(), source.allowPartialRuleSet(), source.assetValue(),
                source.downPayment(), source.initialDirectCosts(), source.guaranteedResidual(),
                source.unguaranteedResidual(), null);
    }

    private static CalculationRequestDto.RuleRowDto emptyRuleRow() {
        return new CalculationRequestDto.RuleRowDto(null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    // ---- fixture helpers ----

    private static CalculationRequestDto baseDto() {
        return new CalculationRequestDto(
                new BigDecimal("1000000"), new BigDecimal("0.06"),
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 1),
                12, null, PaymentFrequency.MONTHLY,
                io.github.cuukenn.fincalc.domain.model.PaymentTiming.IN_ARREARS,
                "THIRTY_360", "NONE", null,
                BigDecimal.ZERO, io.github.cuukenn.fincalc.domain.model.ResidualHandling.SEPARATE,
                BigDecimal.ZERO, null, null, null, new BigDecimal("0.06"), "CNY", "EQUAL_INSTALLMENT",
                null, io.github.cuukenn.fincalc.domain.model.RateChangePolicy.RESOLVE,
                null, null, null, null, null, null, null, null, null, null);
    }

    private static CalculationRequestDto withFrequency(CalculationRequestDto source,
            PaymentFrequency frequency) {
        return new CalculationRequestDto(source.principal(), source.nominalRate(),
                source.commenceDate(), source.firstRentDate(), source.periods(),
                source.explicitPeriods(), frequency, source.timing(), source.dayCountCode(),
                source.dateRollCode(), source.rounding(), source.residualValue(),
                source.residualHandling(), source.deposit(), source.depositTiming(), source.depositSettlement(), source.depositInterestRate(), source.vatRate(), source.currency(),
                source.method(), source.gracePeriod(), source.rateChangePolicy(),
                source.explicitRents(), source.fees(), source.ruleRows(),
                source.allowPartialRuleSet(), source.assetValue(), source.downPayment(), source.initialDirectCosts(), source.guaranteedResidual(), source.unguaranteedResidual(), null);
    }

    private static CalculationRequestDto withExplicitPeriods(CalculationRequestDto source,
            List<CalculationRequestDto.PeriodDateDto> periods) {
        return new CalculationRequestDto(source.principal(), source.nominalRate(),
                source.commenceDate(), source.firstRentDate(), source.periods(), periods,
                source.frequency(), source.timing(), source.dayCountCode(),
                source.dateRollCode(), source.rounding(), source.residualValue(),
                source.residualHandling(), source.deposit(), source.depositTiming(), source.depositSettlement(), source.depositInterestRate(), source.vatRate(), source.currency(),
                source.method(), source.gracePeriod(), source.rateChangePolicy(),
                source.explicitRents(), source.fees(), source.ruleRows(),
                source.allowPartialRuleSet(), source.assetValue(), source.downPayment(), source.initialDirectCosts(), source.guaranteedResidual(), source.unguaranteedResidual(), null);
    }

    private static CalculationRequestDto withFees(CalculationRequestDto source,
            List<CalculationRequestDto.FeeDto> fees) {
        return new CalculationRequestDto(source.principal(), source.nominalRate(),
                source.commenceDate(), source.firstRentDate(), source.periods(),
                source.explicitPeriods(), source.frequency(), source.timing(),
                source.dayCountCode(), source.dateRollCode(), source.rounding(),
                source.residualValue(), source.residualHandling(), source.deposit(), source.depositTiming(), source.depositSettlement(), source.depositInterestRate(),
                source.vatRate(), source.currency(), source.method(), source.gracePeriod(),
                source.rateChangePolicy(), source.explicitRents(), fees, source.ruleRows(),
                source.allowPartialRuleSet(), source.assetValue(), source.downPayment(), source.initialDirectCosts(), source.guaranteedResidual(), source.unguaranteedResidual(), null);
    }

    private static CalculationRequestDto withGracePeriod(CalculationRequestDto source,
            CalculationRequestDto.GracePeriodDto gracePeriod) {
        return new CalculationRequestDto(source.principal(), source.nominalRate(),
                source.commenceDate(), source.firstRentDate(), source.periods(),
                source.explicitPeriods(), source.frequency(), source.timing(),
                source.dayCountCode(), source.dateRollCode(), source.rounding(),
                source.residualValue(), source.residualHandling(), source.deposit(), source.depositTiming(), source.depositSettlement(), source.depositInterestRate(),
                source.vatRate(), source.currency(), source.method(), gracePeriod,
                source.rateChangePolicy(), source.explicitRents(), source.fees(),
                source.ruleRows(), source.allowPartialRuleSet(), source.assetValue(), source.downPayment(), source.initialDirectCosts(), source.guaranteedResidual(), source.unguaranteedResidual(), null);
    }

    private static CalculationRequestDto withAllowPartial(CalculationRequestDto source,
            boolean allowPartial) {
        return new CalculationRequestDto(source.principal(), source.nominalRate(),
                source.commenceDate(), source.firstRentDate(), source.periods(),
                source.explicitPeriods(), source.frequency(), source.timing(),
                source.dayCountCode(), source.dateRollCode(), source.rounding(),
                source.residualValue(), source.residualHandling(), source.deposit(), source.depositTiming(), source.depositSettlement(), source.depositInterestRate(),
                source.vatRate(), source.currency(), source.method(), source.gracePeriod(),
                source.rateChangePolicy(), source.explicitRents(), source.fees(),
                source.ruleRows(), allowPartial, source.assetValue(), source.downPayment(), source.initialDirectCosts(), source.guaranteedResidual(), source.unguaranteedResidual(), null);
    }

    private static CalculationRequestDto withRuleRows(CalculationRequestDto source,
            List<CalculationRequestDto.RuleRowDto> ruleRows) {
        return new CalculationRequestDto(source.principal(), source.nominalRate(),
                source.commenceDate(), source.firstRentDate(), source.periods(),
                source.explicitPeriods(), source.frequency(), source.timing(),
                source.dayCountCode(), source.dateRollCode(), source.rounding(),
                source.residualValue(), source.residualHandling(), source.deposit(), source.depositTiming(), source.depositSettlement(), source.depositInterestRate(),
                source.vatRate(), source.currency(), source.method(), source.gracePeriod(),
                source.rateChangePolicy(), source.explicitRents(), source.fees(), ruleRows,
                source.allowPartialRuleSet(), source.assetValue(), source.downPayment(), source.initialDirectCosts(), source.guaranteedResidual(), source.unguaranteedResidual(), null);
    }

    private static CalculationRequestDto withMethod(CalculationRequestDto source,
            String method) {
        return new CalculationRequestDto(source.principal(), source.nominalRate(),
                source.commenceDate(), source.firstRentDate(), source.periods(),
                source.explicitPeriods(), source.frequency(), source.timing(),
                source.dayCountCode(), source.dateRollCode(), source.rounding(),
                source.residualValue(), source.residualHandling(), source.deposit(), source.depositTiming(), source.depositSettlement(), source.depositInterestRate(),
                source.vatRate(), source.currency(), method, source.gracePeriod(),
                source.rateChangePolicy(), source.explicitRents(), source.fees(),
                source.ruleRows(), source.allowPartialRuleSet(), source.assetValue(), source.downPayment(), source.initialDirectCosts(), source.guaranteedResidual(), source.unguaranteedResidual(), null);
    }
}
