/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.configs.migration.LegacyDocumentMigrations.FIELD_NAME_CONVERSATION_PROPERTIES;
import static ai.labs.eddi.datastore.mongo.MongoResourceStorage.ID_FIELD;
import static ai.labs.eddi.datastore.mongo.MongoResourceStorage.VERSION_FIELD;
import static com.mongodb.client.model.Filters.eq;
import static java.lang.String.format;

@ApplicationScoped
@DefaultBean
public class MigrationManager implements IMigrationManager {
    private static final Logger LOGGER = Logger.getLogger(MigrationManager.class);

    public static final String MIGRATION_CONFIRMATION = "migrated properties,httpcalls,output.";
    public static final String COLLECTION_OUTPUTS = "outputs";
    public static final String COLLECTION_HTTPCALLS = "apicalls";
    public static final String COLLECTION_PROPERTYSETTER = "propertysetter";
    public static final String COLLECTION_CONVERSATION_MEMORY = "conversationmemories";

    /**
     * Documents are copied here in their pre-migration state before they are
     * rewritten, so a bad migration stays recoverable.
     * <p>
     * The copy is deliberately narrowed to the fields a migration can actually
     * rewrite (see {@link #backupProjection(String, Document)}). Conversation
     * memories are reduced to {@code _id} + {@code conversationProperties}: copying
     * the whole snapshot would duplicate every conversation step — verbatim user
     * input — and the owning {@code userId} into a collection that no retention
     * sweep and no GDPR erasure knows about. Personal data must not survive in a
     * store nothing ever deletes.
     * <p>
     * Backups are still not erased automatically. Drop them once the migration is
     * verified, or set {@code eddi.migration.backupBeforeWrite=false} to skip them
     * entirely; a WARN naming the collection is logged whenever one is written.
     */
    public static final String BACKUP_COLLECTION_SUFFIX = ".premigrationbackup";

    /**
     * The only part of a conversation-memory document
     * {@link #migrateConversationMemory()} rewrites, plus the id needed to restore
     * it. Everything else — most of all the conversation steps — stays out of the
     * backup.
     */
    private static final List<String> CONVERSATION_MEMORY_BACKUP_FIELDS = List.of(ID_FIELD, FIELD_NAME_CONVERSATION_PROPERTIES);

    private final MongoDatabase database;
    private final MongoCollection<Document> propertySetterCollection;
    private final MongoCollection<Document> propertySetterCollectionHistory;
    private final MongoCollection<Document> httpCallsCollection;
    private final MongoCollection<Document> httpCallsCollectionHistory;
    private final MongoCollection<Document> outputCollection;
    private final MongoCollection<Document> outputCollectionHistory;
    private final MongoCollection<Document> conversationMemoryCollection;
    private final MigrationLogStore migrationLogStore;
    private final Boolean skipConversationMemories;
    private final boolean backupBeforeWrite;
    private boolean isCurrentlyRunning = false;

    /**
     * Convenience constructor keeping the pre-migration backup enabled.
     */
    public MigrationManager(MongoDatabase database, MigrationLogStore migrationLogStore, Boolean skipConversationMemories) {
        this(database, migrationLogStore, skipConversationMemories, true);
    }

    @Inject
    public MigrationManager(MongoDatabase database, MigrationLogStore migrationLogStore,
            @ConfigProperty(name = "eddi.migration.skipConversationMemories") Boolean skipConversationMemories,
            @ConfigProperty(name = "eddi.migration.backupBeforeWrite", defaultValue = "true") boolean backupBeforeWrite) {
        this.database = database;
        this.propertySetterCollection = database.getCollection(COLLECTION_PROPERTYSETTER);
        this.propertySetterCollectionHistory = database.getCollection(COLLECTION_PROPERTYSETTER + ".history");

        this.httpCallsCollection = database.getCollection(COLLECTION_HTTPCALLS);
        this.httpCallsCollectionHistory = database.getCollection(COLLECTION_HTTPCALLS + ".history");

        this.outputCollection = database.getCollection(COLLECTION_OUTPUTS);
        this.outputCollectionHistory = database.getCollection(COLLECTION_OUTPUTS + ".history");

        this.conversationMemoryCollection = database.getCollection(COLLECTION_CONVERSATION_MEMORY);

        this.migrationLogStore = migrationLogStore;
        this.skipConversationMemories = skipConversationMemories;
        this.backupBeforeWrite = backupBeforeWrite;
    }

