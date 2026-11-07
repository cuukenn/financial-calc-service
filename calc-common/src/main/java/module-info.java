/**
 * Core shared primitives: money, date/time, error codes and the
 * extension SPI. Depends only on the JDK.
 */
module com.fincalc.common {
    requires java.base;

    exports com.fincalc.common.date;
    exports com.fincalc.common.error;
    exports com.fincalc.common.money;
    exports com.fincalc.common.spi;
    exports com.fincalc.common.time;
}
