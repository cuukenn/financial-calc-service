package com.fincalc.domain.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.fincalc.common.money.MoneyScale;
import com.fincalc.common.money.MoneyUtils;
import com.fincalc.domain.model.Installment;
import com.fincalc.domain.model.RoundingPolicy;
import com.fincalc.domain.model.TailAdjustPolicy;
import com.fincalc.domain.port.TailAdjuster;
import com.fincalc.domain.support.tail.FirstPeriodTailAdjuster;
import com.fincalc.domain.support.tail.LastPeriodTailAdjuster;
import com.fincalc.domain.support.tail.ProportionalTailAdjuster;
import com.fincalc.domain.support.tail.TailAdjustment;

/**
 * Entry point for the built-in tail adjustment policies.
 */
public final class TailAdjusters {

    private static final BigDecimal CENT = new BigDecimal("0.01");

    private TailAdjusters() {
    }

    /**
     * Resolves the adjuster for a policy.
     *
     * @throws IllegalArgumentException when the policy has no built-in adjuster
     */
    public static TailAdjuster forPolicy(TailAdjustPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        switch (policy) {
            case LAST_PERIOD:
                return LastPeriodTailAdjuster.INSTANCE;
            case FIRST_PERIOD:
                return FirstPeriodTailAdjuster.INSTANCE;
            case PROPORTIONAL:
                return ProportionalTailAdjuster.INSTANCE;
            default:
                throw new IllegalArgumentException("unsupported tail adjust policy: " + policy);
        }
    }

    /**
     * Spreads {@code expectedTotal - currentTotal} across {@code rows}' rent and principal using the
     * largest-remainder method, so the adjusted rents sum to {@code expectedTotal} exactly.
     *
     * <p>Weights are the absolute rent of each row. When every rent is zero the whole difference is
     * placed on the last row. Closing balances and running totals are not recomputed here.
     *
     * @throws IllegalArgumentException when {@code rows} is empty
     */
    public static List<Installment> applyProportional(List<Installment> rows, BigDecimal expectedTotal) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(expectedTotal, "expectedTotal");
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("cannot adjust an empty schedule");
        }

        BigDecimal current = MoneyUtils.sum(
                rows.stream().map(Installment::rentDue).collect(Collectors.toList()));
        BigDecimal difference = expectedTotal.subtract(current)
                .setScale(MoneyScale.OUTPUT, MoneyScale.ROUNDING);
        if (difference.signum() == 0) {
            return Collections.unmodifiableList(new ArrayList<>(rows));
        }

        BigDecimal[] deltas = allocate(rows, difference);
        RoundingPolicy policy = RoundingPolicy.defaults();
        List<Installment> adjusted = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            adjusted.add(TailAdjustment.shift(rows.get(i), deltas[i], policy));
        }
        return Collections.unmodifiableList(adjusted);
    }

    private static BigDecimal[] allocate(List<Installment> rows, BigDecimal difference) {
        int size = rows.size();
        BigDecimal[] deltas = new BigDecimal[size];
        BigDecimal totalWeight = MoneyUtils.sum(
                rows.stream().map(row -> row.rentDue().abs()).collect(Collectors.toList()));

        if (totalWeight.signum() == 0) {
            Arrays.fill(deltas, BigDecimal.ZERO.setScale(MoneyScale.OUTPUT));
            deltas[size - 1] = difference;
            return deltas;
        }

        BigDecimal magnitude = difference.abs();
        BigDecimal[] fractions = new BigDecimal[size];
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < size; i++) {
            BigDecimal weight = rows.get(i).rentDue().abs();
            BigDecimal exact = magnitude.multiply(weight)
                    .divide(totalWeight, MoneyScale.INTERMEDIATE, RoundingMode.HALF_UP);
            BigDecimal base = exact.setScale(MoneyScale.OUTPUT, RoundingMode.DOWN);
            deltas[i] = base;
            fractions[i] = exact.subtract(base);
            allocated = allocated.add(base);
        }

        int leftoverCents = magnitude.subtract(allocated).movePointRight(2).intValueExact();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            order.add(i);
        }
        order.sort((left, right) -> {
            int byFraction = fractions[right].compareTo(fractions[left]);
            return byFraction != 0 ? byFraction : Integer.compare(left, right);
        });
        for (int i = 0; i < leftoverCents; i++) {
            int index = order.get(i % size);
            deltas[index] = deltas[index].add(CENT);
        }

        if (difference.signum() < 0) {
            for (int i = 0; i < size; i++) {
                deltas[i] = deltas[i].negate();
            }
        }
        return deltas;
    }
}
