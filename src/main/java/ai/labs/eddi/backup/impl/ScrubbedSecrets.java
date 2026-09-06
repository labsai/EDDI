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

    private ScrubbedSecrets() {
    }

    /** Whether this content carries at least one scrubbed value. */
    static boolean carriesPlaceholder(String json) {
        return json != null && json.contains(PLACEHOLDER);
    }

    /**
     * The source content with every scrubbed leaf replaced by the target's value at
     * the same position.
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
     * scrubbed leaf replaced by the target's value at the same position.
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
                merged.add(merge(sourceList.get(i), i < targetList.size() ? targetList.get(i) : null));
            }
            return merged;
        }
        return sourceNode;
    }
}
