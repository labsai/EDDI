package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.reactivex.rxjava3.core.Observable;
import org.bson.Document;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.NoSuchElementException;

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
        try {
            return Observable.fromPublisher(collection.find(new Document("name", name)).first()).blockingFirst();
        } catch (NoSuchElementException e) {
            return null;
        }
    }

    @Override
    public void createMigrationLog(MigrationLog migrationLog) {
        Observable.fromPublisher(collection.insertOne(migrationLog)).blockingFirst();
    }
}
