package io.github.cuukenn.fincalc.domain.support.validate;

import io.github.cuukenn.fincalc.common.money.MoneyTolerance;
import io.github.cuukenn.fincalc.domain.calc.CalcErrorCode;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.IssueLevel;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.model.ValidationIssue;
import io.github.cuukenn.fincalc.domain.port.Validator;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * Checks that the last installment leaves exactly the balance the residual policy asks for:
 * zero, or the residual when it was ballooned into the rent.
 */
public final class TailBalanceValidator implements Validator {

    /**
     * A difference of one cent or more is reported; anything smaller is absorbed rounding. The value
     * is single-sourced in {@link MoneyTolerance} so every §3.4 check judges on the same line.
     */
    public static final BigDecimal TOLERANCE = MoneyTolerance.MONEY_COMPARE;

    @Override
    public String code() {
        return "TAIL_BALANCE";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public List<ValidationIssue> validate(Schedule schedule, LeaseElement element) {
        List<Installment> installments = schedule.installments();
        if (installments.isEmpty()) {
            return Collections.emptyList();
        }
        Installment last = installments.get(installments.size() - 1);
        BigDecimal expected = expectedClosingBalance(element);
        if (last.closingBalance().subtract(expected).abs().compareTo(TOLERANCE) < 0) {
            return Collections.emptyList();
        }
        return Collections.singletonList(new ValidationIssue(IssueLevel.ERROR,
                CalcErrorCode.TAIL_BALANCE_MISMATCH.code(),
                CalcErrorCode.TAIL_BALANCE_MISMATCH.code() + ": 末期余额 "
                        + last.closingBalance().toPlainString()
                        + " 与预期 " + expected.toPlainString() + " 不一致", last.periodNo()));
    }

    /**
     * Balance the schedule must end with under this element's residual policy, expressed at the
     * element's output scale: the generator scales the balance it leaves behind, so comparing against
     * the raw residual would report a valid schedule whenever the rounding policy is coarser than the
     * input.
     */
    private static BigDecimal expectedClosingBalance(LeaseElement element) {
        return element.outstandingResidual()
                .setScale(element.rounding().scale(), element.rounding().mode());
    }
}
