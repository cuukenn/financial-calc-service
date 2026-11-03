package com.fincalc.domain.support.daycount;

import com.fincalc.common.money.MoneyScale;
import com.fincalc.domain.port.DayCountConvention;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Actual/365 Fixed: actual days over a 365-day year, leap days included. */
public final class Act365F implements DayCountConvention {

    @Override
    public String code() {
        return "ACT_365F";
    }

    @Override
    public int days(LocalDate start, LocalDate end) {
        return Math.toIntExact(ChronoUnit.DAYS.between(start, end));
    }

    @Override
    public BigDecimal yearFraction(LocalDate start, LocalDate end) {
        return BigDecimal.valueOf(days(start, end))
                .divide(BigDecimal.valueOf(365), MoneyScale.CALC, MoneyScale.ROUNDING);
    }
}
