package com.fincalc.domain.support.daycount;

import com.fincalc.common.money.MoneyScale;
import com.fincalc.domain.port.DayCountConvention;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Actual/Actual (ISDA): the range is split at every calendar-year boundary and each segment is
 * divided by the number of days in its own year, so a leap year contributes 366 denominator days.
 */
public final class ActAct implements DayCountConvention {

    private static final int INTERMEDIATE_SCALE = MoneyScale.CALC + 4;

    @Override
    public String code() {
        return "ACT_ACT";
    }

    @Override
    public int days(LocalDate start, LocalDate end) {
        return Math.toIntExact(ChronoUnit.DAYS.between(start, end));
    }

    @Override
    public BigDecimal yearFraction(LocalDate start, LocalDate end) {
        if (!end.isAfter(start)) {
            return BigDecimal.ZERO.setScale(MoneyScale.CALC);
        }
        BigDecimal total = BigDecimal.ZERO;
        LocalDate cursor = start;
        while (cursor.isBefore(end)) {
            LocalDate nextYear = LocalDate.of(cursor.getYear() + 1, 1, 1);
            LocalDate segmentEnd = nextYear.isBefore(end) ? nextYear : end;
            int segmentDays = (int) ChronoUnit.DAYS.between(cursor, segmentEnd);
            int daysInYear = cursor.isLeapYear() ? 366 : 365;
            total = total.add(BigDecimal.valueOf(segmentDays)
                    .divide(BigDecimal.valueOf(daysInYear), INTERMEDIATE_SCALE, RoundingMode.HALF_UP));
            cursor = segmentEnd;
        }
        return total.setScale(MoneyScale.CALC, MoneyScale.ROUNDING);
    }
}
