/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.secrets.sanitize.SecretScrubber;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Puts a target's own secret values back into source content that the export's
 * {@link SecretScrubber} redacted.
 * <p>
 * Shared by {@link StructuralMatcher} and {@link UpgradeExecutor} on purpose.
 * Everything in an export ZIP (and everything a remote instance hands out) has
 * been through the scrubber, so an LLM or httpCalls config holding an API key
 * arrives as {@code ${vault:REDACTED}} while the target still carries the live
 * value. Restoring only on the execute side left the two disagreeing: the
 * matcher compared a placeholder against a real credential, so <em>every</em>
 * agent with a credential compared unequal, could never SKIP, and burned a
 * resource and agent version on every sync that changed nothing.
 *
 * @since 6.0.0
 */
final class ScrubbedSecrets {

    /** The marker {@link SecretScrubber} writes in place of a secret value. */
    static final String PLACEHOLDER = SecretScrubber.REDACTED;

    /**
     * Fields that identify one element of a JSON array across a reorder, in
     * priority order. {@code ApiCall.name}, {@code LlmConfiguration.Task.id} and
     * {@code RagConfiguration.name} are the shapes this actually meets.
     */
    private static final List<String> IDENTITY_FIELDS = List.of("name", "id", "key");

    private ScrubbedSecrets() {
    }

    /** Whether this content carries at least one scrubbed value. */
    static boolean carriesPlaceholder(String json) {
        return json != null && json.contains(PLACEHOLDER);
    }

    /**
     * The source content with every scrubbed leaf replaced by the target's own
     * value for that leaf. Object fields are bound by key; array elements by a
     * stable identity where they carry one, and otherwise only by a position the
     * surrounding content proves — see {@link #counterpartOf}. A leaf with no
     * provable counterpart keeps its placeholder.
     *
     * @return the merged JSON
     * @throws Exception
     *             when either side cannot be parsed or the merge cannot be
     *             serialized — the caller decides what to do with the unmerged
     *             source
     */
    static String restore(String sourceJson, String targetJson, IJsonSerialization jsonSerialization)
            throws Exception {
        Object sourceTree = jsonSerialization.deserialize(sourceJson);
        Object targetTree = jsonSerialization.deserialize(targetJson);
        return jsonSerialization.serialize(merge(sourceTree, targetTree));
    }

    /**
     * Walks two parsed configs in parallel and returns the source with every
     * scrubbed leaf replaced by the target's value for the same leaf.
     */
    private static Object merge(Object sourceNode, Object targetNode) {
        if (sourceNode instanceof String text) {
            if (text.contains(PLACEHOLDER) && targetNode instanceof String targetText) {
                return targetText;
            }
            return text;
        }
        if (sourceNode instanceof Map<?, ?> sourceMap) {
            Map<?, ?> targetMap = targetNode instanceof Map<?, ?> map ? map : Map.of();
            Map<String, Object> merged = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : sourceMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                merged.put(key, merge(entry.getValue(), targetMap.get(key)));
            }
            return merged;
        }
        if (sourceNode instanceof List<?> sourceList) {
            List<?> targetList = targetNode instanceof List<?> list ? list : List.of();
            List<Object> merged = new ArrayList<>(sourceList.size());
            for (int i = 0; i < sourceList.size(); i++) {
                Object sourceElement = sourceList.get(i);
                // An element with nothing scrubbed in it needs no counterpart at all,
                // so it never risks being paired with the wrong one.
                Object counterpart = containsPlaceholder(sourceElement)
                        ? counterpartOf(sourceElement, i, sourceList, targetList)
                        : null;
                merged.add(merge(sourceElement, counterpart));
            }
            return merged;
        }
        return sourceNode;
    }

    /**
     * The target element a scrubbed source element may take its value from, or
     * {@code null} when there is no element the pairing can be proven against.
     * <p>
     * Pairing array elements by position alone was a credential disclosure, not
     * only a lost secret: an httpCalls config whose entries were reordered or had
     * one inserted kept each source entry's own endpoint while taking the value at
     * that index from the target, so one endpoint's live {@code Authorization}
     * header was written into the configuration that calls a different host. The
     * two ways out are to bind by something stable or to refuse; refusing costs an
     * operator one re-entered key, guessing wrong hands a credential to whoever is
     * at the other endpoint.
     * <ul>
     * <li>An element carrying one of {@link #IDENTITY_FIELDS} is bound by that
     * value, wherever in the target list it sits. Two target elements sharing the
     * identity are ambiguous, so neither is used.</li>
     * <li>An element with no identity — a bare string in an array, an anonymous
     * object — falls back to its position, and only when the lists are the same
     * length and the two elements are identical in everything the scrubber did not
     * replace. That is what makes them demonstrably the same entry.</li>
     * </ul>
     */
    private static Object counterpartOf(Object sourceElement, int index,
                                        List<?> sourceList, List<?> targetList) {
        String identityField = identityFieldOf(sourceElement);
        if (identityField != null) {
            Object identity = ((Map<?, ?>) sourceElement).get(identityField);
            Object match = null;
            for (Object candidate : targetList) {
                if (candidate instanceof Map<?, ?> candidateMap
                        && Objects.equals(identity, candidateMap.get(identityField))) {
                    if (match != null) {
                        return null;
                    }
                    match = candidate;
                }
            }
            return match;
        }

        if (sourceList.size() != targetList.size() || index >= targetList.size()) {
            return null;
        }
        Object targetElement = targetList.get(index);
        return equalApartFromPlaceholders(sourceElement, targetElement) ? targetElement : null;
    }

    /**
     * The name of the field that identifies this element, or {@code null} when it
     * carries none. Only scalar values count — an object or array under
     * {@code name} is not a natural key.
     */
    private static String identityFieldOf(Object node) {
        if (!(node instanceof Map<?, ?> map)) {
            return null;
        }
        for (String field : IDENTITY_FIELDS) {
            Object value = map.get(field);
            if (value != null && !(value instanceof Map) && !(value instanceof List)) {
                return field;
            }
        }
        return null;
    }

    /**
     * Whether the two nodes agree everywhere the source is not a scrubbed value —
     * that is, whether the target is what the source looked like before the export
     * redacted it.
     */
    private static boolean equalApartFromPlaceholders(Object sourceNode, Object targetNode) {
        if (sourceNode instanceof String text) {
            return text.contains(PLACEHOLDER) || text.equals(targetNode);
        }
        if (sourceNode instanceof Map<?, ?> sourceMap) {
            if (!(targetNode instanceof Map<?, ?> targetMap) || sourceMap.size() != targetMap.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : sourceMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!targetMap.containsKey(key)
                        || !equalApartFromPlaceholders(entry.getValue(), targetMap.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (sourceNode instanceof List<?> sourceList) {
            if (!(targetNode instanceof List<?> targetList) || sourceList.size() != targetList.size()) {
                return false;
            }
            for (int i = 0; i < sourceList.size(); i++) {
                if (!equalApartFromPlaceholders(sourceList.get(i), targetList.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(sourceNode, targetNode);
    }

    /** Whether anything anywhere below this parsed node was scrubbed. */
    private static boolean containsPlaceholder(Object node) {
        if (node instanceof String text) {
            return text.contains(PLACEHOLDER);
        }
        if (node instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                if (containsPlaceholder(value)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof List<?> list) {
            for (Object element : list) {
                if (containsPlaceholder(element)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }
}
