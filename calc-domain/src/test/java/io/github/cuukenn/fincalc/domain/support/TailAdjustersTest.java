package io.github.cuukenn.fincalc.domain.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.RoundingPolicy;
import io.github.cuukenn.fincalc.domain.model.TailAdjustPolicy;

class TailAdjustersTest {

    @Test
    void last_period_absorbs_all_difference() {
        Installment last = inst(12, "86066.43");
        Installment adjusted = TailAdjusters.forPolicy(TailAdjustPolicy.LAST_PERIOD)
                .adjust(last, new BigDecimal("-0.03"), RoundingPolicy.defaults());
        assertThat(adjusted.rentDue()).isEqualByComparingTo("86066.40");
        assertThat(adjusted.principalDue()).isEqualByComparingTo("81066.40");
        assertThat(adjusted.rentDue().scale()).isEqualTo(2);
    }

    @Test
    void first_period_absorbs_all_difference() {
        Installment first = inst(1, "86066.43");
        Installment adjusted = TailAdjusters.forPolicy(TailAdjustPolicy.FIRST_PERIOD)
                .adjust(first, new BigDecimal("0.07"), RoundingPolicy.defaults());
        assertThat(adjusted.rentDue()).isEqualByComparingTo("86066.50");
        assertThat(adjusted.principalDue()).isEqualByComparingTo("81066.50");
    }

    @Test
    void proportional_preserves_sum_exactly() {
        List<Installment> rows = List.of(inst(1, "100.01"), inst(2, "100.01"), inst(3, "100.01"));
        List<Installment> adjusted = TailAdjusters.applyProportional(rows, new BigDecimal("300.00"));
        assertThat(adjusted).extracting(Installment::rentDue)
                .allSatisfy(v -> assertThat(v.scale()).isEqualTo(2));
        assertThat(MoneyUtils.sum(adjusted.stream().map(Installment::rentDue).collect(Collectors.toList())))
                .isEqualByComparingTo("300.00");
    }

    @Test
    void proportional_handles_negative_difference() {
        List<Installment> rows = List.of(inst(1, "100.00"), inst(2, "100.00"), inst(3, "100.01"));
        List<Installment> adjusted = TailAdjusters.applyProportional(rows, new BigDecimal("300.00"));
        assertThat(MoneyUtils.sum(adjusted.stream().map(Installment::rentDue).collect(Collectors.toList())))
                .isEqualByComparingTo("300.00");
    }

    @Test
    void proportional_distributes_remaining_cents_to_largest_remainders() {
        List<Installment> rows = List.of(inst(1, "100.00"), inst(2, "100.00"), inst(3, "100.00"));
        List<Installment> adjusted = TailAdjusters.applyProportional(rows, new BigDecimal("300.01"));
        assertThat(adjusted).extracting(Installment::rentDue)
                .containsExactly(new BigDecimal("100.01"), new BigDecimal("100.00"), new BigDecimal("100.00"));
    }

    @Test
    void proportional_with_zero_total_weight_puts_difference_on_last_row() {
        List<Installment> rows = List.of(inst(1, "0.00"), inst(2, "0.00"));
        List<Installment> adjusted = TailAdjusters.applyProportional(rows, new BigDecimal("1.00"));
        assertThat(adjusted).extracting(Installment::rentDue)
                .containsExactly(new BigDecimal("0.00"), new BigDecimal("1.00"));
    }

    @Test
    void rejects_proportional_adjustment_for_empty_rows() {
        assertThatThrownBy(() -> TailAdjusters.applyProportional(List.of(), new BigDecimal("1.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void proportional_skips_interest_only_rows() {
        List<Installment> rows = List.of(
                instWithPrincipal(1, "5000.00", "0.00"),
                instWithPrincipal(2, "5000.00", "5000.00"));

        List<Installment> adjusted = TailAdjusters.applyProportional(rows, new BigDecimal("9999.99"));

        assertThat(adjusted.get(0).principalDue()).isEqualByComparingTo("0.00");
        assertThat(adjusted.get(0).rentDue()).isEqualByComparingTo("5000.00");
        assertThat(adjusted.get(1).principalDue()).isEqualByComparingTo("4999.99");
        assertThat(adjusted.get(1).rentDue()).isEqualByComparingTo("4999.99");
    }

    @Test
    void rejects_adjustment_that_would_make_principal_negative() {
        Installment row = inst(1, "100.00");
        assertThatThrownBy(() -> TailAdjusters.forPolicy(TailAdjustPolicy.LAST_PERIOD)
                .adjust(row, new BigDecimal("-200.00"), RoundingPolicy.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void exposes_all_policies_by_code() {
        assertThat(TailAdjusters.forPolicy(TailAdjustPolicy.LAST_PERIOD).code()).isEqualTo("LAST_PERIOD");
        assertThat(TailAdjusters.forPolicy(TailAdjustPolicy.FIRST_PERIOD).code()).isEqualTo("FIRST_PERIOD");
        assertThat(TailAdjusters.forPolicy(TailAdjustPolicy.PROPORTIONAL).code()).isEqualTo("PROPORTIONAL");
    }

    private static Installment instWithPrincipal(int periodNo, String rent, String principal) {
        BigDecimal amount = new BigDecimal(rent);
        BigDecimal principalDue = new BigDecimal(principal);
        LocalDate start = LocalDate.of(2024, 1, 1).plusMonths(periodNo - 1L);
        return new Installment(periodNo, start, start.plusMonths(1), 30,
                new BigDecimal("1000000.00"), principalDue, amount.subtract(principalDue),
                amount.subtract(principalDue), BigDecimal.ZERO, BigDecimal.ZERO, amount,
                new BigDecimal("918933.57"), principalDue, amount.subtract(principalDue),
                new BigDecimal("0.06"), null);
    }

    private static Installment inst(int periodNo, String rent) {
        BigDecimal amount = new BigDecimal(rent);
        BigDecimal principal = amount.compareTo(new BigDecimal("5000.00")) > 0
                ? amount.subtract(new BigDecimal("5000.00"))
                : amount;
        LocalDate start = LocalDate.of(2024, 1, 1).plusMonths(periodNo - 1L);
        return new Installment(periodNo, start, start.plusMonths(1), 30,
                new BigDecimal("1000000.00"), principal, new BigDecimal("5000.00"), new BigDecimal("5000.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, amount, new BigDecimal("918933.57"),
                principal, new BigDecimal("5000.00"), new BigDecimal("0.06"), null);
    }
}
