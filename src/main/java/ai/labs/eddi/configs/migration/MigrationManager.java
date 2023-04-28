package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.reactivex.rxjava3.core.Observable;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Arrays;
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
    public static final String COLLECTION_HTTPCALLS = "httpcalls";
    public static final String COLLECTION_PROPERTYSETTER = "propertysetter";
    public static final String COLLECTION_CONVERSATION_MEMORY = "conversationmemories";
    public static final String FIELD_NAME_VALIDATION = "validation";
    public static final String FIELD_NAME_SUB_TYPE = "subType";
    public static final String OLD_FIELD_NAME_IS_PASSWORD = "isPassword";

    private final MongoCollection<Document> propertySetterCollection;
    private final MongoCollection<Document> propertySetterCollectionHistory;
    private final MongoCollection<Document> httpCallsCollection;
    private final MongoCollection<Document> httpCallsCollectionHistory;
    private final MongoCollection<Document> outputCollection;
    private final MongoCollection<Document> outputCollectionHistory;
    private final MongoCollection<Document> conversationMemoryCollection;
    private final MigrationLogStore migrationLogStore;
    private final Boolean skipConversationMemories;
    private boolean isCurrentlyRunning = false;

    @Inject
    public MigrationManager(MongoDatabase database, MigrationLogStore migrationLogStore,
                            @ConfigProperty(name = "eddi.migration.skipConversationMemories") Boolean skipConversationMemories) {
        this.propertySetterCollection = database.getCollection(COLLECTION_PROPERTYSETTER);
        this.propertySetterCollectionHistory = database.getCollection(COLLECTION_PROPERTYSETTER + ".history");

        this.httpCallsCollection = database.getCollection(COLLECTION_HTTPCALLS);
        this.httpCallsCollectionHistory = database.getCollection(COLLECTION_HTTPCALLS + ".history");

        this.outputCollection = database.getCollection(COLLECTION_OUTPUTS);
        this.outputCollectionHistory = database.getCollection(COLLECTION_OUTPUTS + ".history");

        this.conversationMemoryCollection = database.getCollection(COLLECTION_CONVERSATION_MEMORY);

        this.migrationLogStore = migrationLogStore;
        this.skipConversationMemories = skipConversationMemories;
    }

    @Override
    public synchronized void startMigrationIfFirstTimeRun(IMigrationFinished migrationFinished) {
        if (!this.isCurrentlyRunning) {
            this.isCurrentlyRunning = true;
            if (isMigrationNeeded()) {
                startPropertyMigration();
                startHttpCallsMigration();
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
            boolean migrationHasExecuted =
                    iterateMigration(COLLECTION_PROPERTYSETTER, migration,
                            propertySetterCollection, propertySetterCollectionHistory);

            if (migrationHasExecuted) {
                LOGGER.info("Migration of propertysetter documents has finished!");
            } else {
                LOGGER.info("No migration of propertysetter documents was needed!");
            }
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
    }


    private void startHttpCallsMigration() {
        try {
            IDocumentMigration migration = migrateHttpCalls();
            boolean migrationHasExecuted =
                    iterateMigration(COLLECTION_HTTPCALLS, migration,
                            httpCallsCollection, httpCallsCollectionHistory);

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
            boolean migrationHasExecuted =
                    iterateMigration(COLLECTION_OUTPUTS, migration,
                            outputCollection, outputCollectionHistory);

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
            boolean migrationHasExecuted =
                    iterateMigration(COLLECTION_CONVERSATION_MEMORY, migration,
                            conversationMemoryCollection, null);

            if (migrationHasExecuted) {
                LOGGER.info("Migration of conversation memory documents has finished!");
            } else {
                LOGGER.info("No migration of conversation memory documents was needed!");
            }
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
    }

    private boolean iterateMigration(String documentType, IDocumentMigration migration,
                                     MongoCollection<Document> collection,
                                     MongoCollection<Document> collectionHistory) {

        Observable<Document> observable = Observable.fromPublisher(collection.find());
        Iterable<Document> documents = observable.blockingIterable();
        var migrationHasExecuted = migrateDocuments(documentType, documents, migration, collection, false);

        if (collectionHistory != null) {
            observable = Observable.fromPublisher(collectionHistory.find());
            Iterable<Document> historyDocuments = observable.blockingIterable();
            migrationHasExecuted =
                    migrateDocuments(documentType, historyDocuments, migration, collectionHistory, true)
                            || migrationHasExecuted;
        }
        return migrationHasExecuted;
    }

    private boolean migrateDocuments(String documentType, Iterable<Document> documents, IDocumentMigration migration,
                                     MongoCollection<Document> collection,
                                     boolean isHistory) {

        boolean migrationHasExecuted = false;
        for (var document : documents) {
            var migratedDocument = migration.migrate(document);
            if (migratedDocument != null) {
                saveToPersistence(documentType, migratedDocument, isHistory, collection);
                migrationHasExecuted = true;
            }
        }

        return migrationHasExecuted;
    }

    @Override
    public IDocumentMigration migratePropertySetter() {
        return document -> {
            try {
                boolean convertedPropertySetter = false;
                if (document.containsKey(FIELD_NAME_SET_ON_ACTIONS)) {
                    var setOnActions = (List<Map<String, Object>>) document.get(FIELD_NAME_SET_ON_ACTIONS);
                    for (var setOnActionContainer : setOnActions) {
                        if (setOnActionContainer.containsKey(FIELD_NAME_SET_PROPERTIES)) {
                            var setProperties =
                                    (List<Map<String, Object>>) setOnActionContainer.get(FIELD_NAME_SET_PROPERTIES);

                            for (var setProperty : setProperties) {
                                convertedPropertySetter =
                                        convertPropertyInstructions(setProperty) || convertedPropertySetter;
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
    public IDocumentMigration migrateHttpCalls() {
        return document -> {
            try {
                boolean convertedHttpCalls = false;
                if (document.containsKey(OLD_FIELD_NAME_TARGET_SERVER)) {
                    document.put(FIELD_NAME_TARGET_SERVER_URL, document.get(OLD_FIELD_NAME_TARGET_SERVER));
                    document.remove(OLD_FIELD_NAME_TARGET_SERVER);
                    convertedHttpCalls = true;
                }
                String differentOldFieldName = OLD_FIELD_NAME_TARGET_SERVER + "Uri";
                if (document.containsKey(differentOldFieldName)) {
                    document.put(FIELD_NAME_TARGET_SERVER_URL, document.get(differentOldFieldName));
                    document.remove(differentOldFieldName);
                    convertedHttpCalls = true;
                }
                if (document.containsKey(FIELD_NAME_HTTP_CALLS)) {
                    var httpCalls = (List<Map<String, Object>>) document.get(FIELD_NAME_HTTP_CALLS);
                    for (var httpCall : httpCalls) {
                        if (httpCall.containsKey(FIELD_NAME_PRE_REQUEST)) {
                            var preRequest =
                                    (Map<String, List<Map<String, Object>>>) httpCall.get(FIELD_NAME_PRE_REQUEST);
                            convertedHttpCalls = convertPreAndPostProcessing(preRequest) || convertedHttpCalls;
                        }

                        if (httpCall.containsKey(FIELD_NAME_POST_RESPONSE)) {
                            var postResponse =
                                    (Map<String, List<Map<String, Object>>>) httpCall.get(FIELD_NAME_POST_RESPONSE);
                            convertedHttpCalls = convertPreAndPostProcessing(postResponse) || convertedHttpCalls;
                        }
                    }
                }

                return convertedHttpCalls ? document : null;
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

    private boolean convertPropertyInstructions(Map<String, Object> propertyInstruction) {
        boolean converted = false;
        Object value = propertyInstruction.get(FIELD_NAME_VALUE);
        if (value != null) {
            if (value instanceof String) {
                if (value.equals("[[${@java.util.UUID@randomUUID()}]]")) {
                    value = "[# th:with=\"uuid=${@java.util.UUID@randomUUID()}\"][[${uuid}]][/]";
                }
                propertyInstruction.put(FIELD_NAME_VALUE_STRING, value);
            } else if (value instanceof Map) {
                propertyInstruction.put(FIELD_NAME_VALUE_OBJECT, value);
            } else if (value instanceof Integer) {
                propertyInstruction.put(FIELD_NAME_VALUE_INT, value);
            } else if (value instanceof Float) {
                propertyInstruction.put(FIELD_NAME_VALUE_FLOAT, value);
            }

            propertyInstruction.remove(FIELD_NAME_VALUE);

            converted = true;
        } else if (propertyInstruction.containsKey(FIELD_NAME_VALUE)) {
            propertyInstruction.put(FIELD_NAME_VALUE_STRING, null);
            converted = true;
        }

        return converted;
    }

    @Override
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
                                        } else if (valueAlternative instanceof Map outputValue) {
                                            var type = outputValue.get(FIELD_NAME_TYPE);
                                            if (isNullOrEmpty(type) || type.equals(FIELD_NAME_OTHER)) {
                                                if (!isNullOrEmpty(outputValue.get(FIELD_NAME_TEXT))) {
                                                    outputValue.put(FIELD_NAME_TYPE, FIELD_NAME_TEXT);
                                                } else if (!isNullOrEmpty(((Map) valueAlternative).get(FIELD_NAME_URI))) {
                                                    outputValue.put(FIELD_NAME_TYPE, FIELD_NAME_IMAGE);
                                                } else if (!isNullOrEmpty(((Map) valueAlternative).get(FIELD_NAME_EXPRESSIONS))) {
                                                    outputValue.put(FIELD_NAME_TYPE, FIELD_NAME_QUICK_REPLY);
                                                } else if (!isNullOrEmpty(((Map) valueAlternative).get(FIELD_NAME_PLACEHOLDER))) {
                                                    outputValue.put(FIELD_NAME_TYPE, FIELD_NAME_INPUT_FIELD);
                                                    if (outputValue.containsKey(OLD_FIELD_NAME_IS_PASSWORD)) {
                                                        var isPassword =
                                                                parseBoolean(outputValue.get(OLD_FIELD_NAME_IS_PASSWORD).toString());
                                                        if (isPassword) {
                                                            outputValue.put(FIELD_NAME_SUB_TYPE, "password");
                                                        }
                                                    }
                                                } else if (!isNullOrEmpty(((Map) valueAlternative).get(FIELD_NAME_ON_PRESS))) {
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
                                                removeNonSupportedProperties(outputValue, FIELD_NAME_SUB_TYPE,
                                                        FIELD_NAME_LABEL, FIELD_NAME_DEFAULT_VALUE, FIELD_NAME_PLACEHOLDER,
                                                        FIELD_NAME_VALIDATION);
                                            }

                                            if (type.equals(FIELD_NAME_BUTTON)) {
                                                removeNonSupportedProperties(outputValue,
                                                        FIELD_NAME_BUTTON_TYPE, FIELD_NAME_LABEL, FIELD_NAME_ON_PRESS);
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

    private IDocumentMigration migrateConversationMemory() {
        return document -> {
            try {
                boolean convertedConversationMemory = false;

                if (document.containsKey(FIELD_NAME_CONVERSATION_PROPERTIES)) {
                    var conversationProperties =
                            (Map<String, Map<String, Object>>) document.get(FIELD_NAME_CONVERSATION_PROPERTIES);

                    for (var propertyKey : conversationProperties.keySet()) {
                        var conversationProperty = conversationProperties.get(propertyKey);
                        convertedConversationMemory =
                                convertPropertyInstructions(conversationProperty) || convertedConversationMemory;
                    }
                }

                return convertedConversationMemory ? document : null;
            } catch (Exception e) {
                LOGGER.error(e.getLocalizedMessage(), e);
                return null;
            }
        };
    }

    private void saveToPersistence(String documentType, Document document, boolean isHistory,
                                   MongoCollection<Document> collection) {

        String id;
        int version = -1;
        var versionFieldObj = document.get(VERSION_FIELD);
        if (versionFieldObj != null) {
            version = Integer.parseInt(versionFieldObj.toString());
        }

        if (isHistory) {
            var idObj = (Map<String, Object>) document.get(ID_FIELD);
            id = idObj.get(ID_FIELD).toString();

            var idObject = new Document();
            idObject.put(ID_FIELD, new ObjectId(id));
            idObject.put(VERSION_FIELD, version);

            var query = eq(ID_FIELD, idObject);
            Observable.fromPublisher(collection.replaceOne(query, document)).blockingFirst();
        } else {
            id = document.get(ID_FIELD).toString();
            var query = eq(ID_FIELD, new ObjectId(id));
            Observable.fromPublisher(collection.replaceOne(query, document)).blockingFirst();
        }

        var message =
                format("Successfully migrated %s document with id: %s, version: %d to new format.",
                        documentType, id, version);
        LOGGER.info(message);
    }
}
