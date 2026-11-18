/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Keeps a model-supplied tool argument from changing the structure of a JSON
 * request body.
 * <p>
 * A request body is a Qute template and the values it substitutes are written
 * as they are. On the tool path the values are chosen by a model (and so, by
 * whoever can talk to it), which let the argument {@code x","role":"admin} turn
 * {@code {"name":"{name}"}} into a body with a second field, sent with the
 * agent's credentials. This class closes that for <em>tool arguments only</em>
 * — author-controlled data ({@code {properties.x}}, {@code {context.y}}, API
 * responses) is not touched.
 * <p>
 * It works by rewriting the body <em>template</em>, not the rendered body,
 * because only the template knows where a placeholder sits:
 * <ul>
 * <li>inside a JSON string literal ({@code "name":"{name}"}) the placeholder is
 * pointed at a copy of the argument whose string content is JSON-escaped, so it
 * can only ever be the content of the literal the author wrote it into;</li>
 * <li>bare ({@code "count":{count}}) it is pointed at the argument as a
 * canonical JSON value: a number, boolean, object or array must be valid JSON
 * of the declared schema type (anything else is refused), and a string is
 * emitted as a quoted JSON string.</li>
 * </ul>
 * A compound expression that merely starts with an argument name
 * ({@code {filter.field}}) reads the escaped copy, so a leaf string can never
 * break out of its position either. A {@code json:...} expression is the
 * author's own escaping and is left alone, as is a body that is nothing but one
 * variable (the model writes the whole document; the executor's body guard
 * judges it).
 */
public final class ToolArgumentBodyEscaper {

    /**
     * Template-data key under which the rewritten body template travels from the
     * tool provider to {@link ApiCallExecutor}. Absent: render the configured body.
     */
    public static final String REWRITTEN_BODY_KEY = "__eddiToolRewrittenBody";

    /** Prefix of the data entries holding the escaped copy of an argument. */
    static final String ESCAPED_PREFIX = "eddiJsonEsc_";
    /** Prefix of the data entries holding an argument as a canonical JSON value. */
    static final String VALUE_PREFIX = "eddiJsonVal_";

    private static final ObjectMapper STRICT_JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private static final Pattern WHOLE_BODY_TEMPLATE = Pattern.compile("^\\{([A-Za-z_][A-Za-z0-9_]*)}$");

    private ToolArgumentBodyEscaper() {
    }

