package com.fincalc.rule.expression;

import com.fincalc.common.error.BusinessException;
import com.fincalc.domain.support.DecimalMath;
import com.fincalc.rule.RuleErrorCode;
import com.fincalc.rule.function.FormulaFunction;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One node of the expression's abstract syntax tree; {@link #eval(EvalContext)} is a recursive
 * interpreter — no reflection anywhere, function calls go through the
 * {@link FormulaFunction} interface.
 *
 * <p><b>Error model (design §8.5).</b> Evaluation never throws for a failed computation:
 * <ul>
 *   <li>a failed computation (division by zero, an illegal power) <em>is</em> an
 *       {@link ValueType#ERROR ERROR} value;</li>
 *   <li>an {@code ERROR} operand propagates as the operator's result — the left operand wins,
 *       and {@code &&}/{@code ||} keep Java's short-circuit behaviour;</li>
 *   <li>a coercion failure (text that is not a number or {@code TRUE}/{@code FALSE}) becomes an
 *       {@code ERROR} value of the corresponding {@link FormulaError};</li>
 *   <li>{@link CallNode} passes {@code ERROR} arguments to the function verbatim, so the
 *       function — {@code IFERROR} in particular — decides what an error means.</li>
 * </ul>
 */
public interface AstNode {

    /** Evaluates this subtree against the given context. */
    Value eval(EvalContext context);

    /** Converts a coercion failure raised by {@link Value} accessors into an {@code ERROR} value. */
    static Value errorFrom(BusinessException ex) {
        if (ex.errorCode() instanceof FormulaError) {
            return Value.error((FormulaError) ex.errorCode());
        }
        throw ex;
    }

    /** Operator of a {@link UnaryNode} or {@link BinaryNode}. */
    enum Op {

        /** {@code +} */ ADD,
        /** {@code -} */ SUB,
        /** {@code *} */ MUL,
        /** {@code /} */ DIV,
        /** {@code ^} */ POW,
        /** {@code =}, {@code ==} */ EQ,
        /** {@code <>}, {@code !=} */ NE,
        /** {@code <} */ LT,
        /** {@code <=} */ LE,
        /** {@code >} */ GT,
        /** {@code >=} */ GE,
        /** {@code &&} */ AND,
        /** {@code ||} */ OR,
        /** Unary {@code -} */ NEG,
        /** Unary {@code !} */ NOT
    }

    /** A literal: number, string, {@code TRUE}, {@code FALSE} or {@code BLANK}. */
    final class LiteralNode implements AstNode {

        private final Value value;

        public LiteralNode(Value value) {
            this.value = Objects.requireNonNull(value, "value");
        }

        @Override
        public Value eval(EvalContext context) {
            return value;
        }
    }

    /** A whitelisted variable reference, resolved through the context. */
    final class VarNode implements AstNode {

        private final String name;

        public VarNode(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        @Override
        public Value eval(EvalContext context) {
            return context.variable(name);
        }
    }

    /** A unary operation: {@code -x} or {@code !x}. */
    final class UnaryNode implements AstNode {

        private final Op op;
        private final AstNode operand;

        public UnaryNode(Op op, AstNode operand) {
            this.op = Objects.requireNonNull(op, "op");
            this.operand = Objects.requireNonNull(operand, "operand");
        }

        @Override
        public Value eval(EvalContext context) {
            Value value = operand.eval(context);
            if (value.type() == ValueType.ERROR) {
                return value;
            }
            try {
                switch (op) {
                    case NEG:
                        return Value.number(value.asNumber().negate());
                    case NOT:
                        return Value.bool(!value.asBoolean());
                    default:
                        throw new IllegalStateException("unary op " + op);
                }
            } catch (BusinessException ex) {
                return AstNode.errorFrom(ex);
            }
        }
    }

    /**
     * A binary operation: arithmetic ({@code + - * / ^}), comparison ({@code = <> < <= > >=}) or
     * logic ({@code && ||}).
     *
     * <p>Arithmetic runs entirely on {@code BigDecimal} at {@link MathContext#DECIMAL128}.
     * {@code ^} is left-associative and binds looser than unary minus, matching Excel:
     * {@code -2^2 = 4} and {@code 2^3^2 = 64}. Integer exponents use {@link BigDecimal#pow(int,
     * MathContext)} (negative bases allowed); fractional exponents use the shared
     * {@link DecimalMath#pow} for positive bases; Excel's edge readings — {@code 0^0 → #NUM!},
     * {@code 0^(-n) → #DIV/0!}, negative base with fractional exponent → {@code #NUM!} — produce
     * {@code ERROR} values.
     *
     * <p>Comparisons compare numbers, or strings when both operands are text; mixed text/number
     * coerces the text to a number like Excel.
     */
    final class BinaryNode implements AstNode {

        private final Op op;
        private final AstNode left;
        private final AstNode right;

        public BinaryNode(Op op, AstNode left, AstNode right) {
            this.op = Objects.requireNonNull(op, "op");
            this.left = Objects.requireNonNull(left, "left");
            this.right = Objects.requireNonNull(right, "right");
        }

        @Override
        public Value eval(EvalContext context) {
            switch (op) {
                case AND:
                    return logic(context, false);
                case OR:
                    return logic(context, true);
                case EQ: case NE: case LT: case LE: case GT: case GE:
                    return comparison(context);
                default:
                    return arithmetic(context);
            }
        }

        /** {@code &&}/{@code ||} with Java short-circuiting; an {@code ERROR} left operand propagates. */
        private Value logic(EvalContext context, boolean or) {
            Value leftValue = left.eval(context);
            if (leftValue.type() == ValueType.ERROR) {
                return leftValue;
            }
            try {
                if (leftValue.asBoolean() == or) {
                    return Value.bool(or);
                }
            } catch (BusinessException ex) {
                return AstNode.errorFrom(ex);
            }
            Value rightValue = right.eval(context);
            if (rightValue.type() == ValueType.ERROR) {
                return rightValue;
            }
            try {
                return Value.bool(rightValue.asBoolean());
            } catch (BusinessException ex) {
                return AstNode.errorFrom(ex);
            }
        }

        private Value comparison(EvalContext context) {
            Value leftValue = left.eval(context);
            if (leftValue.type() == ValueType.ERROR) {
                return leftValue;
            }
            Value rightValue = right.eval(context);
            if (rightValue.type() == ValueType.ERROR) {
                return rightValue;
            }
            try {
                int cmp = leftValue.type() == ValueType.TEXT && rightValue.type() == ValueType.TEXT
                        ? leftValue.asText().compareTo(rightValue.asText())
                        : leftValue.asNumber().compareTo(rightValue.asNumber());
                switch (op) {
                    case EQ:
                        return Value.bool(cmp == 0);
                    case NE:
                        return Value.bool(cmp != 0);
                    case LT:
                        return Value.bool(cmp < 0);
                    case LE:
                        return Value.bool(cmp <= 0);
                    case GT:
                        return Value.bool(cmp > 0);
                    case GE:
                    default:
                        return Value.bool(cmp >= 0);
                }
            } catch (BusinessException ex) {
                return AstNode.errorFrom(ex);
            }
        }

        private Value arithmetic(EvalContext context) {
            Value leftValue = left.eval(context);
            if (leftValue.type() == ValueType.ERROR) {
                return leftValue;
            }
            Value rightValue = right.eval(context);
            if (rightValue.type() == ValueType.ERROR) {
                return rightValue;
            }
            try {
                BigDecimal a = leftValue.asNumber();
                BigDecimal b = rightValue.asNumber();
                switch (op) {
                    case ADD:
                        return Value.number(a.add(b, Value.mathContext()));
                    case SUB:
                        return Value.number(a.subtract(b, Value.mathContext()));
                    case MUL:
                        return Value.number(a.multiply(b, Value.mathContext()));
                    case DIV:
                        if (b.signum() == 0) {
                            return Value.error(FormulaError.DIV_ZERO);
                        }
                        return Value.number(a.divide(b, Value.mathContext()));
                    case POW:
                    default:
                        return power(a, b);
                }
            } catch (BusinessException ex) {
                return AstNode.errorFrom(ex);
            } catch (ArithmeticException ex) {
                return Value.error(FormulaError.NUM_ERROR);
            }
        }

        private static Value power(BigDecimal base, BigDecimal exponent) {
            if (base.signum() == 0 && exponent.signum() == 0) {
                return Value.error(FormulaError.NUM_ERROR);
            }
            BigDecimal integral = exponent.stripTrailingZeros();
            if (integral.scale() <= 0) {
                if (base.signum() == 0 && integral.signum() < 0) {
                    return Value.error(FormulaError.DIV_ZERO);
                }
                return Value.number(base.pow(integral.intValueExact(), Value.mathContext()));
            }
            if (base.signum() <= 0) {
                return Value.error(FormulaError.NUM_ERROR);
            }
            return Value.number(DecimalMath.pow(base, exponent));
        }
    }

    /**
     * A function call, validated at parse time (existence and arity). Arguments are evaluated
     * eagerly and passed verbatim — including {@code ERROR} values, so the function (for example
     * {@code IFERROR}) decides what an error means.
     */
    final class CallNode implements AstNode {

        private final String code;
        private final List<AstNode> args;

        public CallNode(String code, List<AstNode> args) {
            this.code = Objects.requireNonNull(code, "code");
            this.args = List.copyOf(Objects.requireNonNull(args, "args"));
        }

        @Override
        public Value eval(EvalContext context) {
            FormulaFunction function = context.functions().find(code);
            if (function == null) {
                throw new BusinessException(RuleErrorCode.UNKNOWN_FUNCTION, code);
            }
            List<Value> values = new ArrayList<>(args.size());
            for (AstNode arg : args) {
                values.add(arg.eval(context));
            }
            return function.invoke(values, context);
        }
    }
}
