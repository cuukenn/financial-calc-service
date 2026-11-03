package com.fincalc.domain.support.daycount;

import com.fincalc.common.money.MoneyScale;
import com.fincalc.domain.port.DayCountConvention;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Actual/365.25: actual days over the mean Julian year. */
public final class Act36525 implements DayCountConvention {

    private static final BigDecimal BASIS = new BigDecimal("365.25");

    @Override
    public String code() {
        return "ACT_365_25";
    }

    @Override
    public int days(LocalDate start, LocalDate end) {
        return Math.toIntExact(ChronoUnit.DAYS.between(start, end));
    }

    @Override
    public BigDecimal yearFraction(LocalDate start, LocalDate end) {
        return BigDecimal.valueOf(days(start, end))
                .divide(BASIS, MoneyScale.CALC, MoneyScale.ROUNDING);
    }
}
