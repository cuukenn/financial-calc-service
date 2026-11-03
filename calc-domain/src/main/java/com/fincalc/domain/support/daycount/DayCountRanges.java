package com.fincalc.domain.support.daycount;

import java.time.LocalDate;

/**
 * Shared precondition for day-count conventions.
 *
 * <p>A reversed range would otherwise accrue negative interest under most conventions and zero
 * under ACT/ACT, silently masking a period-builder defect. All conventions reject it uniformly.
 */
final class DayCountRanges {

    private DayCountRanges() {
    }

    static void requireOrdered(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new NullPointerException("start and end must not be null");
        }
        if (end.isBefore(start)) {
            throw new IllegalArgumentException(
                    "end " + end + " must not precede start " + start);
        }
    }
}
