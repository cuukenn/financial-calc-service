package io.github.cuukenn.fincalc.rule.function.datex;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.rule.expression.AstNode;
import io.github.cuukenn.fincalc.rule.expression.EvalContext;
import io.github.cuukenn.fincalc.rule.expression.FormulaError;
import io.github.cuukenn.fincalc.rule.expression.Value;
import io.github.cuukenn.fincalc.rule.function.FunctionRegistry;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The v1 date family (design §8.5): 10 Excel-compatible functions over the Excel 1900 serial
 * system (see {@link DateSerialize}). Dates are NUMBER serials end to end; every conversion goes
 * through {@link DateSerialize} so the phantom-1900-02-29 handling lives in exactly one place.
 *
 * <p>Excel overflow semantics: {@code DATE} accepts month/day overflow
 * ({@code DATE(2024,13,1)} is 2025-01-01, {@code DATE(2024,1,0)} is 2023-12-31) and treats a year
 * below 1900 as 1900+year ({@code DATE(24,1,1)} is 1924-01-01). {@code DAYS(end, start)} follows
 * Excel's argument order (end first). {@code DAYS360} implements the US (NASD) method by default
 * and the European method when the third argument is true; {@code YEARFRAC} implements bases 0
 * (US 30/360), 1 (actual/actual, Excel's algorithm), 2 (actual/360), 3 (actual/365) and 4
 * (European 30/360) — an out-of-range basis is {@code #NUM!}, never a silent mis-computation.
 * {@code DATEVALUE} is a documented pragmatic subset: ISO {@code yyyy-MM-dd} and
 * {@code yyyy/M/d} text only.
 *
 * <p>Every function is stateless and pure; failures surface as {@code ERROR} values.
 */
public final class DateFunctions {

    private static final MathContext MC = MathContext.DECIMAL128;

    private DateFunctions() {
    }

    /** Registers the 10 date functions. */
    public static FunctionRegistry register(FunctionRegistry registry) {
        return registry
                .register(new Date())
                .register(new Year())
                .register(new Month())
                .register(new Day())
                .register(new Edate())
                .register(new Eomonth())
                .register(new Days())
                .register(new Days360())
                .register(new YearFrac())
                .register(new DateValue());
    }

    /** Template shared by every date function: coerce failures into {@code ERROR} values. */
    private abstract static class Fn implements io.github.cuukenn.fincalc.rule.function.FormulaFunction {

        private final String code;
        private final int minArity;
        private final int maxArity;

        Fn(String code, int minArity, int maxArity) {
            this.code = code;
            this.minArity = minArity;
            this.maxArity = maxArity;
        }

        @Override
        public final String code() {
            return code;
        }

        @Override
        public final int minArity() {
            return minArity;
        }

        @Override
        public final int maxArity() {
            return maxArity;
        }

        /**
         * Runs the function, converting every failure into an {@code ERROR} value. Besides
         * coercion ({@code BusinessException}) and arithmetic failures, this catches
         * {@link java.time.DateTimeException}: date inputs a caller can plausibly write —
         * {@code DATEVALUE("2024/2/30")}, {@code DATEVALUE("2024/13/1")}, a month shift that
         * overflows the calendar — throw it from {@code LocalDate.of}/{@code plusMonths}, and the
         * error-as-value model (design §8.5) requires them to surface as {@code #VALUE!} rather
         * than crash evaluation. Future date functions inherit this guard through the template.
         */
        @Override
        public final Value invoke(List<Value> args, EvalContext context) {
            try {
                return apply(args);
            } catch (BusinessException ex) {
                return AstNode.errorFrom(ex);
            } catch (ArithmeticException ex) {
                return Value.error(FormulaError.NUM_ERROR);
            } catch (java.time.DateTimeException ex) {
                return Value.error(FormulaError.VALUE_ERROR);
            }
        }

        abstract Value apply(List<Value> args);
    }

    private static BigDecimal num(List<Value> args, int index) {
        return args.get(index).asNumber();
    }

    private static int toInt(BigDecimal value) {
        return value.setScale(0, RoundingMode.DOWN).intValueExact();
    }

    private static LocalDate toLocalDateOrThrow(BigDecimal serial) {
        return DateSerialize.toLocalDate(serial);
    }

    private static BigDecimal serialOf(LocalDate date) {
        return DateSerialize.toSerial(date);
    }

    private static class Date extends Fn {
        Date() {
            super("DATE", 3, 3);
        }

        @Override
        Value apply(List<Value> args) {
            long year = num(args, 0).setScale(0, RoundingMode.DOWN).longValueExact();
            // Excel: a year below 1900 means 1900+year.
            if (year < 1900) {
                year += 1900;
            }
            int monthShift = toInt(num(args, 1)) - 1;
            int dayShift = toInt(num(args, 2)) - 1;
            try {
                LocalDate date = LocalDate.of((int) year, 1, 1)
                        .plusMonths(monthShift)
                        .plusDays(dayShift);
                return Value.number(serialOf(date));
            } catch (java.time.DateTimeException ex) {
                return Value.error(FormulaError.NUM_ERROR);
            }
        }
    }

    private static class Year extends Fn {
        Year() {
            super("YEAR", 1, 1);
        }

        @Override
        Value apply(List<Value> args) {
            return Value.number(BigDecimal.valueOf(
                    toLocalDateOrThrow(num(args, 0)).getYear()));
        }
    }

    private static class Month extends Fn {
        Month() {
            super("MONTH", 1, 1);
        }

        @Override
        Value apply(List<Value> args) {
            return Value.number(BigDecimal.valueOf(
                    toLocalDateOrThrow(num(args, 0)).getMonthValue()));
        }
    }

    private static class Day extends Fn {
        Day() {
            super("DAY", 1, 1);
        }

        @Override
        Value apply(List<Value> args) {
            return Value.number(BigDecimal.valueOf(
                    toLocalDateOrThrow(num(args, 0)).getDayOfMonth()));
        }
    }

    private static class Edate extends Fn {
        Edate() {
            super("EDATE", 2, 2);
        }

        @Override
        Value apply(List<Value> args) {
            LocalDate date = toLocalDateOrThrow(num(args, 0));
            // LocalDate.plusMonths clamps the day of month exactly like Excel's EDATE.
            return Value.number(serialOf(date.plusMonths(toInt(num(args, 1)))));
        }
    }

    private static class Eomonth extends Fn {
        Eomonth() {
            super("EOMONTH", 2, 2);
        }

        @Override
        Value apply(List<Value> args) {
            LocalDate date = toLocalDateOrThrow(num(args, 0))
                    .plusMonths(toInt(num(args, 1)));
            return Value.number(serialOf(date.withDayOfMonth(date.lengthOfMonth())));
        }
    }

    /** {@code DAYS(end_date, start_date)} — Excel's argument order. */
    private static class Days extends Fn {
        Days() {
            super("DAYS", 2, 2);
        }

        @Override
        Value apply(List<Value> args) {
            LocalDate end = toLocalDateOrThrow(num(args, 0));
            LocalDate start = toLocalDateOrThrow(num(args, 1));
            return Value.number(BigDecimal.valueOf(
                    java.time.temporal.ChronoUnit.DAYS.between(start, end)));
        }
    }

    /**
     * {@code DAYS360(start_date, end_date, [method])} — the US (NASD) method by default: day 31
     * folds to 30, the last day of February folds to 30, and an end day of 31 folds to 30 when
     * the start day already folded to 30. The European method simply clamps both days at 30.
     */
    private static class Days360 extends Fn {
        Days360() {
            super("DAYS360", 2, 3);
        }

        @Override
        Value apply(List<Value> args) {
            LocalDate start = toLocalDateOrThrow(num(args, 0));
            LocalDate end = toLocalDateOrThrow(num(args, 1));
            boolean european = args.size() > 2 && num(args, 2).signum() != 0;
            int day1 = start.getDayOfMonth();
            int day2 = end.getDayOfMonth();
            if (european) {
                day1 = Math.min(day1, 30);
                day2 = Math.min(day2, 30);
            } else {
                boolean startFolded = false;
                if (day1 == 31 || isLastDayOfFebruary(start)) {
                    day1 = 30;
                    startFolded = true;
                }
                if (day2 == 31 && startFolded) {
                    day2 = 30;
                } else if (isLastDayOfFebruary(end) && startFolded) {
                    day2 = 30;
                }
            }
            long days = (long) (end.getYear() - start.getYear()) * 360
                    + (end.getMonthValue() - start.getMonthValue()) * 30
                    + (day2 - day1);
            return Value.number(BigDecimal.valueOf(days));
        }
    }

    private static boolean isLastDayOfFebruary(LocalDate date) {
        return date.getMonthValue() == 2 && date.getDayOfMonth() == date.lengthOfMonth();
    }

    /**
     * {@code YEARFRAC(start_date, end_date, [basis])}. Basis 0 is the US 30/360 count over 360;
     * basis 1 is Excel's actual/actual algorithm (below); bases 2 and 3 are the actual count over
     * 360 and 365; basis 4 is the European 30/360 count over 360.
     *
     * <p>Excel's actual/actual algorithm: a span of at most one year divides by 366 when the
     * range touches a February 29 of a leap year it sits in (or when both ends share a leap
     * year), else by 365; a longer span divides by the average year length from the start of the
     * start year to the start of the year after the end.
     */
    private static class YearFrac extends Fn {
        YearFrac() {
            super("YEARFRAC", 2, 3);
        }

        @Override
        Value apply(List<Value> args) {
            LocalDate start = toLocalDateOrThrow(num(args, 0));
            LocalDate end = toLocalDateOrThrow(num(args, 1));
            int basis = args.size() > 2 ? toInt(num(args, 2)) : 0;
            boolean swap = start.isAfter(end);
            LocalDate from = swap ? end : start;
            LocalDate to = swap ? start : end;
            BigDecimal fraction;
            switch (basis) {
                case 0:
                    fraction = days360(from, to).divide(BigDecimal.valueOf(360), MC);
                    break;
                case 1:
                    fraction = actualActual(from, to);
                    break;
                case 2:
                    fraction = BigDecimal.valueOf(actualDays(from, to))
                            .divide(BigDecimal.valueOf(360), MC);
                    break;
                case 3:
                    fraction = BigDecimal.valueOf(actualDays(from, to))
                            .divide(BigDecimal.valueOf(365), MC);
                    break;
                case 4:
                    fraction = europeanDays360(from, to).divide(BigDecimal.valueOf(360), MC);
                    break;
                default:
                    return Value.error(FormulaError.NUM_ERROR);
            }
            return Value.number(swap ? fraction.negate() : fraction);
        }
    }

    /** The US 30/360 day count between two dates, shared with {@code DAYS360}'s US method. */
    private static BigDecimal days360(LocalDate start, LocalDate end) {
        int day1 = start.getDayOfMonth();
        int day2 = end.getDayOfMonth();
        boolean startFolded = day1 == 31 || isLastDayOfFebruary(start);
        if (startFolded) {
            day1 = 30;
        }
        if ((day2 == 31 || isLastDayOfFebruary(end)) && startFolded) {
            day2 = 30;
        }
        return BigDecimal.valueOf((long) (end.getYear() - start.getYear()) * 360
                + (end.getMonthValue() - start.getMonthValue()) * 30
                + (day2 - day1));
    }

    private static BigDecimal europeanDays360(LocalDate start, LocalDate end) {
        int day1 = Math.min(start.getDayOfMonth(), 30);
        int day2 = Math.min(end.getDayOfMonth(), 30);
        return BigDecimal.valueOf((long) (end.getYear() - start.getYear()) * 360
                + (end.getMonthValue() - start.getMonthValue()) * 30
                + (day2 - day1));
    }

    private static long actualDays(LocalDate start, LocalDate end) {
        return java.time.temporal.ChronoUnit.DAYS.between(start, end);
    }

    private static BigDecimal actualActual(LocalDate start, LocalDate end) {
        long days = actualDays(start, end);
        // At most one year: the denominator is 366 when a leap-day of a year the span lives in
        // falls inside the span (or the whole span shares one leap year), else 365.
        if (sameYearOrLess(start, end)) {
            return BigDecimal.valueOf(days).divide(BigDecimal.valueOf(denominatorForShortSpan(start, end)), MC);
        }
        // Longer spans average over the calendar years the span touches.
        long yearSpanDays = actualDays(LocalDate.of(start.getYear(), 1, 1),
                LocalDate.of(end.getYear() + 1, 1, 1));
        BigDecimal averageYearLength = BigDecimal.valueOf(yearSpanDays)
                .divide(BigDecimal.valueOf(end.getYear() + 1 - start.getYear()), MC);
        return BigDecimal.valueOf(days).divide(averageYearLength, MC);
    }

    /** Whether the span is within one calendar year or reaches at most one year ahead. */
    private static boolean sameYearOrLess(LocalDate start, LocalDate end) {
        if (start.getYear() == end.getYear()) {
            return true;
        }
        if (end.getYear() != start.getYear() + 1) {
            return false;
        }
        MonthDay startMonthDay = MonthDay.from(start);
        MonthDay endMonthDay = MonthDay.from(end);
        return endMonthDay.isAfter(startMonthDay)
                || endMonthDay.equals(startMonthDay);
    }

    private static long denominatorForShortSpan(LocalDate start, LocalDate end) {
        if (start.getYear() == end.getYear()) {
            return start.isLeapYear() ? 366 : 365;
        }
        LocalDate leapDayOfStartYear = LocalDate.of(start.getYear(), 2, 29);
        if (start.isLeapYear() && !leapDayOfStartYear.isBefore(start)) {
            return 366;
        }
        LocalDate leapDayOfEndYear = LocalDate.of(end.getYear(), 2, 29);
        if (end.isLeapYear() && !leapDayOfEndYear.isAfter(end)) {
            return 366;
        }
        return 365;
    }

    /**
     * {@code DATEVALUE(text)} — documented pragmatic subset: ISO {@code yyyy-MM-dd} and
     * {@code yyyy/M/d}; anything else is {@code #VALUE!}.
     */
    private static class DateValue extends Fn {
        DateValue() {
            super("DATEVALUE", 1, 1);
        }

        @Override
        Value apply(List<Value> args) {
            String text = args.get(0).asText().trim();
            try {
                return Value.number(serialOf(LocalDate.parse(text)));
            } catch (DateTimeParseException isoEx) {
                try {
                    String[] parts = text.split("/");
                    if (parts.length != 3) {
                        return Value.error(FormulaError.VALUE_ERROR);
                    }
                    return Value.number(serialOf(LocalDate.of(
                            Integer.parseInt(parts[0]),
                            Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]))));
                } catch (DateTimeParseException | NumberFormatException ex) {
                    return Value.error(FormulaError.VALUE_ERROR);
                }
            }
        }
    }
}
