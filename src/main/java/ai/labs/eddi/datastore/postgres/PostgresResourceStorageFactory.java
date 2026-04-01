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

    @Override
    public <T> IResourceStorage<T> create(String collectionName, IDocumentBuilder documentBuilder, Class<T> documentType, String... indexes) {
        // PostgreSQL storage uses IJsonSerialization directly for clean JSON↔object
        // conversion.
        // The documentBuilder parameter is accepted for interface compatibility but not
        // used.
        return new PostgresResourceStorage<>(dataSourceInstance.get(), collectionName, jsonSerialization, documentType);
    }

    /**
     * Expose the underlying DataSource for stores that need direct JDBC access.
     */
    public DataSource getDataSource() {
        return dataSourceInstance.get();
    }
}
