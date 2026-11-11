package io.github.cuukenn.fincalc.server.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.cuukenn.fincalc.domain.model.FeeChargeMode;
import io.github.cuukenn.fincalc.domain.model.PaymentFrequency;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.model.RateChangePolicy;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
import io.github.cuukenn.fincalc.domain.model.TailAdjustPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
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
 * (final fields + {@code @JsonCreator} constructor + accessors; JDK 11 has no records). The
 * {@code @Schema} documentation lives here only (Plan 2 Task 3); the core stays untouched.
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
@Schema(description = "POST /api/v1/calculations 的请求体：LeaseElement 要素平铺 + 规则行/显式租金。"
        + "金额字段用 JSON 数值或字符串均可（引擎统一转为 BigDecimal 精确运算）；"
        + "日期为 ISO-8601（yyyy-MM-dd）。字段校验失败以统一错误体 {code,message} 返回 HTTP 400（R77）。"
        + "v1 现金流口径固定为 INCLUDE_FEES（含费用、不含保证金收付）；"
        + "INCLUDE_DEPOSIT/CONTRACT 待 Plan 3 暴露为请求字段。")
public final class CalculationRequestDto {

    @Schema(description = "租赁本金，必须为正数（FCS-DOM-1001）", example = "1000000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final BigDecimal principal;

    @Schema(description = "名义年利率，小数形式（0.06 = 6%），取值 [0,1]（FCS-DOM-1002）",
            example = "0.06", requiredMode = Schema.RequiredMode.REQUIRED)
    private final BigDecimal nominalRate;

    @Schema(description = "起租日，ISO-8601（yyyy-MM-dd），必填（FCS-DOM-1004）",
            example = "2024-01-01", requiredMode = Schema.RequiredMode.REQUIRED)
    private final LocalDate commenceDate;

    @Schema(description = "首期租金日，ISO-8601；不得早于起租日（FCS-DOM-1005）",
            example = "2024-02-01", requiredMode = Schema.RequiredMode.REQUIRED)
    private final LocalDate firstRentDate;

    @Schema(description = "期数，≥1（FCS-DOM-1003）；frequency=IRREGULAR 时忽略本字段、改用 explicitPeriods",
            example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
    private final Integer periods;

    @Schema(description = "IRREGULAR 频率的显式期间列表（每项 startDate < endDate，"
            + "逐期给齐时长度即期数，FCS-DOM-1006）；其余频率忽略")
    private final List<PeriodDateDto> explicitPeriods;

    @Schema(description = "还款频率：MONTHLY / QUARTERLY / SEMI_ANNUAL / ANNUAL / IRREGULAR",
            example = "MONTHLY", requiredMode = Schema.RequiredMode.REQUIRED)
    private final PaymentFrequency frequency;

    @Schema(description = "付款时点：IN_ADVANCE（期初付）/ IN_ARREARS（期末付）",
            example = "IN_ARREARS", requiredMode = Schema.RequiredMode.REQUIRED)
    private final PaymentTiming timing;

    @Schema(description = "天数口径 code（DayCountConvention 注册名）："
            + "ACT_360 / ACT_365F / ACT_ACT / THIRTY_360 / THIRTY_E_360 / ACT_365_25；"
            + "未知 code → FCS-ENG-1001（HTTP 400）",
            example = "THIRTY_360", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String dayCountCode;

    @Schema(description = "日期滚动 code（DateRollConvention 注册名）："
            + "NONE / FOLLOWING / MODIFIED_FOLLOWING / PRECEDING / MODIFIED_PRECEDING / END_OF_MONTH；"
            + "未知 code → FCS-ENG-1001（HTTP 400）",
            example = "NONE", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String dateRollCode;

    @Schema(description = "舍入策略；整体缺省用 RoundingPolicy.defaults()（2 位小数、HALF_UP、"
            + "尾差归末期）；对象内字段可只给一部分，缺省字段逐项回退同款默认值")
    private final RoundingDto rounding;

    @Schema(description = "残值金额；缺省 = 无残值。BALLOON_IN_RENT 时须满足 0 ≤ 残值 < 本金（FCS-DOM-1007）")
    private final BigDecimal residualValue;

    @Schema(description = "租赁物价值（公允价值）；缺省 = 未提供，此时租赁内含利率不可计算",
            example = "1000000")
    private final BigDecimal assetValue;

    @Schema(description = "残值处理：SEPARATE（残值期末单独回收）/ BALLOON_IN_RENT（摊入租金）",
            example = "SEPARATE")
    private final ResidualHandling residualHandling;

    @Schema(description = "保证金金额；缺省 = 无保证金")
    private final BigDecimal deposit;

    @Schema(description = "增值税率，小数形式（0.06 = 6%）", example = "0.06")
    private final BigDecimal vatRate;

    @Schema(description = "币种，ISO-4217 代码", example = "CNY")
    private final String currency;

    @Schema(description = "还款方式 code（RepaymentMethodId 枚举名，可选值见 GET /api/v1/algorithms）："
            + "EQUAL_INSTALLMENT / EQUAL_PRINCIPAL / INTEREST_FIRST / PRINCIPAL_FIRST / "
            + "BULLET_LOAN / RULE_DRIVEN / EXPLICIT_RENT；未知 code → FCS-ENG-1002（HTTP 400）；"
            + "RULE_DRIVEN 必须携带 ruleRows（否则 FCS-ENG-1003）",
            example = "EQUAL_INSTALLMENT", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String method;

    @Schema(description = "宽限期；缺省 = 无宽限期（GracePeriod.none()）")
    private final GracePeriodDto gracePeriod;

    @Schema(description = "分段利率策略（设计 §7.5，携带 ruleRows 时生效）："
            + "RESOLVE（默认，段初重解 PMT）/ FIXED_RENT（全程租金不变）/ DRIFT（逐期重解）",
            example = "RESOLVE")
    private final RateChangePolicy rateChangePolicy;

    @Schema(description = "EXPLICIT_RENT 专用：逐期租金数组，长度必须 = 期数且每期 > 0"
            + "（FCS-DOM-1008/1009，引擎反推本息拆分、不钳制末期）",
            example = "[86066.43, 86066.43, 86066.42]")
    private final List<BigDecimal> explicitRents;

    @Schema(description = "费用列表；includeInIrr 缺省 false（费用不计入 IRR 现金流）")
    private final List<FeeDto> fees;

    @Schema(description = "规则行（设计文档 §8.2 列语义，docs/superpowers/specs/"
            + "2026-11-02-financial-calc-service-design.md）：非空时经 RuleCompiler 编译为规则集，"
            + "各内置算法逐期解析费率/本金计划；缺省列按 §8.2 语义取继承值。"
            + "必填列缺失 → FCS-COM-0001（HTTP 400）")
    private final List<RuleRowDto> ruleRows;

    @Schema(description = "规则集未覆盖全部期间时是否放行；缺省 false（覆盖缺口即失败，FCS-RULE-2001）")
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
            @JsonProperty("allowPartialRuleSet") Boolean allowPartialRuleSet,
            @JsonProperty("assetValue") BigDecimal assetValue) {
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
        this.assetValue = assetValue;
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

    public BigDecimal assetValue() {
        return assetValue;
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
    @Schema(description = "IRREGULAR 频率的一个显式期间")
    public static final class PeriodDateDto {

        @Schema(description = "期间起始日，ISO-8601", example = "2024-01-01")
        private final LocalDate startDate;

        @Schema(description = "期间结束日，必须晚于起始日（FCS-DOM-1006）", example = "2024-01-31")
        private final LocalDate endDate;

        @Schema(description = "租金支付日，ISO-8601", example = "2024-02-01")
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
    @Schema(description = "舍入策略分节；字段可缺省，缺省字段回退 RoundingPolicy.defaults()")
    public static final class RoundingDto {

        @Schema(description = "金额小数位（缺省 2）", example = "2")
        private final Integer scale;

        @Schema(description = "舍入模式，java.math.RoundingMode 枚举名（缺省 HALF_UP）",
                example = "HALF_UP")
        private final RoundingMode mode;

        @Schema(description = "尾差处理：LAST_PERIOD（差额归末期，缺省）/ FIRST_PERIOD（归首期）/"
                + "PROPORTIONAL（按期均摊）")
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
    @Schema(description = "宽限期分节；缺省 = 无宽限期")
    public static final class GracePeriodDto {

        @Schema(description = "只付息期数（本金不还、利息照计）", example = "0")
        private final Integer interestOnlyPeriods;

        @Schema(description = "全宽限期数（本息均不计）", example = "0")
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
    @Schema(description = "一笔费用")
    public static final class FeeDto {

        @Schema(description = "费用类型标识（自由文本，进入底稿/指标名）", example = "处置费")
        private final String type;

        @Schema(description = "费用金额", example = "10000")
        private final BigDecimal amount;

        @Schema(description = "收取方式：UPFRONT（期初一次性）/ DEDUCT_FROM_PRINCIPAL"
                + "（本金扣减）/ AMORTIZED（分摊进租金）",
                example = "UPFRONT")
        private final FeeChargeMode chargeMode;

        @Schema(description = "该笔费用的增值税率，小数形式", example = "0.06")
        private final BigDecimal vatRate;

        @Schema(description = "是否计入 IRR 现金流；缺省 false", example = "false")
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
    @Schema(description = "一条规则行，列语义见设计文档 §8.2（规则表列定义）。"
            + "装配层预校验的必填列：ruleId/principalPlan/interestTreatment/paymentTiming/"
            + "specialAction，缺失 → FCS-COM-0001（HTTP 400）；priority 缺省按 0 处理，"
            + "annualRate 可为 null，fromPeriod/toPeriod 缺省按 0 处理（区间/范围问题由"
            + "规则编译的行级校验报错）。枚举列为字符串，由装配层 fail-loud 解析。")
    public static final class RuleRowDto {

        @Schema(description = "§8.2 列 A：规则唯一标识，参与审计与错误定位", example = "R1")
        private final String ruleId;

        @Schema(description = "§8.2 列 B：优先级，区间冲突时数值小者优先", example = "10")
        private final Integer priority;

        @Schema(description = "§8.2 列 E：期次区间起点（1 起）", example = "1")
        private final Integer fromPeriod;

        @Schema(description = "§8.2 列 F：期次区间终点（含）", example = "6")
        private final Integer toPeriod;

        @Schema(description = "§8.2 列 G：年利率，小数形式（0.03 = 3%），支持按区间覆盖",
                example = "0.03")
        private final BigDecimal annualRate;

        @Schema(description = "§8.2 列 H：计息基数（OPENING_BALANCE 等，见设计文档 §8.2）")
        private final String rateBase;

        @Schema(description = "§8.2 列 I：本金计划，精确语义见设计文档 §8.2.1："
                + "EQUAL（均摊）/ FIXED（固定额）/ PERCENT（百分比）/ CUSTOM（逐期序列）/ "
                + "NONE（不还本）/ INHERIT（继承默认算法）",
                example = "INHERIT")
        private final String principalPlan;

        @Schema(description = "§8.2 列 J：配合 principalPlan 使用（金额/百分比/逐期序列）")
        private final BigDecimal principalValue;

        @Schema(description = "§8.2 列 J 的逐期序列形式（principalPlan=CUSTOM 时，逗号分隔）")
        private final String principalSequence;

        @Schema(description = "§8.2 列 K：利息处理（PAY_AS_INCURRED 等，见设计文档 §8.2）",
                example = "PAY_AS_INCURRED")
        private final String interestTreatment;

        @Schema(description = "§8.2 列 L：付款时点（IN_ARREARS 等）", example = "IN_ARREARS")
        private final String paymentTiming;

        @Schema(description = "§8.2 列 M：特殊动作（NONE 等；FEE_AMORTIZE/DEPOSIT_OFFSET "
                + "v1 未实现，命中即 FCS-RULE-2005，见勘误）", example = "NONE")
        private final String specialAction;

        @Schema(description = "§8.2 列 N：动作参数（JSON 片段或数值）")
        private final String actionParam;

        @Schema(description = "§8.2 列 C：阶段序号，仅用于可读性", example = "1")
        private final Integer stage;

        @Schema(description = "§8.2 列 D：生效条件（受限表达式，如 period >= 1 && period <= 12）")
        private final String condition;

        @Schema(description = "§8.2 列 O：备注，进入底稿")
        private final String memo;

        @Schema(description = "来源行号（Excel 对账溯源用，缺省 0）", example = "0")
        private final Integer rowNumber;

        @Schema(description = "来源单元格引用（Excel 对账溯源用，缺省为空）", example = "B3")
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
