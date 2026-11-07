/**
 * Domain model, ports and supporting algorithms for financial
 * calculations. Built on top of the shared primitives module.
 */
module com.fincalc.domain {
    requires com.fincalc.common;

    exports com.fincalc.domain.calc;
    exports com.fincalc.domain.metric;
    exports com.fincalc.domain.model;
    exports com.fincalc.domain.port;
    exports com.fincalc.domain.support;
}
