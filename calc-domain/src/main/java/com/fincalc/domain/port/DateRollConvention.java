package com.fincalc.domain.port;

import com.fincalc.common.spi.OrderedExtension;
import java.time.LocalDate;

/**
 * Date-roll convention: how a nominal date becomes an effective date when it falls on a non-business
 * day.
 *
 * <p>The built-in implementations treat Saturday and Sunday as the only non-business days. A holiday
 * calendar is a v2 extension: an implementation may consult its own calendar set, because the port
 * receives only the date to roll.
 *
 * <p>Rolling is not required to be idempotent for every convention. Every rolling built-in returns
 * a date that is not a weekend; {@code NONE} intentionally returns the nominal date unchanged, even
 * when it is a weekend.
 */
public interface DateRollConvention extends OrderedExtension {

    /**
     * Rolls a nominal date to an effective date.
     *
     * @param date nominal date, never {@code null}
     * @return the effective date
     */
    LocalDate roll(LocalDate date);
}
