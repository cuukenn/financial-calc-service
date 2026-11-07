package com.fincalc.rule.function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fincalc.common.error.BusinessException;
import com.fincalc.rule.expression.EvalContext;
import com.fincalc.rule.expression.Expression;
import com.fincalc.rule.expression.ExpressionParser;
import com.fincalc.rule.expression.FormulaError;
import com.fincalc.rule.expression.Value;
import com.fincalc.rule.expression.ValueType;
import com.fincalc.rule.function.datex.DateSerialize;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Excel-semantic pins for the v1 function library (design §8.5): the brief's seven tests
 * verbatim, the 1900-serial date anchors including the phantom 1900-02-29, ruling R59's
 * case-insensitive text comparison, the lazy-branch proof for IF/IFS/SWITCH, and reference
 * values for the math/financial/text families.
 */
class ExcelSemanticsTest {

    private static Value eval(String expr) {
        return ExpressionParser.parse(expr, FunctionRegistry.defaults())
                .evaluate(new EvalContext(Map.of(), FunctionRegistry.defaults()));
    }

    private static void assertError(Value value, String expectedCode) {
        assertThat(value.type()).isEqualTo(ValueType.ERROR);
        assertThat(((FormulaError) value.raw()).code()).isEqualTo(expectedCode);
    }

    // ------------------------------------------------------------------ brief's seven tests

    @Test
    void round_is_half_away_from_zero_like_excel() {
        assertThat(eval("ROUND(2.5, 0)").asNumber()).isEqualByComparingTo("3");
        assertThat(eval("ROUND(-2.5, 0)").asNumber()).isEqualByComparingTo("-3");
        assertThat(eval("ROUND(2.345, 2)").asNumber()).isEqualByComparingTo("2.35");
    }

    @Test
    void mod_sign_follows_divisor_like_excel() {
        assertThat(eval("MOD(-3, 2)").asNumber()).isEqualByComparingTo("1");
        assertThat(eval("MOD(3, -2)").asNumber()).isEqualByComparingTo("-1");
    }

    @Test
    void int_floor_and_trunc_toward_zero() {
        assertThat(eval("INT(-1.5)").asNumber()).isEqualByComparingTo("-2");
        assertThat(eval("TRUNC(-1.5)").asNumber()).isEqualByComparingTo("-1");
    }

    @Test
    void pmt_matches_excel_reference() {
        // Excel: PMT(0.005, 12, -1000000) = 86066.43
        assertThat(eval("ROUND(PMT(0.005, 12, -1000000), 2)").asNumber())
                .isEqualByComparingTo("86066.43");
    }

    @Test
    void edate_and_eomonth_handle_month_end_and_leap_year() {
        assertThat(eval("EDATE(DATE(2024,1,31), 1)").asNumber())
                .isEqualByComparingTo(String.valueOf(DateSerialize.toSerial(LocalDate.of(2024, 2, 29))));
        assertThat(eval("EOMONTH(DATE(2024,2,1), 0)").asNumber())
                .isEqualByComparingTo(String.valueOf(DateSerialize.toSerial(LocalDate.of(2024, 2, 29))));
    }

    @Test
    void iferror_catches_div0() {
        assertThat(eval("IFERROR(1/0, -1)").asNumber()).isEqualByComparingTo("-1");
    }

    // ------------------------------------------------------- Excel 1900 serial-date anchors

