package io.github.cuukenn.fincalc.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * Response body of {@code POST /api/v1/calculations} (Plan 2 Task 2, R76): a hand-mapped,
 * Jackson-serializable view of the engine's {@code CalculationResult}.
 *
 * <p>Serialization rulings:
 *
 * <ul>
 *   <li><b>Money (and rates) are JSON strings</b> — every {@code BigDecimal} becomes its
 *       {@code toPlainString()} form. This is the financially safe choice: JSON numbers are
 *       parsed as binary doubles by most clients, which corrupts exact decimal amounts on the
 *       round trip; strings preserve the computed scale and digits verbatim. Integral counts
 *       ({@code periods}, {@code periodNo}, {@code days}, {@code sequence}) stay JSON numbers.</li>
 *   <li><b>Nulls are preserved</b> — a metric that is not computable (or a missing VAT split,
 *       memo or §9.7 table) serializes as an explicit {@code null}, never as a dropped field.</li>
 *   <li><b>Dates are ISO-8601 strings</b> ({@code yyyy-MM-dd}), mapped by hand.</li>
 *   <li><b>Enums are their {@code name()}</b> strings.</li>
 * </ul>
 *
 * <p>Records-style final classes with standard getters (JDK 11: no records); no Jackson
 * annotations are needed — the getters carry the JSON property names. {@code effectiveInterest}
 * is nullable by design: IRR non-convergence is an expected outcome. The {@code @Schema}
 * documentation lives here only (Plan 2 Task 3); the core result types stay annotation-free
 * (R76).
 */
@Schema(description = "POST /api/v1/calculations 的响应体（引擎 CalculationResult 的手写映射，R76）。"
        + "金额与利率一律为 JSON 字符串（BigDecimal.toPlainString()，避免客户端按二进制浮点解析"
        + "破坏精确小数）；期数/天数/序号等整数为 JSON 数值；日期为 ISO-8601 字符串；"
        + "不可计算的指标序列化为显式 null（字段不省略）。")
public final class CalculationResponseDto {

    private final String runId;
    private final ScheduleDto schedule;
    private final EffectiveInterestDto effectiveInterest;

    public CalculationResponseDto(String runId, ScheduleDto schedule,
            EffectiveInterestDto effectiveInterest) {
        this.runId = runId;
        this.schedule = schedule;
        this.effectiveInterest = effectiveInterest;
    }

    @Schema(description = "本次测算的运行标识（32 位十六进制，无连字符）",
            example = "b7c9d1e04f2a4c1e9a5f0d3b6f8a2c10",
            requiredMode = Schema.RequiredMode.REQUIRED)
    public String getRunId() {
        return runId;
    }

    @Schema(description = "租金表视图（表头摘要 + 逐期明细 + 现金流 + 校验问题）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    public ScheduleDto getSchedule() {
        return schedule;
    }

    @Schema(description = "§9.7 实际利率法摊销表；IRR 不收敛或不可建表时为 null（预期结果，非错误）")
    public EffectiveInterestDto getEffectiveInterest() {
        return effectiveInterest;
    }

    /** The schedule view: header summary, installments, cash flows and validation issues. */
    @Schema(description = "租金表视图：表头摘要、逐期租金明细、现金流事件与校验问题列表")
    public static final class ScheduleDto {

        private final HeaderDto header;
        private final List<InstallmentDto> installments;
        private final List<CashFlowDto> cashFlows;
        private final List<IssueDto> issues;
        private final boolean hasErrors;

        public ScheduleDto(HeaderDto header, List<InstallmentDto> installments,
                List<CashFlowDto> cashFlows, List<IssueDto> issues, boolean hasErrors) {
            this.header = header;
            this.installments = installments;
            this.cashFlows = cashFlows;
            this.issues = issues;
            this.hasErrors = hasErrors;
        }

        @Schema(description = "表头摘要（期数、日期区间、solver、金融指标）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        public HeaderDto getHeader() {
            return header;
        }

        @Schema(description = "逐期租金明细，长度 = 期数", requiredMode = Schema.RequiredMode.REQUIRED)
        public List<InstallmentDto> getInstallments() {
            return installments;
        }

        @Schema(description = "现金流事件序列（§9.4）；金额为正数，方向见 direction")
        public List<CashFlowDto> getCashFlows() {
            return cashFlows;
        }

        @Schema(description = "校验器发现的问题列表（可能为空数组）")
        public List<IssueDto> getIssues() {
            return issues;
        }

        @Schema(description = "问题列表中是否含 ERROR 级条目（true 表示租金表不守恒，需人工复核）")
        public boolean isHasErrors() {
            return hasErrors;
        }
    }

    /** Header summary; {@code metrics} carries every computed code, null when not computable. */
    @Schema(description = "表头摘要；metrics 为全部 25 项金融指标的 code → 值映射：设计 §9.5 的 21 项"
            + "加 R103 借款方视角的 4 项 BORROWER_*（仅 LESSEE 视角有值，LESSOR 下为 null）"
            + "（值为字符串，不可计算时为显式 null）")
    public static final class HeaderDto {

