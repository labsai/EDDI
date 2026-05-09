/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import java.util.*;
import java.util.Collections;

/**
 * Utility for deep-copying property maps used by conversation memory
 * checkpoints. Recursively copies nested {@link Map}s and {@link List}s to
 * ensure checkpoint snapshots are fully isolated from live memory.
 *
 * @since 6.0.0
 */
public final class DeepCopyUtil {

    private DeepCopyUtil() {
    }

    /**
     * Deep-copy a properties map. Recursively clones nested Maps and Lists.
     * Primitive wrappers (String, Integer, etc.) are immutable and safe to share.
     */
    public static Map<String, Object> deepCopy(Map<String, Object> original) {
        if (original == null) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>(original.size());
        for (Map.Entry<String, Object> entry : original.entrySet()) {
            copy.put(entry.getKey(), deepCopyValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopyValue(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(deepCopyValue(item));
            }
            return copy;
        }
        if (value instanceof Set<?> set) {
            Set<Object> copy = new LinkedHashSet<>(set.size());
            for (Object item : set) {
                copy.add(deepCopyValue(item));
            }
            return copy;
        }
        // Primitives, Strings, enums etc. are immutable — safe to share
        return value;
    }
}