    /**
     * A model argument cannot be placed in the body; the message is safe to give
     * the model (it names the argument and the expectation, never the value).
     */
    public static final class ArgumentRefusedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ArgumentRefusedException(String message) {
            super(message);
        }
    }

    /**
     * Whether a call's content type means the body is a JSON document: the media
     * type alone, parameters stripped, {@code application/json}, {@code text/json}
     * or any {@code +json} suffix.
     */
    public static boolean isJsonContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return mediaType.equals("application/json") || mediaType.equals("text/json") || mediaType.endsWith("+json");
    }

    /**
     * Rewrites {@code bodyTemplate} and adds the entries it reads to
     * {@code templateData}.
     *
     * @param bodyTemplate
     *            the configured body template
     * @param templateData
     *            the data the body is rendered from; model arguments are already
     *            merged in. Receives the escaped and canonical copies.
     * @param argumentNames
     *            the keys that came from the model
     * @param argumentSchema
     *            the call's {@code argumentSchema}, may be null — supplies the
     *            declared type of a bare argument
     * @return the template to render, or {@code null} when nothing in it refers to
     *         a model argument (render the configured body as before)
     * @throws ArgumentRefusedException
     *             a bare placeholder whose argument is not valid JSON of its
     *             declared type
     */
    public static String prepare(String bodyTemplate, Map<String, Object> templateData, Set<String> argumentNames,
                                 Map<String, Object> argumentSchema) {
        if (bodyTemplate == null || bodyTemplate.isEmpty() || argumentNames.isEmpty()) {
            return null;
        }
        if (WHOLE_BODY_TEMPLATE.matcher(bodyTemplate.trim()).matches()) {
            return null;
        }
        Set<String> escaped = new LinkedHashSet<>();
        Set<String> bare = new LinkedHashSet<>();
        String rewritten = rewrite(bodyTemplate, argumentNames, escaped, bare);
        for (String name : escaped) {
            templateData.put(ESCAPED_PREFIX + name, escapeLeaves(templateData.get(name)));
        }
        for (String name : bare) {
            templateData.put(VALUE_PREFIX + name, bareJson(name, templateData.get(name), declaredType(argumentSchema, name)));
        }
        return escaped.isEmpty() && bare.isEmpty() ? null : rewritten;
    }

    /**
     * Header values are rendered from the same data. A control character (CR, LF,
     * NUL, ...) in a model-supplied value would split or corrupt the header block,
     * so a header template that reads such an argument is refused.
     *
     * @throws ArgumentRefusedException
     *             when an argument a header reads contains a control character
     */
    public static void rejectControlCharactersInHeaders(Map<String, String> headerTemplates, Map<String, Object> templateData,
                                                        Set<String> argumentNames) {
        if (headerTemplates == null || headerTemplates.isEmpty()) {
            return;
        }
        for (String name : argumentNames) {
            Object value = templateData.get(name);
            if (value == null || !containsControlCharacter(value instanceof String s ? s : value.toString())) {
                continue;
            }
            Pattern reads = Pattern.compile("\\{\\s*" + Pattern.quote(name) + "(?![A-Za-z0-9_])");
            for (var header : headerTemplates.entrySet()) {
                if (header.getValue() != null && reads.matcher(header.getValue()).find()) {
                    throw new ArgumentRefusedException("Argument '" + name + "' contains a control character (such as a line break) and "
                            + "is used in the request header '" + header.getKey() + "', where that is not allowed. The request was NOT sent. "
                            + "Send the value without control characters.");
                }
            }
        }
    }

    /** The root identifier of every expression in {@code template}, for callers. */
    public static List<String> expressionRoots(String template) {
        List<String> roots = new ArrayList<>();
        scan(template, (root, expr, inString) -> roots.add(root));
        return roots;
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 && c != '\t' || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- rewrite

    private interface Visitor {
        void expression(String root, String expression, boolean inString);
    }

    private static void scan(String body, Visitor visitor) {
        boolean inString = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString && c == '\\') {
                i++;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (c == '{' && i + 1 < body.length() && isExpressionStart(body.charAt(i + 1)) && (i == 0 || body.charAt(i - 1) != '$')) {
                int end = body.indexOf('}', i);
                if (end < 0) {
                    return;
                }
                String expression = body.substring(i + 1, end);
                visitor.expression(rootOf(expression), expression, inString);
                i = end;
            }
        }
    }

    private static String rewrite(String body, Set<String> argumentNames, Set<String> escaped, Set<String> bare) {
        StringBuilder out = new StringBuilder(body.length() + 32);
        boolean inString = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString && c == '\\') {
                out.append(c);
                if (i + 1 < body.length()) {
                    out.append(body.charAt(++i));
                }
                continue;
            }
            if (c == '"') {
                inString = !inString;
                out.append(c);
                continue;
            }
            if (c == '{' && i + 1 < body.length() && isExpressionStart(body.charAt(i + 1)) && (i == 0 || body.charAt(i - 1) != '$')) {
                int end = body.indexOf('}', i);
                if (end < 0) {
                    out.append(body, i, body.length());
                    break;
                }
                String expression = body.substring(i + 1, end);
                String root = rootOf(expression);
                String rest = expression.substring(root.length());
                boolean namespaced = rest.startsWith(":");
                if (argumentNames.contains(root) && !namespaced) {
                    if (!inString && rest.isEmpty()) {
                        bare.add(root);
                        out.append('{').append(VALUE_PREFIX).append(root).append('}');
                    } else {
                        escaped.add(root);
                        out.append('{').append(ESCAPED_PREFIX).append(root).append(rest).append('}');
                    }
                } else {
                    out.append(body, i, end + 1);
                }
                i = end;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isExpressionStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static String rootOf(String expression) {
        int end = 0;
        while (end < expression.length() && (Character.isLetterOrDigit(expression.charAt(end)) || expression.charAt(end) == '_')) {
            end++;
        }
        return expression.substring(0, end);
    }

    // ----------------------------------------------------------------- values

    /**
     * The argument with every string's content JSON-escaped, as the content of a
     * string literal: no quote, backslash or control character survives unescaped.
     */
    static Object escapeLeaves(Object value) {
        if (value instanceof String s) {
            return escapeContent(s);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(k, escapeLeaves(v)));
            return copy;
        }
        if (value instanceof Iterable<?> items) {
            List<Object> copy = new ArrayList<>();
            items.forEach(item -> copy.add(escapeLeaves(item)));
            return copy;
        }
        return value;
    }

    static String escapeContent(String s) {
        return new String(JsonStringEncoder.getInstance().quoteAsString(s));
    }

    private static String declaredType(Map<String, Object> schema, String name) {
        if (schema != null && schema.get("properties") instanceof Map<?, ?> properties && properties.get(name) instanceof Map<?, ?> property
                && property.get("type") instanceof String type) {
            return type;
        }
        return null;
    }

    /**
     * The argument as the canonical JSON text of a bare value position.
     * <ul>
     * <li>declared {@code string}: a quoted JSON string;</li>
     * <li>declared {@code number}, {@code integer}, {@code boolean},
     * {@code object}, {@code array}: the text must be valid JSON of that type,
     * emitted canonically, otherwise refused;</li>
     * <li>not declared: text that is valid JSON is emitted canonically (a stored
     * config whose model sends {@code 5} keeps getting {@code 5}), anything else as
     * a quoted string.</li>
     * </ul>
     */
    static String bareJson(String name, Object value, String type) {
        if (value == null) {
            return "null";
        }
        try {
            if (!(value instanceof String text)) {
                return STRICT_JSON.valueToTree(value).toString();
            }
            if ("string".equals(type)) {
                return quote(text);
            }
            JsonNode node = parseOrNull(text);
            if (type == null) {
                return node != null ? node.toString() : quote(text);
            }
            if (node == null || !matches(node, type)) {
                throw new ArgumentRefusedException("Argument '" + name + "' is used as a JSON value and must be valid JSON of type '" + type
                        + "'. The request was NOT sent. Send a correctly typed value.");
            }
            return node.toString();
        } catch (IllegalArgumentException e) {
            throw new ArgumentRefusedException("Argument '" + name + "' cannot be written as a JSON value. The request was NOT sent.");
        }
    }

    private static boolean matches(JsonNode node, String type) {
        return switch (type) {
            case "number" -> node.isNumber();
            case "integer" -> node.isIntegralNumber();
            case "boolean" -> node.isBoolean();
            case "object" -> node.isObject();
            case "array" -> node.isArray();
            default -> true;
        };
    }

    private static JsonNode parseOrNull(String text) {
        try {
            JsonNode node = STRICT_JSON.readTree(text);
            return node == null || node.isMissingNode() ? null : node;
        } catch (Exception e) {
            return null;
        }
    }

    private static String quote(String text) {
        return "\"" + escapeContent(text) + "\"";
    }
}
