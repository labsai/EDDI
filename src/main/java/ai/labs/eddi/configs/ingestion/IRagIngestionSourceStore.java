/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.ingestion;

import ai.labs.eddi.configs.ingestion.model.RagIngestionSource;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;
import java.util.Map;

/**
 * Store interface for {@link RagIngestionSource} configurations.
 * <p>
 * Provides CRUD operations for ingestion source definitions, managed via REST
 * API at {@code /ragstore/ingestion-sources/}.
 */
public interface IRagIngestionSourceStore extends IResourceStore<RagIngestionSource> {

    /**
     * Finds ingestion sources by their referenced RAG configuration URI.
     *
     * @param ragConfigUri
     *            the RAG config URI to search for (exact match)
     * @param index
     *            pagination index (0-based)
     * @param limit
     *            max results per page
     * @return list of matching ingestion sources as maps with a {@code resource}
     *         key
     * @throws ResourceStoreException
     *             on query failure
     */
    List<Map<String, Object>> findByRagConfigUri(String ragConfigUri, int index, int limit)
            throws ResourceStoreException;
}
