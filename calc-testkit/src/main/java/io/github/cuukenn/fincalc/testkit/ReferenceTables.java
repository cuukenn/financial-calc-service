package io.github.cuukenn.fincalc.testkit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Literal golden reference tables for the four built-in repayment methods, each under
 * THIRTY_360 and ACT_365F, on the authoritative Task 21 fixture (principal 1 000 000, 6%
 * nominal, 12 monthly periods, IN_ARREARS, no fees/deposit/residual).
 *
 * <p>Every money value below is a hand-verified literal, not a computed one. Verification
 * record: 核对人 ZCode（实现代理） / 核对日期 2026-11-07。每张表的前 3 期与末期均按
 * 「期初余额 → 期末计息 → 本金 → 租金 → 期末余额」的口径用独立算术逐分复算（day-count 分数按
 * CALC 12 位 HALF_UP，逐期四舍五入到分，末期吸收尾差），并与实现输出全量比对一致；
 * 逐期算术见 .superpowers/sdd/2026-11-02-core-calculation-engine/task-30-report.md。
 * EQUAL_INSTALLMENT × THIRTY_360 另与 Task 21 权威钉值（86 066.43 / 86 066.42、
 * 1 032 797.15、32 797.15、IRR_ANNUAL 0.061677800455 等）核对一致。
 *
 * <p>Yield/duration expectations are ranges, not points: root finding is allowed 1e-8-level
 * jitter at the last digits, so each range is the implementation value ± 1e-8. Totals are
 * exact cent values. All fixtures pay IN_ARREARS (FCS-DOM-2004, rulings R16/R20).
 */
public final class ReferenceTables {

    private static final BigDecimal JITTER = new BigDecimal("0.00000001");

    private ReferenceTables() {
    }

    /** One expected installment row, every money field at scale 2. */
    public static final class ExpectedRow {

        private final int periodNo;
        private final BigDecimal openingBalance;
        private final BigDecimal principalDue;
        private final BigDecimal interestAccrued;
        private final BigDecimal rentDue;
        private final BigDecimal closingBalance;

        public ExpectedRow(int periodNo, String openingBalance, String principalDue,
                String interestAccrued, String rentDue, String closingBalance) {
            this.periodNo = periodNo;
            this.openingBalance = new BigDecimal(openingBalance);
            this.principalDue = new BigDecimal(principalDue);
            this.interestAccrued = new BigDecimal(interestAccrued);
            this.rentDue = new BigDecimal(rentDue);
            this.closingBalance = new BigDecimal(closingBalance);
        }

        public int periodNo() {
            return periodNo;
        }

        public BigDecimal openingBalance() {
            return openingBalance;
        }

        public BigDecimal principalDue() {
            return principalDue;
        }

        public BigDecimal interestAccrued() {
            return interestAccrued;
        }

        public BigDecimal rentDue() {
            return rentDue;
        }

        public BigDecimal closingBalance() {
            return closingBalance;
        }
    }

    /**
     * Summary metric expectations of one method × day-count combination. Totals are exact cent
     * values; IRR/XIRR/duration are ranges around the verified value (root jitter 1e-8).
     */
    public static final class ExpectedMetrics {

        private final BigDecimal totalInterest;
        private final BigDecimal totalRent;
        private final BigDecimal irrAnnualMin;
        private final BigDecimal irrAnnualMax;
        private final BigDecimal xirrMin;
        private final BigDecimal xirrMax;
        private final BigDecimal durationMin;
        private final BigDecimal durationMax;

        public ExpectedMetrics(String totalInterest, String totalRent,
                String irrAnnualCenter, String xirrCenter, String durationCenter) {
            this.totalInterest = new BigDecimal(totalInterest);
            this.totalRent = new BigDecimal(totalRent);
            BigDecimal irr = new BigDecimal(irrAnnualCenter);
            BigDecimal xirr = new BigDecimal(xirrCenter);
            BigDecimal dur = new BigDecimal(durationCenter);
            this.irrAnnualMin = irr.subtract(JITTER);
            this.irrAnnualMax = irr.add(JITTER);
            this.xirrMin = xirr.subtract(JITTER);
            this.xirrMax = xirr.add(JITTER);
            this.durationMin = dur.subtract(JITTER);
            this.durationMax = dur.add(JITTER);
        }

        public BigDecimal totalInterest() {
            return totalInterest;
        }

