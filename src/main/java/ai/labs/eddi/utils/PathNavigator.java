/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

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
 * <li>String concatenation, any number of operands, evaluated left to right:
 * {@code properties.first+' '+properties.last}</li>
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

    // Matches arithmetic/concat at end of path: "path.to.value+1" or
    // "path.to.value+otherPath"
    private static final Pattern ARITHMETIC_PATTERN = Pattern.compile("^(.+?)([+\\-])(.+)$");

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

        // If plain navigation returned null, check for arithmetic/concatenation
        Matcher arithmeticMatcher = ARITHMETIC_PATTERN.matcher(path);
        if (arithmeticMatcher.matches()) {
            String leftPath = arithmeticMatcher.group(1).trim();
            String operator = arithmeticMatcher.group(2);
            String rightOperand = arithmeticMatcher.group(3).trim();

            // The LEFT operand of a top-level expression must be a real path: an
            // unresolvable one means "not found", not "a bare string literal".
            Object leftValue = navigatePath(leftPath, root);
            if (leftValue != null) {
                return applyOperator(leftValue, operator, evaluateOperand(rightOperand, root));
            }
        }

        return null;
    }

    /**
     * Resolve one operand: a path if it navigates, otherwise a nested expression if
     * it contains an operator, otherwise a literal.
     * <p>
     * The nested case is what makes more than two operands work. ARITHMETIC_PATTERN
     * splits reluctantly, so the class's own documented example
     * {@code properties.first+' '+properties.last} splits into
     * {@code properties.first} and the remainder {@code ' '+properties.last} — and
     * the remainder used to go straight to {@link #parseLiteral}, which found no
     * closing quote, failed both number parses and handed back the raw text. The
     * documented three-operand form therefore produced the literal garbage
     * {@code John' '+properties.last}, silently, into a conversation property or a
     * behaviour-rule comparison. Recursing here evaluates the remainder instead;
     * because each split strictly shortens the string, the recursion terminates.
     */
    private static Object evaluateOperand(String expression, Object root) {
        Object direct = navigatePath(expression, root);
        if (direct != null) {
            return direct;
        }

        Matcher matcher = ARITHMETIC_PATTERN.matcher(expression);
        if (matcher.matches()) {
            Object leftValue = evaluateOperand(matcher.group(1).trim(), root);
            if (leftValue != null) {
                return applyOperator(leftValue, matcher.group(2), evaluateOperand(matcher.group(3).trim(), root));
            }
        }

        return parseLiteral(expression);
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
