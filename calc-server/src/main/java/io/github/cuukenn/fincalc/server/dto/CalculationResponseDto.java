package io.github.cuukenn.fincalc.server.dto;

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
 * is nullable by design: IRR non-convergence is an expected outcome.
 */
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

    public String getRunId() {
        return runId;
    }

    public ScheduleDto getSchedule() {
        return schedule;
    }

    public EffectiveInterestDto getEffectiveInterest() {
        return effectiveInterest;
    }

    /** The schedule view: header summary, installments, cash flows and validation issues. */
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

        public HeaderDto getHeader() {
            return header;
        }

        public List<InstallmentDto> getInstallments() {
            return installments;
        }

        public List<CashFlowDto> getCashFlows() {
            return cashFlows;
        }

        public List<IssueDto> getIssues() {
            return issues;
        }

        public boolean isHasErrors() {
            return hasErrors;
        }
    }

    /** Header summary; {@code metrics} carries every computed code, null when not computable. */
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

        public int getPeriods() {
            return periods;
        }

        public String getFirstDate() {
            return firstDate;
        }

        public String getLastDate() {
            return lastDate;
        }

        public String getSolver() {
            return solver;
        }

        public Map<String, String> getMetrics() {
            return metrics;
        }

        public String getRoundingDifference() {
            return roundingDifference;
        }
    }

    /** One installment; the 16 fields of the domain {@code Installment}, amounts as strings. */
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

        public int getPeriodNo() {
            return periodNo;
        }

        public String getStartDate() {
            return startDate;
        }

        public String getEndDate() {
            return endDate;
        }

        public int getDays() {
            return days;
        }

        public String getOpeningBalance() {
            return openingBalance;
        }

        public String getPrincipalDue() {
            return principalDue;
        }

        public String getInterestAccrued() {
            return interestAccrued;
        }

        public String getInterestPaid() {
            return interestPaid;
        }

        public String getFee() {
            return fee;
        }

        public String getTax() {
            return tax;
        }

        public String getRentDue() {
            return rentDue;
        }

        public String getClosingBalance() {
            return closingBalance;
        }

        public String getCumulativePrincipal() {
            return cumulativePrincipal;
        }

        public String getCumulativeInterest() {
            return cumulativeInterest;
        }

        public String getAppliedRate() {
            return appliedRate;
        }

        public String getMemo() {
            return memo;
        }
    }

    /** One cash flow event; the amount is the positive magnitude, sign lives in {@code direction}. */
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

        public int getSequence() {
            return sequence;
        }

        public String getDate() {
            return date;
        }

        public String getAmount() {
            return amount;
        }

        public String getType() {
            return type;
        }

        public String getDirection() {
            return direction;
        }
    }

    /** One validation finding; {@code periodNo} is null for schedule-wide findings. */
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

        public String getLevel() {
            return level;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        public Integer getPeriodNo() {
            return periodNo;
        }
    }

    /** The §9.7 effective-interest table; the whole object is null when none could be built. */
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

        public List<EffectiveInterestRowDto> getRows() {
            return rows;
        }

        public String getEir() {
            return eir;
        }

        public String getTotalInterestDifference() {
            return totalInterestDifference;
        }
    }

    /** One row of the §9.7 table; amounts as strings. */
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

        public int getPeriodNo() {
            return periodNo;
        }

        public String getOpeningNetInvestment() {
            return openingNetInvestment;
        }

        public String getInterestIncome() {
            return interestIncome;
        }

        public String getCashReceived() {
            return cashReceived;
        }

        public String getPrincipalRecovery() {
            return principalRecovery;
        }

        public String getClosingNetInvestment() {
            return closingNetInvestment;
        }
    }
}
