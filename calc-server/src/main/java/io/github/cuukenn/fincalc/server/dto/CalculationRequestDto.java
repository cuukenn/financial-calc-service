package io.github.cuukenn.fincalc.server.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.cuukenn.fincalc.domain.model.FeeChargeMode;
import io.github.cuukenn.fincalc.domain.model.PaymentFrequency;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.model.RateChangePolicy;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
import io.github.cuukenn.fincalc.domain.model.TailAdjustPolicy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.math.RoundingMode;
import java.util.List;

/**
 * Flat request body of {@code POST /api/v1/calculations} (Plan 2 Task 2, R76): the fields
 * mirror {@link io.github.cuukenn.fincalc.domain.model.LeaseElement}'s builder surface plus
 * the run-level additions ({@code ruleRows}, {@code explicitRents},
 * {@code allowPartialRuleSet}).
 *
 * <p>DTO boundary ruling R76: the core modules carry no Jackson annotations — all binding and
 * mapping is hand-written on this server-side DTO. The class is a records-style final class
 * (final fields + {@code @JsonCreator} constructor + accessors; JDK 11 has no records).
 *
 * <p>Semantics of absent optional fields (documented defaults, applied by the assembler):
 *
 * <ul>
 *   <li>{@code rounding} absent → {@code RoundingPolicy.defaults()}; a present-but-partial
 *       rounding object falls back per field to the same defaults;</li>
 *   <li>{@code gracePeriod} absent → {@code GracePeriod.none()};</li>
 *   <li>{@code residualValue}/{@code deposit} absent → {@code null} (the model's
 *       "no residual/deposit" state);</li>
 *   <li>{@code fees}/{@code explicitRents}/{@code ruleRows} absent → empty collections;</li>
 *   <li>{@code allowPartialRuleSet} absent → {@code false} (rule coverage gaps fail the run).</li>
 * </ul>
 *
 * <p>{@code method} is a {@code String} on purpose: an unknown method name must surface as
 * {@code FCS-ENG-1002} (未注册的还款方式生成器, HTTP 400 per R77) raised by the assembler — a
 * typed enum would instead fail Jackson binding as a generic {@code FCS-SRV-1001}.
 */
public final class CalculationRequestDto {

    private final BigDecimal principal;
    private final BigDecimal nominalRate;
    private final LocalDate commenceDate;
    private final LocalDate firstRentDate;
    private final Integer periods;
    private final List<PeriodDateDto> explicitPeriods;
    private final PaymentFrequency frequency;
    private final PaymentTiming timing;
    private final String dayCountCode;
    private final String dateRollCode;
    private final RoundingDto rounding;
    private final BigDecimal residualValue;
    private final ResidualHandling residualHandling;
    private final BigDecimal deposit;
    private final BigDecimal vatRate;
    private final String currency;
    private final String method;
    private final GracePeriodDto gracePeriod;
    private final RateChangePolicy rateChangePolicy;
    private final List<BigDecimal> explicitRents;
    private final List<FeeDto> fees;
    private final List<RuleRowDto> ruleRows;
    private final Boolean allowPartialRuleSet;

    @JsonCreator
    public CalculationRequestDto(
            @JsonProperty("principal") BigDecimal principal,
            @JsonProperty("nominalRate") BigDecimal nominalRate,
            @JsonProperty("commenceDate") LocalDate commenceDate,
            @JsonProperty("firstRentDate") LocalDate firstRentDate,
            @JsonProperty("periods") Integer periods,
            @JsonProperty("explicitPeriods") List<PeriodDateDto> explicitPeriods,
            @JsonProperty("frequency") PaymentFrequency frequency,
            @JsonProperty("timing") PaymentTiming timing,
            @JsonProperty("dayCountCode") String dayCountCode,
            @JsonProperty("dateRollCode") String dateRollCode,
            @JsonProperty("rounding") RoundingDto rounding,
            @JsonProperty("residualValue") BigDecimal residualValue,
            @JsonProperty("residualHandling") ResidualHandling residualHandling,
            @JsonProperty("deposit") BigDecimal deposit,
            @JsonProperty("vatRate") BigDecimal vatRate,
            @JsonProperty("currency") String currency,
            @JsonProperty("method") String method,
            @JsonProperty("gracePeriod") GracePeriodDto gracePeriod,
            @JsonProperty("rateChangePolicy") RateChangePolicy rateChangePolicy,
            @JsonProperty("explicitRents") List<BigDecimal> explicitRents,
            @JsonProperty("fees") List<FeeDto> fees,
            @JsonProperty("ruleRows") List<RuleRowDto> ruleRows,
            @JsonProperty("allowPartialRuleSet") Boolean allowPartialRuleSet) {
        this.principal = principal;
        this.nominalRate = nominalRate;
        this.commenceDate = commenceDate;
        this.firstRentDate = firstRentDate;
        this.periods = periods;
        this.explicitPeriods = explicitPeriods;
        this.frequency = frequency;
        this.timing = timing;
        this.dayCountCode = dayCountCode;
        this.dateRollCode = dateRollCode;
        this.rounding = rounding;
        this.residualValue = residualValue;
        this.residualHandling = residualHandling;
        this.deposit = deposit;
        this.vatRate = vatRate;
        this.currency = currency;
        this.method = method;
        this.gracePeriod = gracePeriod;
        this.rateChangePolicy = rateChangePolicy;
        this.explicitRents = explicitRents;
        this.fees = fees;
        this.ruleRows = ruleRows;
        this.allowPartialRuleSet = allowPartialRuleSet;
    }

