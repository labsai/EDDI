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
    public static final String PROPERTY_FIELD_OUTPUT_SET = "outputSet";
    private final MongoCollection<Document> outputCollection;
    private final MongoCollection<Document> outputCollectionHistory;
    private final MigrationLogStore migrationLogStore;

    @Inject
    public MigrationManager(MongoDatabase database, MigrationLogStore migrationLogStore) {
        this.outputCollection = database.getCollection("outputs");
        this.outputCollectionHistory = database.getCollection("outputs.history");
        this.migrationLogStore = migrationLogStore;
    }

    @Override
    public void startMigration() {
        startOutputMigration();
    }

    private void startOutputMigration() {
        try {
            Observable<Document> observable = Observable.fromPublisher(outputCollection.find());
            Iterable<Document> outputDocuments = observable.blockingIterable();
            var migrationHasExecuted = migrateOutputs(outputDocuments, false);

            observable = Observable.fromPublisher(outputCollectionHistory.find());
            Iterable<Document> outputHistoryDocuments = observable.blockingIterable();
            migrationHasExecuted = migrateOutputs(outputHistoryDocuments, true) || migrationHasExecuted;

            if (migrationHasExecuted) {
                LOGGER.info("Migration of output document has finished!");
            }
        } catch (
                Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
    }

    private boolean migrateOutputs(Iterable<Document> documents, boolean isHistory) {
        boolean migrationHasExecuted = false;
        for (var document : documents) {
            var migratedOutputDocument = migrateOutput(document);
            if (migratedOutputDocument != null) {
                saveToPersistence(migratedOutputDocument, isHistory);
                migrationHasExecuted = true;
            }
        }

        return migrationHasExecuted;
    }

    @Override
    public Document migrateOutput(Map<String, Object> outputMap) {
        var outputDocument = new Document(outputMap);
        boolean convertedOutput = false;
        if (outputDocument.containsKey(PROPERTY_FIELD_OUTPUT_SET)) {
            var outputSet = (List<Map<String, Object>>) outputDocument.get(PROPERTY_FIELD_OUTPUT_SET);
            for (var outputContainer : outputSet) {
                if (outputContainer.containsKey("outputs")) {
                    var outputs = (List<Map<String, Object>>) outputContainer.get("outputs");
                    for (var output : outputs) {
                        if (output.containsKey("valueAlternatives")) {
                            var valueAlternatives = (List<Object>) output.get("valueAlternatives");
                            for (int i = 0; i < valueAlternatives.size(); i++) {
                                Object valueAlternative = valueAlternatives.get(i);
                                if (valueAlternative instanceof String) {
                                    var textOutput = new TextOutputItem(valueAlternative.toString());
                                    valueAlternatives.set(i, textOutput);
                                    convertedOutput = true;
                                } else if (valueAlternative instanceof Map outputValue) {
                                    if (isNullOrEmpty(outputValue.get("type"))) {
                                        if (!isNullOrEmpty(outputValue.get("text"))) {
                                            outputValue.put("type", "text");
                                        } else if (!isNullOrEmpty(((Map) valueAlternative).get("uri"))) {
                                            outputValue.put("type", "image");
                                        } else if (!isNullOrEmpty(((Map) valueAlternative).get("expressions"))) {
                                            outputValue.put("type", "quickReply");
                                        } else {
                                            outputValue.put("type", "other");
                                        }

                                        convertedOutput = true;
                                    }
                                }
                            }

                            output.put("type", null);
                        }
                    }
                }
            }
        }

        return convertedOutput ? outputDocument : null;
    }

    private void saveToPersistence(Document document, boolean isHistory) {
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
                            set(PROPERTY_FIELD_OUTPUT_SET, document.get(PROPERTY_FIELD_OUTPUT_SET)))).blockingFirst();
        } else {
            id = document.get(ID_FIELD).toString();
            version = Integer.parseInt(document.get(VERSION_FIELD).toString());
            Bson query = Filters.eq(ID_FIELD, new ObjectId(id));
            Observable.fromPublisher(
                    outputCollection.updateOne(query,
                            set(PROPERTY_FIELD_OUTPUT_SET, document.get(PROPERTY_FIELD_OUTPUT_SET)))).blockingFirst();
        }

        var message =
                format("Successfully migrated output document with id: %s, version: %d to new format.",
                        id, version);
        migrationLogStore.createMigrationLog(new MigrationLog(message));
        LOGGER.info(message);
    }
}
