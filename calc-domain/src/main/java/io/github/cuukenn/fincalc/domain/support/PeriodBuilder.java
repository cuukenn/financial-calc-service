package io.github.cuukenn.fincalc.domain.support;

import io.github.cuukenn.fincalc.common.date.DateUtils;
import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.domain.model.DomainErrorCode;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.PaymentFrequency;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.model.PeriodDate;
import io.github.cuukenn.fincalc.domain.port.DateRollConvention;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns a lease's period definition into concrete start/end/payment dates.
 *
 * <p>Regular schedules anchor on {@code firstRentDate}: period {@code k} nominally ends at
 * {@code firstRentDate + (k-1) x monthsPerPeriod}. The first period starts at {@code commenceDate}
 * and every later period starts where the previous one ended, so the schedule is gap-free by
 * construction.
 *
 * <p>Irregular schedules return {@code element.explicitPeriods()} after re-validating it.
 */
public final class PeriodBuilder {

    private PeriodBuilder() {
    }

    /**
     * Builds the period list for one lease.
     *
     * @param element lease elements; its period definition must already be valid
     * @param roll    convention applied to each nominal period end
     * @return an unmodifiable, strictly increasing period list
     * @throws BusinessException {@code FCS-DOM-2001} when a period is empty, reversed or overlaps
     */
    public static List<PeriodDate> build(LeaseElement element, DateRollConvention roll) {
        if (element.frequency() == PaymentFrequency.IRREGULAR) {
            return validate(element.explicitPeriods());
        }

        int periods = element.periods();
        int monthsPerPeriod = element.frequency().monthsPerPeriod();
        LocalDate anchor = element.firstRentDate();
        LocalDate start = element.commenceDate();

        List<PeriodDate> result = new ArrayList<>(periods);
        LocalDate previousEnd = null;
        for (int k = 1; k <= periods; k++) {
            LocalDate nominalEnd = DateUtils.plusMonthsKeepEom(anchor, (k - 1) * monthsPerPeriod);
            LocalDate end = roll.roll(nominalEnd);
            requireOrdered(k, start, end, previousEnd);
            LocalDate paymentDate = element.timing() == PaymentTiming.IN_ARREARS ? end : start;
            result.add(PeriodDate.builder()
                    .startDate(start)
                    .endDate(end)
                    .paymentDate(paymentDate)
                    .build());
            previousEnd = end;
            start = end;
        }
        return Collections.unmodifiableList(result);
    }

    private static List<PeriodDate> validate(List<PeriodDate> explicitPeriods) {
        LocalDate previousEnd = null;
        for (int index = 0; index < explicitPeriods.size(); index++) {
            PeriodDate period = explicitPeriods.get(index);
            requireOrdered(index + 1, period.startDate(), period.endDate(), previousEnd);
            previousEnd = period.endDate();
        }
        return Collections.unmodifiableList(new ArrayList<>(explicitPeriods));
    }

    private static void requireOrdered(int periodNo, LocalDate start, LocalDate end, LocalDate previousEnd) {
        boolean reversed = start == null || end == null || !start.isBefore(end);
        boolean overlaps = start != null && previousEnd != null && start.isBefore(previousEnd);
        if (reversed || overlaps) {
            throw new BusinessException(DomainErrorCode.PERIOD_SEQUENCE_INVALID, periodNo, start, end);
        }
    }
}
