package com.fincalc.rule.function.datex;

import com.fincalc.common.error.BusinessException;
import com.fincalc.rule.expression.FormulaError;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * The Excel 1900 date system as pure arithmetic over {@code BigDecimal} serial numbers (design
 * §8.5: 默认 1900 系统并兼容该 bug). Every date in the rule DSL is a NUMBER serial — this class is
 * the single converter between serials and {@link LocalDate}, used by every {@code date}
 * family function.
 *
 * <p><b>Serial semantics, pinned by test.</b> Serial 1 is 1900-01-01 and serials count calendar
 * days — but Excel (deliberately, for Lotus 1-2-3 compatibility) treats 1900 as a leap year, so
 * the phantom 1900-02-29 occupies serial 60 and every date from 1900-03-01 onward carries a +1
 * shift: serial(1900-02-28) = 59, serial(1900-03-01) = 61, serial(2024-01-01) = 45292,
 * serial(2024-02-29) = 45351.
 *
 * <p><b>Phantom-day scope choice (documented deviation).</b> Excel renders serial 60 as the
 * nonexistent 1900-02-29 and answers {@code YEAR(60)=1900, MONTH(60)=2, DAY(60)=29}. {@code
 * LocalDate} cannot represent that day, and fabricating component semantics outside the shared
 * converter would fork the serial system. This implementation therefore rejects serial 60 (and any
 * serial below 1) with {@code FCS-RULE-3007} ({@code #NUM!}) instead of silently shifting it to
 * 1900-03-01; formulas never reach it through {@link DateFunctions} because
 * {@link DateSerialize#toSerial} cannot produce it.
 *
 * <p>Serials are whole-day numbers in the DSL (no time-of-day fraction); a fractional serial is
 * truncated toward zero to its day part. The supported range is Excel's: serial 1 through
 * 2958465 (9999-12-31).
 */
public final class DateSerialize {

    /** The day before serial 1; serial counts days after it. */
    private static final LocalDate SERIAL_ORIGIN = LocalDate.of(1899, 12, 31);

    /** Last date the +1 phantom shift applies before. */
    private static final LocalDate LAST_PRE_PHANTOM = LocalDate.of(1900, 2, 28);

    /** Excel's maximum serial: 9999-12-31. */
    public static final BigDecimal MAX_SERIAL = BigDecimal.valueOf(2958465);

    /** The phantom 1900-02-29; representable in Excel's serial space but not as a LocalDate. */
    private static final BigDecimal PHANTOM_SERIAL = BigDecimal.valueOf(60);

    private DateSerialize() {
    }

    /**
     * The Excel 1900 serial of a date.
     *
     * @param date a date within 1900-01-01..9999-12-31
     * @return the serial, 1 through 2958465; never the phantom 60
     * @throws BusinessException with {@link FormulaError#NUM_ERROR} when the date is outside the
     *                          Excel 1900 system's range
     */
    public static BigDecimal toSerial(LocalDate date) {
        Objects.requireNonNull(date, "date");
        if (date.isBefore(LocalDate.of(1900, 1, 1)) || date.isAfter(LocalDate.of(9999, 12, 31))) {
            throw new BusinessException(FormulaError.NUM_ERROR);
        }
        long serial = ChronoUnit.DAYS.between(SERIAL_ORIGIN, date);
        // Excel's 1900 leap-year bug: from 1900-03-01 on, every date sits one serial higher.
        if (date.isAfter(LAST_PRE_PHANTOM)) {
            serial++;
        }
        return BigDecimal.valueOf(serial);
    }

    /**
     * The date behind an Excel 1900 serial.
     *
     * @param serial a whole-day serial; a fractional part (a time-of-day Excel would carry) is
     *               truncated toward zero
     * @return the date
     * @throws BusinessException with {@link FormulaError#NUM_ERROR} when the serial is below 1,
     *                          above {@link #MAX_SERIAL}, or is the phantom 60 (no LocalDate
     *                          representation, see the class javadoc)
     */
    public static LocalDate toLocalDate(BigDecimal serial) {
        Objects.requireNonNull(serial, "serial");
        long day = serial.setScale(0, java.math.RoundingMode.DOWN).longValueExact();
        if (day < 1 || serial.compareTo(MAX_SERIAL) > 0
                || serial.compareTo(PHANTOM_SERIAL) == 0) {
            throw new BusinessException(FormulaError.NUM_ERROR);
        }
        // Serials after the phantom day map to one day earlier than their raw count from the
        // origin, because Excel burned serial 60 on the nonexistent 1900-02-29.
        return SERIAL_ORIGIN.plusDays(day <= 59 ? day : day - 1);
    }
}
