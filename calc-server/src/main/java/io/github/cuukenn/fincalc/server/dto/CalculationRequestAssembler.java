package io.github.cuukenn.fincalc.server.dto;

import io.github.cuukenn.fincalc.rule.model.CompiledRuleSet;
import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.common.error.CommonErrorCode;
import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.CashFlowBasis;
import io.github.cuukenn.fincalc.domain.model.FeeItem;
import io.github.cuukenn.fincalc.domain.model.GracePeriod;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.model.PeriodDate;
import io.github.cuukenn.fincalc.domain.model.PrincipalPlanKind;
import io.github.cuukenn.fincalc.domain.model.RateChangePolicy;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
import io.github.cuukenn.fincalc.domain.model.RoundingPolicy;
import io.github.cuukenn.fincalc.domain.model.InterestTreatmentKind;
import io.github.cuukenn.fincalc.domain.model.SpecialActionKind;
import io.github.cuukenn.fincalc.engine.CalculationRequest;
import io.github.cuukenn.fincalc.engine.EngineErrorCode;
import io.github.cuukenn.fincalc.rule.compiler.RuleCompiler;
import io.github.cuukenn.fincalc.rule.model.RateBase;
import io.github.cuukenn.fincalc.rule.function.FunctionRegistry;
import io.github.cuukenn.fincalc.rule.model.RuleRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written R76 mapping: {@link CalculationRequestDto} → engine
 * {@link CalculationRequest} ({@code LeaseElement.Builder} + optional compiled rule set).
 *
 * <p>Every field is mapped explicitly; absent optional fields fall back to the model's own
 * defaults as documented on the DTO. Rule rows, when present, are compiled through
 * {@link RuleCompiler} over {@link FunctionRegistry#defaults()} with
 * {@code allowPartialRuleSet} (request flag, default {@code false}); a failed compilation
 * throws {@code RuleValidationException extends BusinessException} → HTTP 400 under R77.
 *
 * <p>{@code method} is parsed by name; an unknown name raises
 * {@link EngineErrorCode#GENERATOR_NOT_REGISTERED} (FCS-ENG-1002, HTTP 400) — semantically the
 * requested algorithm has no registered generator. {@code method} left {@code null} reaches
 * the domain's own required-field check (FCS-COM-0001, also 400). The {@code EXPLICIT_RENT}
 * rent validation is not duplicated here: the domain raises FCS-DOM-1008/1009 at
 * {@code build()}.
 */
public final class CalculationRequestAssembler {

    private final RuleCompiler ruleCompiler;

    public CalculationRequestAssembler() {
        this.ruleCompiler = new RuleCompiler(FunctionRegistry.defaults());
    }

    /**
     * Maps the request DTO onto the engine request.
     *
     * @param dto the parsed request body; never {@code null}
     * @return the engine request carrying the built element, the compiled rule set (or
     *         {@code null} when no rows were supplied) and the run options
     * @throws BusinessException with the domain/engine/rule code of the first failed check
     */
    public CalculationRequest assemble(CalculationRequestDto dto) {
        LeaseElement element = buildElement(dto);
        boolean allowPartial = Boolean.TRUE.equals(dto.allowPartialRuleSet());
        CompiledRuleSet ruleSet = null;
        if (dto.ruleRows() != null && !dto.ruleRows().isEmpty()) {
            List<RuleRow> rows = new ArrayList<>(dto.ruleRows().size());
            for (int i = 0; i < dto.ruleRows().size(); i++) {
                rows.add(toRuleRow(dto.ruleRows().get(i), i));
            }
            ruleSet = ruleCompiler.compile(rows, element, allowPartial);
        }
        CalculationOptions options = new CalculationOptions(allowPartial, cashFlowBasis(dto));
        return new CalculationRequest(element, ruleSet, options);
    }

    /**
     * The cash-flow basis of one run: a request carrying a positive deposit runs under
     * {@link CashFlowBasis#INCLUDE_DEPOSIT} so the deposit genuinely enters the investment and the
     * IRR — the whole point of the 收取时点/计息/退还抵扣 fields (form-field-gaps ticket 03) — and
     * every other request keeps the v1 {@link CashFlowBasis#INCLUDE_FEES} basis, so a deposit-free
     * run is byte-for-byte what it was. A caller that wants the deposit excluded prices a
     * deposit-free element instead; there is no per-request basis override in v1.
     */
    private static CashFlowBasis cashFlowBasis(CalculationRequestDto dto) {
        boolean hasDeposit = dto.deposit() != null && dto.deposit().signum() > 0;
        return hasDeposit ? CashFlowBasis.INCLUDE_DEPOSIT : CashFlowBasis.INCLUDE_FEES;
    }

    private LeaseElement buildElement(CalculationRequestDto dto) {
        RoundingPolicy rounding = dto.rounding() == null
                ? RoundingPolicy.defaults()
                : toRoundingPolicy(dto.rounding());
        GracePeriod gracePeriod = dto.gracePeriod() == null
                ? GracePeriod.none()
                : GracePeriod.builder()
                        .interestOnlyPeriods(orZero(dto.gracePeriod().interestOnlyPeriods()))
                        .fullGracePeriods(orZero(dto.gracePeriod().fullGracePeriods()))
                        .build();
        LeaseElement.Builder builder = LeaseElement.builder()
                .principal(dto.principal())
                .assetValue(dto.assetValue())
                .downPayment(dto.downPayment())
                .initialDirectCosts(dto.initialDirectCosts())
                .guaranteedResidual(dto.guaranteedResidual())
                .unguaranteedResidual(dto.unguaranteedResidual())
                .nominalRate(dto.nominalRate())
                .commenceDate(dto.commenceDate())
                .firstRentDate(dto.firstRentDate())
                .periods(orZero(dto.periods()))
                .explicitPeriods(toPeriods(dto.explicitPeriods()))
                .frequency(dto.frequency())
                .timing(dto.timing())
                .dayCountCode(dto.dayCountCode())
                .dateRollCode(dto.dateRollCode())
                .rounding(rounding)
                .residualValue(dto.residualValue())
                .residualHandling(dto.residualHandling())
                .deposit(dto.deposit())
                .depositTiming(dto.depositTiming())
                .depositSettlement(dto.depositSettlement())
                .depositInterestRate(dto.depositInterestRate())
                .firstPeriodStub(Boolean.TRUE.equals(dto.firstPeriodStub()))
                .fees(toFees(dto.fees()))
                .explicitRents(dto.explicitRents())
                .vatRate(dto.vatRate())
                .method(parseMethod(dto.method()))
                .gracePeriod(gracePeriod)
                .currency(dto.currency())
                .rateChangePolicy(dto.rateChangePolicy());
        return builder.build();
    }

    private static RoundingPolicy toRoundingPolicy(CalculationRequestDto.RoundingDto dto) {
        RoundingPolicy.Builder builder = RoundingPolicy.builder();
        if (dto.scale() != null) {
            builder.scale(dto.scale());
        }
        if (dto.mode() != null) {
            builder.mode(dto.mode());
        }
        if (dto.tailAdjustPolicy() != null) {
            builder.tailAdjustPolicy(dto.tailAdjustPolicy());
        }
        return builder.build();
    }

    private static List<PeriodDate> toPeriods(List<CalculationRequestDto.PeriodDateDto> dtos) {
        if (dtos == null) {
            return null;
        }
        List<PeriodDate> periods = new ArrayList<>(dtos.size());
        for (CalculationRequestDto.PeriodDateDto dto : dtos) {
            periods.add(PeriodDate.builder()
                    .startDate(dto.startDate())
                    .endDate(dto.endDate())
                    .paymentDate(dto.paymentDate())
                    .build());
        }
        return periods;
    }

    private static List<FeeItem> toFees(List<CalculationRequestDto.FeeDto> dtos) {
        if (dtos == null) {
            return null;
        }
        List<FeeItem> fees = new ArrayList<>(dtos.size());
        for (CalculationRequestDto.FeeDto dto : dtos) {
            fees.add(FeeItem.builder()
                    .type(dto.type())
                    .amount(dto.amount())
                    .chargeMode(dto.chargeMode())
                    .owner(dto.owner())
                    .vatRate(dto.vatRate())
                    .includeInIrr(Boolean.TRUE.equals(dto.includeInIrr()))
                    .build());
        }
        return fees;
    }

    private RuleRow toRuleRow(CalculationRequestDto.RuleRowDto dto, int index) {
        // Fail loud BEFORE RuleRow's constructor: ruleId, principalPlan, interestTreatment,
        // paymentTiming and specialAction are requireNonNull-mandatory there — letting them
        // through would surface a NullPointerException as a 500 instead of the client's own
        // 400. The remaining columns are nullable by the model's contract.
        requireColumn(dto.ruleId(), "ruleId", index);
        requireColumn(dto.principalPlan(), "principalPlan", index);
        requireColumn(dto.interestTreatment(), "interestTreatment", index);
        requireColumn(dto.paymentTiming(), "paymentTiming", index);
        requireColumn(dto.specialAction(), "specialAction", index);
        return new RuleRow(
                dto.ruleId(),
                orZero(dto.priority()),
                orZero(dto.fromPeriod()),
                orZero(dto.toPeriod()),
                dto.annualRate(),
                parseEnum(dto.rateBase(), RateBase.class, "rateBase"),
                parseEnum(dto.principalPlan(), PrincipalPlanKind.class, "principalPlan"),
                dto.principalValue(),
                dto.principalSequence(),
                parseEnum(dto.interestTreatment(), InterestTreatmentKind.class,
                        "interestTreatment"),
                parseEnum(dto.paymentTiming(), PaymentTiming.class, "paymentTiming"),
                parseEnum(dto.specialAction(), SpecialActionKind.class, "specialAction"),
                dto.actionParam(),
                orZero(dto.stage()),
                dto.condition(),
                dto.memo(),
                orZero(dto.rowNumber()),
                dto.cellRef());
    }

    /** A mandatory rule-row column must be non-blank; otherwise FCS-COM-0001 (HTTP 400). */
    private static void requireColumn(String value, String column, int index) {
        if (value == null || value.trim().isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT,
                    "ruleRows[" + index + "]." + column, "缺失");
        }
    }

    private static RepaymentMethodId parseMethod(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return RepaymentMethodId.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(EngineErrorCode.GENERATOR_NOT_REGISTERED, raw);
        }
    }

    private static <T extends Enum<T>> T parseEnum(String raw, Class<T> type, String field) {
        if (raw == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT, field, raw);
        }
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
