/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A small, sandboxed expression language over parsed JSON (maps, lists,
 * strings, numbers, booleans and null) — the evaluator of the
 * {@code expression} output guardrail. A recursive-descent parser in the style
 * of {@code CalculatorTool.SafeMathParser}: no script engine, no reflection, no
 * method calls, no template engine. Everything an expression can do is listed
 * below; nothing else is reachable from it.
 *
 * <h2>Grammar</h2>
 *
 * <pre>
 * expression := or
 * or         := and ('||' and)*
 * and        := equality ('&amp;&amp;' equality)*
 * equality   := relational (('==' | '!=') relational)?      -- not chainable
 * relational := additive (('&lt;' | '&lt;=' | '&gt;' | '&gt;=') additive)?  -- not chainable
 * additive   := term (('+' | '-') term)*
 * term       := unary (('*' | '/' | '%') unary)*
 * unary      := ('!' | '-') unary | primary
 * primary    := NUMBER | STRING | 'true' | 'false' | 'null'
 *             | '(' expression ')'
 *             | FUNCTION '(' [expression (',' expression)*] ')'
 *             | path
 * path       := (IDENT | '$') ('.' IDENT | '[' (INDEX | '*' | STRING) ']')*
 * </pre>
 *
 * Numbers are decimal literals ({@code 12}, {@code 12.50}); strings are single-
 * or double-quoted, with backslash escaping only a backslash or a quote.
 * {@code $} is the reply itself (for a reply that is a JSON array:
 * {@code $[*].amount}). {@code lines[*].amount} is a wildcard and yields a list
 * with one entry per array element. Functions: {@code sum}, {@code count},
 * {@code min}, {@code max}, {@code len}, {@code abs}, {@code round(x, n)},
 * {@code exists(path)} and {@code approx(a, b, tolerance)}.
 *
 * <h2>Semantics</h2>
 * <ul>
 * <li>Numbers are {@link BigDecimal}: {@code 0.1 + 0.2 == 0.3} is true.
 * {@code + - * %} and {@code /} compute to 34 significant digits
 * ({@link MathContext#DECIMAL128}, half-even); {@code round} rounds half-up.
 * {@code 1.0 == 1.00} is true.</li>
 * <li>A path that does not exist is {@code null}, as is a JSON null. A wildcard
 * over something that is not an array is {@code null}; after a wildcard, an
 * element missing the rest of the path contributes {@code null} to the
 * list.</li>
 * <li>{@code == null} and {@code != null} test for null. Any other comparison
 * with a null operand ({@code <}, {@code >}, ...) is {@code false}.</li>
 * <li>Arithmetic, logic or a function applied to null, and any type mismatch (a
 * number compared with a string, {@code &&} on a number, {@code sum} over a
 * list holding text), is an evaluation error — "could not decide", never
 * {@code false}. So is division by zero, an empty {@code min}/{@code max}, a
 * number with more than 1000 digits or a scale beyond ±1000, and exceeding a
 * bound below.</li>
 * <li>{@code &&} and {@code ||} short-circuit, so
 * {@code exists(discount) && discount > 0} never evaluates the right side for a
 * reply without a discount.</li>
 * <li>{@code exists(path)} is true when every step of the path is present (a
 * key holding JSON null is present). It takes a path without a wildcard.</li>
 * </ul>
 *
 * <h2>Bounds</h2> An expression is at most {@value #MAX_EXPRESSION_CHARS}
 * characters, {@value #MAX_DEPTH} levels of nesting, {@value #MAX_NODES} parts
 * and {@value #MAX_PATH_SEGMENTS} segments per path. Evaluating it touches at
 * most {@value #MAX_FAN_OUT} values per path and takes at most
 * {@value #MAX_STEPS} steps. Compile once ({@link #compile}), evaluate any
 * number of times: a compiled expression is immutable and thread-safe.
 * <p>
 * Kept to the JDK and to Java 17 syntax on purpose: ClusterFuzzLite compiles a
 * vendored copy of this file on an older JDK.
 */
public final class SafeJsonExpression {

    public static final int MAX_EXPRESSION_CHARS = 2000;
    public static final int MAX_DEPTH = 32;
    public static final int MAX_NODES = 500;
    public static final int MAX_PATH_SEGMENTS = 32;
    public static final int MAX_FAN_OUT = 10_000;
    public static final int MAX_STEPS = 200_000;
    public static final int MAX_ROUND_DIGITS = 20;
    /** Bound on a number's digits and on its scale, either sign. */
    public static final int MAX_NUMBER_SCALE = 1000;

    static final MathContext MATH = MathContext.DECIMAL128;

    /** The evaluation-error codes; EDDI-written, never data from the reply. */
    public static final String ERR_TYPE_MISMATCH = "type_mismatch";
    public static final String ERR_NULL_OPERAND = "null_operand";
    public static final String ERR_DIVISION_BY_ZERO = "division_by_zero";
    public static final String ERR_EMPTY_AGGREGATE = "empty_aggregate";
    public static final String ERR_BAD_ARGUMENT = "bad_argument";
    public static final String ERR_NUMBER_RANGE = "number_out_of_range";
    public static final String ERR_FAN_OUT = "fan_out";
    public static final String ERR_STEP_BUDGET = "step_budget";

    private final Node root;
    private final int nodeCount;

    private SafeJsonExpression(Node root, int nodeCount) {
        this.root = root;
        this.nodeCount = nodeCount;
    }

    /**
     * Parses an expression.
     *
     * @throws SyntaxException
     *             with the position (0-based character offset) of the problem
     */
    public static SafeJsonExpression compile(String source) {
        if (source == null || source.isBlank()) {
            throw new SyntaxException("the expression is empty", 0);
        }
        if (source.length() > MAX_EXPRESSION_CHARS) {
            throw new SyntaxException("the expression is longer than " + MAX_EXPRESSION_CHARS + " characters", MAX_EXPRESSION_CHARS);
        }
        Parser parser = new Parser(new Lexer(source).tokenize());
        Node node = parser.parseExpression();
        parser.expectEnd();
        return new SafeJsonExpression(node, parser.nodes);
    }

    /** How many parts the expression has; at most {@link #MAX_NODES}. */
    public int nodeCount() {
        return nodeCount;
    }

    /**
     * Evaluates against a parsed JSON value (a {@code Map}, {@code List} or
     * scalar). Never modifies it.
     *
     * @return a {@link Boolean}, {@link BigDecimal}, {@link String}, {@code List},
     *         {@code Map} or null
     * @throws EvaluationException
     *             when the expression cannot be evaluated over this data
     */
    public Object evaluate(Object data) {
        Context context = new Context(data);
        try {
            return root.eval(context);
        } catch (ArithmeticException e) {
            throw new EvaluationException(ERR_NUMBER_RANGE);
        }
    }

    // === Errors ===

    /** The expression is not valid; no stack trace, the message is EDDI's. */
    public static final class SyntaxException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final int position;

        SyntaxException(String message, int position) {
            super(message);
            this.position = position;
        }

        /** 0-based character offset in the expression. */
        public int position() {
            return position;
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    /**
     * The expression could not be evaluated over this data. {@link #code()} is one
     * of the {@code ERR_*} constants — never anything taken from the data.
     */
    public static final class EvaluationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        EvaluationException(String code) {
            super(code, null, false, false);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    // === Lexer ===

    private enum T {
        NUMBER, STRING, IDENT, DOLLAR, LPAREN, RPAREN, LBRACKET, RBRACKET, DOT, COMMA, PLUS, MINUS, STAR, SLASH, PERCENT, EQ, NE, LT, LE, GT, GE, AND, OR, NOT, EOF
    }

    private static final class Token {
        final T type;
        final String text;
        final Object value;
        final int pos;

        Token(T type, String text, Object value, int pos) {
            this.type = type;
            this.text = text;
            this.value = value;
            this.pos = pos;
        }
    }

    private static final class Lexer {
        private static final char BACKSLASH = 92;
        private final String s;
        private int pos;

        Lexer(String s) {
            this.s = s;
        }

        List<Token> tokenize() {
            List<Token> tokens = new ArrayList<>();
            while (true) {
                while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                    pos++;
                }
                if (pos >= s.length()) {
                    tokens.add(new Token(T.EOF, "", null, pos));
                    return tokens;
                }
                tokens.add(next());
            }
        }

        private Token next() {
            int start = pos;
            char c = s.charAt(pos);
            if (isDigit(c)) {
                return number(start);
            }
            if (c == '\'' || c == '"') {
                return string(start, c);
            }
            if (isIdentStart(c)) {
                while (pos < s.length() && isIdentPart(s.charAt(pos))) {
                    pos++;
                }
                return new Token(T.IDENT, s.substring(start, pos), null, start);
            }
            pos++;
            switch (c) {
                case '$' :
                    return new Token(T.DOLLAR, "$", null, start);
                case '(' :
                    return new Token(T.LPAREN, "(", null, start);
                case ')' :
                    return new Token(T.RPAREN, ")", null, start);
                case '[' :
                    return new Token(T.LBRACKET, "[", null, start);
                case ']' :
                    return new Token(T.RBRACKET, "]", null, start);
                case '.' :
                    return new Token(T.DOT, ".", null, start);
                case ',' :
                    return new Token(T.COMMA, ",", null, start);
                case '+' :
                    return new Token(T.PLUS, "+", null, start);
                case '-' :
                    return new Token(T.MINUS, "-", null, start);
                case '*' :
                    return new Token(T.STAR, "*", null, start);
                case '/' :
                    return new Token(T.SLASH, "/", null, start);
                case '%' :
                    return new Token(T.PERCENT, "%", null, start);
                case '=' :
                    if (take('=')) {
                        return new Token(T.EQ, "==", null, start);
                    }
                    throw new SyntaxException("'=' is not an operator; compare with '=='", start);
                case '!' :
                    return take('=') ? new Token(T.NE, "!=", null, start) : new Token(T.NOT, "!", null, start);
                case '<' :
                    return take('=') ? new Token(T.LE, "<=", null, start) : new Token(T.LT, "<", null, start);
                case '>' :
                    return take('=') ? new Token(T.GE, ">=", null, start) : new Token(T.GT, ">", null, start);
                case '&' :
                    if (take('&')) {
                        return new Token(T.AND, "&&", null, start);
                    }
                    throw new SyntaxException("'&' is not an operator; use '&&'", start);
                case '|' :
                    if (take('|')) {
                        return new Token(T.OR, "||", null, start);
                    }
                    throw new SyntaxException("'|' is not an operator; use '||'", start);
                default :
                    throw new SyntaxException("unexpected character" + shown(c), start);
            }
        }

        private boolean take(char expected) {
            if (pos < s.length() && s.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private Token number(int start) {
            while (pos < s.length() && isDigit(s.charAt(pos))) {
                pos++;
            }
            if (pos + 1 < s.length() && s.charAt(pos) == '.' && isDigit(s.charAt(pos + 1))) {
                pos++;
                while (pos < s.length() && isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < s.length() && isIdentPart(s.charAt(pos))) {
                throw new SyntaxException("a number must not be followed by a letter", pos);
            }
            String text = s.substring(start, pos);
            return new Token(T.NUMBER, text, new BigDecimal(text), start);
        }

        private Token string(int start, char quote) {
            pos++;
            StringBuilder out = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == quote) {
                    return new Token(T.STRING, s.substring(start, pos), out.toString(), start);
                }
                if (c == BACKSLASH) {
                    if (pos >= s.length()) {
                        break;
                    }
                    char e = s.charAt(pos++);
                    if (e != BACKSLASH && e != '\'' && e != '"') {
                        throw new SyntaxException("unsupported escape in a string: a backslash may only escape a backslash or a quote", pos - 2);
                    }
                    out.append(e);
                } else {
                    out.append(c);
                }
            }
            throw new SyntaxException("unterminated string", start);
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private static boolean isIdentStart(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
        }

        private static boolean isIdentPart(char c) {
            return isIdentStart(c) || isDigit(c);
        }

        private static String shown(char c) {
            return c >= 0x21 && c <= 0x7e ? " '" + c + "'" : String.format(" U+%04X", (int) c);
        }
    }

    // === Parser ===

    private static final class Parser {
        private final List<Token> tokens;
        private int index;
        private int depth;
        int nodes;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        private Token peek() {
            return tokens.get(index);
        }

        private Token advance() {
            Token token = tokens.get(index);
            if (token.type != T.EOF) {
                index++;
            }
            return token;
        }

        private boolean at(T type) {
            return peek().type == type;
        }

        private boolean atComparison() {
            return at(T.LT) || at(T.LE) || at(T.GT) || at(T.GE) || at(T.EQ) || at(T.NE);
        }

        private Token expect(T type, String what) {
            if (!at(type)) {
                throw new SyntaxException("expected " + what, peek().pos);
            }
            return advance();
        }

        void expectEnd() {
            if (!at(T.EOF)) {
                throw new SyntaxException("unexpected '" + abbreviated(peek().text) + "' after the end of the expression", peek().pos);
            }
        }

        private <N extends Node> N node(N n, int pos) {
            if (++nodes > MAX_NODES) {
                throw new SyntaxException("the expression has more than " + MAX_NODES + " parts", pos);
            }
            return n;
        }

        private void enter(int pos) {
            if (++depth > MAX_DEPTH) {
                throw new SyntaxException("the expression is nested more than " + MAX_DEPTH + " levels deep", pos);
            }
        }

        Node parseExpression() {
            enter(peek().pos);
            try {
                return parseOr();
            } finally {
                depth--;
            }
        }

        private Node parseOr() {
            Node left = parseAnd();
            while (at(T.OR)) {
                int pos = advance().pos;
                left = node(new Logic(true, left, parseAnd()), pos);
            }
            return left;
        }

        private Node parseAnd() {
            Node left = parseEquality();
            while (at(T.AND)) {
                int pos = advance().pos;
                left = node(new Logic(false, left, parseEquality()), pos);
            }
            return left;
        }

        private Node parseEquality() {
            Node left = parseRelational();
            if (at(T.EQ) || at(T.NE)) {
                Token op = advance();
                left = node(new Equality(op.type == T.NE, left, parseRelational()), op.pos);
                if (atComparison()) {
                    throw new SyntaxException("comparisons cannot be chained; combine them with '&&'", peek().pos);
                }
            }
            return left;
        }

        private Node parseRelational() {
            Node left = parseAdditive();
            if (at(T.LT) || at(T.LE) || at(T.GT) || at(T.GE)) {
                Token op = advance();
                left = node(new Relational(op.type, left, parseAdditive()), op.pos);
                if (at(T.LT) || at(T.LE) || at(T.GT) || at(T.GE)) {
                    throw new SyntaxException("comparisons cannot be chained; combine them with '&&'", peek().pos);
                }
            }
            return left;
        }

        private Node parseAdditive() {
            Node left = parseTerm();
            while (at(T.PLUS) || at(T.MINUS)) {
                Token op = advance();
                left = node(new Arithmetic(op.type, left, parseTerm()), op.pos);
            }
            return left;
        }

        private Node parseTerm() {
            Node left = parseUnary();
            while (at(T.STAR) || at(T.SLASH) || at(T.PERCENT)) {
                Token op = advance();
                left = node(new Arithmetic(op.type, left, parseUnary()), op.pos);
            }
            return left;
        }

        private Node parseUnary() {
            if (at(T.NOT) || at(T.MINUS)) {
                Token op = advance();
                enter(op.pos);
                try {
                    Node operand = parseUnary();
                    return node(op.type == T.NOT ? new Not(operand) : new Negate(operand), op.pos);
                } finally {
                    depth--;
                }
            }
            return parsePrimary();
        }

        private Node parsePrimary() {
            Token token = peek();
            switch (token.type) {
                case NUMBER :
                case STRING :
                    advance();
                    return node(new Literal(token.value), token.pos);
                case LPAREN : {
                    advance();
                    Node inner = parseExpression();
                    expect(T.RPAREN, "')'");
                    return inner;
                }
                case DOLLAR :
                    return parsePath();
                case IDENT :
                    if ("true".equals(token.text) || "false".equals(token.text)) {
                        advance();
                        return node(new Literal(Boolean.valueOf(token.text)), token.pos);
                    }
                    if ("null".equals(token.text)) {
                        advance();
                        return node(new Literal(null), token.pos);
                    }
                    if (tokens.get(index + 1).type == T.LPAREN) {
                        return parseCall();
                    }
                    return parsePath();
                case EOF :
                    throw new SyntaxException("the expression ends where a value is expected", token.pos);
                default :
                    throw new SyntaxException("expected a value, found '" + abbreviated(token.text) + "'", token.pos);
            }
        }

        private Node parseCall() {
            Token name = advance();
            Function function = Function.byName(name.text);
            if (function == null) {
                throw new SyntaxException(
                        "unknown function '" + abbreviated(name.text) + "' (allowed: sum, count, min, max, len, abs, round, exists, approx)",
                        name.pos);
            }
            expect(T.LPAREN, "'('");
            List<Node> args = new ArrayList<>();
            if (!at(T.RPAREN)) {
                enter(name.pos);
                try {
                    args.add(function == Function.EXISTS ? parseExistsArgument() : parseExpression());
                    while (at(T.COMMA)) {
                        advance();
                        if (args.size() >= function.maxArgs) {
                            throw new SyntaxException(function.label + " takes " + function.arity, name.pos);
                        }
                        args.add(parseExpression());
                    }
                } finally {
                    depth--;
                }
            }
            expect(T.RPAREN, "')' after the arguments of " + function.label);
            if (args.size() < function.minArgs || args.size() > function.maxArgs) {
                throw new SyntaxException(function.label + " takes " + function.arity, name.pos);
            }
            return node(new Call(function, args), name.pos);
        }

        private Node parseExistsArgument() {
            int pos = peek().pos;
            if (!at(T.IDENT) && !at(T.DOLLAR)) {
                throw new SyntaxException("exists() takes a path, such as exists(invoice.total)", pos);
            }
            Path path = parsePath();
            if (path.hasWildcard) {
                throw new SyntaxException("exists() takes a path without a [*] wildcard", pos);
            }
            return path;
        }

        private Path parsePath() {
            Token start = advance();
            List<Segment> segments = new ArrayList<>();
            if (start.type == T.IDENT) {
                segments.add(Segment.field(start.text));
            }
            while (at(T.DOT) || at(T.LBRACKET)) {
                Token token = advance();
                if (token.type == T.DOT) {
                    segments.add(Segment.field(expect(T.IDENT, "a field name after '.'").text));
                } else {
                    Token inner = advance();
                    if (inner.type == T.STAR) {
                        segments.add(Segment.WILDCARD);
                    } else if (inner.type == T.STRING) {
                        segments.add(Segment.field((String) inner.value));
                    } else if (inner.type == T.NUMBER && inner.text.indexOf('.') < 0 && inner.text.length() <= 9) {
                        segments.add(Segment.index(Integer.parseInt(inner.text)));
                    } else {
                        throw new SyntaxException("expected an index, '*' or a quoted field name inside '[ ]'", inner.pos);
                    }
                    expect(T.RBRACKET, "']'");
                }
                if (segments.size() > MAX_PATH_SEGMENTS) {
                    throw new SyntaxException("a path has more than " + MAX_PATH_SEGMENTS + " segments", token.pos);
                }
            }
            return node(new Path(segments), start.pos);
        }

        private static String abbreviated(String text) {
            return text.length() > 20 ? text.substring(0, 20) + "..." : text;
        }
    }

    // === Evaluation ===

    private static final class Context {
        final Object data;
        int steps;

        Context(Object data) {
            this.data = data;
        }

        void step(int count) {
            steps += count;
            if (steps > MAX_STEPS) {
                throw new EvaluationException(ERR_STEP_BUDGET);
            }
        }
    }

    private abstract static class Node {
        abstract Object eval(Context context);
    }

    private static final class Literal extends Node {
        final Object value;

        Literal(Object value) {
            this.value = value;
        }

        @Override
        Object eval(Context context) {
            context.step(1);
            return value;
        }
    }

    private static final class Logic extends Node {
        final boolean or;
        final Node left;
        final Node right;

        Logic(boolean or, Node left, Node right) {
            this.or = or;
            this.left = left;
            this.right = right;
        }

        @Override
        Object eval(Context context) {
            context.step(1);
            boolean l = bool(left.eval(context));
            if (or == l) {
                // true || ... is true, false && ... is false: the right side is not evaluated
                return l;
            }
            return bool(right.eval(context));
        }
    }

    private static final class Not extends Node {
        final Node operand;

        Not(Node operand) {
            this.operand = operand;
        }

        @Override
        Object eval(Context context) {
            context.step(1);
            return !bool(operand.eval(context));
        }
    }

    private static final class Negate extends Node {
        final Node operand;

        Negate(Node operand) {
            this.operand = operand;
        }

        @Override
        Object eval(Context context) {
            context.step(1);
            return number(operand.eval(context)).negate();
        }
    }

    private static final class Equality extends Node {
        final boolean negated;
        final Node left;
        final Node right;

        Equality(boolean negated, Node left, Node right) {
            this.negated = negated;
            this.left = left;
            this.right = right;
        }

        @Override
        Object eval(Context context) {
            context.step(1);
            Object a = left.eval(context);
            Object b = right.eval(context);
            boolean equal;
            if (a == null || b == null) {
                equal = a == b;
            } else if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
                equal = x.compareTo(y) == 0;
            } else if ((a instanceof String && b instanceof String) || (a instanceof Boolean && b instanceof Boolean)) {
                equal = a.equals(b);
            } else {
                throw new EvaluationException(ERR_TYPE_MISMATCH);
            }
            return negated != equal;
        }
    }

    private static final class Relational extends Node {
        final T op;
        final Node left;
        final Node right;

        Relational(T op, Node left, Node right) {
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        Object eval(Context context) {
            context.step(1);
            Object a = left.eval(context);
            Object b = right.eval(context);
            if (a == null || b == null) {
                return Boolean.FALSE;
            }
            int cmp;
            if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
                cmp = x.compareTo(y);
            } else if (a instanceof String x && b instanceof String y) {
                cmp = x.compareTo(y);
            } else {
                throw new EvaluationException(ERR_TYPE_MISMATCH);
            }
            switch (op) {
                case LT :
                    return cmp < 0;
                case LE :
                    return cmp <= 0;
                case GT :
                    return cmp > 0;
                default :
                    return cmp >= 0;
            }
        }
    }

    private static final class Arithmetic extends Node {
        final T op;
        final Node left;
        final Node right;

        Arithmetic(T op, Node left, Node right) {
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        Object eval(Context context) {
            context.step(1);
            BigDecimal a = number(left.eval(context));
            BigDecimal b = number(right.eval(context));
            BigDecimal result;
            switch (op) {
                case PLUS :
                    result = a.add(b, MATH);
                    break;
                case MINUS :
                    result = a.subtract(b, MATH);
                    break;
                case STAR :
                    result = a.multiply(b, MATH);
                    break;
                case SLASH :
                    if (b.signum() == 0) {
                        throw new EvaluationException(ERR_DIVISION_BY_ZERO);
                    }
                    result = a.divide(b, MATH);
                    break;
                default :
                    if (b.signum() == 0) {
                        throw new EvaluationException(ERR_DIVISION_BY_ZERO);
                    }
                    result = a.remainder(b, MATH);
                    break;
            }
            return checked(result);
        }
    }

    /** One step of a path: a field name, an array index, or the wildcard. */
    private static final class Segment {
        static final Segment WILDCARD = new Segment(null, -1);
        final String field;
        final int index;

        private Segment(String field, int index) {
            this.field = field;
            this.index = index;
        }

        static Segment field(String name) {
            return new Segment(name, -1);
        }

        static Segment index(int index) {
            return new Segment(null, index);
        }

        boolean isWildcard() {
            return this == WILDCARD;
        }
    }

    private static final class Path extends Node {
        final List<Segment> segments;
        final boolean hasWildcard;

        Path(List<Segment> segments) {
            this.segments = List.copyOf(segments);
            boolean wildcard = false;
            for (Segment segment : segments) {
                wildcard |= segment.isWildcard();
            }
            this.hasWildcard = wildcard;
        }

        @Override
        Object eval(Context context) {
            context.step(1);
            Object single = context.data;
            List<Object> current = null;
            for (Segment segment : segments) {
                if (current == null) {
                    context.step(1);
                    if (!segment.isWildcard()) {
                        single = step(single, segment);
                        continue;
                    }
                    if (!(single instanceof List<?> list)) {
                        return null;
                    }
                    fanOut(context, list.size(), list.size());
                    current = new ArrayList<>(list);
                    continue;
                }
                context.step(current.size());
                List<Object> next = new ArrayList<>(current.size());
                for (Object value : current) {
                    if (!segment.isWildcard()) {
                        next.add(step(value, segment));
                    } else if (value instanceof List<?> list) {
                        fanOut(context, next.size() + list.size(), list.size());
                        next.addAll(list);
                    } else {
                        next.add(null);
                    }
                }
                current = next;
            }
            if (current == null) {
                return normalize(single);
            }
            List<Object> result = new ArrayList<>(current.size());
            for (Object value : current) {
                result.add(normalize(value));
            }
            return Collections.unmodifiableList(result);
        }

        boolean exists(Context context) {
            context.step(1);
            Object value = context.data;
            for (Segment segment : segments) {
                context.step(1);
                if (segment.field != null) {
                    if (!(value instanceof Map<?, ?> map) || !map.containsKey(segment.field)) {
                        return false;
                    }
                    value = map.get(segment.field);
                } else {
                    if (!(value instanceof List<?> list) || segment.index >= list.size()) {
                        return false;
                    }
                    value = list.get(segment.index);
                }
            }
            return true;
        }

        /** {@code total} values so far on this path, {@code added} of them new. */
        private static void fanOut(Context context, int total, int added) {
            if (total > MAX_FAN_OUT) {
                throw new EvaluationException(ERR_FAN_OUT);
            }
            context.step(added);
        }

        private static Object step(Object value, Segment segment) {
            if (segment.field != null) {
                return value instanceof Map<?, ?> map ? map.get(segment.field) : null;
            }
            if (value instanceof List<?> list && segment.index < list.size()) {
                return list.get(segment.index);
            }
            return null;
        }
    }

    private enum Function {
        SUM("sum", 1, 1, "one list"), COUNT("count", 1, 1, "one list"), MIN("min", 1, MAX_NODES, "a list, or two or more numbers"), MAX("max", 1,
                MAX_NODES, "a list, or two or more numbers"), LEN("len", 1, 1, "one value"), ABS("abs", 1, 1, "one number"), ROUND("round", 1, 2,
                        "a number and an optional number of decimals"), EXISTS("exists", 1, 1,
                                "one path"), APPROX("approx", 3, 3, "two numbers and a tolerance");

        final String name;
        final String label;
        final int minArgs;
        final int maxArgs;
        final String arity;

        Function(String name, int minArgs, int maxArgs, String arity) {
            this.name = name;
            this.label = name + "()";
            this.minArgs = minArgs;
            this.maxArgs = maxArgs;
            this.arity = arity;
        }

        static Function byName(String name) {
            for (Function function : values()) {
                if (function.name.equals(name)) {
                    return function;
                }
            }
            return null;
        }
    }

    private static final class Call extends Node {
        final Function function;
        final List<Node> args;

        Call(Function function, List<Node> args) {
            this.function = function;
            this.args = List.copyOf(args);
        }

        @Override
        Object eval(Context context) {
            context.step(1);
            switch (function) {
                case EXISTS :
                    return ((Path) args.get(0)).exists(context);
                case SUM : {
                    BigDecimal total = BigDecimal.ZERO;
                    for (Object value : list(args.get(0).eval(context))) {
                        context.step(1);
                        total = checked(total.add(number(value), MATH));
                    }
                    return total;
                }
                case COUNT :
                    return BigDecimal.valueOf(list(args.get(0).eval(context)).size());
                case MIN :
                    return extreme(context, false);
                case MAX :
                    return extreme(context, true);
                case LEN :
                    return length(args.get(0).eval(context));
                case ABS :
                    return number(args.get(0).eval(context)).abs();
                case ROUND :
                    return round(context);
                default :
                    return approx(context);
            }
        }

        private BigDecimal extreme(Context context, boolean max) {
            List<?> values;
            if (args.size() == 1) {
                values = list(args.get(0).eval(context));
            } else {
                List<Object> evaluated = new ArrayList<>(args.size());
                for (Node arg : args) {
                    evaluated.add(arg.eval(context));
                }
                values = evaluated;
            }
            if (values.isEmpty()) {
                throw new EvaluationException(ERR_EMPTY_AGGREGATE);
            }
            BigDecimal best = null;
            for (Object value : values) {
                context.step(1);
                BigDecimal n = number(value);
                if (best == null || (max ? n.compareTo(best) > 0 : n.compareTo(best) < 0)) {
                    best = n;
                }
            }
            return best;
        }

        private static BigDecimal length(Object value) {
            if (value == null) {
                throw new EvaluationException(ERR_NULL_OPERAND);
            }
            if (value instanceof String text) {
                return BigDecimal.valueOf(text.codePointCount(0, text.length()));
            }
            if (value instanceof List<?> list) {
                return BigDecimal.valueOf(list.size());
            }
            if (value instanceof Map<?, ?> map) {
                return BigDecimal.valueOf(map.size());
            }
            throw new EvaluationException(ERR_TYPE_MISMATCH);
        }

        private BigDecimal round(Context context) {
            BigDecimal value = number(args.get(0).eval(context));
            int digits = 0;
            if (args.size() > 1) {
                BigDecimal n = number(args.get(1).eval(context));
                if (n.signum() < 0 || n.compareTo(BigDecimal.valueOf(MAX_ROUND_DIGITS)) > 0 || n.stripTrailingZeros().scale() > 0) {
                    throw new EvaluationException(ERR_BAD_ARGUMENT);
                }
                digits = n.intValue();
            }
            return checked(value.setScale(digits, RoundingMode.HALF_UP));
        }

        private boolean approx(Context context) {
            BigDecimal a = number(args.get(0).eval(context));
            BigDecimal b = number(args.get(1).eval(context));
            BigDecimal tolerance = number(args.get(2).eval(context));
            if (tolerance.signum() < 0) {
                throw new EvaluationException(ERR_BAD_ARGUMENT);
            }
            return checked(a.subtract(b, MATH)).abs().compareTo(tolerance) <= 0;
        }
    }

    // === Values ===

    private static boolean bool(Object value) {
        if (value == null) {
            throw new EvaluationException(ERR_NULL_OPERAND);
        }
        if (value instanceof Boolean b) {
            return b;
        }
        throw new EvaluationException(ERR_TYPE_MISMATCH);
    }

    private static BigDecimal number(Object value) {
        if (value == null) {
            throw new EvaluationException(ERR_NULL_OPERAND);
        }
        Object normalized = normalize(value);
        if (normalized instanceof BigDecimal n) {
            return n;
        }
        throw new EvaluationException(ERR_TYPE_MISMATCH);
    }

    private static List<?> list(Object value) {
        if (value == null) {
            throw new EvaluationException(ERR_NULL_OPERAND);
        }
        if (value instanceof List<?> list) {
            if (list.size() > MAX_FAN_OUT) {
                throw new EvaluationException(ERR_FAN_OUT);
            }
            return list;
        }
        throw new EvaluationException(ERR_TYPE_MISMATCH);
    }

    /** A JSON number of any Java type becomes a bounded BigDecimal. */
    private static Object normalize(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Map || value instanceof List) {
            return value;
        }
        if (value instanceof BigDecimal d) {
            return checked(d);
        }
        if (value instanceof BigInteger i) {
            if (i.bitLength() > 4 * MAX_NUMBER_SCALE) {
                throw new EvaluationException(ERR_NUMBER_RANGE);
            }
            return checked(new BigDecimal(i));
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new EvaluationException(ERR_NUMBER_RANGE);
            }
            return checked(new BigDecimal(Double.toString(d)));
        }
        throw new EvaluationException(ERR_TYPE_MISMATCH);
    }

    private static BigDecimal checked(BigDecimal value) {
        if (value.precision() > MAX_NUMBER_SCALE || value.scale() > MAX_NUMBER_SCALE || value.scale() < -MAX_NUMBER_SCALE) {
            throw new EvaluationException(ERR_NUMBER_RANGE);
        }
        return value;
    }
}
