/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating;

import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A read-only view of template data in which every string has lost the
 * characters of the expression notation: {@code (}, {@code )} and {@code ,}.
 * <p>
 * The {@code expressions} field of a quick reply is templated with data (a
 * property, an API value) and the result becomes a trusted temporary dictionary
 * phrase on the next turn. Data that carries {@code x), intent(admin} would
 * therefore close the author's expression and add one of its own. Rendering
 * that one field against this view keeps the author's notation (it is template
 * text) and removes the data's. Only that field is rendered this way: the quick
 * reply's value, which the user sees and types, keeps the data as it is.
 * <p>
 * Strings are cleaned when they are read, nested maps and lists are wrapped
 * when they are read, and any other value (a number, a boolean, a bean) is
 * passed through: nothing is copied up front, so the cost is proportional to
 * what the template touches.
 */
public final class NotationSafeData {

    private NotationSafeData() {
    }

    /** The text without {@code (}, {@code )} and {@code ,}. */
    public static String stripNotation(String text) {
        if (text == null || (text.indexOf('(') < 0 && text.indexOf(')') < 0 && text.indexOf(',') < 0)) {
            return text;
        }
        StringBuilder clean = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '(' && c != ')' && c != ',') {
                clean.append(c);
            }
        }
        return clean.toString();
    }

    /** A notation-free view of {@code data}; {@code null} stays {@code null}. */
    public static Map<String, Object> wrap(Map<String, Object> data) {
        return data == null ? null : new SafeMap<>(data);
    }

    @SuppressWarnings("unchecked")
    private static Object safe(Object value) {
        if (value instanceof CharSequence sequence) {
            return stripNotation(sequence.toString());
        }
        if (value instanceof Map<?, ?> map) {
            return new SafeMap<>((Map<Object, Object>) map);
        }
        if (value instanceof List<?> list) {
            return new SafeList(list);
        }
        return value;
    }

    private static final class SafeMap<K> extends AbstractMap<K, Object> {
        private final Map<K, Object> delegate;

        private SafeMap(Map<K, Object> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object get(Object key) {
            return safe(delegate.get(key));
        }

        @Override
        public boolean containsKey(Object key) {
            return delegate.containsKey(key);
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public Set<Entry<K, Object>> entrySet() {
            Map<K, Object> view = new LinkedHashMap<>();
            delegate.forEach((key, value) -> view.put(key, safe(value)));
            return view.entrySet();
        }
    }

    private static final class SafeList extends AbstractList<Object> {
        private final List<?> delegate;

        private SafeList(List<?> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object get(int index) {
            return safe(delegate.get(index));
        }

        @Override
        public int size() {
            return delegate.size();
        }
    }
}