        public BigDecimal totalRent() {
            return totalRent;
        }

        public BigDecimal irrAnnualMin() {
            return irrAnnualMin;
        }

        public BigDecimal irrAnnualMax() {
            return irrAnnualMax;
        }

        public BigDecimal xirrMin() {
            return xirrMin;
        }

        public BigDecimal xirrMax() {
            return xirrMax;
        }

        public BigDecimal durationMin() {
            return durationMin;
        }

        public BigDecimal durationMax() {
            return durationMax;
        }
    }

    /** The {@code EQUAL_INSTALLMENT} golden table under the given day-count code. */
    public static List<ExpectedRow> equalInstallment(String dayCountCode) {
        return table("EQUAL_INSTALLMENT", dayCountCode);
    }

    /** The {@code EQUAL_PRINCIPAL} golden table under the given day-count code. */
    public static List<ExpectedRow> equalPrincipal(String dayCountCode) {
        return table("EQUAL_PRINCIPAL", dayCountCode);
    }

    /** The {@code INTEREST_FIRST} golden table under the given day-count code. */
    public static List<ExpectedRow> interestFirst(String dayCountCode) {
        return table("INTEREST_FIRST", dayCountCode);
    }

    /** The {@code PRINCIPAL_FIRST} golden table under the given day-count code. */
    public static List<ExpectedRow> principalFirst(String dayCountCode) {
        return table("PRINCIPAL_FIRST", dayCountCode);
    }

    /** The metric expectations of the given method × day-count combination. */
    public static ExpectedMetrics metricExpectation(String method, String dayCountCode) {
        ExpectedMetrics metrics = METRICS.get(key(method, dayCountCode));
        if (metrics == null) {
            throw new IllegalArgumentException("no metric expectation for " + method
                    + " x " + dayCountCode);
        }
        return metrics;
    }

    /** All 8 golden combinations as {@code (method, dayCount, rows)} argument triples. */
    public static List<Object[]> all() {
        List<Object[]> arguments = new ArrayList<>();
        for (String method : METHODS) {
            for (String dayCount : DAY_COUNTS) {
                arguments.add(new Object[] {method, dayCount, table(method, dayCount)});
            }
        }
        return arguments;
    }

    /** All 8 metric expectation combinations as {@code (method, dayCount, metrics)} triples. */
    public static List<Object[]> metricExpectations() {
        List<Object[]> arguments = new ArrayList<>();
        for (String method : METHODS) {
            for (String dayCount : DAY_COUNTS) {
                arguments.add(new Object[] {method, dayCount,
                        metricExpectation(method, dayCount)});
            }
        }
        return arguments;
    }

    private static final String[] METHODS = {"EQUAL_INSTALLMENT", "EQUAL_PRINCIPAL",
        "INTEREST_FIRST", "PRINCIPAL_FIRST"};

    private static final String[] DAY_COUNTS = {"THIRTY_360", "ACT_365F"};

    private static final Map<String, List<ExpectedRow>> TABLES = buildTables();

    private static final Map<String, ExpectedMetrics> METRICS = buildMetrics();

    private static String key(String method, String dayCount) {
        return method + "#" + dayCount;
    }

    private static List<ExpectedRow> table(String method, String dayCount) {
        List<ExpectedRow> rows = TABLES.get(key(method, dayCount));
        if (rows == null) {
            throw new IllegalArgumentException("no reference table for " + method
                    + " x " + dayCount);
        }
        return rows;
    }

