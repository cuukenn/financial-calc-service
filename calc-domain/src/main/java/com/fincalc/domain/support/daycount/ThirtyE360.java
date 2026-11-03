package com.fincalc.domain.support.daycount;

import com.fincalc.common.money.MoneyScale;
import com.fincalc.domain.port.DayCountConvention;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 30E/360 (Eurobond): both endpoints clamp day 31 to 30, symmetrically. */
public final class ThirtyE360 implements DayCountConvention {

    @Override
    public String code() {
        return "THIRTY_E_360";
    }

    @Override
    public int days(LocalDate start, LocalDate end) {
        DayCountRanges.requireOrdered(start, end);
        int d1 = Math.min(start.getDayOfMonth(), 30);
        int d2 = Math.min(end.getDayOfMonth(), 30);
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
