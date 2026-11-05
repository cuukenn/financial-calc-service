package com.fincalc.domain.port;

import com.fincalc.common.spi.OrderedExtension;
import com.fincalc.domain.model.CalculationOptions;
import com.fincalc.domain.model.CashFlow;
import com.fincalc.domain.model.CashFlowPerspective;
import com.fincalc.domain.model.LeaseElement;
import com.fincalc.domain.model.Schedule;
import java.util.List;

/**
 * Turns a finished schedule into the ordered cash flow list every financial metric consumes
 * (design document §9.4, extension point {@code CashFlowBuilder} in §12 — the standard event
 * table can be replaced by a special cash flow convention without touching the metrics).
 *
 * <p>Implementations must produce the same total order the metrics depend on: by date, then the
 * fixed same-day type order, then renumbered {@code sequence} from 0.
 */
public interface CashFlowBuilder extends OrderedExtension {

    /** The lessor view (§9.4 default) with the run's cash flow basis. */
    default List<CashFlow> build(Schedule schedule, LeaseElement element, CalculationOptions options) {
        return build(schedule, element, options, CashFlowPerspective.LESSOR);
    }

    /**
     * Cash flows of a finished schedule, seen from {@code perspective}.
     *
     * @return an unmodifiable list ordered by date, same-day type order, then sequence 0..n-1
     */
    List<CashFlow> build(Schedule schedule, LeaseElement element, CalculationOptions options,
            CashFlowPerspective perspective);
}
