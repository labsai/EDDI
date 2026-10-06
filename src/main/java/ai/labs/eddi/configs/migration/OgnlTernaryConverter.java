/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.modules.templating.TemplateEscaping;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts the body of a Thymeleaf output expression that holds a conditional
 * ({@code cond ? a : b}, nested to any depth, with string concatenation in the
 * branches) into Qute {@code {#if}…{#else}…{/if}} sections.
 *
 * <p>
 * A small recursive-descent parser over a token list, deliberately narrow: a
 * condition is paths, number and string literals, comparisons, {@code &&},
 * {@code ||}, {@code !} and parentheses; a branch is string literals and paths
 * joined by {@code +}, or another conditional. Anything else — a method call,
 * an index, arithmetic, a comparison whose result would differ for a missing
 * value — makes {@link #convert(String)} answer {@code null}, and the caller
 * then leaves the expression to the generic conversion and the parse check
 * behind it. It never guesses.
 * </p>
 *
 * <p>
 * A comparison with a number ({@code x > 0}) on a value that may be missing is
 * guarded ({@code x && x > 0}), because Qute throws when it compares a missing
 * value. The guard is only emitted where it cannot change the answer: Qute
 * reads a zero as false, so {@code x && x >= 0} would be wrong for
 * {@code x == 0}, and those comparisons are refused instead.
 * </p>
 */
final class OgnlTernaryConverter {

    private enum Kind {
        STRING, NUMBER, PATH, NULL, OP
    }

    private record Token(Kind kind, String text) {
    }

    /** Raised on any shape the converter is not sure about. */
    private static final class Unsure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unsure() {
            super(null, null, false, false);
        }
    }

    /** A converted condition and how tightly it binds, to know when to wrap it. */
    private record Cond(String text, int level) {
        static final int ATOM = 0;
        static final int AND = 1;
        static final int OR = 2;
    }

    private final List<Token> tokens;
    private int pos;

    private OgnlTernaryConverter(List<Token> tokens) {
        this.tokens = tokens;
    }

    /**
     * Whether {@code expr} holds a conditional operator outside any string literal
     * — a {@code ?} that is not the start of {@code ?:} (Elvis) or {@code ?.}.
     */
    static boolean hasConditional(String expr) {
        char openQuote = 0;
        boolean escaped = false;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (openQuote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == openQuote) {
                    openQuote = 0;
                }
            } else if (c == '\'' || c == '"') {
                openQuote = c;
            } else if (c == '?') {
                char next = i + 1 < expr.length() ? expr.charAt(i + 1) : 0;
                if (next != ':' && next != '.') {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The Qute for {@code expr}, or {@code null} when it cannot be converted
     * safely.
     */
    static String convert(String expr) {
        try {
            var converter = new OgnlTernaryConverter(tokenize(expr));
            String result = converter.parseValue();
            return converter.pos == converter.tokens.size() ? result : null;
        } catch (Unsure | NumberFormatException e) {
            return null;
        }
    }

    // ---- tokenizer ----

    private static List<Token> tokenize(String s) {
        var out = new ArrayList<Token>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '\'' || c == '"') {
                int end = closingQuote(s, i);
                out.add(new Token(Kind.STRING, TemplateSyntaxMigrator.literalText(s.substring(i, end + 1))));
                i = end + 1;
            } else if (Character.isDigit(c)) {
                int end = i;
                while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '.')) {
                    end++;
                }
                out.add(new Token(Kind.NUMBER, s.substring(i, end)));
                i = end;
            } else if (Character.isLetter(c) || c == '_') {
                int end = i;
                while (end < s.length() && (Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) == '_' || s.charAt(end) == '.')) {
                    end++;
                }
                String word = s.substring(i, end);
                if (word.endsWith(".") || word.contains("..")) {
                    throw new Unsure();
                }
                out.add(switch (word) {
                    case "and" -> new Token(Kind.OP, "&&");
                    case "or" -> new Token(Kind.OP, "||");
                    case "not" -> new Token(Kind.OP, "!");
                    case "null" -> new Token(Kind.NULL, word);
                    default -> new Token(Kind.PATH, word);
                });
                i = end;
            } else {
                String op = operatorAt(s, i);
                if (op == null) {
                    throw new Unsure();
                }
                out.add(new Token(Kind.OP, op));
                i += op.length();
            }
        }
        return out;
    }

    private static int closingQuote(String s, int open) {
        char quote = s.charAt(open);
        boolean escaped = false;
        for (int i = open + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == quote) {
                return i;
            }
        }
        throw new Unsure();
    }

    private static String operatorAt(String s, int i) {
        for (String op : new String[]{"&&", "||", ">=", "<=", "==", "!=", "?", ":", "(", ")", "+", ">", "<", "!"}) {
            if (s.startsWith(op, i)) {
                return op;
            }
        }
        return null;
    }

    // ---- parser ----

    private Token peek() {
        return pos < tokens.size() ? tokens.get(pos) : null;
    }

    private boolean atOp(String op) {
        Token t = peek();
        return t != null && t.kind() == Kind.OP && t.text().equals(op);
    }

    private void expectOp(String op) {
        if (!atOp(op)) {
            throw new Unsure();
        }
        pos++;
    }

    /** A conditional, or a concatenation. */
    private String parseValue() {
        int start = pos;
        try {
            Cond cond = parseOr();
            if (atOp("?")) {
                pos++;
                String whenTrue = parseValue();
                expectOp(":");
                String whenFalse = parseValue();
                return "{#if " + cond.text() + "}" + whenTrue + "{#else}" + whenFalse + "{/if}";
            }
        } catch (Unsure e) {
            // not a condition: it may be a concatenation, e.g. ('a' + b)
        }
        pos = start;
        return parseConcat();
    }

    private String parseConcat() {
        var out = new StringBuilder();
        out.append(parseTerm());
        while (atOp("+")) {
            pos++;
            out.append(parseTerm());
        }
        return out.toString();
    }

    private String parseTerm() {
        Token t = peek();
        if (t == null) {
            throw new Unsure();
        }
        if (t.kind() == Kind.OP && t.text().equals("(")) {
            pos++;
            String inner = parseValue();
            expectOp(")");
            return inner;
        }
        pos++;
        return switch (t.kind()) {
            // A literal holding '{' would open a Qute expression when inlined.
            case STRING -> t.text().indexOf('{') >= 0 ? TemplateEscaping.unparsedBlock(t.text()) : t.text();
            case PATH -> "{" + t.text() + "}";
            case NULL -> "";
            // A number next to a +: arithmetic, not concatenation, in OGNL.
            default -> throw new Unsure();
        };
    }

    private Cond parseOr() {
        var parts = new ArrayList<Cond>();
        parts.add(parseAnd());
        while (atOp("||")) {
            pos++;
            parts.add(parseAnd());
        }
        return join(parts, " || ", Cond.OR);
    }

    private Cond parseAnd() {
        var parts = new ArrayList<Cond>();
        parts.add(parseUnary());
        while (atOp("&&")) {
            pos++;
            parts.add(parseUnary());
        }
        return join(parts, " && ", Cond.AND);
    }

    private static Cond join(List<Cond> parts, String operator, int level) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        var text = new StringBuilder();
        for (Cond part : parts) {
            if (text.length() > 0) {
                text.append(operator);
            }
            text.append(part.level() == Cond.ATOM ? part.text() : "(" + part.text() + ")");
        }
        return new Cond(text.toString(), level);
    }

    private Cond parseUnary() {
        if (atOp("!")) {
            pos++;
            Cond operand = parseUnary();
            return new Cond("!" + (operand.level() == Cond.ATOM ? operand.text() : "(" + operand.text() + ")"), Cond.ATOM);
        }
        if (atOp("(")) {
            pos++;
            Cond inner = parseOr();
            expectOp(")");
            return new Cond("(" + inner.text() + ")", Cond.ATOM);
        }
        Token left = peek();
        if (left == null || left.kind() != Kind.PATH) {
            throw new Unsure();
        }
        pos++;
        Token op = peek();
        if (op == null || op.kind() != Kind.OP || !isComparison(op.text())) {
            return new Cond(left.text(), Cond.ATOM);
        }
        pos++;
        Token right = peek();
        if (right == null) {
            throw new Unsure();
        }
        pos++;
        String comparison = op.text();
        boolean equality = comparison.equals("==") || comparison.equals("!=");
        if (right.kind() == Kind.STRING && equality && right.text().indexOf('\'') < 0 && right.text().indexOf('\\') < 0) {
            return new Cond(left.text() + " " + comparison + " '" + right.text() + "'", Cond.ATOM);
        }
        if (right.kind() == Kind.NUMBER && !equality) {
            // Guard against a missing value. Qute reads zero as false, so the guard
            // is only right where x == 0 is false for the comparison anyway.
            double bound = Double.parseDouble(right.text());
            boolean zeroIsFalse = (comparison.equals(">") && bound >= 0) || (comparison.equals(">=") && bound > 0);
            if (!zeroIsFalse) {
                throw new Unsure();
            }
            return new Cond(left.text() + " && " + left.text() + " " + comparison + " " + right.text(), Cond.AND);
        }
        throw new Unsure();
    }

    private static boolean isComparison(String op) {
        return switch (op) {
            case ">", "<", ">=", "<=", "==", "!=" -> true;
            default -> false;
        };
    }
}
