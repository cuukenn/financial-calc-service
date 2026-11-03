package com.fincalc.domain.port;

import com.fincalc.domain.calc.CalculationContext;
import com.fincalc.domain.model.Schedule;
import com.fincalc.common.spi.OrderedExtension;

/**
 * Port of an amortization algorithm. Implementations are stateless and selected by
 * {@link #code()}, which matches the {@code RepaymentMethodId} name or a rule-driven code.
 */
public interface ScheduleGenerator extends OrderedExtension {

    /** Whether this generator can handle the given context. */
    boolean supports(CalculationContext context);

    /** Produces a raw schedule: installments only, no cash flows and no metrics. */
    Schedule generate(CalculationContext context);
}
