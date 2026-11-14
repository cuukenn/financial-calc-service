package io.github.cuukenn.fincalc.domain.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cuukenn.fincalc.common.money.MoneyTolerance;
import io.github.cuukenn.fincalc.domain.support.validate.PeriodIdentityValidator;
import io.github.cuukenn.fincalc.domain.support.validate.PrincipalConservationValidator;
import io.github.cuukenn.fincalc.domain.support.validate.RentCompositionValidator;
import io.github.cuukenn.fincalc.domain.support.validate.TailBalanceValidator;
import org.junit.jupiter.api.Test;

/**
 *  fixes one money comparison line (0.01 元): differences above it must be
 * disclosed, never silently absorbed. The §3.4 validators must therefore judge against one value —
 * four private copies could drift, and a validator holding a wider line would absorb a drift its
 * sibling reports.
 */
class SharedMoneyToleranceTest {

    @Test
    void every_validator_compares_against_the_same_money_tolerance() {
        assertThat(PrincipalConservationValidator.TOLERANCE)
                .isSameAs(TailBalanceValidator.TOLERANCE)
                .isSameAs(PeriodIdentityValidator.TOLERANCE)
                .isSameAs(RentCompositionValidator.TOLERANCE)
                .isSameAs(MoneyTolerance.MONEY_COMPARE);
    }
}
