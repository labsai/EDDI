package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import com.mongodb.client.MongoDatabase;
import io.quarkus.arc.DefaultBean;
import io.quarkus.arc.profile.IfBuildProfile;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * MongoDB implementation of {@link IResourceStorageFactory}.
 * <p>
 * This is the default storage factory ({@code @DefaultBean}). It creates
 * {@link MongoResourceStorage} instances backed by the injected
 * {@link MongoDatabase}.
 * <p>
 * Future database backends (e.g., PostgreSQL) can override this by providing an
 * alternative {@code @ApplicationScoped} bean activated via
 * {@code @LookupIfProperty}.
 */
@ApplicationScoped
@DefaultBean
@IfBuildProfile("!postgres")
public class MongoResourceStorageFactory implements IResourceStorageFactory {

    private final MongoDatabase database;

    @Inject
    public MongoResourceStorageFactory(MongoDatabase database) {
        this.database = database;
    }

    @Override
    public <T> IResourceStorage<T> create(String collectionName, IDocumentBuilder documentBuilder, Class<T> documentType, String... indexes) {
        return new MongoResourceStorage<>(database, collectionName, documentBuilder, documentType, indexes);
    }

    /**
     * Expose the underlying database for stores that need direct MongoDB access
     * (e.g., AgentStore, WorkflowStore with custom queries).
     * <p>
     * This method is intentionally on the concrete class, not on the interface, to
     * keep the interface DB-agnostic.
     */
    public MongoDatabase getDatabase() {
        return database;
    }
}