    @Override
    public synchronized void startMigrationIfFirstTimeRun(IMigrationFinished migrationFinished) {
        if (!this.isCurrentlyRunning) {
            this.isCurrentlyRunning = true;
            if (isMigrationNeeded()) {
                startPropertyMigration();
                startApiCallsMigration();
                startOutputMigration();
                if (!skipConversationMemories) {
                    startConversationMemoryMigration();
                }

                migrationLogStore.createMigrationLog(new MigrationLog(MIGRATION_CONFIRMATION));
            }
            migrationFinished.onComplete();
            this.isCurrentlyRunning = false;
        }
    }

    private boolean isMigrationNeeded() {
        var migrationLog = migrationLogStore.readMigrationLog(MIGRATION_CONFIRMATION);
        return migrationLog == null;
    }

    private void startPropertyMigration() {
        try {
            IDocumentMigration migration = migratePropertySetter();
            boolean migrationHasExecuted = iterateMigration(COLLECTION_PROPERTYSETTER, migration, propertySetterCollection,
                    propertySetterCollectionHistory);

            if (migrationHasExecuted) {
                LOGGER.info("Migration of propertysetter documents has finished!");
            } else {
                LOGGER.info("No migration of propertysetter documents was needed!");
            }
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
    }

    private void startApiCallsMigration() {
        try {
            IDocumentMigration migration = migrateApiCalls();
            boolean migrationHasExecuted = iterateMigration(COLLECTION_HTTPCALLS, migration, httpCallsCollection, httpCallsCollectionHistory);

            if (migrationHasExecuted) {
                LOGGER.info("Migration of httpcalls documents has finished!");
            } else {
                LOGGER.info("No migration of httpcalls documents was needed!");
            }
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
    }

    private void startOutputMigration() {
        try {
            IDocumentMigration migration = migrateOutput();
            boolean migrationHasExecuted = iterateMigration(COLLECTION_OUTPUTS, migration, outputCollection, outputCollectionHistory);

            if (migrationHasExecuted) {
                LOGGER.info("Migration of output documents has finished!");
            } else {
                LOGGER.info("No migration of output documents was needed!");
            }
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
    }

    private void startConversationMemoryMigration() {
        try {
            IDocumentMigration migration = migrateConversationMemory();
            boolean migrationHasExecuted = iterateMigration(COLLECTION_CONVERSATION_MEMORY, migration, conversationMemoryCollection, null);

            if (migrationHasExecuted) {
                LOGGER.info("Migration of conversation memory documents has finished!");
            } else {
                LOGGER.info("No migration of conversation memory documents was needed!");
            }
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
    }

    private boolean iterateMigration(String documentType, IDocumentMigration migration, MongoCollection<Document> collection,
                                     MongoCollection<Document> collectionHistory) {

        var migrationHasExecuted = migrateDocuments(documentType, collection.find(), migration, collection, false);

        if (collectionHistory != null) {
            migrationHasExecuted = migrateDocuments(documentType, collectionHistory.find(), migration, collectionHistory, true)
                    || migrationHasExecuted;
        }
        return migrationHasExecuted;
    }

    private boolean migrateDocuments(String documentType, Iterable<Document> documents, IDocumentMigration migration,
                                     MongoCollection<Document> collection, boolean isHistory) {

        boolean migrationHasExecuted = false;
        var backupCollection = resolveBackupCollection(documentType, isHistory);
        int backedUp = 0;
        for (var document : documents) {
            // snapshot before the migration mutates the document in place — a rewrite
            // that turns out to be wrong must stay recoverable
            var originalDocument = backupCollection != null ? backupProjection(documentType, document) : null;
            var migratedDocument = migration.migrate(document);
            if (migratedDocument != null) {
                if (backupDocument(backupCollection, originalDocument)) {
                    backedUp++;
                }
                saveToPersistence(documentType, migratedDocument, isHistory, collection);
                migrationHasExecuted = true;
            }
        }

        if (backedUp > 0) {
            // operators have to know this collection exists: nothing reads, expires or
            // erases it, so it is theirs to drop once the migration is verified
            LOGGER.warnf("Kept %d pre-migration document(s) in '%s'. Nothing removes that collection automatically — "
                    + "drop it once the migration is verified, or set eddi.migration.backupBeforeWrite=false to skip "
                    + "the pre-migration backup entirely.", backedUp, backupCollectionName(documentType, isHistory));
        }

        return migrationHasExecuted;
    }

    private MongoCollection<Document> resolveBackupCollection(String documentType, boolean isHistory) {
        if (!backupBeforeWrite) {
            return null;
        }

        return database.getCollection(backupCollectionName(documentType, isHistory));
    }

    private static String backupCollectionName(String documentType, boolean isHistory) {
        return documentType + (isHistory ? ".history" : "") + BACKUP_COLLECTION_SUFFIX;
    }

    /**
     * Pre-migration copy of the fields the migration for {@code documentType} can
     * rewrite.
     * <p>
     * Configuration documents (propertysetter, apicalls, outputs) are copied whole
     * — they hold no personal data and a whole-document restore is the safest thing
     * to have. Conversation memories are projected down to
     * {@link #CONVERSATION_MEMORY_BACKUP_FIELDS}, because
     * {@link #migrateConversationMemory()} only ever rewrites
     * {@code conversationProperties}: the narrowed copy restores everything the
     * migration touched, while transcripts and the owning {@code userId} never
     * reach a collection that GDPR erasure does not know about.
     */
    private static Document backupProjection(String documentType, Document document) {
        if (!COLLECTION_CONVERSATION_MEMORY.equals(documentType)) {
            return deepCopy(document);
        }

        var projection = new Document();
        for (String field : CONVERSATION_MEMORY_BACKUP_FIELDS) {
            if (document.containsKey(field)) {
                projection.put(field, deepCopyValue(document.get(field)));
            }
        }
        return projection;
    }

    /**
     * @return true if the pre-migration state was actually stored
     */
    private boolean backupDocument(MongoCollection<Document> backupCollection, Document originalDocument) {
        if (backupCollection == null || originalDocument == null) {
            return false;
        }

        try {
            backupCollection.insertOne(originalDocument);
            return true;
        } catch (Exception e) {
            // a failed backup must not abort the migration, but it has to be visible
            LOGGER.warnf("Could not back up document before migrating it: %s", e.getLocalizedMessage());
            return false;
        }
    }

    /**
     * Deep copy of a BSON document. Migrations mutate nested maps and lists in
     * place, so a shallow copy would share exactly the structures that are about to
     * change and the "backup" would already contain the migrated state.
     */
    static Document deepCopy(Document document) {
        var copy = new Document();
        document.forEach((key, value) -> copy.put(key, deepCopyValue(value)));
        return copy;
    }

    private static Object deepCopyValue(Object value) {
        if (value instanceof Document nestedDocument) {
            return deepCopy(nestedDocument);
        }

        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<String, Object>();
            map.forEach((key, nestedValue) -> copy.put(String.valueOf(key), deepCopyValue(nestedValue)));
            return copy;
        }

        if (value instanceof List<?> list) {
            var copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(deepCopyValue(item)));
            return copy;
        }

        // everything else is a scalar BSON value and immutable
        return value;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Delegates to {@link LegacyDocumentMigrations}: the transform itself is a pure
     * {@code Document -> Document} function, shared with
     * {@code PostgresMigrationManager} so a ZIP import behaves the same on both
     * backends. Only the collection sweep above is MongoDB-specific.
     */
    @Override
    public IDocumentMigration migratePropertySetter() {
        return LegacyDocumentMigrations.propertySetter();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Delegates to {@link LegacyDocumentMigrations} — see
     * {@link #migratePropertySetter()}.
     */
    @Override
    public IDocumentMigration migrateApiCalls() {
        return LegacyDocumentMigrations.apiCalls();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Delegates to {@link LegacyDocumentMigrations} — see
     * {@link #migratePropertySetter()}.
     */
    @Override
    public IDocumentMigration migrateOutput() {
        return LegacyDocumentMigrations.output();
    }

    private IDocumentMigration migrateConversationMemory() {
        return LegacyDocumentMigrations.conversationMemory();
    }

    private void saveToPersistence(String documentType, Document document, boolean isHistory, MongoCollection<Document> collection) {

        String id;
        int version = -1;
        var versionFieldObj = document.get(VERSION_FIELD);
        if (versionFieldObj != null) {
            version = Integer.parseInt(versionFieldObj.toString());
        }

        if (isHistory) {
            @SuppressWarnings("unchecked")
            var idObj = (Map<String, Object>) document.get(ID_FIELD);
            id = idObj.get(ID_FIELD).toString();

            var idObject = new Document();
            idObject.put(ID_FIELD, new ObjectId(id));
            idObject.put(VERSION_FIELD, version);

            var query = eq(ID_FIELD, idObject);
            collection.replaceOne(query, document);
        } else {
            id = document.get(ID_FIELD).toString();
            var query = eq(ID_FIELD, new ObjectId(id));
            collection.replaceOne(query, document);
        }

        var message = format("Successfully migrated %s document with id: %s, version: %d to new format.", documentType, id, version);
        LOGGER.info(message);
    }
}