    @Test
    void serial_system_reproduces_excel_1900_anchors_including_the_phantom_feb29() {
        assertThat(DateSerialize.toSerial(LocalDate.of(1900, 1, 1))).isEqualByComparingTo("1");
        assertThat(DateSerialize.toSerial(LocalDate.of(1900, 2, 28))).isEqualByComparingTo("59");
        // Excel's leap-year bug: serial 60 is the phantom 1900-02-29, so 1900-03-01 is 61.
        assertThat(DateSerialize.toSerial(LocalDate.of(1900, 3, 1))).isEqualByComparingTo("61");
        assertThat(DateSerialize.toSerial(LocalDate.of(2024, 1, 1))).isEqualByComparingTo("45292");
        assertThat(DateSerialize.toSerial(LocalDate.of(2024, 2, 29))).isEqualByComparingTo("45351");
        assertThat(DateSerialize.toLocalDate(BigDecimal.valueOf(59)))
                .isEqualTo(LocalDate.of(1900, 2, 28));
        assertThat(DateSerialize.toLocalDate(BigDecimal.valueOf(61)))
                .isEqualTo(LocalDate.of(1900, 3, 1));
        assertThat(DateSerialize.toLocalDate(BigDecimal.valueOf(45292)))
                .isEqualTo(LocalDate.of(2024, 1, 1));
        // The phantom day has no LocalDate representation and is rejected, never shifted.
        assertThatThrownBy(() -> DateSerialize.toLocalDate(BigDecimal.valueOf(60)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-RULE-3007");
        assertThatThrownBy(() -> DateSerialize.toLocalDate(BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-RULE-3007");
    }

    @Test
    void date_functions_follow_excel_overflow_and_component_semantics() {
        assertThat(eval("DATE(2024,13,1)").asNumber())
                .isEqualByComparingTo(DateSerialize.toSerial(LocalDate.of(2025, 1, 1)));
        assertThat(eval("DATE(2024,1,0)").asNumber())
                .isEqualByComparingTo(DateSerialize.toSerial(LocalDate.of(2023, 12, 31)));
        assertThat(eval("DATE(2024,2,30)").asNumber())
                .isEqualByComparingTo(DateSerialize.toSerial(LocalDate.of(2024, 3, 1)));
        assertThat(eval("YEAR(DATE(2024,2,29))").asNumber()).isEqualByComparingTo("2024");
        assertThat(eval("MONTH(DATE(2024,2,29))").asNumber()).isEqualByComparingTo("2");
        assertThat(eval("DAY(DATE(2024,2,29))").asNumber()).isEqualByComparingTo("29");
        assertThat(eval("DAYS(DATE(2024,3,1), DATE(2024,2,1))").asNumber())
                .isEqualByComparingTo("29");
        assertThat(eval("DATEVALUE(\"2024-01-01\")").asNumber()).isEqualByComparingTo("45292");
        assertThat(eval("DATEVALUE(\"2024/1/1\")").asNumber()).isEqualByComparingTo("45292");
        assertError(eval("DATEVALUE(\"nope\")"), "FCS-RULE-3006");
        assertThat(eval("EDATE(DATE(2023,1,31), 1)").asNumber())
                .isEqualByComparingTo(DateSerialize.toSerial(LocalDate.of(2023, 2, 28)));
        assertThat(eval("EOMONTH(DATE(2024,1,15), -1)").asNumber())
                .isEqualByComparingTo(DateSerialize.toSerial(LocalDate.of(2023, 12, 31)));
    }

    @Test
    void invalid_date_inputs_surface_as_error_values_never_crashes() {
        // A plausible but impossible date: LocalDate.of throws a plain DateTimeException,
        // which evaluation must convert into an ERROR value (#VALUE!), never rethrow.
        assertError(eval("DATEVALUE(\"2024/2/30\")"), "FCS-RULE-3006");
        assertError(eval("DATEVALUE(\"2024/13/1\")"), "FCS-RULE-3006");
        // Month-shift overflow: for 2e9 months plusMonths still lands on a representable
        // calendar year, so the failure is the serial range check — Excel's #NUM! for an
        // out-of-range result, not a crash. (An even larger shift misses the calendar itself
        // and is #VALUE! through the DateTimeException guard.)
        assertError(eval("EDATE(DATE(2024,1,1), 2000000000)"), "FCS-RULE-3007");
    }

    @Test
    void days360_uses_the_us_method_by_default_and_european_when_asked() {
        assertThat(eval("DAYS360(DATE(2024,1,31), DATE(2024,3,31))").asNumber())
                .isEqualByComparingTo("60");
        // US method folds the last day of February to day 30; the European method keeps it.
        assertThat(eval("DAYS360(DATE(2024,2,29), DATE(2024,3,31))").asNumber())
                .isEqualByComparingTo("30");
        assertThat(eval("DAYS360(DATE(2024,2,29), DATE(2024,3,31), TRUE())").asNumber())
                .isEqualByComparingTo("31");
    }

    @Test
    void yearfrac_implements_the_documented_bases() {
        assertThat(eval("YEARFRAC(DATE(2024,1,1), DATE(2024,7,1), 0)").asNumber())
                .isEqualByComparingTo("0.5");
        assertThat(eval("YEARFRAC(DATE(2023,1,1), DATE(2024,1,1), 0)").asNumber())
                .isEqualByComparingTo("1");
        // basis 1 (actual/actual): same-year span of a leap year whose Feb 29 is in range -> 366.
        assertThat(eval("ROUND(YEARFRAC(DATE(2024,1,1), DATE(2024,7,1), 1), 10)").asNumber())
                .isEqualByComparingTo("0.4972677596");
        assertThat(eval("ROUND(YEARFRAC(DATE(2024,1,1), DATE(2024,7,1), 3), 10)").asNumber())
                .isEqualByComparingTo("0.4986301370");
    }

    // ------------------------------------------------------------------ R59 text comparison

    @Test
    void text_comparisons_are_case_insensitive_r59() {
        assertThat(eval("\"ABC\" = \"abc\"").asBoolean()).isTrue();
        assertThat(eval("\"ABC\" <> \"abc\"").asBoolean()).isFalse();
        assertThat(eval("\"abc\" < \"ABD\"").asBoolean()).isTrue();
        assertThat(eval("\"abc\" > \"ABD\"").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ lazy evaluation pins

    /**
     * Discriminating lazy proof: {@code BOOM()} resolves at parse time but not at evaluation
     * time, so an eager {@code CallNode} would throw {@code FCS-RULE-3009} while evaluating the
     * untaken branch; a lazy IF must return the taken branch instead.
     */
    @Test
    void untaken_if_branches_are_never_evaluated() {
        FunctionRegistry parseOnly = FunctionRegistry.defaults().register(new FormulaFunction() {
            @Override
            public String code() {
                return "BOOM";
            }

            @Override
            public int minArity() {
                return 0;
            }

            @Override
            public int maxArity() {
                return 0;
            }

            @Override
            public Value invoke(List<Value> args, EvalContext context) {
                return Value.error(FormulaError.NA_ERROR);
            }
        });
        EvalContext evalCtx = new EvalContext(Map.of(), FunctionRegistry.defaults());
        Expression e = ExpressionParser.parse("IF(TRUE(), 1, BOOM())", parseOnly);
        assertThat(e.evaluate(evalCtx).asNumber()).isEqualByComparingTo("1");
        Expression ifs = ExpressionParser.parse("IFS(FALSE(), BOOM(), TRUE(), 5)", parseOnly);
        assertThat(ifs.evaluate(evalCtx).asNumber()).isEqualByComparingTo("5");
        Expression sw = ExpressionParser.parse("SWITCH(2, 1, BOOM(), 2, 20, 99)", parseOnly);
        assertThat(sw.evaluate(evalCtx).asNumber()).isEqualByComparingTo("20");
        // An untaken branch that only produces an error value is likewise discarded.
        assertThat(eval("IF(TRUE(), 1, 1/0)").asNumber()).isEqualByComparingTo("1");
    }

    @Test
    void iferror_ifna_and_switch_follow_excel_selection_semantics() {
        // Excel's IFERROR catches #N/A too; IFNA is the one that discriminates.
        assertThat(eval("IFERROR(NA(), -1)").asNumber()).isEqualByComparingTo("-1");
        assertThat(eval("IFNA(NA(), -1)").asNumber()).isEqualByComparingTo("-1");
        assertError(eval("IFNA(1/0, -1)"), "FCS-RULE-3005");
        assertThat(eval("SWITCH(2, 1, 10, 2, 20, 99)").asNumber()).isEqualByComparingTo("20");
        assertThat(eval("SWITCH(9, 1, 10, 99)").asNumber()).isEqualByComparingTo("99");
        assertError(eval("SWITCH(9, 1, 10)"), "FCS-RULE-3008");
        assertError(eval("IF(1/0, 1, 2)"), "FCS-RULE-3005");
        assertError(eval("IFS(FALSE(), 1)"), "FCS-RULE-3008");
    }

    // ------------------------------------------------------------------ math reference pins

    @Test
    void math_reference_values_match_excel() {
        assertThat(eval("ABS(-7.5)").asNumber()).isEqualByComparingTo("7.5");
        assertThat(eval("SIGN(-5)").asNumber()).isEqualByComparingTo("-1");
        assertThat(eval("SIGN(0)").asNumber()).isEqualByComparingTo("0");
        assertThat(eval("POWER(2, 10)").asNumber()).isEqualByComparingTo("1024");
        assertThat(eval("POWER(-2, 3)").asNumber()).isEqualByComparingTo("-8");
        assertError(eval("POWER(-8, 0.5)"), "FCS-RULE-3007");
        assertError(eval("POWER(0, 0)"), "FCS-RULE-3007");
        assertError(eval("SQRT(-1)"), "FCS-RULE-3007");
        assertThat(eval("ROUND(SQRT(2), 9)").asNumber()).isEqualByComparingTo("1.414213562");
        assertThat(eval("ROUND(EXP(1), 9)").asNumber()).isEqualByComparingTo("2.718281828");
        assertThat(eval("ROUND(LN(EXP(3)), 9)").asNumber()).isEqualByComparingTo("3");
        assertThat(eval("ROUND(LOG(1000), 9)").asNumber()).isEqualByComparingTo("3");
        assertThat(eval("ROUND(LOG(8, 2), 9)").asNumber()).isEqualByComparingTo("3");
        assertThat(eval("ROUND(LOG10(100), 9)").asNumber()).isEqualByComparingTo("2");
        assertError(eval("LN(0)"), "FCS-RULE-3007");
        assertThat(eval("MAX(3, 1, 2)").asNumber()).isEqualByComparingTo("3");
        assertThat(eval("MIN(3, 1, 2)").asNumber()).isEqualByComparingTo("1");
        assertThat(eval("SUM(1, 2, 3)").asNumber()).isEqualByComparingTo("6");
        assertThat(eval("PRODUCT(2, 3, 4)").asNumber()).isEqualByComparingTo("24");
        assertThat(eval("AVERAGE(2, 4)").asNumber()).isEqualByComparingTo("3");
        assertThat(eval("ROUNDUP(1.21, 1)").asNumber()).isEqualByComparingTo("1.3");
        assertThat(eval("ROUNDUP(-1.21, 1)").asNumber()).isEqualByComparingTo("-1.3");
        assertThat(eval("ROUNDDOWN(1.29, 1)").asNumber()).isEqualByComparingTo("1.2");
        assertThat(eval("ROUNDDOWN(-1.29, 1)").asNumber()).isEqualByComparingTo("-1.2");
        assertThat(eval("INT(8.9)").asNumber()).isEqualByComparingTo("8");
        assertThat(eval("TRUNC(8.9)").asNumber()).isEqualByComparingTo("8");
        assertThat(eval("TRUNC(-8.96, 1)").asNumber()).isEqualByComparingTo("-8.9");
    }

    @Test
    void ceiling_and_floor_follow_classic_excel_sign_rules() {
        assertThat(eval("CEILING(2.5)").asNumber()).isEqualByComparingTo("3");
        assertThat(eval("CEILING(2.5, 1)").asNumber()).isEqualByComparingTo("3");
        assertThat(eval("CEILING(-2.5, -1)").asNumber()).isEqualByComparingTo("-3");
        assertThat(eval("CEILING(1.234, 0.01)").asNumber()).isEqualByComparingTo("1.24");
        assertThat(eval("CEILING(1.5, 0)").asNumber()).isEqualByComparingTo("0");
        assertError(eval("CEILING(-2.5, 1)"), "FCS-RULE-3007");
        assertThat(eval("FLOOR(2.5)").asNumber()).isEqualByComparingTo("2");
        assertThat(eval("FLOOR(-2.5, -2)").asNumber()).isEqualByComparingTo("-2");
        assertThat(eval("FLOOR(2.5, 0.5)").asNumber()).isEqualByComparingTo("2.5");
        assertError(eval("FLOOR(2.5, 0)"), "FCS-RULE-3005");
        assertError(eval("FLOOR(-2.5, 2)"), "FCS-RULE-3007");
    }

    @Test
    void mod_is_exact_even_for_non_terminating_quotients() {
        assertThat(eval("MOD(10.5, 3)").asNumber()).isEqualByComparingTo("1.5");
        assertThat(eval("MOD(-3, -2)").asNumber()).isEqualByComparingTo("-1");
        assertThat(eval("MOD(1, 3)").asNumber()).isEqualByComparingTo("1");
        assertThat(eval("MOD(1, 7)").asNumber()).isEqualByComparingTo("1");
        assertError(eval("MOD(1, 0)"), "FCS-RULE-3005");
    }

    @Test
    void errors_propagate_through_variadic_aggregates() {
        assertError(eval("SUM(1, 1/0)"), "FCS-RULE-3005");
        assertError(eval("AVERAGE(\"x\")"), "FCS-RULE-3006");
    }

    // ------------------------------------------------------------ financial reference pins

    @Test
    void annuity_family_round_trips_like_excel() {
        // Excel: PV(0.005, 12, -PMT(0.005, 12, -1000000)) = 1000000
        assertThat(eval("ROUND(PV(0.005, 12, -PMT(0.005, 12, -1000000)), 2)").asNumber())
                .isEqualByComparingTo("1000000");
        // Excel: FV(0.005, 12, 0, -1000000) = 1061677.81
        assertThat(eval("ROUND(FV(0.005, 12, 0, -1000000), 2)").asNumber())
                .isEqualByComparingTo("1061677.81");
        // Zero-rate edge: FV(0, 12, -100, 0) = 1200
        assertThat(eval("FV(0, 12, -100, 0)").asNumber()).isEqualByComparingTo("1200");
        // Excel: IPMT(0.005, 1, 12, -1000000) = 5000; PPMT = 81066.43
        assertThat(eval("ROUND(IPMT(0.005, 1, 12, -1000000), 2)").asNumber())
                .isEqualByComparingTo("5000");
        assertThat(eval("ROUND(PPMT(0.005, 1, 12, -1000000), 2)").asNumber())
                .isEqualByComparingTo("81066.43");
        assertThat(eval("ROUND(IPMT(0.005, 2, 12, -1000000), 2)").asNumber())
                .isEqualByComparingTo("4594.67");
    }

    @Test
    void rate_and_nper_solve_like_excel() {
        assertThat(eval("ROUND(RATE(12, -PMT(0.005, 12, -1000000), 1000000), 6)").asNumber())
                .isEqualByComparingTo("0.005");
        assertThat(eval("ROUND(NPER(0.005, -PMT(0.005, 12, -1000000), 1000000), 6)").asNumber())
                .isEqualByComparingTo("12");
        // NPER closed form at a zero rate: NPER(0, -100, 1200) = 12
        assertThat(eval("NPER(0, -100, 1200)").asNumber()).isEqualByComparingTo("12");
        assertError(eval("RATE(12, 86066.43, 1000000)"), "FCS-RULE-3007");
    }

    @Test
    void npv_irr_and_mirr_match_excel_reference_values() {
        // Excel: NPV(0.1, 100, 200, 300) = 481.592787395621
        assertThat(eval("ROUND(NPV(0.1, 100, 200, 300), 6)").asNumber())
                .isEqualByComparingTo("481.592787");
        // Exact root: IRR(-100, 110) = 10%
        assertThat(eval("ROUND(IRR(-100, 110), 6)").asNumber()).isEqualByComparingTo("0.1");
        // Excel documents MIRR(-120000, 39000, 30000, 21000, 37000, 46000, 10%, 12%) = 12.61%
        // (annual flows, so the periodic rate equals the annual rate).
        assertThat(eval("ROUND(MIRR(-120000, 39000, 30000, 21000, 37000, 46000, 0.1, 0.12), 4)")
                .asNumber()).isEqualByComparingTo("0.1261");
        assertError(eval("IRR(-100, -200)"), "FCS-RULE-3007");
        // XIRR over 366 days (2024 is a leap year): (1+r)^(366/365) = 1.1
        assertThat(eval("XIRR(-1000, DATE(2024,1,1), 1100, DATE(2025,1,1))").asNumber())
                .isBetween(new BigDecimal("0.0997"), new BigDecimal("0.0998"));
    }

    @Test
    void depreciation_and_rate_conversion_match_excel_references() {
        assertThat(eval("SLN(30000, 7500, 10)").asNumber()).isEqualByComparingTo("2250");
        // Excel: SYD(30000, 7500, 10, 1) = 4090.909091
        assertThat(eval("ROUND(SYD(30000, 7500, 10, 1), 6)").asNumber())
                .isEqualByComparingTo("4090.909091");
        // Excel: DDB(2400, 300, 10, 1) = 480; DDB(2400, 300, 10, 10) = 22.12
        assertThat(eval("DDB(2400, 300, 10, 1)").asNumber()).isEqualByComparingTo("480");
        assertThat(eval("ROUND(DDB(2400, 300, 10, 10), 2)").asNumber())
                .isEqualByComparingTo("22.12");
        // Excel: DB(1000000, 100000, 6, 1, 7) = 186083.33; DB(..., 2, 7) = 259639.42
        assertThat(eval("ROUND(DB(1000000, 100000, 6, 1, 7), 2)").asNumber())
                .isEqualByComparingTo("186083.33");
        assertThat(eval("ROUND(DB(1000000, 100000, 6, 2, 7), 2)").asNumber())
                .isEqualByComparingTo("259639.42");
        // Excel: EFFECT(0.12, 12) = 0.1268250301
        assertThat(eval("ROUND(EFFECT(0.12, 12), 10)").asNumber())
                .isEqualByComparingTo("0.1268250301");
        assertThat(eval("ROUND(NOMINAL(0.1268250301, 12), 6)").asNumber())
                .isEqualByComparingTo("0.12");
    }

    // ------------------------------------------------------------- lookup reference pins

    @Test
    void lookup_functions_follow_the_scalar_v1_forms() {
        assertThat(eval("CHOOSE(2, \"a\", \"b\", \"c\")").asText()).isEqualTo("b");
        assertError(eval("CHOOSE(4, \"a\")"), "FCS-RULE-3006");
        assertThat(eval("INDEX(\"a\", \"b\", \"c\", 2)").asText()).isEqualTo("b");
        assertError(eval("INDEX(\"a\", \"b\", 5)"), "FCS-RULE-3006");
        // R59: text matching is case-insensitive.
        assertThat(eval("MATCH(\"b\", \"a\", \"B\", \"c\")").asNumber()).isEqualByComparingTo("2");
        assertThat(eval("MATCH(3, 1, 2, 3)").asNumber()).isEqualByComparingTo("3");
        assertError(eval("MATCH(\"x\", 1, 2)"), "FCS-RULE-3008");
        assertThat(eval("LOOKUP(3, 1, 2, 3, 4)").asNumber()).isEqualByComparingTo("3");
        assertThat(eval("LOOKUP(2.5, 1, 2, 3)").asNumber()).isEqualByComparingTo("2");
        assertError(eval("LOOKUP(0, 1, 2)"), "FCS-RULE-3008");
        assertThat(eval("VLOOKUP(\"b\", \"a\", 1, \"b\", 2, \"c\", 3)").asNumber())
                .isEqualByComparingTo("2");
        assertError(eval("VLOOKUP(\"z\", \"a\", 1, \"b\", 2)"), "FCS-RULE-3008");
        // An odd pair count is malformed at evaluation; below the arity floor is a parse error.
        assertThatThrownBy(() -> eval("VLOOKUP(\"a\", \"a\")"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-RULE-3009");
    }

    // -------------------------------------------------------------- text reference pins

    @Test
    void text_functions_match_excel() {
        assertThat(eval("LEFT(\"abcdef\", 2)").asText()).isEqualTo("ab");
        assertThat(eval("LEFT(\"abc\")").asText()).isEqualTo("a");
        assertThat(eval("RIGHT(\"abcdef\", 2)").asText()).isEqualTo("ef");
        assertThat(eval("MID(\"abcdef\", 2, 3)").asText()).isEqualTo("bcd");
        assertThat(eval("LEN(\"abc\")").asNumber()).isEqualByComparingTo("3");
        assertThat(eval("TRIM(\"  a   b  \")").asText()).isEqualTo("a b");
        assertThat(eval("UPPER(\"aBc\")").asText()).isEqualTo("ABC");
        assertThat(eval("LOWER(\"aBc\")").asText()).isEqualTo("abc");
        assertThat(eval("CONCAT(\"a\", 1, TRUE())").asText()).isEqualTo("a1TRUE");
        assertThat(eval("CONCATENATE(\"x\", \"y\")").asText()).isEqualTo("xy");
        assertThat(eval("VALUE(\"12.5\")").asNumber()).isEqualByComparingTo("12.5");
        assertError(eval("VALUE(\"nope\")"), "FCS-RULE-3006");
        assertError(eval("MID(\"abc\", 0, 1)"), "FCS-RULE-3006");
        assertError(eval("LEFT(\"abc\", -1)"), "FCS-RULE-3006");
    }

    @Test
    void text_supports_the_documented_format_subset() {
        assertThat(eval("TEXT(1234.567, \"#,##0.00\")").asText()).isEqualTo("1,234.57");
        assertThat(eval("TEXT(1234.5, \"#,##0\")").asText()).isEqualTo("1,235");
        assertThat(eval("TEXT(5, \"0.00\")").asText()).isEqualTo("5.00");
        assertThat(eval("TEXT(5, \"000.0\")").asText()).isEqualTo("005.0");
        assertThat(eval("TEXT(0.125, \"0%\")").asText()).isEqualTo("13%");
        assertThat(eval("TEXT(-1234.5, \"#,##0\")").asText()).isEqualTo("-1,235");
        assertThat(eval("TEXT(45292, \"yyyy-mm-dd\")").asText()).isEqualTo("2024-01-01");
        assertThat(eval("TEXT(45292, \"yyyy/m/d\")").asText()).isEqualTo("2024/1/1");
        assertError(eval("TEXT(1, \"0.00E+00\")"), "FCS-RULE-3006");
    }

    // -------------------------------------------------------------- info and logic pins

    @Test
    void info_functions_classify_without_coercion() {
        assertThat(eval("ISBLANK(BLANK)").asBoolean()).isTrue();
        assertThat(eval("ISBLANK(0)").asBoolean()).isFalse();
        assertThat(eval("ISNUMBER(1)").asBoolean()).isTrue();
        assertThat(eval("ISNUMBER(\"1\")").asBoolean()).isFalse();
        assertThat(eval("ISTEXT(\"a\")").asBoolean()).isTrue();
        assertThat(eval("ISTEXT(1)").asBoolean()).isFalse();
        assertThat(eval("ISERROR(1/0)").asBoolean()).isTrue();
        assertThat(eval("ISERROR(1)").asBoolean()).isFalse();
        assertError(eval("NA()"), "FCS-RULE-3008");
    }

    @Test
    void logical_functions_match_excel() {
        assertThat(eval("AND(TRUE(), 1)").asBoolean()).isTrue();
        assertThat(eval("AND(TRUE(), 0)").asBoolean()).isFalse();
        assertThat(eval("OR(0, FALSE())").asBoolean()).isFalse();
        assertThat(eval("NOT(0)").asBoolean()).isTrue();
        assertThat(eval("XOR(1, 1, 1)").asBoolean()).isTrue();
        assertThat(eval("XOR(1, 1)").asBoolean()).isFalse();
        assertThat(eval("TRUE()").asBoolean()).isTrue();
        assertThat(eval("FALSE()").asBoolean()).isFalse();
    }
}
