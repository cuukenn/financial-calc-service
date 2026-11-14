package io.github.cuukenn.fincalc.domain.support;

import io.github.cuukenn.fincalc.common.money.MoneyScale;
import io.github.cuukenn.fincalc.domain.model.PaymentFrequency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 首期 stub 的年利率占比（AGENTS.md 第 9 条「首期 stub」；form-field-gaps ticket 05）。
 *
 * <p>P-14①（银发〔2005〕129 号逐笔计息法之一）：计息期为整年（月）的部分按年（月）利率、
 * 零头天数部分按日利率（P-03：日利率 = 年利率 ÷ 360）——两种 129 号算法单笔内不得混用，
 * 所以开了 stub 的单子，**首期窗口整体**走 ①：整月部分 = k/12，零头 = 实际天数/360。
 * 关窗之后的各期照旧按该期自己的 yearFraction 计（那是它们的整期，① ② 在整期上等价）。
 *
 * <p>与 ②（全部化成天数，即现行 yearFraction 一段计）的差异只在 ACT_* 基准的非对齐或
 * 大小月窗口上出现；THIRTY_360 的对齐窗口两者逐位相等。月份走日历月（{@code plusMonths}），
 * 零头走实际天数——「整月」是一个日历概念，一个月就是一个月利率，不随该月长短伸缩。
 *
 * <p>精度：结果在 {@link MoneyScale#CALC}（12 位，HALF_UP）上，与各 DayCount 的
 * yearFraction 同精度，求根与摊销两侧吃同一个数。
 */
public final class FirstPeriodStubSplit {

    private static final BigDecimal MONTHS_OF_YEAR = BigDecimal.valueOf(12);
    private static final BigDecimal DAYS_OF_YEAR = BigDecimal.valueOf(360);

    private FirstPeriodStubSplit() {
    }

    /**
     * Whether the stub split is applicable to the frequency: only regular month-anchored
     * frequencies have a「整月」概念; {@code IRREGULAR} has none and is rejected at
     * {@code LeaseElement} validation, not silently ignored here.
     */
    public static boolean applicable(PaymentFrequency frequency) {
        return frequency.isRegular();
    }

    /**
     * The stub year fraction of one window: {@code k/12 + d/360} where {@code k} is the number of
     * full calendar months from {@code start} and {@code d} the remaining actual days
     * (算头不算尾：含起始日、不含结束日）.
     *
     * @param start     window start, inclusive
     * @param end       window end, exclusive
     * @param frequency the schedule's frequency (must be {@link #applicable regular})
     * @return the year fraction at {@link MoneyScale#CALC}, in [0, 1) for sane windows
     */
    public static BigDecimal yearFraction(LocalDate start, LocalDate end,
            PaymentFrequency frequency) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(frequency, "frequency");
        if (!applicable(frequency)) {
            throw new IllegalArgumentException("first-period stub needs a regular frequency: "
                    + frequency);
        }
        int fullMonths = 0;
        LocalDate cursor = start;
        while (!cursor.plusMonths(1).isAfter(end)) {
            cursor = cursor.plusMonths(1);
            fullMonths++;
        }
        long remainderDays = Math.max(0, ChronoUnit.DAYS.between(cursor, end));
        return BigDecimal.valueOf(fullMonths).divide(MONTHS_OF_YEAR, MoneyScale.CALC,
                        MoneyScale.ROUNDING)
                .add(BigDecimal.valueOf(remainderDays).divide(DAYS_OF_YEAR, MoneyScale.CALC,
                        MoneyScale.ROUNDING))
                .setScale(MoneyScale.CALC, MoneyScale.ROUNDING);
    }
}
