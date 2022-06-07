package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import com.mongodb.client.model.Filters;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.reactivex.rxjava3.core.Observable;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.datastore.mongo.MongoResourceStorage.ID_FIELD;
import static ai.labs.eddi.datastore.mongo.MongoResourceStorage.VERSION_FIELD;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static com.mongodb.client.model.Updates.set;
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
    public static final String COLLECTION_OUTPUTS = "outputs";
    public static final String COLLECTION_HTTPCALLS = "httpcalls";
    public static final String COLLECTION_PROPERTYSETTER = "propertysetter";
    public static final String OLD_FIELD_NAME_TARGET_SERVER = "targetServer";
    public static final String FIELD_NAME_TARGET_SERVER_URL = "targetServerUrl";
    private final MongoCollection<Document> propertySetterCollection;
    private final MongoCollection<Document> propertySetterCollectionHistory;
    private final MongoCollection<Document> httpCallsCollection;
    private final MongoCollection<Document> httpCallsCollectionHistory;
    private final MongoCollection<Document> outputCollection;
    private final MongoCollection<Document> outputCollectionHistory;
    private final MigrationLogStore migrationLogStore;
    private boolean isCurrentlyRunning = false;

    @Inject
    public MigrationManager(MongoDatabase database, MigrationLogStore migrationLogStore) {
        this.propertySetterCollection = database.getCollection(COLLECTION_PROPERTYSETTER);
        this.propertySetterCollectionHistory = database.getCollection(COLLECTION_PROPERTYSETTER + ".history");

        this.httpCallsCollection = database.getCollection(COLLECTION_HTTPCALLS);
        this.httpCallsCollectionHistory = database.getCollection(COLLECTION_PROPERTYSETTER + ".history");

        this.outputCollection = database.getCollection(COLLECTION_OUTPUTS);
        this.outputCollectionHistory = database.getCollection(COLLECTION_OUTPUTS + ".history");

        this.migrationLogStore = migrationLogStore;
    }

    @Override
    public void startMigrationIfFirstTimeRun(IMigrationFinished migrationFinished) {
        if (!this.isCurrentlyRunning) {
            this.isCurrentlyRunning = true;
            if (isMigrationNeeded()) {
                startPropertyMigration();
                startHttpCallsMigration();
                startOutputMigration();

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
                    iterateMigration(COLLECTION_PROPERTYSETTER, FIELD_NAME_SET_ON_ACTIONS, migration,
                            propertySetterCollection, propertySetterCollectionHistory);

            if (migrationHasExecuted) {
                LOGGER.info("Migration of propertysetter document has finished!");
            }
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
    }


    private void startHttpCallsMigration() {
        try {
            IDocumentMigration migration = migrateHttpCalls();
            boolean migrationHasExecuted =
                    iterateMigration(COLLECTION_HTTPCALLS, FIELD_NAME_HTTP_CALLS, migration,
                            httpCallsCollection, httpCallsCollectionHistory);

            if (migrationHasExecuted) {
                LOGGER.info("Migration of httpcalls document has finished!");
            }
        } catch (
                Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
    }

    private void startOutputMigration() {
        try {
            IDocumentMigration migration = migrateOutput();
            boolean migrationHasExecuted =
                    iterateMigration(COLLECTION_OUTPUTS, FIELD_NAME_OUTPUT_SET, migration,
                            outputCollection, outputCollectionHistory);

            if (migrationHasExecuted) {
                LOGGER.info("Migration of output document has finished!");
            }
        } catch (
                Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
    }

    private boolean iterateMigration(String fieldName, String fieldName1,
                                     IDocumentMigration migration, MongoCollection<Document> httpCallsCollection,
                                     MongoCollection<Document> httpCallsCollectionHistory) {

        Observable<Document> observable = Observable.fromPublisher(httpCallsCollection.find());
        Iterable<Document> httpCallsDocuments = observable.blockingIterable();
        var migrationHasExecuted = migrateDocuments(fieldName, httpCallsDocuments, migration,
                fieldName1, httpCallsCollection, httpCallsCollectionHistory, false);

        observable = Observable.fromPublisher(httpCallsCollectionHistory.find());
        Iterable<Document> httpCallsHistoryDocuments = observable.blockingIterable();
        migrationHasExecuted = migrateDocuments(fieldName, httpCallsHistoryDocuments, migration,
                fieldName1, httpCallsCollection, httpCallsCollectionHistory, true) || migrationHasExecuted;
        return migrationHasExecuted;
    }

    private boolean migrateDocuments(String documentType,
                                     Iterable<Document> documents,
                                     IDocumentMigration migration,
                                     String fieldNameToMigrate,
                                     MongoCollection<Document> outputCollection,
                                     MongoCollection<Document> outputCollectionHistory,
                                     boolean isHistory) {
        boolean migrationHasExecuted = false;
        for (var document : documents) {
            var migratedDocument = migration.migrate(document);
            if (migratedDocument != null) {
                saveToPersistence(documentType, migratedDocument, fieldNameToMigrate, isHistory,
                        outputCollection, outputCollectionHistory);
                migrationHasExecuted = true;
            }
        }

        return migrationHasExecuted;
    }

    @Override
    public IDocumentMigration migratePropertySetter() {
        return document -> {
            boolean convertedPropertySetter = false;
            if (document.containsKey(FIELD_NAME_SET_ON_ACTIONS)) {
                var setOnActions = (List<Map<String, Object>>) document.get(FIELD_NAME_SET_ON_ACTIONS);
                for (var setOnActionContainer : setOnActions) {
                    if (setOnActionContainer.containsKey(FIELD_NAME_SET_PROPERTIES)) {
                        var setProperties = (List<Map<String, Object>>) setOnActionContainer.get(FIELD_NAME_SET_PROPERTIES);
                        for (var setProperty : setProperties) {
                            convertedPropertySetter =
                                    convertPropertyInstructions(convertedPropertySetter, setProperty) ||
                                            convertedPropertySetter;
                        }
                    }
                }
            }

            return convertedPropertySetter ? document : null;
        };
    }

    @Override
    public IDocumentMigration migrateHttpCalls() {
        return document -> {
            boolean convertedHttpCalls = false;
            if (document.containsKey(OLD_FIELD_NAME_TARGET_SERVER)) {
                document.put(FIELD_NAME_TARGET_SERVER_URL, document.get(OLD_FIELD_NAME_TARGET_SERVER));
                convertedHttpCalls = true;
            }
            if (document.containsKey(FIELD_NAME_HTTP_CALLS)) {
                var httpCalls = (List<Map<String, Object>>) document.get(FIELD_NAME_HTTP_CALLS);
                for (var httpCall : httpCalls) {
                    if (httpCall.containsKey(FIELD_NAME_PRE_REQUEST)) {
                        var preRequest = (Map<String, List<Map<String, Object>>>) httpCall.get(FIELD_NAME_PRE_REQUEST);
                        convertedHttpCalls = convertPreAndPostProcessing(convertedHttpCalls, preRequest) || convertedHttpCalls;
                    }

                    if (httpCall.containsKey(FIELD_NAME_POST_RESPONSE)) {
                        var postResponse = (Map<String, List<Map<String, Object>>>) httpCall.get(FIELD_NAME_POST_RESPONSE);
                        convertedHttpCalls = convertPreAndPostProcessing(convertedHttpCalls, postResponse) || convertedHttpCalls;
                    }
                }
            }

            return convertedHttpCalls ? document : null;
        };
    }

    private boolean convertPreAndPostProcessing(boolean convertedHttpCalls, Map<String, List<Map<String, Object>>> preRequest) {
        if (preRequest.containsKey(FIELD_NAME_PROPERTY_INSTRUCTIONS)) {
            for (var propertyInstruction : preRequest.get(FIELD_NAME_PROPERTY_INSTRUCTIONS)) {
                convertedHttpCalls = convertPropertyInstructions(convertedHttpCalls, propertyInstruction);
            }
        }
        return convertedHttpCalls;
    }

    private boolean convertPropertyInstructions(boolean convertedHttpCalls, Map<String, Object> propertyInstruction) {
        Object value = propertyInstruction.get(FIELD_NAME_VALUE);
        if (value != null) {
            if (value instanceof String) {
                if (value.equals("[[${@java.util.UUID@randomUUID()}]]")) {
                    value = "[# th:with=\\\"uuid=${@java.util.UUID@randomUUID()}\\\"][[${uuid}]][/]";
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

            convertedHttpCalls = true;
        } else if (propertyInstruction.containsKey(FIELD_NAME_VALUE)) {
            propertyInstruction.put(FIELD_NAME_VALUE_STRING, null);
            convertedHttpCalls = true;
        }

        return convertedHttpCalls;
    }

    @Override
    public IDocumentMigration migrateOutput() {
        return document -> {
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
                                        if (isNullOrEmpty(outputValue.get(FIELD_NAME_TYPE))) {
                                            if (!isNullOrEmpty(outputValue.get(FIELD_NAME_TEXT))) {
                                                outputValue.put(FIELD_NAME_TYPE, FIELD_NAME_TEXT);
                                            } else if (!isNullOrEmpty(((Map) valueAlternative).get(FIELD_NAME_URI))) {
                                                outputValue.put(FIELD_NAME_TYPE, FIELD_NAME_IMAGE);
                                            } else if (!isNullOrEmpty(((Map) valueAlternative).get(FIELD_NAME_EXPRESSIONS))) {
                                                outputValue.put(FIELD_NAME_TYPE, FIELD_NAME_QUICK_REPLY);
                                            } else {
                                                outputValue.put(FIELD_NAME_TYPE, FIELD_NAME_OTHER);
                                            }

                                            convertedOutput = true;
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
        };
    }

    private void saveToPersistence(String documentType,
                                   Document document,
                                   String fieldNameToMigrate,
                                   boolean isHistory,
                                   MongoCollection<Document> outputCollection,
                                   MongoCollection<Document> outputCollectionHistory) {

        String id;
        int version;
        if (isHistory) {
            var idObj = (Map<String, Object>) document.get(ID_FIELD);
            id = idObj.get(ID_FIELD).toString();
            version = Integer.parseInt(idObj.get(VERSION_FIELD).toString());
            var query = Filters.eq(ID_FIELD,
                    new Document(Map.of(ID_FIELD, new ObjectId(id), VERSION_FIELD, version)));

            Observable.fromPublisher(
                    outputCollectionHistory.updateOne(query,
                            set(fieldNameToMigrate, document.get(fieldNameToMigrate)))).blockingFirst();
        } else {
            id = document.get(ID_FIELD).toString();
            version = Integer.parseInt(document.get(VERSION_FIELD).toString());
            Bson query = Filters.eq(ID_FIELD, new ObjectId(id));
            Observable.fromPublisher(
                    outputCollection.updateOne(query,
                            set(fieldNameToMigrate, document.get(fieldNameToMigrate)))).blockingFirst();
        }

        var message =
                format("Successfully migrated %s document with id: %s, version: %d to new format.",
                        documentType, id, version);
        LOGGER.info(message);
    }
}
