package io.github.cuukenn.fincalc.domain.support.daycount;

import io.github.cuukenn.fincalc.common.date.DateUtils;
import io.github.cuukenn.fincalc.common.money.MoneyScale;
import io.github.cuukenn.fincalc.domain.port.DayCountConvention;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Actual/365.25: actual days over the mean Julian year. */
public final class Act36525 implements DayCountConvention {

    private static final BigDecimal BASIS = new BigDecimal("365.25");

    @Override
    public String code() {
        return "ACT_365_25";
    }

    @Override
    public int days(LocalDate start, LocalDate end) {
        DayCountRanges.requireOrdered(start, end);
        return DateUtils.daysBetween(start, end);
    }

    @Override
    public BigDecimal yearFraction(LocalDate start, LocalDate end) {
        return BigDecimal.valueOf(days(start, end))
                .divide(BASIS, MoneyScale.CALC, MoneyScale.ROUNDING);
    }
}
