package io.github.cuukenn.fincalc.server.dto;

import io.github.cuukenn.fincalc.domain.metric.FinancialMetrics;
import io.github.cuukenn.fincalc.domain.model.CashFlow;
import io.github.cuukenn.fincalc.domain.model.EffectiveInterestRow;
import io.github.cuukenn.fincalc.domain.model.EffectiveInterestTable;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.model.ValidationIssue;
import io.github.cuukenn.fincalc.engine.CalculationResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written R76 mapping: engine {@link CalculationResult} →
 * {@link CalculationResponseDto}.
 *
 * <p>Serialization rulings live on the DTO: {@code BigDecimal} → {@code toPlainString()}
 * (money/rates as JSON strings), {@code LocalDate} → ISO string, enums → {@code name()},
 * nulls preserved (metrics not computable → explicit null entry in the metrics map;
 * {@code effectiveInterest} → null object).
 */
public final class CalculationResponseAssembler {

    /**
     * Maps one engine result onto the response DTO.
     *
     * @param result the engine output; never {@code null}
     * @return the serializable response; {@code effectiveInterest} is null when the engine
     *         could not build the §9.7 table
     */
    public CalculationResponseDto assemble(CalculationResult result) {
        return new CalculationResponseDto(
                result.runId(),
                toSchedule(result.schedule()),
                toEffectiveInterest(result.effectiveInterest()));
    }

    private CalculationResponseDto.ScheduleDto toSchedule(Schedule schedule) {
        return new CalculationResponseDto.ScheduleDto(
                toHeader(schedule.header()),
                toInstallments(schedule.installments()),
                toCashFlows(schedule.cashFlows()),
                toIssues(schedule.issues()),
                schedule.hasErrors());
    }

    private CalculationResponseDto.HeaderDto toHeader(
            io.github.cuukenn.fincalc.domain.model.ScheduleHeader header) {
        FinancialMetrics metrics = header.metrics();
        Map<String, String> mapped = new LinkedHashMap<>();
        for (String code : metrics.codes()) {
            mapped.put(code, money(metrics.find(code)));
        }
        return new CalculationResponseDto.HeaderDto(
                header.periods(),
                date(header.firstDate()),
                date(header.lastDate()),
                header.solver(),
                mapped,
                money(header.roundingDifference()));
    }

    private List<CalculationResponseDto.InstallmentDto> toInstallments(List<Installment> rows) {
        List<CalculationResponseDto.InstallmentDto> dtos = new ArrayList<>(rows.size());
        for (Installment row : rows) {
            dtos.add(new CalculationResponseDto.InstallmentDto(
                    row.periodNo(),
                    date(row.startDate()),
                    date(row.endDate()),
                    row.days(),
                    money(row.openingBalance()),
                    money(row.principalDue()),
                    money(row.interestAccrued()),
                    money(row.interestPaid()),
                    money(row.fee()),
                    money(row.tax()),
                    money(row.rentDue()),
                    money(row.closingBalance()),
                    money(row.cumulativePrincipal()),
                    money(row.cumulativeInterest()),
                    money(row.appliedRate()),
                    row.memo()));
        }
        return dtos;
    }

    private List<CalculationResponseDto.CashFlowDto> toCashFlows(List<CashFlow> flows) {
        List<CalculationResponseDto.CashFlowDto> dtos = new ArrayList<>(flows.size());
        for (CashFlow flow : flows) {
            dtos.add(new CalculationResponseDto.CashFlowDto(
                    flow.sequence(),
                    date(flow.date()),
                    money(flow.amount()),
                    flow.type().name(),
                    flow.direction().name()));
        }
        return dtos;
    }

    private List<CalculationResponseDto.IssueDto> toIssues(List<ValidationIssue> issues) {
        List<CalculationResponseDto.IssueDto> dtos = new ArrayList<>(issues.size());
        for (ValidationIssue issue : issues) {
            dtos.add(new CalculationResponseDto.IssueDto(
                    issue.level().name(),
                    issue.code(),
                    issue.message(),
                    issue.periodNo()));
        }
        return dtos;
    }

    private CalculationResponseDto.EffectiveInterestDto toEffectiveInterest(
            EffectiveInterestTable table) {
        if (table == null) {
            return null;
        }
        List<CalculationResponseDto.EffectiveInterestRowDto> rows =
                new ArrayList<>(table.rows().size());
        for (EffectiveInterestRow row : table.rows()) {
            rows.add(new CalculationResponseDto.EffectiveInterestRowDto(
                    row.periodNo(),
                    money(row.openingNetInvestment()),
                    money(row.interestIncome()),
                    money(row.cashReceived()),
                    money(row.principalRecovery()),
                    money(row.closingNetInvestment())));
        }
        return new CalculationResponseDto.EffectiveInterestDto(
                rows,
                money(table.eir()),
                money(table.totalInterestDifference()));
    }

    private static String money(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static String date(LocalDate value) {
        return value == null ? null : value.toString();
    }
}
