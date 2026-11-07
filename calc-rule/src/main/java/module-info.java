/**
 * Rule engine: expression parsing/evaluation, rule compilation and
 * script inlining, on top of the domain module.
 */
module com.fincalc.rule {
    requires com.fincalc.common;
    requires com.fincalc.domain;

    exports com.fincalc.rule;
    exports com.fincalc.rule.compiler;
    exports com.fincalc.rule.generator;
    exports com.fincalc.rule.expression;
    exports com.fincalc.rule.function;
    exports com.fincalc.rule.model;
    exports com.fincalc.rule.script;
}
