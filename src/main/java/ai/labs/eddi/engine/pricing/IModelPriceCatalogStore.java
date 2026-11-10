/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;

import java.util.Optional;

/**
 * Persistence for the deployment's model price catalog: one document, keyed
 * {@code default} so a per-tenant key can be added later without a migration.
 * Writes are compare-and-set on the document's revision. A MongoDB and a
 * PostgreSQL implementation exist; {@code DataStoreProducers} picks one.
 */
public interface IModelPriceCatalogStore {

    /** The stored catalog, or empty when none has ever been written. */
    Optional<ModelPriceCatalog> read();

    /**
     * Writes {@code next} if, and only if, the stored revision is still
     * {@code expectedRevision} ({@code 0}: no document exists yet).
     *
     * @return {@code false} when another write got there first
     */
    boolean compareAndSet(long expectedRevision, ModelPriceCatalog next);
}
