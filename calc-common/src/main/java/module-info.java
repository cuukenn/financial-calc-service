/**
 * Core shared primitives: money, date/time, error codes and the
 * extension SPI. Depends only on the JDK.
 */
module io.github.cuukenn.fincalc.common {
    requires java.base;

    exports io.github.cuukenn.fincalc.common.date;
    exports io.github.cuukenn.fincalc.common.error;
    exports io.github.cuukenn.fincalc.common.money;
    exports io.github.cuukenn.fincalc.common.spi;
    exports io.github.cuukenn.fincalc.common.time;
}