        private final int periods;
        private final String firstDate;
        private final String lastDate;
        private final String solver;
        private final Map<String, String> metrics;
        private final String roundingDifference;

        public HeaderDto(int periods, String firstDate, String lastDate, String solver,
                Map<String, String> metrics, String roundingDifference) {
            this.periods = periods;
            this.firstDate = firstDate;
            this.lastDate = lastDate;
            this.solver = solver;
            this.metrics = metrics;
            this.roundingDifference = roundingDifference;
        }

        @Schema(description = "期数", example = "12")
        public int getPeriods() {
            return periods;
        }

        @Schema(description = "首期起始日，ISO-8601", example = "2024-01-01")
        public String getFirstDate() {
            return firstDate;
        }

        @Schema(description = "末期结束日，ISO-8601", example = "2025-01-01")
        public String getLastDate() {
            return lastDate;
        }

        @Schema(description = "租金生成器/求解器标识（如 EQUAL_INSTALLMENT、SEGMENTED_RESOLVE）",
                example = "EQUAL_INSTALLMENT")
        public String getSolver() {
            return solver;
        }

        @Schema(description = "§9.5 金融指标（code → 值字符串），如 TOTAL_RENT=1032797.15、IRR；"
                + "金额均为字符串（money-as-string 约定）",
                example = "{\"TOTAL_RENT\": \"1032797.15\"}")
        public Map<String, String> getMetrics() {
            return metrics;
        }

        @Schema(description = "尾差调整额（末期/首期吸收的舍入差额，字符串）")
        public String getRoundingDifference() {
            return roundingDifference;
        }
    }

    /** One installment; the 16 fields of the domain {@code Installment}, amounts as strings. */
    @Schema(description = "一期租金明细（领域 Installment 的 16 字段映射）；"
            + "金额字段均为字符串（money-as-string）")
    public static final class InstallmentDto {

        private final int periodNo;
        private final String startDate;
        private final String endDate;
        private final int days;
        private final String openingBalance;
        private final String principalDue;
        private final String interestAccrued;
        private final String interestPaid;
        private final String fee;
        private final String tax;
        private final String rentDue;
        private final String closingBalance;
        private final String cumulativePrincipal;
        private final String cumulativeInterest;
        private final String appliedRate;
        private final String memo;

        public InstallmentDto(int periodNo, String startDate, String endDate, int days,
                String openingBalance, String principalDue, String interestAccrued,
                String interestPaid, String fee, String tax, String rentDue,
                String closingBalance, String cumulativePrincipal, String cumulativeInterest,
                String appliedRate, String memo) {
            this.periodNo = periodNo;
            this.startDate = startDate;
            this.endDate = endDate;
            this.days = days;
            this.openingBalance = openingBalance;
            this.principalDue = principalDue;
            this.interestAccrued = interestAccrued;
            this.interestPaid = interestPaid;
            this.fee = fee;
            this.tax = tax;
            this.rentDue = rentDue;
            this.closingBalance = closingBalance;
            this.cumulativePrincipal = cumulativePrincipal;
            this.cumulativeInterest = cumulativeInterest;
            this.appliedRate = appliedRate;
            this.memo = memo;
        }

        @Schema(description = "期次号（1 起）", example = "1")
        public int getPeriodNo() {
            return periodNo;
        }

        @Schema(description = "计息期间起始日，ISO-8601")
        public String getStartDate() {
            return startDate;
        }

        @Schema(description = "计息期间结束日，ISO-8601")
        public String getEndDate() {
            return endDate;
        }

        @Schema(description = "本期计息天数（按 dayCountCode 口径）", example = "30")
        public int getDays() {
            return days;
        }

        @Schema(description = "期初余额（字符串金额）")
        public String getOpeningBalance() {
            return openingBalance;
        }

        @Schema(description = "本期归还本金（字符串金额）")
        public String getPrincipalDue() {
            return principalDue;
        }

        @Schema(description = "本期计提利息（字符串金额）")
        public String getInterestAccrued() {
            return interestAccrued;
        }

        @Schema(description = "本期实付利息（字符串金额）")
        public String getInterestPaid() {
            return interestPaid;
        }

        @Schema(description = "本期费用（字符串金额）")
        public String getFee() {
            return fee;
        }

        @Schema(description = "本期税额（字符串金额）")
        public String getTax() {
            return tax;
        }

        @Schema(description = "本期租金（字符串金额；本金 + 利息 + 费用 + 税）")
        public String getRentDue() {
            return rentDue;
        }

        @Schema(description = "期末余额（字符串金额）")
        public String getClosingBalance() {
            return closingBalance;
        }

        @Schema(description = "累计已还本金（字符串金额）")
        public String getCumulativePrincipal() {
            return cumulativePrincipal;
        }

        @Schema(description = "累计已付利息（字符串金额）")
        public String getCumulativeInterest() {
            return cumulativeInterest;
        }

        @Schema(description = "本期适用期利率（字符串小数，如 0.005）")
        public String getAppliedRate() {
            return appliedRate;
        }

