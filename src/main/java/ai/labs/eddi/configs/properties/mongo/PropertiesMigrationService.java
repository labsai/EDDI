/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.mongo;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.secrets.sanitize.SecretScrubber;
import com.mongodb.MongoNamespace;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
 * <p>
 * <b>Credentials are not migrated.</b> Long-term memories are loaded into every
 * future conversation as {@code properties}, so anything copied here reaches
 * template data, and from there prompts and outbound API calls. Two kinds of
 * key are therefore left behind:
 * <ul>
 * <li>the keys in {@code eddi.migration.properties.skip-keys} — by default
 * {@code userInfo}, which EDDI 5 clients sent with every request as the
 * caller's identity (a platform bearer token beside names and ids). It was
 * never a user's memory; on one production 5.x database it held a learner's
 * token in 159 of 277 documents;</li>
 * <li>any key whose value, at any depth, {@link SecretScrubber} would redact on
 * export — the same rules, so an API key stored as a property is caught
 * too.</li>
 * </ul>
 * A skipped key is logged by name and count, never by value, and is not a
 * failure: its value stays in {@code properties_migrated_v6}, readable by an
 * operator, and nothing is loaded from there.
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class PropertiesMigrationService {

    private static final Logger LOGGER = Logger.getLogger(PropertiesMigrationService.class);
    private static final String LEGACY_COLLECTION = "properties";
    private static final String BACKUP_COLLECTION = "properties_migrated_v6";

    /** The default of {@code eddi.migration.properties.skip-keys}. */
    static final String DEFAULT_SKIP_KEYS = "userInfo";

    private final MongoDatabase database;
    private final IUserMemoryStore userMemoryStore;
    private final String datastoreType;
    private final SecretScrubber secretScrubber;
    private final Set<String> skipKeys;

    @Inject
    public PropertiesMigrationService(MongoDatabase database, IUserMemoryStore userMemoryStore,
            @ConfigProperty(name = "eddi.datastore.type", defaultValue = "mongodb") String datastoreType, SecretScrubber secretScrubber,
            @ConfigProperty(name = "eddi.migration.properties.skip-keys", defaultValue = DEFAULT_SKIP_KEYS) List<String> skipKeys) {
        this.database = database;
        this.userMemoryStore = userMemoryStore;
        this.datastoreType = datastoreType;
        this.secretScrubber = secretScrubber;
        this.skipKeys = new TreeSet<>(skipKeys == null ? List.of() : skipKeys.stream().map(String::trim).filter(k -> !k.isEmpty()).toList());
    }

    /**
     * {@code value} with every BSON {@code ObjectId} replaced by a fixed
     * placeholder, for the credential check only. An ObjectId is an identifier by
     * type; serialised as extended JSON it becomes <code>{"$oid": "65a1…"}</code>,
     * a random-looking hex string the scrubber's entropy rule would take for a key.
     */
    static Object withoutObjectIds(Object value) {
        if (value instanceof ObjectId) {
            return "objectid";
        }
        if (value instanceof Map<?, ?> map) {
            Document copy = new Document();
            map.forEach((k, v) -> copy.put(String.valueOf(k), withoutObjectIds(v)));
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(PropertiesMigrationService::withoutObjectIds).toList();
        }
        return value;
    }

    void onStartup(@Observes StartupEvent event) {
        if (!"mongodb".equals(datastoreType)) {
            LOGGER.debug("[MIGRATION] Skipping properties migration — not in MongoDB mode");
            return;
        }
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
        int failedCount = 0;
        int unownedDocuments = 0;
        int keptNewerEntries = 0;
        Map<String, Integer> skippedByConfig = new TreeMap<>();
        Map<String, Integer> skippedAsCredential = new TreeMap<>();

        for (Document doc : legacyCollection.find()) {
            String userId = doc.getString("userId");
            if (userId == null) {
                // Not a failure: a document with no owner can never be migrated, on this
                // boot or any other. Counting it as one held the rename back forever,
                // re-running the migration on every startup. Its contents stay readable
                // in the backup collection the source is renamed to.
                LOGGER.warnf("[MIGRATION] Skipping document without userId: %s — it remains in '%s' after the rename.",
                        doc.getObjectId("_id"), BACKUP_COLLECTION);
                unownedDocuments++;
                continue;
            }

            for (String key : doc.keySet()) {
                // Skip MongoDB internal fields and the userId field itself
                if ("_id".equals(key) || "userId".equals(key))
                    continue;
                if (IUserMemoryStore.isReservedKey(key)) {
                    // The store refuses these, and counting the refusal as a failure
                    // would keep the legacy collection from ever being retired. A legacy
                    // property can never have been a GDPR flag, which postdates it.
                    LOGGER.warnf("[MIGRATION] Skipping legacy key='%s' for userId='%s': reserved for GDPR bookkeeping", key, userId);
                    continue;
                }

                if (skipKeys.contains(key)) {
                    skippedByConfig.merge(key, 1, Integer::sum);
                    continue;
                }
                Object value = doc.get(key);
                if (secretScrubber.containsCredential(new Document(key, withoutObjectIds(value)).toJson())) {
                    skippedAsCredential.merge(key, 1, Integer::sum);
                    continue;
                }
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

                // A user who already has a GLOBAL entry for this key wrote it after the
                // v5 data was frozen — or a previous (partial) run of this migration did.
                // Either way the existing entry wins: upserting the legacy value over it
                // replaced a newer answer with a stale one, on every retry. Only the
                // global identity counts — a self or group entry with the same key is a
                // different memory and does not stand in for the shared one. The insert
                // is atomic, so a value another node writes meanwhile cannot be lost.
                try {
                    if (userMemoryStore.insertIfAbsent(entry) != null) {
                        entryCount++;
                    } else {
                        keptNewerEntries++;
                    }
                } catch (Exception e) {
                    failedCount++;
                    LOGGER.warnf("[MIGRATION] Failed to migrate key='%s' for userId='%s': %s", key, userId, e.getMessage());
                }
            }
            userCount++;
        }

        if (!skippedByConfig.isEmpty()) {
            LOGGER.infof("[MIGRATION] Not migrated (eddi.migration.properties.skip-keys) — key: documents %s. These values stay "
                    + "in '%s' and are never loaded into a conversation.", skippedByConfig, BACKUP_COLLECTION);
        }
        if (!skippedAsCredential.isEmpty()) {
            LOGGER.warnf("[MIGRATION] Not migrated because the value holds a credential by the export scrubber's rules — "
                    + "key: documents %s. These values stay in '%s' and are never loaded into a conversation; move any that "
                    + "are needed into the secrets vault by hand.", skippedAsCredential, BACKUP_COLLECTION);
        }

        // Only retire the source once every key made it across. The loop is idempotent
        // — a key already present in usermemories is skipped — so leaving the
        // collection in place lets
        // the next boot retry the entries that failed. Renaming on a partial run made
        // the migration a permanent no-op afterwards (collectionExists is then false),
        // so a transient Mongo error on three of four hundred users silently stranded
        // those users' long-term properties in the backup collection, recoverable only
        // by renaming it back by hand.
        if (failedCount > 0) {
            LOGGER.errorf("[MIGRATION] Migrated %d entries for %d users, but %d failed. Leaving '%s' in place; "
                    + "the migration will retry on the next startup. Fix the underlying error and restart.", entryCount, userCount,
                    failedCount, LEGACY_COLLECTION);
            return;
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
            LOGGER.infof("[MIGRATION] Complete: migrated %d entries for %d users (%d keys kept their newer usermemories value, "
                    + "%d documents had no userId). Old collection renamed to '%s'", entryCount, userCount, keptNewerEntries, unownedDocuments,
                    BACKUP_COLLECTION);
        } catch (Exception e) {
            LOGGER.warnf("[MIGRATION] Migration data written but failed to rename collection: %s", e.getMessage());
        }
    }
}
