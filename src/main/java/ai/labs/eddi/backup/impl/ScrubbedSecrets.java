/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.secrets.sanitize.SecretScrubber;
import ai.labs.eddi.utils.EndpointParameters;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
 * <p>
 * <b>Vault references are the target's too.</b> The scrubber leaves
 * {@code ${vault:openai-key}} legible on purpose — it is a pointer, not a
 * secret — so it used to travel like any other value and overwrite the target's
 * own pointer. That is wrong for the same reason a credential is: which vault
 * entry an environment uses is that environment's business. Staging names
 * {@code ${vault:openai-key}}, production names
 * {@code ${vault:prod-openai-key}}, and a promotion that rewrote production's
 * reference broke every LLM call it made. A value holding a vault reference is
 * therefore bound to the target exactly like a placeholder: where the target
 * has its own value at the same place, that value is kept. Where it has none —
 * a first promotion, a newly added call — the reference travels, because it is
 * the only hint the operator gets of which entry to create.
 *
 * @since 6.0.0
 */
final class ScrubbedSecrets {

    /** The marker {@link SecretScrubber} writes in place of a secret value. */
    static final String PLACEHOLDER = SecretScrubber.REDACTED;

    private static final List<String> VAULT_REFERENCE_PREFIXES = List.of("${vault:", "${eddivault:");

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
     * Whether this content carries anything {@link #restore} would take from the
     * target — a scrubbed placeholder or a vault reference.
     */
    static boolean carriesTargetBoundValue(String json) {
        if (json == null) {
            return false;
        }
        if (json.contains(PLACEHOLDER)) {
            return true;
        }
        for (String prefix : VAULT_REFERENCE_PREFIXES) {
            if (json.contains(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a single value belongs to the target rather than the source: a
     * scrubbed placeholder, or a value naming a vault entry.
     */
    static boolean isTargetBound(String text) {
        return text != null && carriesTargetBoundValue(text);
    }

    /**
     * The source content with every target-bound leaf — a scrubbed placeholder or a
     * vault reference — replaced by the target's own value for that leaf. Object
     * fields are bound by key; array elements by a stable identity where they carry
     * one, and otherwise only by a position the surrounding content proves — see
     * {@link #counterpartOf}. A leaf with no provable counterpart keeps the
     * source's value.
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
        return jsonSerialization.serialize(merge(sourceTree, targetTree, false));
    }

    /**
     * Walks two parsed configs in parallel and returns the source with every
     * scrubbed leaf replaced by the target's value for the same leaf.
     *
     * @param destinationChanged
     *            an enclosing object of the source sends its requests somewhere
     *            other than the target's — see {@link #changesDestination}
     */
    private static Object merge(Object sourceNode, Object targetNode, boolean destinationChanged) {
        if (sourceNode instanceof String text) {
            if (isTargetBound(text) && targetNode instanceof String targetText && keepsTarget(text, targetText)
                    && (!destinationChanged || isVaultReference(targetText))) {
                return targetText;
            }
            return text;
        }
        if (sourceNode instanceof Map<?, ?> sourceMap) {
            Map<?, ?> targetMap = targetNode instanceof Map<?, ?> map ? map : Map.of();
            boolean changed = destinationChanged || changesDestination(sourceMap, targetMap);
            Map<String, Object> merged = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : sourceMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                merged.put(key, merge(entry.getValue(), targetMap.get(key), changed));
            }
            return merged;
        }
        if (sourceNode instanceof List<?> sourceList) {
            List<?> targetList = targetNode instanceof List<?> list ? list : List.of();
            List<Object> merged = new ArrayList<>(sourceList.size());
            Map<String, Map<Object, Object>> targetIndex = new HashMap<>();
            for (int i = 0; i < sourceList.size(); i++) {
                Object sourceElement = sourceList.get(i);
                // An element with nothing scrubbed in it needs no counterpart at all,
                // so it never risks being paired with the wrong one.
                Object counterpart = containsTargetBound(sourceElement)
                        ? counterpartOf(sourceElement, i, sourceList, targetList, targetIndex)
                        : null;
                merged.add(merge(sourceElement, counterpart, destinationChanged));
            }
            return merged;
        }
        return sourceNode;
    }

    /**
     * Whether this source object sends its requests to a different place than its
     * target counterpart: a field naming a destination (any key
     * {@link EndpointParameters#isEndpointKey} accepts — {@code targetServerUrl},
     * {@code baseUrl}, {@code apiEndpoint}, {@code host}, … — or a {@code path}
     * that is an absolute URL on either side) whose origin differs.
     * <p>
     * A literal credential the target holds was authorised for the target's
     * destination; editors cannot read it back, which is the whole point of hiding
     * it. Restoring it by name into a call that keeps the name and changes the host
     * bound that hidden credential to a destination the editor chose, and the next
     * turn sent it there. So under a changed destination a placeholder stays a
     * placeholder and the operator supplies the value. A vault reference still
     * travels: it is a pointer, and a vault key's {@code allowedHosts} is what
     * decides where its secret may go.
     * <p>
     * A destination the source itself carries as a target-bound value is taken from
     * the target by the same merge, so it does not count as a change.
     * <p>
     * The fields of both sides are compared, not only the source's: a source that
     * drops the target's {@code baseUrl} sends to the provider's default endpoint
     * instead, which is a change of destination too. A destination that is not a
     * string — a number where a host belongs — is the same only when it is equal on
     * both sides: skipping it let a numeric host past the comparison and become a
     * different server's address once the config was decoded.
     */
    static boolean changesDestination(Map<?, ?> sourceMap, Map<?, ?> targetMap) {
        Set<String> keys = new LinkedHashSet<>();
        sourceMap.keySet().forEach(key -> keys.add(String.valueOf(key)));
        targetMap.keySet().forEach(key -> keys.add(String.valueOf(key)));
        for (String key : keys) {
            Object sourceValue = sourceMap.get(key);
            Object targetValue = targetMap.get(key);
            if (sourceValue instanceof String bound && isTargetBound(bound)) {
                continue;
            }
            String sourceText = sourceValue instanceof String text ? text : null;
            String targetText = targetValue instanceof String text ? text : null;
            if (!isDestinationField(key, sourceText, targetText) || (sourceValue == null && targetValue == null)) {
                continue;
            }
            if (sourceText == null || targetText == null) {
                if (!Objects.equals(sourceValue, targetValue)) {
                    return true;
                }
                continue;
            }
            if (!Objects.equals(destinationOf(sourceText), destinationOf(targetText))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDestinationField(String key, String sourceValue, String targetValue) {
        if (EndpointParameters.isEndpointKey(key)) {
            return true;
        }
        // A relative path stays on the host its targetServerUrl names, which is
        // compared on its own; an absolute one is a destination in itself.
        return key.equalsIgnoreCase("path") && (isAbsolute(sourceValue) || isAbsolute(targetValue));
    }

    /**
     * Where a value sends a request: the origin ({@code scheme://host:port}) of an
     * absolute URL — a different path on the same origin is the same destination —
     * and otherwise the value itself.
     */
    private static String destinationOf(String value) {
        String trimmed = value.trim();
        if (isAbsolute(trimmed)) {
            try {
                URI uri = URI.create(trimmed);
                if (uri.getHost() != null) {
                    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                    return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + effectivePort(scheme, uri.getPort());
                }
            } catch (IllegalArgumentException unparseable) {
                // a template or otherwise not a URI: compared as written
            }
        }
        return trimmed;
    }

    /**
     * The port a request actually uses: an explicit default ({@code :443} on https)
     * and an omitted one are the same destination, and treating them as different
     * would replace a working credential with a placeholder.
     */
    private static int effectivePort(String scheme, int port) {
        if (port != -1) {
            return port;
        }
        return switch (scheme) {
            case "http", "ws" -> 80;
            case "https", "wss" -> 443;
            default -> -1;
        };
    }

    private static boolean isAbsolute(String value) {
        return value != null && value.contains("://");
    }

    private static boolean isVaultReference(String text) {
        for (String prefix : VAULT_REFERENCE_PREFIXES) {
            if (text.trim().startsWith(prefix)) {
                return true;
            }
        }
        return false;
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
    private static Object counterpartOf(Object sourceElement, int index, List<?> sourceList, List<?> targetList,
                                        Map<String, Map<Object, Object>> targetIndex) {
        String identityField = identityFieldOf(sourceElement);
        if (identityField != null) {
            Object identity = ((Map<?, ?>) sourceElement).get(identityField);
            // Indexed once per list and field: scanning the whole target list for every
            // scrubbed source element made a long array quadratic.
            Object match = targetIndex.computeIfAbsent(identityField, field -> indexBy(field, targetList)).get(identity);
            return match == AMBIGUOUS ? null : match;
        }

        if (sourceList.size() != targetList.size() || index >= targetList.size()) {
            return null;
        }
        Object targetElement = targetList.get(index);
        return equalApartFromPlaceholders(sourceElement, targetElement) ? targetElement : null;
    }

    /** Marks an identity two target elements share, which binds neither. */
    private static final Object AMBIGUOUS = new Object();

    private static Map<Object, Object> indexBy(String identityField, List<?> targetList) {
        Map<Object, Object> index = new HashMap<>();
        for (Object candidate : targetList) {
            if (candidate instanceof Map<?, ?> candidateMap && candidateMap.containsKey(identityField)) {
                Object identity = candidateMap.get(identityField);
                index.merge(identity, candidate, (first, second) -> AMBIGUOUS);
            }
        }
        return index;
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
            return isTargetBound(text) || text.equals(targetNode);
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

    /**
     * Whether the target's value replaces a target-bound source value. A
     * placeholder always yields: whatever the target holds is better than a marker
     * that can never work. A vault reference yields only to a target value that is
     * actually there — a blank one would trade a working pointer for nothing.
     */
    private static boolean keepsTarget(String sourceText, String targetText) {
        return sourceText.contains(PLACEHOLDER) || !targetText.isBlank();
    }

    /** Whether anything anywhere below this parsed node is target-bound. */
    private static boolean containsTargetBound(Object node) {
        if (node instanceof String text) {
            return isTargetBound(text);
        }
        if (node instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                if (containsTargetBound(value)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof List<?> list) {
            for (Object element : list) {
                if (containsTargetBound(element)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }
}