    public BigDecimal principal() {
        return principal;
    }

    public BigDecimal nominalRate() {
        return nominalRate;
    }

    public LocalDate commenceDate() {
        return commenceDate;
    }

    public LocalDate firstRentDate() {
        return firstRentDate;
    }

    public Integer periods() {
        return periods;
    }

    public List<PeriodDateDto> explicitPeriods() {
        return explicitPeriods;
    }

    public PaymentFrequency frequency() {
        return frequency;
    }

    public PaymentTiming timing() {
        return timing;
    }

    public String dayCountCode() {
        return dayCountCode;
    }

    public String dateRollCode() {
        return dateRollCode;
    }

    public RoundingDto rounding() {
        return rounding;
    }

    public BigDecimal residualValue() {
        return residualValue;
    }

    public ResidualHandling residualHandling() {
        return residualHandling;
    }

    public BigDecimal deposit() {
        return deposit;
    }

    public BigDecimal vatRate() {
        return vatRate;
    }

    public String currency() {
        return currency;
    }

    public String method() {
        return method;
    }

    public GracePeriodDto gracePeriod() {
        return gracePeriod;
    }

    public RateChangePolicy rateChangePolicy() {
        return rateChangePolicy;
    }

    public List<BigDecimal> explicitRents() {
        return explicitRents;
    }

    public List<FeeDto> fees() {
        return fees;
    }

    public List<RuleRowDto> ruleRows() {
        return ruleRows;
    }

    public Boolean allowPartialRuleSet() {
        return allowPartialRuleSet;
    }

    /** One explicitly given period of an IRREGULAR schedule, mirroring {@code PeriodDate}. */
    public static final class PeriodDateDto {

        private final LocalDate startDate;
        private final LocalDate endDate;
        private final LocalDate paymentDate;

        @JsonCreator
        public PeriodDateDto(
                @JsonProperty("startDate") LocalDate startDate,
                @JsonProperty("endDate") LocalDate endDate,
                @JsonProperty("paymentDate") LocalDate paymentDate) {
            this.startDate = startDate;
            this.endDate = endDate;
            this.paymentDate = paymentDate;
        }

        public LocalDate startDate() {
            return startDate;
        }

        public LocalDate endDate() {
            return endDate;
        }

        public LocalDate paymentDate() {
            return paymentDate;
        }
    }

    /**
     * Rounding policy section; every field is nullable and falls back to
     * {@code RoundingPolicy.defaults()} values when absent.
     */
    public static final class RoundingDto {

        private final Integer scale;
        private final RoundingMode mode;
        private final TailAdjustPolicy tailAdjustPolicy;

        @JsonCreator
        public RoundingDto(
                @JsonProperty("scale") Integer scale,
                @JsonProperty("mode") RoundingMode mode,
                @JsonProperty("tailAdjustPolicy") TailAdjustPolicy tailAdjustPolicy) {
            this.scale = scale;
            this.mode = mode;
            this.tailAdjustPolicy = tailAdjustPolicy;
        }

        public Integer scale() {
            return scale;
        }

        public RoundingMode mode() {
            return mode;
        }

        public TailAdjustPolicy tailAdjustPolicy() {
            return tailAdjustPolicy;
        }
    }

    /** Grace period section; absent → {@code GracePeriod.none()}. */
    public static final class GracePeriodDto {

        private final Integer interestOnlyPeriods;
        private final Integer fullGracePeriods;

        @JsonCreator
        public GracePeriodDto(
                @JsonProperty("interestOnlyPeriods") Integer interestOnlyPeriods,
                @JsonProperty("fullGracePeriods") Integer fullGracePeriods) {
            this.interestOnlyPeriods = interestOnlyPeriods;
            this.fullGracePeriods = fullGracePeriods;
        }

        public Integer interestOnlyPeriods() {
            return interestOnlyPeriods;
        }

        public Integer fullGracePeriods() {
            return fullGracePeriods;
        }
    }

