/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Builds the small runtime config scripts the shells load before their bundles
 * — {@code window.__EDDI_AUTH__}, {@code window.__EDDI_CHAT__},
 * {@code window.__EDDI_BRAND__} — with the one JavaScript string escaper they
 * all share.
 * <p>
 * Output is an object literal with unquoted keys, exactly the shape the scripts
 * have always had: {@code window.__EDDI_CHAT__={urlTokenEnabled:false};}.
 */
public final class JsGlobalScript {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private final String global;
    private final ObjectBuilder root = new ObjectBuilder();

    private JsGlobalScript(String global) {
        this.global = requireIdentifier(global);
    }

    /** A script assigning an object to {@code window.<global>}. */
    public static JsGlobalScript assign(String global) {
        return new JsGlobalScript(global);
    }

    public JsGlobalScript put(String key, String value) {
        root.put(key, value);
        return this;
    }

    public JsGlobalScript put(String key, boolean value) {
        root.put(key, value);
        return this;
    }

    public JsGlobalScript put(String key, long value) {
        root.put(key, value);
        return this;
    }

    public JsGlobalScript putObject(String key, Consumer<ObjectBuilder> body) {
        root.putObject(key, body);
        return this;
    }

    public String toJs() {
        return "window." + global + "=" + root.toJs() + ";";
    }

    /** One object literal; keys are identifiers, values are escaped. */
    public static final class ObjectBuilder {
        private final StringBuilder body = new StringBuilder();

        private ObjectBuilder() {
        }

        private void key(String key) {
            if (!body.isEmpty()) {
                body.append(',');
            }
            body.append(requireIdentifier(key)).append(':');
        }

        /** A string value, or {@code null}. */
        public ObjectBuilder put(String key, String value) {
            key(key);
            body.append(stringLiteral(value));
            return this;
        }

        public ObjectBuilder put(String key, boolean value) {
            key(key);
            body.append(value);
            return this;
        }

        public ObjectBuilder put(String key, long value) {
            key(key);
            body.append(value);
            return this;
        }

        public ObjectBuilder putObject(String key, Consumer<ObjectBuilder> nested) {
            ObjectBuilder child = new ObjectBuilder();
            nested.accept(child);
            key(key);
            body.append(child.toJs());
            return this;
        }

        String toJs() {
            return "{" + body + "}";
        }
    }

    private static String requireIdentifier(String key) {
        if (key == null || !IDENTIFIER.matcher(key).matches()) {
            throw new IllegalArgumentException("Not a JavaScript identifier: " + key);
        }
        return key;
    }

    /**
     * {@code value} as a double-quoted JavaScript string literal, or {@code null}.
     * <p>
     * Backslash, the double quote and the common whitespace escapes keep their
     * familiar forms; every other C0 control, DEL, U+2028/U+2029 (line terminators
     * in older engines) and {@code <}, {@code >}, {@code &} become
     * {@code \}{@code uXXXX}, so the literal stays inert even if it is ever inlined
     * into a {@code <script>} element ({@code </script>} cannot close it).
     */
    public static String stringLiteral(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7F || c == ' ' || c == ' ' || c == '<' || c == '>' || c == '&' || c == '\'') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
