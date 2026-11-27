/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.exception.UnsupportedFeatureException;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * A vector store that can change the metadata of stored segments in place,
 * without their embeddings being computed again.
 *
 * <p>
 * langchain4j's {@code EmbeddingStore} has no such operation, so this is EDDI's
 * own, implemented by the two stores EDDI subclasses: the in-memory store and
 * pgvector. Ingestion uses it when a document was renamed at its source but its
 * content did not change: the chunks keep their vectors and get the new title
 * and URL. On every other store the caller falls back to embedding that one
 * document again.
 */
public interface SegmentMetadataUpdates {

    /** What a metadata key must look like to be matched on or written. */
    Pattern METADATA_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    /**
     * Sets {@code values} on the metadata of every stored segment whose metadata
     * has each of {@code match}'s keys with exactly that value. Keys not in
     * {@code values} keep their values.
     *
     * @return how many segments were changed
     * @throws UnsupportedFeatureException
     *             when this store cannot do it after all, which the caller treats
     *             as a store without the capability
     * @throws IllegalArgumentException
     *             for an empty {@code match}, which would change every segment, or
     *             a key outside {@link #METADATA_KEY}
     */
    int updateMetadata(Map<String, String> match, Map<String, String> values);

    /** The argument checks every implementation applies. */
    static void requireValid(Map<String, String> match, Map<String, String> values) {
        if (match == null || match.isEmpty()) {
            throw new IllegalArgumentException("A metadata update must match on at least one key");
        }
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("A metadata update must set at least one key");
        }
        // Iterated rather than asked with containsKey/containsValue(null): an
        // immutable map answers those with a NullPointerException.
        for (Map.Entry<String, String> condition : match.entrySet()) {
            requireKey(condition.getKey());
            if (condition.getValue() == null) {
                throw new IllegalArgumentException("A metadata update cannot match on a missing value");
            }
        }
        for (String key : values.keySet()) {
            requireKey(key);
        }
    }

    private static void requireKey(String key) {
        if (key == null || !METADATA_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Not a metadata key: " + key);
        }
    }
}
