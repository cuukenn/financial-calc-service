package io.github.cuukenn.fincalc.common.money;

import java.math.BigDecimal;

/**
 * The money comparison line shared by every validation net.
 *
 * <p>AGENTS.md 补充口径规则 2：金额相等判定容差为 0.01 元；超过 0.01 元的差异必须作为校验问题
 * 披露，不得静默吸收。[实] In one value: a money difference of one cent or more is a finding,
 * anything smaller is absorbed rounding.
 *
 * <p>Comparisons use {@link BigDecimal#compareTo(BigDecimal)} against {@link #MONEY_COMPARE} on the
 * unrounded figures the caller holds; a value rounded to scale 2 for output must not be fed back into
 * the comparison, or a sub-cent drift would be hidden before it is judged.
 *
 * <p>This is a comparison line, not a calculation precision. The solver tolerances (root finding,
 * PMT search, rate epsilon) and the {@link MoneyScale} scales stay independent of it — AGENTS.md P-11
 * fixes root-solver precision on its own, so merging the two would be a behaviour change.
 */
public final class MoneyTolerance {

    /** 0.01 元, the line at which a money difference stops being rounding and becomes a finding. */
    public static final BigDecimal MONEY_COMPARE = new BigDecimal("0.01");

    private MoneyTolerance() {
    }
}
