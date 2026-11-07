package io.github.cuukenn.fincalc.domain.support.daycount;

import io.github.cuukenn.fincalc.common.date.DateUtils;
import io.github.cuukenn.fincalc.common.money.MoneyScale;
import io.github.cuukenn.fincalc.domain.port.DayCountConvention;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Actual/365 Fixed: actual days over a 365-day year, leap days included. */
public final class Act365F implements DayCountConvention {

    @Override
    public String code() {
        return "ACT_365F";
    }

    @Override
    public int days(LocalDate start, LocalDate end) {
        DayCountRanges.requireOrdered(start, end);
        return DateUtils.daysBetween(start, end);
    }

    @Override
    public BigDecimal yearFraction(LocalDate start, LocalDate end) {
        return BigDecimal.valueOf(days(start, end))
                .divide(BigDecimal.valueOf(365), MoneyScale.CALC, MoneyScale.ROUNDING);
    }
}
