/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import java.util.List;
import java.util.Map;

/**
 * Result of a content fetch operation.
 *
 * @param documents
 *            Fetched documents (content + metadata)
 * @param errors
 *            Any errors that occurred during fetching
 * @param durationMs
 *            Time taken to fetch in milliseconds
 *
 * @since 6.0.3
 */
public record FetchResult(
        List<FetchedDocument> documents,
        List<FetchError> errors,
        long durationMs) {
}

/**
 * A single fetched document with content and metadata.
 *
 * @param id
 *            Unique document identifier (URL for web, path for file, etc.)
 * @param title
 *            Document title
 * @param content
 *            Raw document content
 * @param contentType
 *            MIME type (e.g., "text/html", "text/markdown")
 * @param metadata
 *            Source-specific metadata (URLs, paths, timestamps, etc.)
 *
 * @since 6.0.3
 */
record FetchedDocument(
        String id,
        String title,
        String content,
        String contentType,
        Map<String, String> metadata) {
}

/**
 * An error that occurred during fetching.
 *
 * @param source
 *            The source being fetched (URL, path, etc.)
 * @param error
 *            Error message
 *
 * @since 6.0.3
 */
record FetchError(
        String source,
        String error) {
}
