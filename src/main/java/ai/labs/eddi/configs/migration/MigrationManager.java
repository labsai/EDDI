/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.datastore.mongo.MongoResourceStorage.ID_FIELD;
import static ai.labs.eddi.datastore.mongo.MongoResourceStorage.VERSION_FIELD;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static com.mongodb.client.model.Filters.eq;
import static java.lang.Boolean.parseBoolean;
import static java.lang.String.format;

@ApplicationScoped
@DefaultBean
public class MigrationManager implements IMigrationManager {
    private static final Logger LOGGER = Logger.getLogger(MigrationManager.class);

    public static final String MIGRATION_CONFIRMATION = "migrated properties,httpcalls,output.";
    public static final String FIELD_NAME_HTTP_CALLS = "httpCalls";
    public static final String FIELD_NAME_OUTPUTS = "outputs";
    public static final String FIELD_NAME_OUTPUT_SET = "outputSet";
    public static final String FIELD_NAME_SET_PROPERTIES = "setProperties";
    public static final String FIELD_NAME_PRE_REQUEST = "preRequest";
    public static final String FIELD_NAME_POST_RESPONSE = "postResponse";
    public static final String FIELD_NAME_PROPERTY_INSTRUCTIONS = "propertyInstructions";
    public static final String FIELD_NAME_TYPE = "type";
    public static final String FIELD_NAME_VALUE_ALTERNATIVES = "valueAlternatives";
    public static final String FIELD_NAME_TEXT = "text";
    public static final String FIELD_NAME_URI = "uri";
    public static final String FIELD_NAME_IMAGE = "image";
    public static final String FIELD_NAME_EXPRESSIONS = "expressions";
    public static final String FIELD_NAME_QUICK_REPLY = "quickReply";
    public static final String FIELD_NAME_OTHER = "other";
    public static final String FIELD_NAME_VALUE_STRING = "valueString";
    public static final String FIELD_NAME_VALUE_OBJECT = "valueObject";
    public static final String FIELD_NAME_VALUE_INT = "valueInt";
    public static final String FIELD_NAME_VALUE_FLOAT = "valueFloat";
    public static final String FIELD_NAME_VALUE_LIST = "valueList";
    public static final String FIELD_NAME_VALUE_BOOLEAN = "valueBoolean";
    public static final String FIELD_NAME_VALUE = "value";
    public static final String FIELD_NAME_SET_ON_ACTIONS = "setOnActions";
    public static final String FIELD_NAME_CONVERSATION_PROPERTIES = "conversationProperties";
    public static final String FIELD_NAME_BUTTON = "button";
    public static final String FIELD_NAME_LABEL = "label";
    public static final String FIELD_NAME_DEFAULT_VALUE = "defaultValue";
    public static final String FIELD_NAME_PLACEHOLDER = "placeholder";
    public static final String FIELD_NAME_BUTTON_TYPE = "buttonType";
    public static final String FIELD_NAME_ON_PRESS = "onPress";
    public static final String FIELD_NAME_INPUT_FIELD = "inputField";
    public static final String FIELD_NAME_ALT = "alt";
    public static final String FIELD_NAME_DELAY = "delay";
    public static final String FIELD_NAME_TARGET_SERVER_URL = "targetServerUrl";
    public static final String OLD_FIELD_NAME_TARGET_SERVER = "targetServer";
    public static final String COLLECTION_OUTPUTS = "outputs";
    public static final String COLLECTION_HTTPCALLS = "apicalls";
    public static final String COLLECTION_PROPERTYSETTER = "propertysetter";
    public static final String COLLECTION_CONVERSATION_MEMORY = "conversationmemories";
    public static final String FIELD_NAME_VALIDATION = "validation";
    public static final String FIELD_NAME_SUB_TYPE = "subType";
    public static final String OLD_FIELD_NAME_IS_PASSWORD = "isPassword";

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

    private static final String LEGACY_UUID_EXPRESSION = "[[${@java.util.UUID@randomUUID()}]]";
    private static final String QUTE_UUID_EXPRESSION = "{uuidUtils:generateUUID()}";

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

