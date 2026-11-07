package io.github.cuukenn.fincalc.domain.port;

import io.github.cuukenn.fincalc.common.spi.OrderedExtension;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.model.ValidationIssue;
import java.util.List;

/**
 * A schedule-level acceptance check. Validators never mutate the schedule and never throw for a
 * business failure: they report {@link ValidationIssue}s and let the engine decide whether the
 * schedule may be published.
 *
 * <p>They run after a generator produced the plan table, so they see the finished schedule — which
 * is what makes them independent of which algorithm produced it.
 */
public interface Validator extends OrderedExtension {

    /**
     * Findings for {@code schedule}. An empty list means the schedule passes this validator.
     */
    List<ValidationIssue> validate(Schedule schedule, LeaseElement element);
}
