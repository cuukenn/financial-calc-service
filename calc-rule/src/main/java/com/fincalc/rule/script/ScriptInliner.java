package com.fincalc.rule.script;

import com.fincalc.common.error.BusinessException;
import com.fincalc.rule.RuleErrorCode;
import com.fincalc.rule.expression.AstNode;
import com.fincalc.rule.expression.EvalContext;
import com.fincalc.rule.expression.ExpressionParser;
import com.fincalc.rule.expression.Value;
import com.fincalc.rule.function.FormulaFunction;
import com.fincalc.rule.function.FunctionRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compiles script definition bodies and expands every call to a script function into the
 * callee's own AST (design §8.6, Task 27), so composed scripts carry no runtime call overhead:
 * after {@link #inline} the only remaining {@link AstNode.CallNode}s target non-script
 * (built-in) functions.
 *
 * <p><b>Pipeline</b> (driven by
 * {@link FunctionRegistry#withDefinitions}): {@link #compileBodies} parses each body with
 * {@link ExpressionParser#parseAst(String, FunctionRegistry, Set)} against a scope holding the
 * built-ins plus a placeholder per defined name (forward references resolve; arity is validated
 * at parse time), attributing any parse failure to the definition's {@code 文件:行号};
 * {@link #inline} first DFS-checks the call graph ({@code FCS-RULE-3011} on any direct or
 * indirect cycle), then memoized-expands every definition, substituting formal parameters by
 * their argument subtrees, and re-checks the §8.6 depth cap of 64 <em>after</em> expansion
 * (R58) on the expanded AST.
 *
 * <p>Substitution is lexical: argument subtrees are expanded under the caller's parameter
 * environment first, then bound to the callee's formal names, so shadowing across composition
 * levels behaves like ordinary function application. Nodes are never mutated — expansion
 * rebuilds the tree and shares the immutable leaf/literal nodes.
 */
public final class ScriptInliner {

    /** §8.6's 单表达式深度 ≤ 64, re-checked on the inlined AST (R58). */
    static final int MAX_DEPTH = 64;

    private ScriptInliner() {
    }

    /**
     * Parses every definition's body source into an {@link AstNode}.
     *
     * @param definitions the definitions keyed by upper-cased name
     * @param scope       registry the bodies resolve against: the base registry plus a
     *                    {@code arity}-exact placeholder per defined name
     * @return the parsed bodies keyed by upper-cased name
     * @throws BusinessException with the body parse failure's own code (3001/3004/3009), its
     *                           detail prefixed with the definition's {@code 文件:行号}
     */
    public static Map<String, AstNode> compileBodies(Map<String, FunctionDefinition> definitions,
            FunctionRegistry scope) {
        Map<String, AstNode> bodies = new LinkedHashMap<>();
        for (FunctionDefinition definition : definitions.values()) {
            try {
                bodies.put(definition.name(), ExpressionParser.parseAst(definition.bodySource(),
                        scope, new HashSet<>(definition.parameters())));
            } catch (BusinessException ex) {
                throw withLocation(definition, ex);
            }
        }
        return bodies;
    }

    /**
     * Expands every definition into a script-call-free AST.
     *
     * @param definitions the definitions keyed by upper-cased name
     * @param bodies      the parsed bodies from {@link #compileBodies}
     * @return the fully expanded ASTs keyed by upper-cased name
     * @throws BusinessException with {@code FCS-RULE-3011} on a recursive definition chain, or
     *                           {@code FCS-RULE-3004} when an expanded AST exceeds
     *                           {@value #MAX_DEPTH} nesting levels
     */
    public static Map<String, AstNode> inline(Map<String, FunctionDefinition> definitions,
            Map<String, AstNode> bodies) {
        checkAcyclic(definitions, bodies);
        Map<String, AstNode> inlined = new LinkedHashMap<>();
        for (FunctionDefinition definition : definitions.values()) {
            AstNode expanded = expandDefinition(definition.name(), definitions, bodies, inlined);
            int depth = depth(expanded);
            if (depth > MAX_DEPTH) {
                throw new BusinessException(RuleErrorCode.EXPRESSION_SYNTAX_ERROR,
                        definition.sourceName() + " 第 " + definition.line()
                                + " 行 内联展开后表达式深度 " + depth + " > " + MAX_DEPTH);
            }
            inlined.put(definition.name(), expanded);
        }
        return inlined;
    }

    /** AST depth in the parser's counting: every node level (call, unary, binary) is one. */
    static int depth(AstNode node) {
        if (node instanceof AstNode.CallNode) {
            int max = 0;
            for (AstNode arg : ((AstNode.CallNode) node).args()) {
                max = Math.max(max, depth(arg));
            }
            return 1 + max;
        }
        if (node instanceof AstNode.UnaryNode) {
            return 1 + depth(((AstNode.UnaryNode) node).operand());
        }
        if (node instanceof AstNode.BinaryNode) {
            AstNode.BinaryNode binary = (AstNode.BinaryNode) node;
            return 1 + Math.max(depth(binary.left()), depth(binary.right()));
        }
        return 1;
    }

    /**
     * The function an inlined definition registers as: an eager {@link FormulaFunction} whose
     * {@code invoke} evaluates the definition's fully expanded body with the formal parameters
     * bound to the received argument values (via
     * {@link EvalContext#withVariables(Map)}). No script call remains inside the body, so the
     * only per-call work is the parameter binding itself.
     */
    public static final class ScriptFunction implements FormulaFunction {

        private final String code;
        private final List<String> parameters;
        private final AstNode body;
        private final String bodySource;

        public ScriptFunction(String code, List<String> parameters, AstNode body,
                String bodySource) {
            this.code = Objects.requireNonNull(code, "code");
            this.parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
            this.body = Objects.requireNonNull(body, "body");
            this.bodySource = Objects.requireNonNull(bodySource, "bodySource");
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public int minArity() {
            return parameters.size();
        }

        @Override
        public int maxArity() {
            return parameters.size();
        }

        /** The formal parameter names, upper-cased, in source order; immutable. */
        public List<String> parameters() {
            return parameters;
        }

        /** The definition's body source, feeding the definition-aware registry hash. */
        public String bodySource() {
            return bodySource;
        }

        @Override
        public Value invoke(List<Value> args, EvalContext context) {
            if (args.size() != parameters.size()) {
                throw new BusinessException(RuleErrorCode.UNKNOWN_FUNCTION,
                        code + " 恰好需要 " + parameters.size() + " 个参数, 实际 " + args.size());
            }
            Map<String, Value> bindings = new LinkedHashMap<>();
            for (int i = 0; i < parameters.size(); i++) {
                bindings.put(parameters.get(i),
                        Objects.requireNonNull(args.get(i), "args[" + i + "]"));
            }
            return body.eval(context.withVariables(bindings));
        }
    }

    // ---- expansion ----

    /** Expands one definition (memoized); the graph is known acyclic, so this terminates. */
    private static AstNode expandDefinition(String name,
            Map<String, FunctionDefinition> definitions, Map<String, AstNode> bodies,
            Map<String, AstNode> memo) {
        AstNode done = memo.get(name);
        if (done != null) {
            return done;
        }
        AstNode expanded = expand(bodies.get(name), definitions, bodies, memo, Map.of());
        memo.put(name, expanded);
        return expanded;
    }

    /**
     * Rewrites one subtree: formal parameters resolve to their argument subtrees, and calls to
     * script functions expand to the callee's (recursively inlined) body with the call's
     * arguments bound to the formal names. Built-in calls are rebuilt with expanded arguments.
     */
    private static AstNode expand(AstNode node, Map<String, FunctionDefinition> definitions,
            Map<String, AstNode> bodies, Map<String, AstNode> memo, Map<String, AstNode> env) {
        if (node instanceof AstNode.VarNode) {
            AstNode substituted = env.get(((AstNode.VarNode) node).name());
            return substituted != null ? substituted : node;
        }
        if (node instanceof AstNode.LiteralNode) {
            return node;
        }
        if (node instanceof AstNode.UnaryNode) {
            AstNode.UnaryNode unary = (AstNode.UnaryNode) node;
            return new AstNode.UnaryNode(unary.op(),
                    expand(unary.operand(), definitions, bodies, memo, env));
        }
        if (node instanceof AstNode.BinaryNode) {
            AstNode.BinaryNode binary = (AstNode.BinaryNode) node;
            return new AstNode.BinaryNode(binary.op(),
                    expand(binary.left(), definitions, bodies, memo, env),
                    expand(binary.right(), definitions, bodies, memo, env));
        }
        AstNode.CallNode call = (AstNode.CallNode) node;
        List<AstNode> expandedArgs = new ArrayList<>(call.args().size());
        for (AstNode arg : call.args()) {
            expandedArgs.add(expand(arg, definitions, bodies, memo, env));
        }
        FunctionDefinition callee = definitions.get(call.code());
        if (callee == null) {
            return new AstNode.CallNode(call.code(), expandedArgs);
        }
        // Parse-time arity validation against the callee's placeholder guarantees this.
        if (expandedArgs.size() != callee.parameters().size()) {
            throw new BusinessException(RuleErrorCode.UNKNOWN_FUNCTION,
                    call.code() + " 恰好需要 " + callee.parameters().size()
                            + " 个参数, 实际 " + expandedArgs.size());
        }
        AstNode calleeBody = expandDefinition(callee.name(), definitions, bodies, memo);
        Map<String, AstNode> bound = new LinkedHashMap<>();
        for (int i = 0; i < callee.parameters().size(); i++) {
            bound.put(callee.parameters().get(i), expandedArgs.get(i));
        }
        return expand(calleeBody, definitions, bodies, memo, bound);
    }

    // ---- recursion check (FCS-RULE-3011) ----

    /**
     * DFS cycle check over the definitions' call graph: an edge {@code A -> B} exists when A's
     * body calls script function B. Any back edge to a node on the current DFS path is a direct
     * or indirect recursion and raises {@code FCS-RULE-3011}, naming the chain.
     */
    private static void checkAcyclic(Map<String, FunctionDefinition> definitions,
            Map<String, AstNode> bodies) {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (FunctionDefinition definition : definitions.values()) {
            edges.put(definition.name(),
                    collectScriptCalls(bodies.get(definition.name()), definitions.keySet()));
        }
        Map<String, Boolean> visiting = new HashMap<>();
        List<String> path = new ArrayList<>();
        for (String name : edges.keySet()) {
            visit(name, edges, visiting, path, definitions);
        }
    }

    private static void visit(String name, Map<String, Set<String>> edges,
            Map<String, Boolean> visiting, List<String> path,
            Map<String, FunctionDefinition> definitions) {
        Boolean inProgress = visiting.get(name);
        if (inProgress != null) {
            if (inProgress) {
                List<String> cycle = new ArrayList<>(path.subList(path.indexOf(name),
                        path.size()));
                cycle.add(name);
                FunctionDefinition definition = definitions.get(name);
                throw new BusinessException(RuleErrorCode.SCRIPT_RECURSION,
                        String.join(" -> ", cycle) + " (" + definition.sourceName() + " 第 "
                                + definition.line() + " 行)");
            }
            return;
        }
        visiting.put(name, true);
        path.add(name);
        for (String next : edges.get(name)) {
            visit(next, edges, visiting, path, definitions);
        }
        path.remove(path.size() - 1);
        visiting.put(name, false);
    }

    /** The codes called by this subtree that are script-defined names. */
    private static Set<String> collectScriptCalls(AstNode node, Set<String> scriptNames) {
        Set<String> calls = new LinkedHashSet<>();
        collect(node, scriptNames, calls);
        return calls;
    }

    private static void collect(AstNode node, Set<String> scriptNames, Set<String> into) {
        if (node instanceof AstNode.CallNode) {
            AstNode.CallNode call = (AstNode.CallNode) node;
            if (scriptNames.contains(call.code())) {
                into.add(call.code());
            }
            for (AstNode arg : call.args()) {
                collect(arg, scriptNames, into);
            }
        } else if (node instanceof AstNode.UnaryNode) {
            collect(((AstNode.UnaryNode) node).operand(), scriptNames, into);
        } else if (node instanceof AstNode.BinaryNode) {
            AstNode.BinaryNode binary = (AstNode.BinaryNode) node;
            collect(binary.left(), scriptNames, into);
            collect(binary.right(), scriptNames, into);
        }
    }

    // ---- helpers ----

    /** Re-raises a body parse failure with the definition's {@code 文件:行号} prepended. */
    private static BusinessException withLocation(FunctionDefinition definition,
            BusinessException cause) {
        String location = definition.sourceName() + " 第 " + definition.line() + " 行: ";
        Object[] args = cause.args();
        Object[] effective = new Object[Math.max(1, args.length)];
        if (args.length == 0) {
            effective[0] = location;
        } else {
            effective[0] = location + args[0];
            System.arraycopy(args, 1, effective, 1, args.length - 1);
        }
        return new BusinessException(cause.errorCode(), effective);
    }
}
