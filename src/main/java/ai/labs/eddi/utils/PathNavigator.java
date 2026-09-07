/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Safe navigation utility for dot-separated paths through Map/List structures.
 * Replaces explicit OGNL calls (Ognl.getValue/Ognl.setValue) to eliminate the
 * security surface from arbitrary method invocation.
 * <p>
 * Supports:
 * <ul>
 * <li>Dot-path navigation: {@code a.b.c}</li>
 * <li>Array index access: {@code items[0].name}</li>
 * <li>Simple arithmetic on the final value: {@code properties.count+1}</li>
 * <li>{@code +} and {@code -} over any number of operands, evaluated strictly
 * left to right, so {@code properties.count-1-1} is
 * {@code (count - 1) - 1}</li>
 * <li>Single-quoted string literals, which may themselves contain {@code +} or
 * {@code -}: {@code properties.first+' - '+properties.last}. An operator inside
 * quotes is part of the literal, never a separator</li>
 * </ul>
 * An expression that cannot be resolved yields {@code null} — including a
 * subtraction whose operands are not both numbers, which is how an absent
 * hyphenated key ({@code properties.my-key}) used to resolve to the value of a
 * shorter path that did exist.
 * <p>
 * Does NOT support method invocation, static class access, or object
 * instantiation.
 */
public class PathNavigator {

    // Matches a path segment with optional array index, e.g. "items[0]" or "name"
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("([^.\\[]+)(?:\\[(-?\\d+)])?");

    /**
     * Navigate a dot-separated path through a Map/List structure and return the
     * value.
     *
     * @param path
     *            dot-separated path, e.g.
     *            "memory.current.httpCalls.weather[0].temp"
     * @param root
     *            the root Map to navigate
     * @return the value at the path, or null if not found
     */
    public static Object getValue(String path, Object root) {
        if (path == null || path.isEmpty() || root == null) {
            return null;
        }

        // Try plain path navigation first
        Object result = navigatePath(path, root);
        if (result != null) {
            return result;
        }

        // If plain navigation returned null, treat it as an arithmetic/concat
        // expression.
        return evaluateExpression(path, root);
    }

    /** One operand's half-open range in the source expression. */
    private record Operand(int start, int end) {
    }

