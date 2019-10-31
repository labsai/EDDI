package ai.labs.resources.impl.migration;

import ai.labs.resources.rest.migration.IMigrationLogStore;
import ai.labs.resources.rest.migration.model.MigrationLog;
import ai.labs.utilities.RuntimeUtilities;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import javax.inject.Inject;

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
