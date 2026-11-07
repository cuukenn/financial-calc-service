/**
 * Use-case orchestration over the domain and rule modules.
 */
module com.fincalc.engine {
    requires com.fincalc.common;
    requires com.fincalc.domain;
    requires com.fincalc.rule;

    exports com.fincalc.engine;
}
