package com.fincalc.domain.support.tail;

import java.math.BigDecimal;
import java.util.Objects;

import com.fincalc.domain.model.Installment;
import com.fincalc.domain.model.RoundingPolicy;

/**
 * Rebuilds an {@link Installment} with a shifted rent and principal.
 *
 * <p>Rent and principal move by the same delta so that {@code rent = principal + interest + fee + tax}
 * keeps holding per row. Callers that change principal must recompute closing balances and running
 * totals afterwards; this helper deliberately does not, because it has no access to neighbouring rows.
 */
public final class TailAdjustment {

    private TailAdjustment() {
    }

    public static Installment shift(Installment source, BigDecimal delta, RoundingPolicy policy) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(policy, "policy");

        BigDecimal rent = source.rentDue().add(delta).setScale(policy.scale(), policy.mode());
        BigDecimal principal = source.principalDue().add(delta).setScale(policy.scale(), policy.mode());

        return new Installment(source.periodNo(), source.startDate(), source.endDate(), source.days(),
                source.openingBalance(), principal, source.interestAccrued(), source.interestPaid(),
                source.fee(), source.tax(), rent, source.closingBalance(),
                source.cumulativePrincipal(), source.cumulativeInterest(),
                source.appliedRate(), source.memo());
    }
}
