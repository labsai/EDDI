package ai.labs.eddi.utils;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Safe navigation utility for dot-separated paths through Map/List structures.
 * Replaces explicit OGNL calls (Ognl.getValue/Ognl.setValue) to eliminate
 * the security surface from arbitrary method invocation.
 * <p>
 * Supports:
 * - Dot-path navigation: "a.b.c"
 * - Array index access: "items[0].name"
 * - Simple arithmetic on final value: "properties.count+1"
 * - String concatenation: "properties.first+' '+properties.last"
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
     * @param path dot-separated path, e.g.
     *             "memory.current.httpCalls.weather[0].temp"
     * @param root the root Map to navigate
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

            Object leftValue = navigatePath(leftPath, root);
            if (leftValue != null) {
                // Try to resolve right operand as a path first, then as a literal
                Object rightValue = navigatePath(rightOperand, root);
                if (rightValue == null) {
                    rightValue = parseLiteral(rightOperand);
                }

                return applyOperator(leftValue, operator, rightValue);
            }
        }

        return null;
    }

    /**
     * Set a value at a dot-separated path in a Map structure.
     *
     * @param path  dot-separated path to set the value at
     * @param root  the root Map
     * @param value the value to set
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
                    int index = Integer.parseInt(indexStr);
                    if (index >= 0 && index < l.size()) {
                        ((List<Object>) l).set(index, value);
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
            int index = Integer.parseInt(indexStr);
            if (index >= 0 && index < list.size()) {
                current = list.get(index);
            } else {
                return null;
            }
        }

        return current;
    }

    private static Object applyOperator(Object left, String operator, Object right) {
        if (left == null) {
            return right;
        }

        // Agenth are numbers — do arithmetic
        if (left instanceof Number leftNum && right instanceof Number rightNum) {
            if (left instanceof Double || left instanceof Float ||
                    right instanceof Double || right instanceof Float) {
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

        return left;
    }

    private static Object parseLiteral(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        // String literal: 'some text'
        if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
            return value.substring(1, value.length() - 1);
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
