/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import java.util.*;

/**
 * Utility for deep-copying property maps used by conversation memory
 * checkpoints. Recursively copies nested {@link Map}s and {@link List}s to
 * ensure checkpoint snapshots are fully isolated from live memory.
 *
 * @since 6.0.0
 */
public final class DeepCopyUtil {

    /**
     * Maximum recursion depth for deep-copy operations. Conversation property maps
     * are typically 2–3 levels deep; 32 is generous but prevents runaway recursion
     * on circular or adversarially nested structures.
     */
    static final int MAX_DEPTH = 32;

    private DeepCopyUtil() {
    }

    /**
     * Deep-copy a properties map. Recursively clones nested Maps and Lists.
     * Primitive wrappers (String, Integer, etc.) are immutable and safe to share.
     *
     * @throws IllegalStateException
     *             if nesting exceeds {@link #MAX_DEPTH}
     */
    public static Map<String, Object> deepCopy(Map<String, Object> original) {
        if (original == null) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>(original.size());
        for (Map.Entry<String, Object> entry : original.entrySet()) {
            copy.put(entry.getKey(), deepCopyValue(entry.getValue(), 1));
        }
        return Collections.unmodifiableMap(copy);
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object value, int depth) {
        if (value == null) {
            return null;
        }
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException(
                    "Deep copy exceeded maximum nesting depth of " + MAX_DEPTH
                            + ". Possible circular reference or adversarial input.");
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopyValue(entry.getValue(), depth + 1));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(deepCopyValue(item, depth + 1));
            }
            return copy;
        }
        if (value instanceof Set<?> set) {
            Set<Object> copy = new LinkedHashSet<>(set.size());
            for (Object item : set) {
                copy.add(deepCopyValue(item, depth + 1));
            }
            return copy;
        }
        // Primitives, Strings, enums etc. are immutable — safe to share
        return value;
    }
}
