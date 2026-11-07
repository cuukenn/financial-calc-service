package io.github.cuukenn.fincalc.domain.support.daycount;

import io.github.cuukenn.fincalc.common.date.DateUtils;
import io.github.cuukenn.fincalc.common.money.MoneyScale;
import io.github.cuukenn.fincalc.domain.port.DayCountConvention;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Actual/360: actual days over a 360-day year. */
public final class Act360 implements DayCountConvention {

    @Override
    public String code() {
        return "ACT_360";
    }

    @Override
    public int days(LocalDate start, LocalDate end) {
        DayCountRanges.requireOrdered(start, end);
        return DateUtils.daysBetween(start, end);
    }

    @Override
    public BigDecimal yearFraction(LocalDate start, LocalDate end) {
        return BigDecimal.valueOf(days(start, end))
                .divide(BigDecimal.valueOf(360), MoneyScale.CALC, MoneyScale.ROUNDING);
    }
}
