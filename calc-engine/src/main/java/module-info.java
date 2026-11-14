/**
 * Use-case orchestration over the domain and rule modules.
 */
module io.github.cuukenn.fincalc.engine {
    requires io.github.cuukenn.fincalc.common;
    requires io.github.cuukenn.fincalc.domain;
    requires io.github.cuukenn.fincalc.rule;
    // 核心编排日志：slf4j-api 1.7.36+ 自带 Automatic-Module-Name: org.slf4j
    requires org.slf4j;

    exports io.github.cuukenn.fincalc.engine;
}
