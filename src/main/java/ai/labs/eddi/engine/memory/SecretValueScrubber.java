/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.model.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes a known plaintext from the plain values conversation memory is made
 * of — strings, lists, maps and {@link Context} entries — without mutating
 * them.
 * <p>
 * Shared by the two places that must keep a secret out of the stored
 * conversation: {@code PropertySetterTask} after it vaults a
 * {@code scope: "secret"} property, and {@code Conversation} at the end of a
 * turn that carried a context value the client marked secret.
 */
public final class SecretValueScrubber {

    private static final Logger LOGGER = Logger.getLogger(SecretValueScrubber.class);

    /**
     * Configured like the persistence mapper, so an object is scrubbed in exactly
     * the JSON form it would be stored and returned in.
     */
    private static final ObjectMapper TREE_MAPPER = SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);

    private SecretValueScrubber() {
    }

    /**
     * A copy of {@code value} with every occurrence of {@code plaintext} replaced
     * by {@code placeholder}, or {@code null} when it does not carry the plaintext
     * at all (so the caller can tell "nothing to do" from "replaced"). Values of
     * any other type are not inspected and yield {@code null}.
     */
    public static Object scrubValue(Object value, String plaintext, String placeholder) {
        if (value instanceof String text) {
            return text.contains(plaintext) ? text.replace(plaintext, placeholder) : null;
        }
        if (value instanceof Context context) {
            return scrubContext(context, plaintext, placeholder);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list);
            boolean changed = false;
            for (int i = 0; i < copy.size(); i++) {
                Object cleaned = scrubValue(copy.get(i), plaintext, placeholder);
                if (cleaned != null) {
                    copy.set(i, cleaned);
                    changed = true;
                }
            }
            return changed ? copy : null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            boolean changed = false;
            for (var entry : map.entrySet()) {
                Object cleaned = scrubValue(entry.getValue(), plaintext, placeholder);
                copy.put(String.valueOf(entry.getKey()), cleaned != null ? cleaned : entry.getValue());
                changed |= cleaned != null;
            }
            return changed ? copy : null;
        }
        return null;
    }

    /**
     * {@link #scrubValue} applied for each plaintext in turn, or {@code null} when
     * none of them occurs. Pass longer plaintexts first so a shorter one contained
     * in a longer one cannot leave a fragment behind.
     */
    public static Object scrubAll(Object value, Collection<String> plaintexts, String placeholder) {
        Object current = value;
        boolean changed = false;
        for (String plaintext : plaintexts) {
            Object cleaned = scrubValue(current, plaintext, placeholder);
            if (cleaned != null) {
                current = cleaned;
                changed = true;
            }
        }
        return changed ? current : null;
    }

    /**
     * A copy of {@code context} — type and secret flag kept — with the plaintext
     * removed from its value, or {@code null} when it does not carry it.
     */
    public static Context scrubContext(Context context, String plaintext, String placeholder) {
        Object cleaned = scrubValue(context.getValue(), plaintext, placeholder);
        if (cleaned == null) {
            return null;
        }
        var copy = new Context(context.getType(), cleaned);
        copy.setSecret(context.getSecret());
        return copy;
    }

    /**
     * Like {@link #scrubAll}, but also reaches into objects the plain walk cannot
     * enter — output items, records, other POJOs — through their JSON form: such an
     * object that carries a plaintext is returned as the scrubbed JSON tree (maps,
     * lists, scalars), which stores and serializes exactly as the object did. An
     * object that cannot be converted, but whose {@code toString()} shows a
     * plaintext, is replaced by {@code placeholder} whole (logged at WARN).
     *
     * @return the scrubbed copy, or {@code null} when {@code value} carries none of
     *         the plaintexts
     */
    public static Object scrubDeep(Object value, Collection<String> plaintexts, String placeholder) {
        if (value == null || plaintexts.isEmpty()) {
            return null;
        }
        if (value instanceof String || value instanceof List<?> || value instanceof Map<?, ?>) {
            return scrubDeepWalk(value, plaintexts, placeholder);
        }
        if (value instanceof Context context) {
            Object cleaned = scrubDeep(context.getValue(), plaintexts, placeholder);
            if (cleaned == null) {
                return null;
            }
            var copy = new Context(context.getType(), cleaned);
            copy.setSecret(context.getSecret());
            return copy;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character || value instanceof Enum<?>
                || value instanceof Date || value instanceof TemporalAccessor) {
            return null;
        }
        Object tree;
        try {
            tree = TREE_MAPPER.convertValue(value, Object.class);
        } catch (IllegalArgumentException e) {
            String rendered = String.valueOf(value);
            if (plaintexts.stream().anyMatch(rendered::contains)) {
                LOGGER.warnf("A %s carrying a secret could not be converted for scrubbing; replaced it whole", value.getClass().getSimpleName());
                return placeholder;
            }
            return null;
        }
        return tree == null ? null : scrubDeep(tree, plaintexts, placeholder);
    }

    private static Object scrubDeepWalk(Object value, Collection<String> plaintexts, String placeholder) {
        if (value instanceof String text) {
            String cleaned = text;
            for (String plaintext : plaintexts) {
                cleaned = cleaned.replace(plaintext, placeholder);
            }
            return cleaned.equals(text) ? null : cleaned;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list);
            boolean changed = false;
            for (int i = 0; i < copy.size(); i++) {
                Object cleaned = scrubDeep(copy.get(i), plaintexts, placeholder);
                if (cleaned != null) {
                    copy.set(i, cleaned);
                    changed = true;
                }
            }
            return changed ? copy : null;
        }
        Map<?, ?> map = (Map<?, ?>) value;
        Map<String, Object> copy = new LinkedHashMap<>();
        boolean changed = false;
        for (var entry : map.entrySet()) {
            Object cleaned = scrubDeep(entry.getValue(), plaintexts, placeholder);
            copy.put(String.valueOf(entry.getKey()), cleaned != null ? cleaned : entry.getValue());
            changed |= cleaned != null;
        }
        return changed ? copy : null;
    }

    /**
     * Adds every string, number and boolean found in {@code value} (walking lists
     * and maps) to {@code target} in its string form — the forms in which a context
     * value can be copied into other memory data by a template.
     */
    public static void collectPlaintexts(Object value, Set<String> target) {
        if (value instanceof String text) {
            target.add(text);
        } else if (value instanceof Number || value instanceof Boolean) {
            target.add(String.valueOf(value));
        } else if (value instanceof List<?> list) {
            list.forEach(item -> collectPlaintexts(item, target));
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collectPlaintexts(item, target));
        }
    }
}
