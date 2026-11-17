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
 */
public record SourceContext(IngestionSource source, String sourceKey, String runId, boolean preview) {

    public SourceContext {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceKey, "sourceKey");
    }
}
