/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.ingestion.model.SourceConfig;

/**
 * Pluggable content fetcher for ingestion sources.
 * <p>
 * Implementations handle gathering content from different source types: web
 * crawling, file system scanning, Git repository cloning, API polling, etc.
 * <p>
 * All implementations are discovered via CDI and registered by their
 * {@link #getType()} return value. The {@link RagIngestionService} dispatches
 * to the appropriate fetcher based on the source configuration's type field.
 *
 * @since 6.0.3
 */
public interface ContentFetcher {

    /**
     * Returns the source type this fetcher handles.
     * <p>
     * Must match the {@code type} field in {@code RagIngestionSource}. Examples:
     * "web", "file", "git", "api".
     *
     * @return the source type identifier
     */
    String getType();

    /**
     * Fetches content from the source.
     * <p>
     * The implementation should:
     * <ol>
     * <li>Validate and cast {@code config} to the appropriate subtype</li>
     * <li>Fetch content from the source (crawl, scan, clone, etc.)</li>
     * <li>Return documents with their raw content and metadata</li>
     * <li>Report any errors without failing the entire operation</li>
     * </ol>
     *
     * @param sourceId
     *            the ingestion source ID (for logging/metrics)
     * @param config
     *            the source-type-specific configuration
     * @return fetch result containing documents and any errors
     * @throws IllegalArgumentException
     *             if {@code config} is not the expected type
     */
    FetchResult fetch(String sourceId, SourceConfig config);
}