        @Schema(description = "备注（如尾差调整、规则行来源），可为 null")
        public String getMemo() {
            return memo;
        }
    }

    /** One cash flow event; the amount is the positive magnitude, sign lives in {@code direction}. */
    @Schema(description = "一笔现金流事件；amount 为正数幅值，方向由 direction 表达")
    public static final class CashFlowDto {

        private final int sequence;
        private final String date;
        private final String amount;
        private final String type;
        private final String direction;

        public CashFlowDto(int sequence, String date, String amount, String type,
                String direction) {
            this.sequence = sequence;
            this.date = date;
            this.amount = amount;
            this.type = type;
            this.direction = direction;
        }

        @Schema(description = "事件序号（0 起，含期初事件）", example = "0")
        public int getSequence() {
            return sequence;
        }

        @Schema(description = "事件日期，ISO-8601")
        public String getDate() {
            return date;
        }

        @Schema(description = "金额幅值（恒为正，字符串）")
        public String getAmount() {
            return amount;
        }

        @Schema(description = "事件类型（PRINCIPAL / INTEREST / FEE / RENT 等 name() 字符串）")
        public String getType() {
            return type;
        }

        @Schema(description = "方向：INFLOW（流入）/ OUTFLOW（流出）")
        public String getDirection() {
            return direction;
        }
    }

    /** One validation finding; {@code periodNo} is null for schedule-wide findings. */
    @Schema(description = "一条校验发现（§9 校验器输出）；periodNo 为 null 表示全表级问题")
    public static final class IssueDto {

        private final String level;
        private final String code;
        private final String message;
        private final Integer periodNo;

        public IssueDto(String level, String code, String message, Integer periodNo) {
            this.level = level;
            this.code = code;
            this.message = message;
            this.periodNo = periodNo;
        }

        @Schema(description = "级别（如 ERROR / WARN）", example = "WARN")
        public String getLevel() {
            return level;
        }

        @Schema(description = "问题 code（FCS-CALC-3001/3002 等错误码族）", example = "FCS-CALC-3001")
        public String getCode() {
            return code;
        }

        @Schema(description = "问题描述")
        public String getMessage() {
            return message;
        }

        @Schema(description = "关联期次号；null = 全表级发现")
        public Integer getPeriodNo() {
            return periodNo;
        }
    }

    /** The §9.7 effective-interest table; the whole object is null when none could be built. */
    @Schema(description = "§9.7 实际利率法摊销表；整体可为 null（IRR 不收敛或场景不适用）")
    public static final class EffectiveInterestDto {

        private final List<EffectiveInterestRowDto> rows;
        private final String eir;
        private final String totalInterestDifference;

        public EffectiveInterestDto(List<EffectiveInterestRowDto> rows, String eir,
                String totalInterestDifference) {
            this.rows = rows;
            this.eir = eir;
            this.totalInterestDifference = totalInterestDifference;
        }

        @Schema(description = "逐期摊销行", requiredMode = Schema.RequiredMode.REQUIRED)
        public List<EffectiveInterestRowDto> getRows() {
            return rows;
        }

        @Schema(description = "实际利率 EIR（周期利率，字符串小数）", example = "0.005083")
        public String getEir() {
            return eir;
        }

        @Schema(description = "利息总额调整差（实际利率法 vs 名义口径，字符串金额）")
        public String getTotalInterestDifference() {
            return totalInterestDifference;
        }
    }

    /** One row of the §9.7 table; amounts as strings. */
    @Schema(description = "§9.7 摊销表的一行；金额均为字符串（money-as-string）")
    public static final class EffectiveInterestRowDto {

        private final int periodNo;
        private final String openingNetInvestment;
        private final String interestIncome;
        private final String cashReceived;
        private final String principalRecovery;
        private final String closingNetInvestment;

        public EffectiveInterestRowDto(int periodNo, String openingNetInvestment,
                String interestIncome, String cashReceived, String principalRecovery,
                String closingNetInvestment) {
            this.periodNo = periodNo;
            this.openingNetInvestment = openingNetInvestment;
            this.interestIncome = interestIncome;
            this.cashReceived = cashReceived;
            this.principalRecovery = principalRecovery;
            this.closingNetInvestment = closingNetInvestment;
        }

        @Schema(description = "期次号（1 起）", example = "1")
        public int getPeriodNo() {
            return periodNo;
        }

        @Schema(description = "期初净投资（字符串金额）")
        public String getOpeningNetInvestment() {
            return openingNetInvestment;
        }

        @Schema(description = "本期利息收入（EIR × 期初净投资，字符串金额）")
        public String getInterestIncome() {
            return interestIncome;
        }

        @Schema(description = "本期收到现金（字符串金额）")
        public String getCashReceived() {
            return cashReceived;
        }

        @Schema(description = "本期本金回收（字符串金额）")
        public String getPrincipalRecovery() {
            return principalRecovery;
        }

        @Schema(description = "期末净投资（字符串金额；fully-amortizing/BALLOON 场景末期归零）")
        public String getClosingNetInvestment() {
            return closingNetInvestment;
        }
    }
}
