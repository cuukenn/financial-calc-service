/**
 * Use-case orchestration over the domain and rule modules.
 */
module io.github.cuukenn.fincalc.engine {
    requires io.github.cuukenn.fincalc.common;
    requires io.github.cuukenn.fincalc.domain;
    requires io.github.cuukenn.fincalc.rule;

    exports io.github.cuukenn.fincalc.engine;
}
