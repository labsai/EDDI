/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.ingestion;

import ai.labs.eddi.configs.ingestion.model.RagIngestionSource;
import ai.labs.eddi.datastore.IResourceStore;

/**
 * Store interface for {@link RagIngestionSource} configurations.
 * <p>
 * Provides CRUD operations for ingestion source definitions, managed via REST
 * API at {@code /ragstore/ingestion-sources/}.
 */
public interface IRagIngestionSourceStore extends IResourceStore<RagIngestionSource> {
}
