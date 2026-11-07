package com.fincalc.rule.function;

import static org.assertj.core.api.Assertions.assertThat;

import com.fincalc.rule.function.datex.DateFunctions;
import com.fincalc.rule.function.financial.FinancialFunctions;
import com.fincalc.rule.function.info.InfoFunctions;
import com.fincalc.rule.function.logical.LogicalFunctions;
import com.fincalc.rule.function.lookup.LookupFunctions;
import com.fincalc.rule.function.math.MathFunctions;
import com.fincalc.rule.function.textx.TextFunctions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Registry-level pins for the v1 Excel-compatible function library: the full code list, the
 * per-family {@code register} entry points, and the stable SHA-256 fingerprint the rule-set
 * hash carries (design §8.2/§8.5).
 */
class FunctionRegistryTest {

    /** The binding v1 list of the brief, family by family, 80 functions in total. */
    private static final List<String> MATH_CODES = List.of(
            "ABS", "ROUND", "ROUNDUP", "ROUNDDOWN", "INT", "TRUNC", "MOD", "POWER", "SQRT",
            "EXP", "LN", "LOG", "LOG10", "SIGN", "MAX", "MIN", "SUM", "PRODUCT", "AVERAGE",
            "CEILING", "FLOOR");
    private static final List<String> FINANCIAL_CODES = List.of(
            "PMT", "IPMT", "PPMT", "PV", "FV", "RATE", "NPER", "NPV", "IRR", "XIRR", "MIRR",
            "EFFECT", "NOMINAL", "SLN", "DB", "DDB", "SYD");
    private static final List<String> DATE_CODES = List.of(
            "DATE", "YEAR", "MONTH", "DAY", "EDATE", "EOMONTH", "DAYS", "DAYS360", "YEARFRAC",
            "DATEVALUE");
    private static final List<String> LOGICAL_CODES = List.of(
            "IF", "IFS", "AND", "OR", "NOT", "XOR", "IFERROR", "IFNA", "SWITCH", "TRUE",
            "FALSE");
    private static final List<String> LOOKUP_CODES = List.of(
            "CHOOSE", "INDEX", "MATCH", "LOOKUP", "VLOOKUP");
    private static final List<String> TEXT_CODES = List.of(
            "CONCAT", "CONCATENATE", "LEFT", "RIGHT", "MID", "LEN", "TRIM", "UPPER", "LOWER",
            "TEXT", "VALUE");
    private static final List<String> INFO_CODES = List.of(
            "ISBLANK", "ISNUMBER", "ISTEXT", "ISERROR", "NA");

    @Test
    void registry_exposes_all_codes_and_stable_hash() {
        FunctionRegistry r1 = FunctionRegistry.defaults();
        FunctionRegistry r2 = FunctionRegistry.defaults();
        assertThat(r1.codes()).contains("PMT", "ROUND", "EDATE", "IF", "SUM");
        assertThat(r1.hash()).isEqualTo(r2.hash());
    }

    @Test
    void defaults_registers_the_whole_v1_list() {
        List<String> codes = FunctionRegistry.defaults().codes();
        assertThat(codes)
                .containsAll(MATH_CODES)
                .containsAll(FINANCIAL_CODES)
                .containsAll(DATE_CODES)
                .containsAll(LOGICAL_CODES)
                .containsAll(LOOKUP_CODES)
                .containsAll(TEXT_CODES)
                .containsAll(INFO_CODES);
        assertThat(codes).hasSize(
                MATH_CODES.size() + FINANCIAL_CODES.size() + DATE_CODES.size()
                        + LOGICAL_CODES.size() + LOOKUP_CODES.size() + TEXT_CODES.size()
                        + INFO_CODES.size());
    }

    @Test
    void every_family_registers_its_own_functions() {
        assertThat(MathFunctions.register(FunctionRegistry.empty()).codes())
                .containsExactlyInAnyOrderElementsOf(MATH_CODES);
        assertThat(FinancialFunctions.register(FunctionRegistry.empty()).codes())
                .containsExactlyInAnyOrderElementsOf(FINANCIAL_CODES);
        assertThat(DateFunctions.register(FunctionRegistry.empty()).codes())
                .containsExactlyInAnyOrderElementsOf(DATE_CODES);
        assertThat(LogicalFunctions.register(FunctionRegistry.empty()).codes())
                .containsExactlyInAnyOrderElementsOf(LOGICAL_CODES);
        assertThat(LookupFunctions.register(FunctionRegistry.empty()).codes())
                .containsExactlyInAnyOrderElementsOf(LOOKUP_CODES);
        assertThat(TextFunctions.register(FunctionRegistry.empty()).codes())
                .containsExactlyInAnyOrderElementsOf(TEXT_CODES);
        assertThat(InfoFunctions.register(FunctionRegistry.empty()).codes())
                .containsExactlyInAnyOrderElementsOf(INFO_CODES);
    }

    @Test
    void hash_is_sha256_of_the_sorted_code_list_as_64_lowercase_hex() throws Exception {
        FunctionRegistry registry = FunctionRegistry.defaults();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] expected = digest.digest(
                String.join("\n", registry.codes()).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : expected) {
            hex.append(String.format("%02x", b));
        }
        assertThat(registry.hash()).matches("[0-9a-f]{64}").isEqualTo(hex.toString());
    }

    @Test
    void defaults_stay_intact_when_a_caller_registers_on_top() {
        FunctionRegistry defaults = FunctionRegistry.defaults();
        FunctionRegistry extended = defaults.register(new FormulaFunction() {
            @Override
            public String code() {
                return "FOO";
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
            public com.fincalc.rule.expression.Value invoke(
                    List<com.fincalc.rule.expression.Value> args,
                    com.fincalc.rule.expression.EvalContext context) {
                return com.fincalc.rule.expression.Value.number(java.math.BigDecimal.ONE);
            }
        });
        assertThat(extended.find("FOO")).isNotNull();
        assertThat(defaults.find("FOO")).isNull();
        assertThat(defaults).isSameAs(FunctionRegistry.defaults());
    }
}
