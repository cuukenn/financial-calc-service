/**
 * Domain model, ports and supporting algorithms for financial
 * calculations. Built on top of the shared primitives module.
 */
module io.github.cuukenn.fincalc.domain {
    requires io.github.cuukenn.fincalc.common;

    exports io.github.cuukenn.fincalc.domain.calc;
    exports io.github.cuukenn.fincalc.domain.metric;
    exports io.github.cuukenn.fincalc.domain.model;
    exports io.github.cuukenn.fincalc.domain.port;
    exports io.github.cuukenn.fincalc.domain.support;
    exports io.github.cuukenn.fincalc.domain.support.algo;
    exports io.github.cuukenn.fincalc.domain.support.cashflow;
}