    /**
     * Split an expression on its TOP-LEVEL {@code +} and {@code -} — the ones
     * outside single quotes — into operand ranges plus the operators between them.
     * <p>
     * Splitting with a regex before recognising quotes is what made
     * {@code properties.first+'-'+properties.last} evaluate to {@code John}: the
     * remainder {@code '-'+properties.last} was split again at the hyphen INSIDE
     * the literal, and every branch below it then failed, leaving an empty string
     * to concatenate. A separator character in a literal is ordinary —
     * {@code ' - '} is the most obvious way to join a first and last name — so
     * quotes have to be respected before any splitting happens, not after.
     * <p>
     * An unterminated quote simply swallows the rest of the expression into one
     * operand, which {@link #parseLiteral} answers with null rather than handing
     * the raw text back. That null is dropped, not propagated: {@code +} then
     * concatenates it as the empty string, so {@code properties.first+'oops} is
     * {@code "John"} — the broken literal disappears instead of becoming
     * {@code "John'oops"} (see
     * {@code shouldDropAnUnterminatedStringLiteralInsteadOfConcatenatingItRaw}). A
     * {@code -} with a non-numeric right operand yields null, as it does for any
     * other unresolvable operand.
     * <p>
     * A {@code +}/{@code -} with nothing but whitespace before it is the SIGN of
     * the operand that follows, not a separator: {@code count+-1} is
     * {@code count + (-1)}. Splitting there produced an EMPTY operand, which
     * {@link #parseLiteral} answers with null and {@link #applyOperator}
     * concatenates as {@code ""} — so a signed literal stopped resolving altogether
     * ({@code count+-1} → null) and, worse, the folded string then made a dangling
     * operator resolve to a plausible-looking value ({@code count+} →
     * {@code "10"}). Signs belong to their operand.
     *
     * @param operators
     *            out-parameter, filled with the operator between operand i and
     *            operand i+1
     */
    private static List<Operand> tokenize(String expression, List<String> operators) {
        List<Operand> operands = new ArrayList<>();
        boolean insideLiteral = false;
        int start = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '\'') {
                insideLiteral = !insideLiteral;
            } else if (!insideLiteral && (c == '+' || c == '-') && !isBlank(expression, start, i)) {
                operands.add(new Operand(start, i));
                operators.add(String.valueOf(c));
                start = i + 1;
            }
        }
        operands.add(new Operand(start, expression.length()));
        return operands;
    }

    /** Whether {@code expression[from..to)} is empty or whitespace only. */
    private static boolean isBlank(String expression, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!Character.isWhitespace(expression.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Fold a tokenised expression from left to right.
     * <p>
     * The previous implementation re-applied its split pattern to the RIGHT-hand
     * remainder, which made the whole thing right-associative: {@code count-1-1}
     * evaluated as {@code count-(1-1)} and answered 10 where the Javadoc promised
     * 8. Folding a token list instead gives the documented left-to-right order.
     * <p>
     * Operands are resolved GREEDILY — the longest run of remaining tokens that
     * navigates as a path wins — because a path segment may itself contain a
     * hyphen. {@code properties.a+properties.my-key} must resolve
     * {@code properties.my-key} as one key when that key exists, and only fall back
     * to {@code properties.my} minus the literal {@code key} when it does not.
     * <p>
     * Two deliberate refusals. The LEFT operand must be a real path: an
     * unresolvable one means "not found", not "a bare string literal". And a fold
     * step that yields null ends the whole expression as null rather than carrying
     * on — {@link #applyOperator} answers a null left operand with the RIGHT one,
     * which is how a failed subtraction used to disguise itself as a plausible
     * value.
     * <p>
     * A blank operand is a malformed expression, never a value. With signs now
     * attached to their operand ({@link #tokenize}), the only way one can survive
     * is a dangling operator — {@code properties.count+} — and answering that with
     * the left operand folded into a string is exactly the "plausible-looking value
     * instead of not-found" this class exists to refuse.
     */
    private static Object evaluateExpression(String expression, Object root) {
        List<String> operators = new ArrayList<>();
        List<Operand> operands = tokenize(expression, operators);
        if (operators.isEmpty()) {
            return null; // no operator: plain navigation already answered
        }
        for (Operand operand : operands) {
            if (isBlank(expression, operand.start(), operand.end())) {
                return null;
            }
        }

        Object result = null;
        int index = 0;
        for (int end = operands.size() - 1; end >= 0; end--) {
            Object value = navigatePath(join(expression, operands, 0, end), root);
            if (value != null) {
                result = value;
                index = end + 1;
                break;
            }
        }
        if (result == null) {
            return null;
        }

        while (index < operands.size()) {
            String operator = operators.get(index - 1);
            Object right = null;
            int end = index;
            for (int candidate = operands.size() - 1; candidate >= index; candidate--) {
                Object value = navigatePath(join(expression, operands, index, candidate), root);
                if (value != null) {
                    right = value;
                    end = candidate;
                    break;
                }
            }
            if (right == null) {
                right = parseLiteral(join(expression, operands, index, index));
            }
            result = applyOperator(result, operator, right);
            if (result == null) {
                return null;
            }
            index = end + 1;
        }
        return result;
    }

    /** The source text of operands {@code from..to}, operators included. */
    private static String join(String expression, List<Operand> operands, int from, int to) {
        return expression.substring(operands.get(from).start(), operands.get(to).end()).trim();
    }

    /**
     * Set a value at a dot-separated path in a Map structure.
     *
     * @param path
     *            dot-separated path to set the value at
     * @param root
     *            the root Map
     * @param value
     *            the value to set
     */
    @SuppressWarnings("unchecked")
    public static void setValue(String path, Object root, Object value) {
        if (path == null || path.isEmpty() || root == null) {
            return;
        }

        String[] segments = path.split("\\.");
        Object current = root;

        // Navigate to the parent of the target
        for (int i = 0; i < segments.length - 1; i++) {
            current = resolveSegment(segments[i], current);
            if (current == null) {
                return;
            }
        }

        // Set the value on the last segment
        String lastSegment = segments[segments.length - 1];
        Matcher matcher = SEGMENT_PATTERN.matcher(lastSegment);
        if (matcher.matches()) {
            String key = matcher.group(1);
            String indexStr = matcher.group(2);

            if (indexStr != null && current instanceof Map<?, ?> parentMap) {
                Object list = parentMap.get(key);
                if (list instanceof List<?> l) {
                    try {
                        int index = Integer.parseInt(indexStr);
                        if (index >= 0 && index < l.size()) {
                            ((List<Object>) l).set(index, value);
                        }
                    } catch (NumberFormatException _) {
                        // Index exceeds int range — ignore silently
                    }
                }
            } else if (current instanceof Map<?, ?>) {
                ((Map<String, Object>) current).put(key, value);
            }
        }
    }

    private static Object navigatePath(String path, Object root) {
        Object current = root;
        String[] segments = path.split("\\.");

        for (String segment : segments) {
            if (current == null) {
                return null;
            }
            current = resolveSegment(segment, current);
        }

        return current;
    }

    private static Object resolveSegment(String segment, Object current) {
        Matcher matcher = SEGMENT_PATTERN.matcher(segment);
        if (!matcher.matches()) {
            return null;
        }

        String key = matcher.group(1);
        String indexStr = matcher.group(2);

        // Navigate into Map
        if (current instanceof Map<?, ?> map) {
            current = map.get(key);
        } else {
            return null;
        }

        // Handle array index if present
        if (indexStr != null && current instanceof List<?> list) {
            try {
                int index = Integer.parseInt(indexStr);
                if (index >= 0 && index < list.size()) {
                    current = list.get(index);
                } else {
                    return null;
                }
            } catch (NumberFormatException _) {
                return null; // Index exceeds int range
            }
        }

        return current;
    }

    private static Object applyOperator(Object left, String operator, Object right) {
        if (left == null) {
            return right;
        }

        // both are numbers — do arithmetic
        if (left instanceof Number leftNum && right instanceof Number rightNum) {
            if (left instanceof Double || left instanceof Float || right instanceof Double || right instanceof Float) {
                double result = switch (operator) {
                    case "+" -> leftNum.doubleValue() + rightNum.doubleValue();
                    case "-" -> leftNum.doubleValue() - rightNum.doubleValue();
                    default -> leftNum.doubleValue();
                };
                return result;
            } else {
                long result = switch (operator) {
                    case "+" -> leftNum.longValue() + rightNum.longValue();
                    case "-" -> leftNum.longValue() - rightNum.longValue();
                    default -> leftNum.longValue();
                };
                // Return Integer if it fits, otherwise Long
                if (result >= Integer.MIN_VALUE && result <= Integer.MAX_VALUE) {
                    return (int) result;
                }
                return result;
            }
        }

        // String concatenation (+ operator only)
        if ("+".equals(operator)) {
            String leftStr = left.toString();
            String rightStr = right != null ? right.toString() : "";
            return leftStr + rightStr;
        }

        // Subtraction whose operands are not both numbers is not an expression at all
        // — and answering with the left operand made a MISSING key resolve to the
        // value of a shorter one. "properties.my-key", with no such key but a
        // "properties.my" present, split into left=10 / op='-' / right="key" and
        // returned 10. Hyphenated keys are ordinary in EDDI's template data, and the
        // callers (MatchingUtilities, PropertySetterTask, SizeMatcher) read null as
        // "not found" and any non-null as a match — so an absent key could make a rule
        // fire, or pull a neighbouring value into a property, with no error and no log
        // line. Null is the honest answer.
        return null;
    }

    private static Object parseLiteral(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        // String literal: 'some text'
        if (value.startsWith("'")) {
            // An opening quote with no closing quote is a malformed literal. It used to
            // fall through every branch below and be returned as raw text, which is how
            // a broken expression turned into a plausible-looking value instead of a
            // "not found".
            return value.length() >= 2 && value.endsWith("'") ? value.substring(1, value.length() - 1) : null;
        }

        // Integer
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException _) {
        }

        // Double
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException _) {
        }

        // Fallback: treat as string
        return value;
    }
}
