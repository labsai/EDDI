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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * The shortest form of a message the client flagged {@code secretInput} that is
     * searched for in the rest of the turn — its other step data, its output, a
     * pending tool-call batch, its audit entries. Every nonempty form is still
     * replaced wholesale where it IS the input ({@code input:initial},
     * {@code input:normalized}, the displayed {@code input}).
     * <p>
     * Four, not the eight used for secret context values: the client explicitly
     * marked this text a secret, and a 4-digit PIN or a short password is exactly
     * what a password field carries. Not lower, because the search replaces every
     * occurrence in every value and map key of the turn — a one- to three-character
     * "secret" would shred the turn's reply and rename the fields of its stored API
     * responses and output items, while being trivially guessable anyway. The audit
     * ledger uses the same floor, so the stored turn and its ledger entries agree.
     */
    public static final int MIN_SEARCHED_SECRET_INPUT_LENGTH = 4;

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
        return plaintext == null || plaintext.isEmpty() ? null : scrubSorted(value, List.of(plaintext), placeholder, false, Match.SUBSTRING);
    }

    /**
     * {@link #scrubValue} for several plaintexts at once, or {@code null} when none
     * of them occurs. Longer plaintexts are replaced first, whatever the order of
     * {@code plaintexts}, so a shorter one contained in a longer one cannot leave a
     * fragment behind.
     */
    public static Object scrubAll(Object value, Collection<String> plaintexts, String placeholder) {
        List<String> sorted = longestFirst(plaintexts);
        return sorted.isEmpty() ? null : scrubSorted(value, sorted, placeholder, false, Match.SUBSTRING);
    }

    /**
     * A copy of {@code context} — type and secret flag kept — with the plaintext
     * removed from its value, or {@code null} when it does not carry it.
     */
    public static Context scrubContext(Context context, String plaintext, String placeholder) {
        return plaintext == null || plaintext.isEmpty()
                ? null
                : (Context) scrubSorted(context, List.of(plaintext), placeholder, false, Match.SUBSTRING);
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
        List<String> sorted = longestFirst(plaintexts);
        return value == null || sorted.isEmpty() ? null : scrubSorted(value, sorted, placeholder, true, Match.SUBSTRING);
    }

    /**
     * Like {@link #scrubDeep}, but a plaintext is replaced only where it stands as
     * a whole token - not directly preceded or followed by a letter or digit. For
     * values too short to replace verbatim: a three-digit PIN copied into a reply
     * is removed, while the same digits inside a longer number are left alone. Map
     * keys are never changed in this mode — only values — because a short secret is
     * often also a field name ({@code id}, {@code to}).
     *
     * @return the scrubbed copy, or {@code null} when {@code value} carries none of
     *         the plaintexts as a whole token
     */
    public static Object scrubDeepTokens(Object value, Collection<String> plaintexts, String placeholder) {
        List<String> sorted = longestFirst(plaintexts);
        return value == null || sorted.isEmpty() ? null : scrubSorted(value, sorted, placeholder, true, Match.WHOLE_TOKEN);
    }

    /**
     * Like {@link #scrubDeep}, but a {@code ${vault:…}} / {@code ${eddivault:…}}
     * reference is left exactly as it is, and map keys are never renamed. For data
     * that holds the reference a plaintext was just vaulted under next to copies of
     * that plaintext — the template data after a post-response instruction: the
     * slot name ends in the property name, so a plaintext that occurs inside the
     * name would otherwise break the reference, and the property's key with it.
     *
     * @return the scrubbed copy, or {@code null} when {@code value} carries none of
     *         the plaintexts outside a reference
     */
    public static Object scrubDeepKeepingReferences(Object value, Collection<String> plaintexts, String placeholder) {
        List<String> sorted = longestFirst(plaintexts);
        return value == null || sorted.isEmpty() ? null : scrubSorted(value, sorted, placeholder, true, Match.OUTSIDE_REFERENCES);
    }

    /**
     * Like {@link #scrubDeep}, but a string or number is replaced only where it IS
     * one of {@code exactValues} — never where it merely contains one. For short
     * secret context values: a four-digit PIN copied whole into a property, a datum
     * or an API response is the secret, while "order 14711" or "code 4711" is left
     * as it is. Map keys are never changed in this mode, only values.
     * <p>
     * A string holding serialized JSON (a paused tool call's raw arguments, a
     * transcript) is parsed and its values are matched the same way, so
     * {@code {"pin":"4711"}} does not keep the secret just because the whole string
     * is not equal to it. It is rewritten only when something was replaced; nested
     * serialized JSON is reached by the same walk.
     *
     * @return the scrubbed copy, or {@code null} when no value equals one of
     *         {@code exactValues}
     */
    public static Object scrubDeepExact(Object value, Collection<String> exactValues, String placeholder) {
        List<String> sorted = longestFirst(exactValues);
        return value == null || sorted.isEmpty() ? null : scrubSorted(value, sorted, placeholder, true, Match.EXACT);
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
        return scrubTyped(value, type, plaintexts, placeholder, Match.SUBSTRING);
    }

    /**
     * {@link #scrubDeepTokens} for a value that has to stay of its own type — the
     * whole-token counterpart of {@link #scrubTyped}. Map keys (the type's field
     * names in JSON form) are never changed.
     *
     * @return the scrubbed copy, or {@code null} when {@code value} carries none of
     *         the plaintexts as a whole token
     * @throws IllegalArgumentException
     *             if the scrubbed form cannot be read back as {@code type}
     */
    public static <T> T scrubTypedTokens(T value, Class<T> type, Collection<String> plaintexts, String placeholder) {
        return scrubTyped(value, type, plaintexts, placeholder, Match.WHOLE_TOKEN);
    }

    /**
     * {@link #scrubDeepExact} for a value that has to stay of its own type.
     *
     * @return the scrubbed copy, or {@code null} when no value equals one of
     *         {@code exactValues}
     * @throws IllegalArgumentException
     *             if the scrubbed form cannot be read back as {@code type}
     */
    public static <T> T scrubTypedExact(T value, Class<T> type, Collection<String> exactValues, String placeholder) {
        return scrubTyped(value, type, exactValues, placeholder, Match.EXACT);
    }

    private static <T> T scrubTyped(T value, Class<T> type, Collection<String> plaintexts, String placeholder, Match match) {
        List<String> sorted = longestFirst(plaintexts);
        if (value == null || sorted.isEmpty()) {
            return null;
        }
        Object cleaned = scrubSorted(TREE_MAPPER.convertValue(value, Object.class), sorted, placeholder, true, match);
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

    /**
     * The walk shared by every entry point.
     *
     * @param plaintexts
     *            longest first
     * @param deep
     *            whether objects outside strings, numbers, lists, maps and contexts
     *            are scrubbed through their JSON form
     */
    private static Object scrubSorted(Object value, List<String> plaintexts, String placeholder, boolean deep, Match match) {
        if (value instanceof String text) {
            if (match == Match.EXACT) {
                return plaintexts.contains(text) ? placeholder : scrubEmbeddedJson(text, plaintexts, placeholder);
            }
            String cleaned = match == Match.OUTSIDE_REFERENCES
                    ? replaceOutsideReferences(text, plaintexts, placeholder)
                    : replaceAll(text, plaintexts, placeholder, match == Match.WHOLE_TOKEN);
            return cleaned.equals(text) ? null : cleaned;
        }
        if (value instanceof Number number) {
            return plaintexts.contains(String.valueOf(number)) ? placeholder : null;
        }
        if (value instanceof Context context) {
            Object cleaned = scrubSorted(context.getValue(), plaintexts, placeholder, deep, match);
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
                Object cleaned = scrubSorted(copy.get(i), plaintexts, placeholder, deep, match);
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
                // Only substring mode renames a key. A short secret ("id", "to") is also a
                // common field name, and renaming it would corrupt every stored API response
                // and output item of the turn; a property name must keep addressing its value.
                String cleanedKey = match != Match.SUBSTRING ? key : replaceAll(key, plaintexts, placeholder, false);
                Object cleaned = scrubSorted(entry.getValue(), plaintexts, placeholder, deep, match);
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
            if (match == Match.EXACT ? plaintexts.contains(rendered) : plaintexts.stream().anyMatch(rendered::contains)) {
                LOGGER.warnf("A %s carrying a secret could not be converted for scrubbing; replaced it whole", value.getClass().getSimpleName());
                return placeholder;
            }
            return null;
        }
        return tree == null ? null : scrubSorted(tree, plaintexts, placeholder, true, match);
    }

    /**
     * A string that holds serialized JSON, rewritten with every value that IS one
     * of {@code exactValues} replaced, or {@code null} when it is not JSON or holds
     * none — see {@link #scrubDeepExact}.
     */
    private static String scrubEmbeddedJson(String text, List<String> exactValues, String placeholder) {
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
        Object cleaned = scrubSorted(tree, exactValues, placeholder, false, Match.EXACT);
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

    /** How a plaintext is matched against a string. */
    private enum Match {
        /** Every occurrence, in values and map keys. */
        SUBSTRING,
        /** Where it stands as a whole token, in values only. */
        WHOLE_TOKEN,
        /** Where a value is exactly the plaintext, in values only. */
        EXACT,
        /** Every occurrence outside a vault reference, in values only. */
        OUTSIDE_REFERENCES
    }

    /** A vault reference, in either spelling — left intact by that mode. */
    private static final Pattern VAULT_REFERENCE = Pattern.compile("\\$\\{(?:vault|eddivault):[^}]*\\}");

    private static String replaceOutsideReferences(String text, List<String> plaintexts, String placeholder) {
        Matcher reference = VAULT_REFERENCE.matcher(text);
        var cleaned = new StringBuilder(text.length());
        int from = 0;
        while (reference.find()) {
            cleaned.append(replaceAll(text.substring(from, reference.start()), plaintexts, placeholder, false)).append(reference.group());
            from = reference.end();
        }
        return cleaned.append(replaceAll(text.substring(from), plaintexts, placeholder, false)).toString();
    }

    private static String replaceAll(String text, List<String> plaintexts, String placeholder, boolean wholeToken) {
        String cleaned = text;
        for (String plaintext : plaintexts) {
            if (wholeToken) {
                cleaned = Pattern.compile(TOKEN_START + Pattern.quote(plaintext) + TOKEN_END)
                        .matcher(cleaned).replaceAll(Matcher.quoteReplacement(placeholder));
            } else {
                cleaned = cleaned.replace(plaintext, placeholder);
            }
        }
        return cleaned;
    }

    /** No letter or digit directly before the match. */
    private static final String TOKEN_START = "(?<![\\p{L}\\p{N}])";
    /** No letter or digit directly after the match. */
    private static final String TOKEN_END = "(?![\\p{L}\\p{N}])";

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
