package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@ApplicationScoped
public class MigrationLogStore implements IMigrationLogStore {
    private static final String COLLECTION_MIGRATION_LOG = "migrationlog";
    private final MongoCollection<MigrationLog> collection;

    @Inject
    public MigrationLogStore(MongoDatabase database) {
        RuntimeUtilities.checkNotNull(database, "database");
        this.collection = database.getCollection(COLLECTION_MIGRATION_LOG, MigrationLog.class);
    }

    @Override
    public MigrationLog readMigrationLog(String name) {
        return collection.find(new Document("name", name)).first();
    }

    @Override
    public void createMigrationLog(MigrationLog migrationLog) {
        collection.insertOne(migrationLog);
    }
}
