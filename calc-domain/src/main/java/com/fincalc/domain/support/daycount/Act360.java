package com.fincalc.domain.support.daycount;

import com.fincalc.common.money.MoneyScale;
import com.fincalc.common.money.MoneyUtils;
import com.fincalc.domain.port.DayCountConvention;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Actual/360: actual days over a 360-day year. */
public final class Act360 implements DayCountConvention {

    @Override
    public String code() {
        return "ACT_360";
    }

    @Override
    public int days(LocalDate start, LocalDate end) {
        return Math.toIntExact(ChronoUnit.DAYS.between(start, end));
    }

    @Override
    public BigDecimal yearFraction(LocalDate start, LocalDate end) {
        return MoneyUtils.scale(BigDecimal.valueOf(days(start, end))
                .divide(BigDecimal.valueOf(360), MoneyScale.CALC, MoneyScale.ROUNDING), MoneyScale.CALC);
    }
}
