/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.model.Context;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes a known plaintext from the plain values conversation memory is made
 * of — strings, numbers, lists, maps (keys and values) and {@link Context}
 * entries — without mutating them.
 * <p>
 * Shared by the two places that must keep a secret out of the stored
 * conversation: {@code PropertySetterTask} after it vaults a
 * {@code scope: "secret"} property, and {@code Conversation} at the end of a
 * turn that carried a context value the client marked secret.
 * <p>
 * A string is scrubbed by replacing every occurrence of the plaintext. A number
 * is replaced by the placeholder only when its string form equals a plaintext:
 * a number cannot hold part of a secret without being the secret.
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
        return plaintext == null || plaintext.isEmpty() ? null : scrubSorted(value, List.of(plaintext), Set.of(), placeholder, false);
    }

    /**
     * {@link #scrubValue} for several plaintexts at once, or {@code null} when none
     * of them occurs. Longer plaintexts are replaced first, whatever the order of
     * {@code plaintexts}, so a shorter one contained in a longer one cannot leave a
     * fragment behind.
     */
    public static Object scrubAll(Object value, Collection<String> plaintexts, String placeholder) {
        List<String> sorted = longestFirst(plaintexts);
        return sorted.isEmpty() ? null : scrubSorted(value, sorted, Set.of(), placeholder, false);
    }

    /**
     * A copy of {@code context} — type and secret flag kept — with the plaintext
     * removed from its value, or {@code null} when it does not carry it.
     */
    public static Context scrubContext(Context context, String plaintext, String placeholder) {
        return plaintext == null || plaintext.isEmpty() ? null : (Context) scrubSorted(context, List.of(plaintext), Set.of(), placeholder, false);
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
        return scrubDeep(value, plaintexts, Set.of(), placeholder);
    }

    /**
     * {@link #scrubDeep(Object, Collection, String)}, additionally replacing any
     * string (or number) that is EXACTLY one of {@code exactValues} — never a
     * substring of one.
     * <p>
     * For values too short to search for: replacing every "4711" inside a turn's
     * output would destroy it, but a property, list element or map value that IS
     * the short secret is the secret, and used to survive the end-of-turn scrub
     * into the stored properties, the user memory store and the audit trail.
     *
     * @return the scrubbed copy, or {@code null} when {@code value} carries none of
     *         the plaintexts and none of the exact values
     */
    public static Object scrubDeep(Object value, Collection<String> plaintexts, Collection<String> exactValues, String placeholder) {
        List<String> sorted = longestFirst(plaintexts);
        Set<String> exact = usable(exactValues);
        return value == null || (sorted.isEmpty() && exact.isEmpty()) ? null : scrubSorted(value, sorted, exact, placeholder, true);
    }

    /**
     * {@link #scrubDeep} for a value that has to stay of its own type — scrubbed
     * through its JSON form and read back as {@code type}.
     *
     * @return the scrubbed copy, or {@code null} when {@code value} carries none of
     *         the plaintexts
     * @throws IllegalArgumentException
     *             if the scrubbed form cannot be read back as {@code type}
     */
    public static <T> T scrubTyped(T value, Class<T> type, Collection<String> plaintexts, String placeholder) {
        return scrubTyped(value, type, plaintexts, Set.of(), placeholder);
    }

    /**
     * {@link #scrubTyped(Object, Class, Collection, String)} with the exact-match
     * values of {@link #scrubDeep(Object, Collection, Collection, String)}.
     */
    public static <T> T scrubTyped(T value, Class<T> type, Collection<String> plaintexts, Collection<String> exactValues, String placeholder) {
        if (value == null || (longestFirst(plaintexts).isEmpty() && usable(exactValues).isEmpty())) {
            return null;
        }
        Object cleaned = scrubDeep(TREE_MAPPER.convertValue(value, Object.class), plaintexts, exactValues, placeholder);
        return cleaned == null ? null : TREE_MAPPER.convertValue(cleaned, type);
    }

    /**
     * The usable plaintexts, longest first. Null and empty ones are dropped:
     * replacing "" would insert the placeholder between every character.
     */
    private static List<String> longestFirst(Collection<String> plaintexts) {
        return plaintexts.stream()
                .filter(plaintext -> plaintext != null && !plaintext.isEmpty())
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    private static Set<String> usable(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> usable = new HashSet<>();
        values.stream().filter(v -> v != null && !v.isEmpty()).forEach(usable::add);
        return usable;
    }

    /**
     * The walk shared by every entry point.
     *
     * @param plaintexts
     *            longest first
     * @param exact
     *            values replaced only where a string or number equals them whole
     * @param deep
     *            whether objects outside strings, numbers, lists, maps and contexts
     *            are scrubbed through their JSON form
     */
    private static Object scrubSorted(Object value, List<String> plaintexts, Set<String> exact, String placeholder, boolean deep) {
        if (value instanceof String text) {
            if (exact.contains(text)) {
                return placeholder;
            }
            String cleaned = replaceAll(text, plaintexts, placeholder);
            String json = exact.isEmpty() ? null : scrubEmbeddedJson(cleaned, exact, placeholder);
            if (json != null) {
                return json;
            }
            return cleaned.equals(text) ? null : cleaned;
        }
        if (value instanceof Number number) {
            String rendered = String.valueOf(number);
            return plaintexts.contains(rendered) || exact.contains(rendered) ? placeholder : null;
        }
        if (value instanceof Context context) {
            Object cleaned = scrubSorted(context.getValue(), plaintexts, exact, placeholder, deep);
            if (cleaned == null) {
                return null;
            }
            var copy = new Context(context.getType(), cleaned);
            copy.setSecret(context.getSecret());
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list);
            boolean changed = false;
            for (int i = 0; i < copy.size(); i++) {
                Object cleaned = scrubSorted(copy.get(i), plaintexts, exact, placeholder, deep);
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
                String key = String.valueOf(entry.getKey());
                // A key that IS a short secret is the secret, exactly like a value.
                String cleanedKey = exact.contains(key) ? placeholder : replaceAll(key, plaintexts, placeholder);
                Object cleaned = scrubSorted(entry.getValue(), plaintexts, exact, placeholder, deep);
                copy.put(cleanedKey, cleaned != null ? cleaned : entry.getValue());
                changed |= cleaned != null || !cleanedKey.equals(key);
            }
            return changed ? copy : null;
        }
        if (!deep || value == null || value instanceof Boolean || value instanceof Character || value instanceof Enum<?>
                || value instanceof Date || value instanceof TemporalAccessor) {
            return null;
        }
        Object tree;
        try {
            tree = TREE_MAPPER.convertValue(value, Object.class);
        } catch (IllegalArgumentException e) {
            String rendered = String.valueOf(value);
            if (plaintexts.stream().anyMatch(rendered::contains) || exact.contains(rendered)) {
                LOGGER.warnf("A %s carrying a secret could not be converted for scrubbing; replaced it whole", value.getClass().getSimpleName());
                return placeholder;
            }
            return null;
        }
        return tree == null ? null : scrubSorted(tree, plaintexts, exact, placeholder, true);
    }

    /**
     * A string that holds serialized JSON — a tool call's raw arguments, a chat
     * transcript, a request body — rewritten with every value (or key) that IS an
     * exact-match secret replaced, or {@code null} when it is not JSON or carries
     * none. Without this, {@code {"pin":"4711"}} is one string that does not equal
     * "4711", and a short secret survived wherever a JSON document was stored as
     * text. Nested serialized JSON is reached by the same walk.
     */
    private static String scrubEmbeddedJson(String text, Set<String> exact, String placeholder) {
        String trimmed = text.strip();
        if (trimmed.length() < 2 || !(trimmed.startsWith("{") && trimmed.endsWith("}") || trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return null;
        }
        Object tree;
        try {
            tree = TREE_MAPPER.readValue(trimmed, Object.class);
        } catch (JsonProcessingException e) {
            return null;
        }
        Object cleaned = scrubSorted(tree, List.of(), exact, placeholder, false);
        if (cleaned == null) {
            return null;
        }
        try {
            return TREE_MAPPER.writeValueAsString(cleaned);
        } catch (JsonProcessingException e) {
            LOGGER.warn("Serialized JSON carrying a secret could not be rewritten after scrubbing; replaced it whole");
            return placeholder;
        }
    }

    private static String replaceAll(String text, List<String> plaintexts, String placeholder) {
        String cleaned = text;
        for (String plaintext : plaintexts) {
            cleaned = cleaned.replace(plaintext, placeholder);
        }
        return cleaned;
    }

    /**
     * Adds every string, number and boolean found in {@code value} (walking lists
     * and map values) to {@code target} in its string form — the forms in which a
     * context value can be copied into other memory data by a template.
     * <p>
     * Map keys are not collected: in a secret object they are field names
     * ({@code accessToken}, {@code refreshToken}), and searching for those would
     * rename the same fields in every saved API response and reply of the turn. A
     * key that contains a collected value is still scrubbed by the walkers above.
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