    /** One fee, mirroring {@code FeeItem}'s builder surface; {@code includeInIrr} defaults false. */
    public static final class FeeDto {

        private final String type;
        private final BigDecimal amount;
        private final FeeChargeMode chargeMode;
        private final BigDecimal vatRate;
        private final Boolean includeInIrr;

        @JsonCreator
        public FeeDto(
                @JsonProperty("type") String type,
                @JsonProperty("amount") BigDecimal amount,
                @JsonProperty("chargeMode") FeeChargeMode chargeMode,
                @JsonProperty("vatRate") BigDecimal vatRate,
                @JsonProperty("includeInIrr") Boolean includeInIrr) {
            this.type = type;
            this.amount = amount;
            this.chargeMode = chargeMode;
            this.vatRate = vatRate;
            this.includeInIrr = includeInIrr;
        }

        public String type() {
            return type;
        }

        public BigDecimal amount() {
            return amount;
        }

        public FeeChargeMode chargeMode() {
            return chargeMode;
        }

        public BigDecimal vatRate() {
            return vatRate;
        }

        public Boolean includeInIrr() {
            return includeInIrr;
        }
    }

    /**
     * One rule row, mirroring {@code RuleRow}'s 16 semantic fields including {@code stage} and
     * {@code condition}. Enum columns are raw strings and are parsed (fail-loud) by the
     * assembler; presentation/provenance columns ({@code memo}, {@code rowNumber},
     * {@code cellRef}) default to 0/absent.
     */
    public static final class RuleRowDto {

        private final String ruleId;
        private final Integer priority;
        private final Integer fromPeriod;
        private final Integer toPeriod;
        private final BigDecimal annualRate;
        private final String rateBase;
        private final String principalPlan;
        private final BigDecimal principalValue;
        private final String principalSequence;
        private final String interestTreatment;
        private final String paymentTiming;
        private final String specialAction;
        private final String actionParam;
        private final Integer stage;
        private final String condition;
        private final String memo;
        private final Integer rowNumber;
        private final String cellRef;

        @JsonCreator
        public RuleRowDto(
                @JsonProperty("ruleId") String ruleId,
                @JsonProperty("priority") Integer priority,
                @JsonProperty("fromPeriod") Integer fromPeriod,
                @JsonProperty("toPeriod") Integer toPeriod,
                @JsonProperty("annualRate") BigDecimal annualRate,
                @JsonProperty("rateBase") String rateBase,
                @JsonProperty("principalPlan") String principalPlan,
                @JsonProperty("principalValue") BigDecimal principalValue,
                @JsonProperty("principalSequence") String principalSequence,
                @JsonProperty("interestTreatment") String interestTreatment,
                @JsonProperty("paymentTiming") String paymentTiming,
                @JsonProperty("specialAction") String specialAction,
                @JsonProperty("actionParam") String actionParam,
                @JsonProperty("stage") Integer stage,
                @JsonProperty("condition") String condition,
                @JsonProperty("memo") String memo,
                @JsonProperty("rowNumber") Integer rowNumber,
                @JsonProperty("cellRef") String cellRef) {
            this.ruleId = ruleId;
            this.priority = priority;
            this.fromPeriod = fromPeriod;
            this.toPeriod = toPeriod;
            this.annualRate = annualRate;
            this.rateBase = rateBase;
            this.principalPlan = principalPlan;
            this.principalValue = principalValue;
            this.principalSequence = principalSequence;
            this.interestTreatment = interestTreatment;
            this.paymentTiming = paymentTiming;
            this.specialAction = specialAction;
            this.actionParam = actionParam;
            this.stage = stage;
            this.condition = condition;
            this.memo = memo;
            this.rowNumber = rowNumber;
            this.cellRef = cellRef;
        }

        public String ruleId() {
            return ruleId;
        }

        public Integer priority() {
            return priority;
        }

        public Integer fromPeriod() {
            return fromPeriod;
        }

        public Integer toPeriod() {
            return toPeriod;
        }

        public BigDecimal annualRate() {
            return annualRate;
        }

        public String rateBase() {
            return rateBase;
        }

        public String principalPlan() {
            return principalPlan;
        }

        public BigDecimal principalValue() {
            return principalValue;
        }

        public String principalSequence() {
            return principalSequence;
        }

        public String interestTreatment() {
            return interestTreatment;
        }

        public String paymentTiming() {
            return paymentTiming;
        }

        public String specialAction() {
            return specialAction;
        }

        public String actionParam() {
            return actionParam;
        }

        public Integer stage() {
            return stage;
        }

        public String condition() {
            return condition;
        }

        public String memo() {
            return memo;
        }

        public Integer rowNumber() {
            return rowNumber;
        }

        public String cellRef() {
            return cellRef;
        }
    }
}