    @Override
    @SuppressWarnings("unchecked")
    public IDocumentMigration migratePropertySetter() {
        return document -> {
            try {
                boolean convertedPropertySetter = false;
                if (document.containsKey(FIELD_NAME_SET_ON_ACTIONS)) {
                    var setOnActions = (List<Map<String, Object>>) document.get(FIELD_NAME_SET_ON_ACTIONS);
                    for (var setOnActionContainer : setOnActions) {
                        if (setOnActionContainer.containsKey(FIELD_NAME_SET_PROPERTIES)) {
                            var setProperties = (List<Map<String, Object>>) setOnActionContainer.get(FIELD_NAME_SET_PROPERTIES);

                            for (var setProperty : setProperties) {
                                convertedPropertySetter = convertPropertyInstructions(setProperty) || convertedPropertySetter;
                            }
                        }
                    }
                }

                return convertedPropertySetter ? document : null;
            } catch (Exception e) {
                LOGGER.error(e.getLocalizedMessage(), e);
                return null;
            }
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public IDocumentMigration migrateApiCalls() {
        return document -> {
            try {
                boolean convertedApiCalls = false;
                if (document.containsKey(OLD_FIELD_NAME_TARGET_SERVER)) {
                    document.put(FIELD_NAME_TARGET_SERVER_URL, document.get(OLD_FIELD_NAME_TARGET_SERVER));
                    document.remove(OLD_FIELD_NAME_TARGET_SERVER);
                    convertedApiCalls = true;
                }
                String differentOldFieldName = OLD_FIELD_NAME_TARGET_SERVER + "Uri";
                if (document.containsKey(differentOldFieldName)) {
                    document.put(FIELD_NAME_TARGET_SERVER_URL, document.get(differentOldFieldName));
                    document.remove(differentOldFieldName);
                    convertedApiCalls = true;
                }
                if (document.containsKey(FIELD_NAME_HTTP_CALLS)) {
                    var httpCalls = (List<Map<String, Object>>) document.get(FIELD_NAME_HTTP_CALLS);
                    for (var httpCall : httpCalls) {
                        if (httpCall.containsKey(FIELD_NAME_PRE_REQUEST)) {
                            var preRequest = (Map<String, List<Map<String, Object>>>) httpCall.get(FIELD_NAME_PRE_REQUEST);
                            convertedApiCalls = convertPreAndPostProcessing(preRequest) || convertedApiCalls;
                        }

                        if (httpCall.containsKey(FIELD_NAME_POST_RESPONSE)) {
                            var postResponse = (Map<String, List<Map<String, Object>>>) httpCall.get(FIELD_NAME_POST_RESPONSE);
                            convertedApiCalls = convertPreAndPostProcessing(postResponse) || convertedApiCalls;
                        }
                    }
                }

                return convertedApiCalls ? document : null;
            } catch (Exception e) {
                LOGGER.error(e.getLocalizedMessage(), e);
                return null;
            }
        };
    }

    private boolean convertPreAndPostProcessing(Map<String, List<Map<String, Object>>> preRequest) {
        boolean converted = false;
        if (preRequest.containsKey(FIELD_NAME_PROPERTY_INSTRUCTIONS)) {
            for (var propertyInstruction : preRequest.get(FIELD_NAME_PROPERTY_INSTRUCTIONS)) {
                converted = convertPropertyInstructions(propertyInstruction) || converted;
            }
        }
        return converted;
    }

    /**
     * Moves the legacy untyped {@code value} field onto the typed field the v6
     * property model expects. Every BSON type that model can hold is mapped; a
     * value it cannot hold (dates, binary, Decimal128, out-of-int-range longs, …)
     * is left untouched under its original {@code value} key and reported —
     * removing it without a replacement would erase the value irrecoverably.
     *
     * @return true if the instruction was rewritten
     */
    private boolean convertPropertyInstructions(Map<String, Object> propertyInstruction) {
        if (!propertyInstruction.containsKey(FIELD_NAME_VALUE)) {
            return false;
        }

        Object value = propertyInstruction.get(FIELD_NAME_VALUE);
        Object migratedValue = value;
        String targetField;

        if (value == null || value instanceof String) {
            targetField = FIELD_NAME_VALUE_STRING;
            if (LEGACY_UUID_EXPRESSION.equals(value)) {
                migratedValue = QUTE_UUID_EXPRESSION;
            }
        } else if (value instanceof Map<?, ?>) {
            targetField = FIELD_NAME_VALUE_OBJECT;
        } else if (value instanceof List<?>) {
            targetField = FIELD_NAME_VALUE_LIST;
        } else if (value instanceof Boolean) {
            targetField = FIELD_NAME_VALUE_BOOLEAN;
        } else if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            targetField = FIELD_NAME_VALUE_INT;
            migratedValue = ((Number) value).intValue();
        } else if (value instanceof Long longValue && longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
            targetField = FIELD_NAME_VALUE_INT;
            migratedValue = longValue.intValue();
        } else if (value instanceof Float || value instanceof Double) {
            targetField = FIELD_NAME_VALUE_FLOAT;
        } else {
            LOGGER.warnf("Keeping legacy property field '%s' of unsupported type %s as is — the property model has no "
                    + "field for it and dropping it would lose the value. Migrate this document manually.", FIELD_NAME_VALUE,
                    value.getClass().getName());
            return false;
        }

        propertyInstruction.put(targetField, migratedValue);
        propertyInstruction.remove(FIELD_NAME_VALUE);

        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public IDocumentMigration migrateOutput() {
        return document -> {
            try {
                boolean convertedOutput = false;
                if (document.containsKey(FIELD_NAME_OUTPUT_SET)) {
                    var outputSet = (List<Map<String, Object>>) document.get(FIELD_NAME_OUTPUT_SET);
                    for (var outputContainer : outputSet) {
                        if (outputContainer.containsKey(FIELD_NAME_OUTPUTS)) {
                            var outputs = (List<Map<String, Object>>) outputContainer.get(FIELD_NAME_OUTPUTS);
                            for (var output : outputs) {
                                output.remove(FIELD_NAME_TYPE);
                                if (output.containsKey(FIELD_NAME_VALUE_ALTERNATIVES)) {
                                    var valueAlternatives = (List<Object>) output.get(FIELD_NAME_VALUE_ALTERNATIVES);
                                    for (int i = 0; i < valueAlternatives.size(); i++) {
                                        Object valueAlternative = valueAlternatives.get(i);
                                        if (valueAlternative instanceof String) {
                                            var textOutput = new TextOutputItem(valueAlternative.toString());
                                            valueAlternatives.set(i, textOutput);
                                            convertedOutput = true;
                                        } else if (valueAlternative instanceof Map<?, ?>) {
                                            var outputValue = (Map<String, Object>) valueAlternative;
                                            var type = outputValue.get(FIELD_NAME_TYPE);
                                            if (isNullOrEmpty(type) || type.equals(FIELD_NAME_OTHER)) {
                                                if (!isNullOrEmpty(outputValue.get(FIELD_NAME_TEXT))) {
                                                    outputValue.put(FIELD_NAME_TYPE, FIELD_NAME_TEXT);
                                                } else if (!isNullOrEmpty(outputValue.get(FIELD_NAME_URI))) {
                                                    outputValue.put(FIELD_NAME_TYPE, FIELD_NAME_IMAGE);
                                                } else if (!isNullOrEmpty(outputValue.get(FIELD_NAME_EXPRESSIONS))) {
                                                    outputValue.put(FIELD_NAME_TYPE, FIELD_NAME_QUICK_REPLY);
                                                } else if (!isNullOrEmpty(outputValue.get(FIELD_NAME_PLACEHOLDER))) {
                                                    outputValue.put(FIELD_NAME_TYPE, FIELD_NAME_INPUT_FIELD);
                                                    if (outputValue.containsKey(OLD_FIELD_NAME_IS_PASSWORD)) {
                                                        var isPassword = parseBoolean(outputValue.get(OLD_FIELD_NAME_IS_PASSWORD).toString());
                                                        if (isPassword) {
                                                            outputValue.put(FIELD_NAME_SUB_TYPE, "password");
                                                        }
                                                    }
                                                } else if (!isNullOrEmpty(outputValue.get(FIELD_NAME_ON_PRESS))) {
                                                    outputValue.put(FIELD_NAME_TYPE, FIELD_NAME_BUTTON);
                                                } else {
                                                    outputValue.put(FIELD_NAME_TYPE, FIELD_NAME_OTHER);
                                                }

                                                convertedOutput = true;
                                            }

                                            type = outputValue.get(FIELD_NAME_TYPE);

                                            if (type.equals(FIELD_NAME_TEXT)) {
                                                removeNonSupportedProperties(outputValue, FIELD_NAME_TEXT, FIELD_NAME_DELAY);
                                            }

                                            if (type.equals(FIELD_NAME_IMAGE)) {
                                                removeNonSupportedProperties(outputValue, FIELD_NAME_URI, FIELD_NAME_ALT);
                                            }

                                            if (type.equals(FIELD_NAME_INPUT_FIELD)) {
                                                removeNonSupportedProperties(outputValue, FIELD_NAME_SUB_TYPE, FIELD_NAME_LABEL,
                                                        FIELD_NAME_DEFAULT_VALUE, FIELD_NAME_PLACEHOLDER, FIELD_NAME_VALIDATION);
                                            }

                                            if (type.equals(FIELD_NAME_BUTTON)) {
                                                removeNonSupportedProperties(outputValue, FIELD_NAME_BUTTON_TYPE, FIELD_NAME_LABEL,
                                                        FIELD_NAME_ON_PRESS);
                                            }

                                            if (type.equals(FIELD_NAME_OTHER)) {
                                                removeNonStringProperties(outputValue);
                                            }
                                        }
                                    }

                                    output.put(FIELD_NAME_TYPE, null);
                                }
                            }
                        }
                    }
                }

                return convertedOutput ? document : null;
            } catch (Exception e) {
                LOGGER.error(e.getLocalizedMessage(), e);
                return null;
            }
        };
    }

    private void removeNonStringProperties(Map<String, Object> outputValue) {
        var toBeRemoved = new LinkedList<String>();
        for (String outputKey : outputValue.keySet()) {
            var value = outputValue.get(outputKey);
            if (value != null && !(value instanceof String)) {
                toBeRemoved.add(outputKey);
            }
        }

        toBeRemoved.forEach(outputValue::remove);
    }

    private void removeNonSupportedProperties(Map<String, Object> outputValue, String... fieldNames) {
        var toBeRemoved = new LinkedList<String>();
        for (String outputKey : outputValue.keySet()) {
            if (!outputKey.equals(FIELD_NAME_TYPE) && !Arrays.asList(fieldNames).contains(outputKey)) {
                toBeRemoved.add(outputKey);
            }
        }

        toBeRemoved.forEach(outputValue::remove);
    }

    @SuppressWarnings("unchecked")
    private IDocumentMigration migrateConversationMemory() {
        return document -> {
            try {
                boolean convertedConversationMemory = false;

                if (document.containsKey(FIELD_NAME_CONVERSATION_PROPERTIES)) {
                    var conversationProperties = (Map<String, Map<String, Object>>) document.get(FIELD_NAME_CONVERSATION_PROPERTIES);

                    for (var propertyKey : conversationProperties.keySet()) {
                        var conversationProperty = conversationProperties.get(propertyKey);
                        convertedConversationMemory = convertPropertyInstructions(conversationProperty) || convertedConversationMemory;
                    }
                }

                return convertedConversationMemory ? document : null;
            } catch (Exception e) {
                LOGGER.error(e.getLocalizedMessage(), e);
                return null;
            }
        };
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