    private static Map<String, List<ExpectedRow>> buildTables() {
        Map<String, List<ExpectedRow>> tables = new LinkedHashMap<>();

        tables.put(key("EQUAL_INSTALLMENT", "THIRTY_360"), unmodifiable(rows(
                new ExpectedRow(1, "1000000.00", "81066.43", "5000.00", "86066.43", "918933.57"),
                new ExpectedRow(2, "918933.57", "81471.76", "4594.67", "86066.43", "837461.81"),
                new ExpectedRow(3, "837461.81", "81879.12", "4187.31", "86066.43", "755582.69"),
                new ExpectedRow(4, "755582.69", "82288.52", "3777.91", "86066.43", "673294.17"),
                new ExpectedRow(5, "673294.17", "82699.96", "3366.47", "86066.43", "590594.21"),
                new ExpectedRow(6, "590594.21", "83113.46", "2952.97", "86066.43", "507480.75"),
                new ExpectedRow(7, "507480.75", "83529.03", "2537.40", "86066.43", "423951.72"),
                new ExpectedRow(8, "423951.72", "83946.67", "2119.76", "86066.43", "340005.05"),
                new ExpectedRow(9, "340005.05", "84366.40", "1700.03", "86066.43", "255638.65"),
                new ExpectedRow(10, "255638.65", "84788.24", "1278.19", "86066.43", "170850.41"),
                new ExpectedRow(11, "170850.41", "85212.18", "854.25", "86066.43", "85638.23"),
                new ExpectedRow(12, "85638.23", "85638.23", "428.19", "86066.42", "0.00"))));

        tables.put(key("EQUAL_INSTALLMENT", "ACT_365F"), unmodifiable(rows(
                new ExpectedRow(1, "1000000.00", "80972.21", "5095.89", "86068.10", "919027.79"),
                new ExpectedRow(2, "919027.79", "81686.98", "4381.12", "86068.10", "837340.81"),
                new ExpectedRow(3, "837340.81", "81801.10", "4267.00", "86068.10", "755539.71"),
                new ExpectedRow(4, "755539.71", "82342.15", "3725.95", "86068.10", "673197.56"),
                new ExpectedRow(5, "673197.56", "82637.56", "3430.54", "86068.10", "590560.00"),
                new ExpectedRow(6, "590560.00", "83155.75", "2912.35", "86068.10", "507404.25"),
                new ExpectedRow(7, "507404.25", "83482.42", "2585.68", "86068.10", "423921.83"),
                new ExpectedRow(8, "423921.83", "83907.84", "2160.26", "86068.10", "340013.99"),
                new ExpectedRow(9, "340013.99", "84391.32", "1676.78", "86068.10", "255622.67"),
                new ExpectedRow(10, "255622.67", "84765.47", "1302.63", "86068.10", "170857.20"),
                new ExpectedRow(11, "170857.20", "85225.52", "842.58", "86068.10", "85631.68"),
                new ExpectedRow(12, "85631.68", "85631.68", "436.37", "86068.05", "0.00"))));

        tables.put(key("EQUAL_PRINCIPAL", "THIRTY_360"), unmodifiable(rows(
                new ExpectedRow(1, "1000000.00", "83333.33", "5000.00", "88333.33", "916666.67"),
                new ExpectedRow(2, "916666.67", "83333.33", "4583.33", "87916.66", "833333.34"),
                new ExpectedRow(3, "833333.34", "83333.33", "4166.67", "87500.00", "750000.01"),
                new ExpectedRow(4, "750000.01", "83333.33", "3750.00", "87083.33", "666666.68"),
                new ExpectedRow(5, "666666.68", "83333.33", "3333.33", "86666.66", "583333.35"),
                new ExpectedRow(6, "583333.35", "83333.33", "2916.67", "86250.00", "500000.02"),
                new ExpectedRow(7, "500000.02", "83333.33", "2500.00", "85833.33", "416666.69"),
                new ExpectedRow(8, "416666.69", "83333.33", "2083.33", "85416.66", "333333.36"),
                new ExpectedRow(9, "333333.36", "83333.33", "1666.67", "85000.00", "250000.03"),
                new ExpectedRow(10, "250000.03", "83333.33", "1250.00", "84583.33", "166666.70"),
                new ExpectedRow(11, "166666.70", "83333.33", "833.33", "84166.66", "83333.37"),
                new ExpectedRow(12, "83333.37", "83333.37", "416.67", "83750.04", "0.00"))));

        tables.put(key("EQUAL_PRINCIPAL", "ACT_365F"), unmodifiable(rows(
                new ExpectedRow(1, "1000000.00", "83333.33", "5095.89", "88429.22", "916666.67"),
                new ExpectedRow(2, "916666.67", "83333.33", "4369.86", "87703.19", "833333.34"),
                new ExpectedRow(3, "833333.34", "83333.33", "4246.58", "87579.91", "750000.01"),
                new ExpectedRow(4, "750000.01", "83333.33", "3698.63", "87031.96", "666666.68"),
                new ExpectedRow(5, "666666.68", "83333.33", "3397.26", "86730.59", "583333.35"),
                new ExpectedRow(6, "583333.35", "83333.33", "2876.71", "86210.04", "500000.02"),
                new ExpectedRow(7, "500000.02", "83333.33", "2547.95", "85881.28", "416666.69"),
                new ExpectedRow(8, "416666.69", "83333.33", "2123.29", "85456.62", "333333.36"),
                new ExpectedRow(9, "333333.36", "83333.33", "1643.84", "84977.17", "250000.03"),
                new ExpectedRow(10, "250000.03", "83333.33", "1273.97", "84607.30", "166666.70"),
                new ExpectedRow(11, "166666.70", "83333.33", "821.92", "84155.25", "83333.37"),
                new ExpectedRow(12, "83333.37", "83333.37", "424.66", "83758.03", "0.00"))));

        tables.put(key("INTEREST_FIRST", "THIRTY_360"), unmodifiable(rows(
                new ExpectedRow(1, "1000000.00", "0.00", "5000.00", "5000.00", "1000000.00"),
                new ExpectedRow(2, "1000000.00", "0.00", "5000.00", "5000.00", "1000000.00"),
                new ExpectedRow(3, "1000000.00", "0.00", "5000.00", "5000.00", "1000000.00"),
                new ExpectedRow(4, "1000000.00", "0.00", "5000.00", "5000.00", "1000000.00"),
                new ExpectedRow(5, "1000000.00", "0.00", "5000.00", "5000.00", "1000000.00"),
                new ExpectedRow(6, "1000000.00", "0.00", "5000.00", "5000.00", "1000000.00"),
                new ExpectedRow(7, "1000000.00", "0.00", "5000.00", "5000.00", "1000000.00"),
                new ExpectedRow(8, "1000000.00", "0.00", "5000.00", "5000.00", "1000000.00"),
                new ExpectedRow(9, "1000000.00", "0.00", "5000.00", "5000.00", "1000000.00"),
                new ExpectedRow(10, "1000000.00", "0.00", "5000.00", "5000.00", "1000000.00"),
                new ExpectedRow(11, "1000000.00", "0.00", "5000.00", "5000.00", "1000000.00"),
                new ExpectedRow(12, "1000000.00", "1000000.00", "5000.00", "1005000.00", "0.00"))));

        tables.put(key("INTEREST_FIRST", "ACT_365F"), unmodifiable(rows(
                new ExpectedRow(1, "1000000.00", "0.00", "5095.89", "5095.89", "1000000.00"),
                new ExpectedRow(2, "1000000.00", "0.00", "4767.12", "4767.12", "1000000.00"),
                new ExpectedRow(3, "1000000.00", "0.00", "5095.89", "5095.89", "1000000.00"),
                new ExpectedRow(4, "1000000.00", "0.00", "4931.51", "4931.51", "1000000.00"),
                new ExpectedRow(5, "1000000.00", "0.00", "5095.89", "5095.89", "1000000.00"),
                new ExpectedRow(6, "1000000.00", "0.00", "4931.51", "4931.51", "1000000.00"),
                new ExpectedRow(7, "1000000.00", "0.00", "5095.89", "5095.89", "1000000.00"),
                new ExpectedRow(8, "1000000.00", "0.00", "5095.89", "5095.89", "1000000.00"),
                new ExpectedRow(9, "1000000.00", "0.00", "4931.51", "4931.51", "1000000.00"),
                new ExpectedRow(10, "1000000.00", "0.00", "5095.89", "5095.89", "1000000.00"),
                new ExpectedRow(11, "1000000.00", "0.00", "4931.51", "4931.51", "1000000.00"),
                new ExpectedRow(12, "1000000.00", "1000000.00", "5095.89", "1005095.89", "0.00"))));

        tables.put(key("PRINCIPAL_FIRST", "THIRTY_360"), unmodifiable(rows(
                new ExpectedRow(1, "1000000.00", "83333.33", "5000.00", "83333.33", "916666.67"),
                new ExpectedRow(2, "916666.67", "83333.33", "4583.33", "83333.33", "833333.34"),
                new ExpectedRow(3, "833333.34", "83333.33", "4166.67", "83333.33", "750000.01"),
                new ExpectedRow(4, "750000.01", "83333.33", "3750.00", "83333.33", "666666.68"),
                new ExpectedRow(5, "666666.68", "83333.33", "3333.33", "83333.33", "583333.35"),
                new ExpectedRow(6, "583333.35", "83333.33", "2916.67", "83333.33", "500000.02"),
                new ExpectedRow(7, "500000.02", "83333.33", "2500.00", "83333.33", "416666.69"),
                new ExpectedRow(8, "416666.69", "83333.33", "2083.33", "83333.33", "333333.36"),
                new ExpectedRow(9, "333333.36", "83333.33", "1666.67", "83333.33", "250000.03"),
                new ExpectedRow(10, "250000.03", "83333.33", "1250.00", "83333.33", "166666.70"),
                new ExpectedRow(11, "166666.70", "83333.33", "833.33", "83333.33", "83333.37"),
                new ExpectedRow(12, "83333.37", "83333.37", "416.67", "115833.37", "0.00"))));

        tables.put(key("PRINCIPAL_FIRST", "ACT_365F"), unmodifiable(rows(
                new ExpectedRow(1, "1000000.00", "83333.33", "5095.89", "83333.33", "916666.67"),
                new ExpectedRow(2, "916666.67", "83333.33", "4369.86", "83333.33", "833333.34"),
                new ExpectedRow(3, "833333.34", "83333.33", "4246.58", "83333.33", "750000.01"),
                new ExpectedRow(4, "750000.01", "83333.33", "3698.63", "83333.33", "666666.68"),
                new ExpectedRow(5, "666666.68", "83333.33", "3397.26", "83333.33", "583333.35"),
                new ExpectedRow(6, "583333.35", "83333.33", "2876.71", "83333.33", "500000.02"),
                new ExpectedRow(7, "500000.02", "83333.33", "2547.95", "83333.33", "416666.69"),
                new ExpectedRow(8, "416666.69", "83333.33", "2123.29", "83333.33", "333333.36"),
                new ExpectedRow(9, "333333.36", "83333.33", "1643.84", "83333.33", "250000.03"),
                new ExpectedRow(10, "250000.03", "83333.33", "1273.97", "83333.33", "166666.70"),
                new ExpectedRow(11, "166666.70", "83333.33", "821.92", "83333.33", "83333.37"),
                new ExpectedRow(12, "83333.37", "83333.37", "424.66", "115853.93", "0.00"))));

        return tables;
    }

