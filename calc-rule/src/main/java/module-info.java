/**
 * Rule engine: expression parsing/evaluation, rule compilation and
 * script inlining, on top of the domain module.
 */
module io.github.cuukenn.fincalc.rule {
    requires io.github.cuukenn.fincalc.common;
    requires io.github.cuukenn.fincalc.domain;

    exports io.github.cuukenn.fincalc.rule;
    exports io.github.cuukenn.fincalc.rule.compiler;
    exports io.github.cuukenn.fincalc.rule.generator;
    exports io.github.cuukenn.fincalc.rule.expression;
    exports io.github.cuukenn.fincalc.rule.function;
    exports io.github.cuukenn.fincalc.rule.model;
    exports io.github.cuukenn.fincalc.rule.script;
}
