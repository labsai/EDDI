/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.connector;

import ai.labs.eddi.configs.rag.model.IngestionSource;

import java.util.Objects;

/**
 * The run a connector is working for.
 *
 * @param source
 *            the source's configuration, including the connector's own typed
 *            block
 * @param sourceKey
 *            the state key of the source within its knowledge base — what its
 *            stored files, document state and chunks are all keyed by
 * @param runId
 *            the run's id, or {@code "preview"} for a preview
 * @param preview
 *            the run only reports what would change; a connector must not write
 *            anything to the provider in either mode, and in a preview it
 *            should also avoid anything expensive it can skip
 * @param credentials
 *            where a connector that calls an authenticated provider gets the
 *            credential for each request — {@code SERVICE} connections only.
 *            Never null; {@link SourceCredentials#NONE} when the run has no
 *            connections
 */
public record SourceContext(IngestionSource source, String sourceKey, String runId, boolean preview,
        SourceCredentials credentials) {

    public SourceContext {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceKey, "sourceKey");
        credentials = credentials == null ? SourceCredentials.NONE : credentials;
    }

    /** A run of a source that needs no credential. */
    public SourceContext(IngestionSource source, String sourceKey, String runId, boolean preview) {
        this(source, sourceKey, runId, preview, SourceCredentials.NONE);
    }
}
