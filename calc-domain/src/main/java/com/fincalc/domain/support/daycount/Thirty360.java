package com.fincalc.domain.support.daycount;

import com.fincalc.common.money.MoneyScale;
import com.fincalc.domain.port.DayCountConvention;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 30/360 (US, NASD): every month counts as 30 days.
 *
 * <p>A start day of 31 becomes 30; an end day of 31 becomes 30 only when the start was already 30.
 */
public final class Thirty360 implements DayCountConvention {

    @Override
    public String code() {
        return "THIRTY_360";
    }

    @Override
    public int days(LocalDate start, LocalDate end) {
        int d1 = start.getDayOfMonth();
        int d2 = end.getDayOfMonth();
        if (d1 == 31) {
            d1 = 30;
        }
        if (d2 == 31 && d1 == 30) {
            d2 = 30;
        }
        return 360 * (end.getYear() - start.getYear())
                + 30 * (end.getMonthValue() - start.getMonthValue())
                + (d2 - d1);
    }

    @Override
    public BigDecimal yearFraction(LocalDate start, LocalDate end) {
        return BigDecimal.valueOf(days(start, end))
                .divide(BigDecimal.valueOf(360), MoneyScale.CALC, MoneyScale.ROUNDING);
    }
}
