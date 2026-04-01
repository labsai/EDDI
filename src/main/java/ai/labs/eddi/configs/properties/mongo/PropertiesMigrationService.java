package ai.labs.eddi.configs.properties.mongo;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import com.mongodb.MongoNamespace;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.bson.Document;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * One-time startup migration: moves all legacy {@code properties} documents
 * into the unified {@code usermemories} collection as {@code global} entries.
 * <p>
 * Idempotent: if the {@code properties} collection doesn't exist or is empty,
 * this is a no-op. After successful migration, the old collection is renamed to
 * {@code properties_migrated_v6} as a safety backup.
 * <p>
 * Only active in MongoDB mode (not Postgres — Postgres was added in v6
 * alongside the usermemories table, so there is no legacy properties table to
 * migrate).
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class PropertiesMigrationService {

    private static final Logger LOGGER = Logger.getLogger(PropertiesMigrationService.class);
    private static final String LEGACY_COLLECTION = "properties";
    private static final String BACKUP_COLLECTION = "properties_migrated_v6";

    private final MongoDatabase database;
    private final IUserMemoryStore userMemoryStore;

    @Inject
    public PropertiesMigrationService(MongoDatabase database, IUserMemoryStore userMemoryStore) {
        this.database = database;
        this.userMemoryStore = userMemoryStore;
    }

    void onStartup(@Observes StartupEvent event) {
        try {
            migrateIfNeeded();
        } catch (Exception e) {
            LOGGER.error("[MIGRATION] Failed to migrate legacy properties — will retry on next startup", e);
        }
    }

    private void migrateIfNeeded() {
        // Check if legacy collection exists and has documents
        boolean collectionExists = false;
        for (String name : database.listCollectionNames()) {
            if (LEGACY_COLLECTION.equals(name)) {
                collectionExists = true;
                break;
            }
        }

        if (!collectionExists) {
            LOGGER.debug("[MIGRATION] No legacy 'properties' collection found — skipping migration");
            return;
        }

        MongoCollection<Document> legacyCollection = database.getCollection(LEGACY_COLLECTION);
        long docCount = legacyCollection.countDocuments();
        if (docCount == 0) {
            LOGGER.debug("[MIGRATION] Legacy 'properties' collection is empty — skipping migration");
            return;
        }

        LOGGER.infof("[MIGRATION] Migrating %d legacy property documents to 'usermemories'...", docCount);

        int userCount = 0;
        int entryCount = 0;

        for (Document doc : legacyCollection.find()) {
            String userId = doc.getString("userId");
            if (userId == null) {
                LOGGER.warnf("[MIGRATION] Skipping document without userId: %s", doc.getObjectId("_id"));
                continue;
            }

            for (String key : doc.keySet()) {
                // Skip MongoDB internal fields and the userId field itself
                if ("_id".equals(key) || "userId".equals(key))
                    continue;

                Object value = doc.get(key);
                UserMemoryEntry entry = new UserMemoryEntry(null, // id — generated on insert
                        userId, key, value, "legacy", // category — easy to identify migrated entries
                        Visibility.global, // matches old unscoped behavior
                        null, // no sourceAgentId (was shared across all agents)
                        List.of(), // no groupIds
                        null, // no sourceConversationId
                        false, // not conflicted
                        0, // accessCount
                        null, // createdAt — set by upsert
                        null // updatedAt — set by upsert
                );

                try {
                    userMemoryStore.upsert(entry);
                    entryCount++;
                } catch (Exception e) {
                    LOGGER.warnf("[MIGRATION] Failed to migrate key='%s' for userId='%s': %s", key, userId, e.getMessage());
                }
            }
            userCount++;
        }

        // Rename old collection as safety backup
        try {
            // Drop backup if it exists from a previous partial run
            for (String name : database.listCollectionNames()) {
                if (BACKUP_COLLECTION.equals(name)) {
                    database.getCollection(BACKUP_COLLECTION).drop();
                    break;
                }
            }
            legacyCollection.renameCollection(new MongoNamespace(database.getName(), BACKUP_COLLECTION));
            LOGGER.infof("[MIGRATION] Complete: migrated %d entries for %d users. " + "Old collection renamed to '%s'", entryCount, userCount,
                    BACKUP_COLLECTION);
        } catch (Exception e) {
            LOGGER.warnf("[MIGRATION] Migration data written but failed to rename collection: %s", e.getMessage());
        }
    }
}
