/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import io.quarkus.arc.DefaultBean;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;
import javax.sql.DataSource;

/**
 * PostgreSQL implementation of {@link IResourceStorageFactory}.
 * <p>
 * Activated when {@code eddi.datastore.type=postgres} is set. Creates
 * {@link PostgresResourceStorage} instances backed by the Quarkus-managed
 * {@link DataSource} (Agroal connection pool).
 * <p>
 * This overrides the default
 * {@link ai.labs.eddi.datastore.mongo.MongoResourceStorageFactory} when the
 * PostgreSQL profile is active.
 *
 * @see ai.labs.eddi.datastore.mongo.MongoResourceStorageFactory
 */
@ApplicationScoped
@DefaultBean
public class PostgresResourceStorageFactory implements IResourceStorageFactory {

    private final Instance<DataSource> dataSourceInstance;
    private final IJsonSerialization jsonSerialization;

    @Inject
    public PostgresResourceStorageFactory(Instance<DataSource> dataSourceInstance, IJsonSerialization jsonSerialization) {
        this.dataSourceInstance = dataSourceInstance;
        this.jsonSerialization = jsonSerialization;
    }

    /**
     * {@inheritDoc}
     * <p>
     * {@code documentBuilder} is accepted for interface compatibility but unused:
     * PostgreSQL storage goes through {@link IJsonSerialization} directly for
     * JSON↔object conversion.
     * <p>
     * {@code indexes} used to be dropped on the floor here. Callers that pass real
     * hints (AgentStore, WorkflowStore, GroupConversationStore) got sequential
     * scans and no way to tell — so they are now materialised as expression indexes
     * by {@link PostgresResourceStorage}.
     */
    @Override
    public <T> IResourceStorage<T> create(String collectionName, IDocumentBuilder documentBuilder, Class<T> documentType, String... indexes) {
        return new PostgresResourceStorage<>(dataSourceInstance.get(), collectionName, jsonSerialization, documentType, indexes);
    }

    /**
     * Expose the underlying DataSource for stores that need direct JDBC access.
     */
    public DataSource getDataSource() {
        return dataSourceInstance.get();
    }
}
