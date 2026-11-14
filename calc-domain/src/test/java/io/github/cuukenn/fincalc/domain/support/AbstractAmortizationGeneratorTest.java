package io.github.cuukenn.fincalc.domain.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import io.github.cuukenn.fincalc.common.time.SystemClockProvider;
import io.github.cuukenn.fincalc.domain.calc.CalculationContext;
import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.GracePeriod;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.PaymentFrequency;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.model.RateChangePolicy;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
import io.github.cuukenn.fincalc.domain.model.RoundingPolicy;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.support.dateroll.NoRoll;
import io.github.cuukenn.fincalc.domain.support.daycount.Thirty360;

class AbstractAmortizationGeneratorTest {

    @Test
    void skeleton_computes_interest_closes_balance_and_fills_cumulative() {
        Schedule s = new FixedPrincipalStub().generate(context(baseElement()));

        assertThat(s.installments()).hasSize(12);
        assertThat(s.installments().get(0).interestAccrued()).isEqualByComparingTo("5000.00");
        assertThat(s.installments().get(0).closingBalance()).isEqualByComparingTo("916666.67");
        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("0.00");
        assertThat(s.installments().get(11).cumulativePrincipal()).isEqualByComparingTo("1000000.00");
        assertThat(s.hasErrors()).isFalse();
    }

    @Test
    void zero_rate_is_handled_without_division_by_zero() {
        Schedule s = new FixedPrincipalStub().generate(context(baseElement().toBuilder()
                .nominalRate(BigDecimal.ZERO).build()));

        assertThat(s.installments()).allSatisfy(i ->
                assertThat(i.interestAccrued()).isEqualByComparingTo("0.00"));
        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void balloon_residual_may_remain_at_maturity() {
        LeaseElement element = baseElement().toBuilder()
                .residualValue(new BigDecimal("100000"))
                .residualHandling(ResidualHandling.BALLOON_IN_RENT)
                .build();

        Schedule s = new BalloonStub().generate(context(element));

        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("100000.00");
        assertThat(s.hasErrors()).isFalse();
    }

    @Test
    void tail_rounding_difference_keeps_positive_sign() {
        // 末期少摊 0.006 元本金（小于 0.01 元容差，属可吸收舍入）：差额为正，带符号记录。
        Schedule s = new TailDeltaStub(new BigDecimal("-0.006")).generate(context(fineElement()));

        assertThat(s.hasErrors()).isFalse();
        assertThat(s.header().roundingDifference()).isEqualByComparingTo("0.0060");
    }

    @Test
    void tail_rounding_difference_keeps_negative_sign() {
        // 末期多摊 0.006 元本金：差额为负，必须以负号记录（不得取绝对值后丢弃符号）。
        Schedule s = new TailDeltaStub(new BigDecimal("0.006")).generate(context(fineElement()));

        assertThat(s.hasErrors()).isFalse();
        assertThat(s.header().roundingDifference()).isEqualByComparingTo("-0.0060");
    }

    /** 本金 100 元、4 位小数舍入：让亚分级尾差在 header 中可辨。 */
    private static LeaseElement fineElement() {
        return baseElement().toBuilder()
                .principal(new BigDecimal("100"))
                .periods(3)
                .rounding(RoundingPolicy.builder().scale(4).build())
                .build();
    }

    private static CalculationContext context(LeaseElement element) {
        return new CalculationContext(element, null, new Thirty360(), new NoRoll(),
                SystemClockProvider.INSTANCE, CalculationOptions.defaults());
    }

    private static LeaseElement baseElement() {
        return LeaseElement.builder()
                .principal(new BigDecimal("1000000"))
                .nominalRate(new BigDecimal("0.06"))
                .commenceDate(LocalDate.of(2024, 1, 1))
                .firstRentDate(LocalDate.of(2024, 2, 1))
                .periods(12)
                .frequency(PaymentFrequency.MONTHLY)
                .timing(PaymentTiming.IN_ARREARS)
                .dayCountCode("THIRTY_360")
                .dateRollCode("NONE")
                .rounding(RoundingPolicy.defaults())
                .residualValue(BigDecimal.ZERO)
                .residualHandling(ResidualHandling.SEPARATE)
                .deposit(BigDecimal.ZERO)
                .method(RepaymentMethodId.EQUAL_PRINCIPAL)
                .gracePeriod(GracePeriod.none())
                .rateChangePolicy(RateChangePolicy.RESOLVE)
                .build();
    }

    /** Test double: amortizes principal minus the residual, leaving the balloon at maturity. */
    private static final class BalloonStub extends AbstractAmortizationGenerator {

        @Override
        public String code() {
            return "BALLOON_STUB";
        }

        @Override
        public boolean supports(CalculationContext context) {
            return true;
        }

        @Override
        protected Allocation allocate(AllocationInput input, AmortizationState state) {
            BigDecimal principal = input.lastPeriod()
                    ? input.remainingPrincipal().subtract(input.element().residualValue())
                    : new BigDecimal("75000");
            return new Allocation(principal, input.accruedInterest());
        }
    }

    /** Test double: fixed principal per period, remainder in the last period. */
    private static final class FixedPrincipalStub extends AbstractAmortizationGenerator {
        @Override
        public String code() {
            return "FIXED_PRINCIPAL_STUB";
        }

        @Override
        public boolean supports(CalculationContext context) {
            return true;
        }

        @Override
        protected Allocation allocate(AllocationInput input, AmortizationState state) {
            BigDecimal principal = input.lastPeriod()
                    ? input.remainingPrincipal()
                    : new BigDecimal("83333.33");
            return new Allocation(principal, input.accruedInterest());
        }
    }

    /**
     * Test double: pays 33.3333 of principal per period and shifts the final period's principal
     * (and therefore the tail balance) by {@code tailDelta} — a sub-tolerance, sub-cent drift
     * whose sign the header must preserve.
     */
    private static final class TailDeltaStub extends AbstractAmortizationGenerator {

        private final BigDecimal tailDelta;

        TailDeltaStub(BigDecimal tailDelta) {
            this.tailDelta = tailDelta;
        }

        @Override
        public String code() {
            return "TAIL_DELTA_STUB";
        }

        @Override
        public boolean supports(CalculationContext context) {
            return true;
        }

        @Override
        protected Allocation allocate(AllocationInput input, AmortizationState state) {
            BigDecimal principal = input.lastPeriod()
                    ? input.remainingPrincipal().add(tailDelta)
                    : new BigDecimal("33.3333");
            return new Allocation(principal, input.accruedInterest());
        }
    }
}