    private static Map<String, ExpectedMetrics> buildMetrics() {
        Map<String, ExpectedMetrics> metrics = new LinkedHashMap<>();
        // EQUAL_INSTALLMENT × THIRTY_360 centers are the Task 21 authoritative pinned values.
        metrics.put(key("EQUAL_INSTALLMENT", "THIRTY_360"),
                new ExpectedMetrics("32797.15", "1032797.15", "0.061677800455",
                        "0.0616393559", "0.2683570275"));
        metrics.put(key("EQUAL_INSTALLMENT", "ACT_365F"),
                new ExpectedMetrics("32817.15", "1032817.15", "0.061716107692",
                        "0.0616776407", "0.2683555262"));
        metrics.put(key("EQUAL_PRINCIPAL", "THIRTY_360"),
                new ExpectedMetrics("32500.00", "1032500.00", "0.061677809329",
                        "0.0616410331", "0.2659536711"));
        metrics.put(key("EQUAL_PRINCIPAL", "ACT_365F"),
                new ExpectedMetrics("32520.56", "1032520.56", "0.061714545865",
                        "0.0616776504", "0.2659648845"));
        metrics.put(key("INTEREST_FIRST", "THIRTY_360"),
                new ExpectedMetrics("60000.00", "1060000.00", "0.061677811864",
                        "0.0615085357", "0.4865427803"));
        metrics.put(key("INTEREST_FIRST", "ACT_365F"),
                new ExpectedMetrics("60164.39", "1060164.39", "0.061847138717",
                        "0.0616773243", "0.4865408195"));
        metrics.put(key("PRINCIPAL_FIRST", "THIRTY_360"),
                new ExpectedMetrics("32500.00", "1032500.00", "0.059475070079",
                        "0.0594307209", "0.2755461049"));
        metrics.put(key("PRINCIPAL_FIRST", "ACT_365F"),
                new ExpectedMetrics("32520.56", "1032520.56", "0.059512376985",
                        "0.0594679965", "0.2755489362"));
        return metrics;
    }

    private static List<ExpectedRow> rows(ExpectedRow... rowArray) {
        List<ExpectedRow> list = new ArrayList<>();
        Collections.addAll(list, rowArray);
        return list;
    }

    private static List<ExpectedRow> unmodifiable(List<ExpectedRow> list) {
        return Collections.unmodifiableList(list);
    }
}
