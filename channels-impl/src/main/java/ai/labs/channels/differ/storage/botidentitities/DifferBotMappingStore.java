package ai.labs.channels.differ.storage.botidentitities;

import ai.labs.channels.differ.model.DifferBotMapping;
import ai.labs.channels.differ.storage.IDifferBotMappingStore;
import ai.labs.persistence.IResourceStore;
import ai.labs.serialization.IDocumentBuilder;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.bson.types.ObjectId;

import javax.inject.Inject;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static ai.labs.utilities.RuntimeUtilities.checkNotEmpty;
import static ai.labs.utilities.RuntimeUtilities.checkNotNull;

public class DifferBotMappingStore implements IDifferBotMappingStore {
    private static final String COLLECTION_DIFFER_BOT_MAPPINGS = "differbotmappings";
    private static final String BOT_USER_ID_FIELD = "botUserId";
    private static final String BOT_INTENT_FIELD = "botIntent";
    private final MongoCollection<Document> collection;
    private final IDocumentBuilder documentBuilder;
    private DifferBotMappingResourceStore differBotMappingResourceStore;

    @Inject
    public DifferBotMappingStore(MongoDatabase database,
                                 IDocumentBuilder documentBuilder) {
        checkNotNull(database, "database");
        this.collection = database.getCollection(COLLECTION_DIFFER_BOT_MAPPINGS);
        this.documentBuilder = documentBuilder;
        this.differBotMappingResourceStore = new DifferBotMappingResourceStore();
        collection.createIndex(Indexes.ascending(BOT_USER_ID_FIELD));
        collection.createIndex(Indexes.ascending(BOT_INTENT_FIELD));
    }

    @Override
    public List<DifferBotMapping> readAllDifferBotMappings() throws IResourceStore.ResourceStoreException {
        return differBotMappingResourceStore.readAllDifferBotMappings();
    }

    @Override
    public void createDifferBotMapping(DifferBotMapping differBotMapping)
            throws IResourceStore.ResourceAlreadyExistsException, IResourceStore.ResourceStoreException {

        checkNotNull(differBotMapping, "differBotMapping");
        checkNotNull(differBotMapping.getBotIntent(), "differBotMapping.botIntent");
        checkNotEmpty(differBotMapping.getDifferBotUserIds(), "differBotMapping.differBotUserIds");

        try {
            for (String differBotUserId : differBotMapping.getDifferBotUserIds()) {
                Document mapping = differBotMappingResourceStore.findMappingByBotUserIds(Collections.singletonList(differBotUserId));
                if (mapping != null) {
                    String message = "differBotUserId %s has already be defined in one of the botMappings (%s).";
                    message = String.format(message, differBotUserId, documentBuilder.toString(mapping));
                    throw new IResourceStore.ResourceAlreadyExistsException(message);
                }
            }

            differBotMappingResourceStore.createDifferBotMapping(differBotMapping);
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void addBotUserIdToDifferBotMapping(String botIntent, String botUserId) throws IResourceStore.ResourceStoreException {
        checkNotNull(botIntent, BOT_INTENT_FIELD);
        checkNotNull(botUserId, BOT_USER_ID_FIELD);

        differBotMappingResourceStore.addBotUserIdToDifferBotMapping(botIntent, botUserId);
    }

    @Override
    public void deleteBotUserIdFromDifferBotMappings(String botUserId) throws IResourceStore.ResourceStoreException {
        checkNotNull(botUserId, BOT_USER_ID_FIELD);

        differBotMappingResourceStore.deleteBotUserIdFromDifferBotMappings(botUserId);
    }

    @Override
    public void deleteDifferBotMapping(String botIntent) {
        checkNotNull(botIntent, BOT_INTENT_FIELD);

        differBotMappingResourceStore.deleteDifferBotMapping(botIntent);
    }

    @Override
    public DifferBotMapping readDifferBotMapping(String botIntent)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        checkNotNull(botIntent, BOT_INTENT_FIELD);

        return differBotMappingResourceStore.readDifferBotMapping(botIntent);
    }

    private class DifferBotMappingResourceStore {
        static final String ID_FIELD = "_id";
        static final String DIFFER_BOT_USER_IDS_FIELD = "differBotUserIds";

        void createDifferBotMapping(DifferBotMapping differBotMapping)
                throws IResourceStore.ResourceStoreException, IResourceStore.ResourceAlreadyExistsException {

            try {
                Document alreadyExistingMapping = findMappingByBotUserIds(differBotMapping.getDifferBotUserIds());
                if (alreadyExistingMapping != null) {
                    String message = "Some botUserId is already defined in the following DifferBotMapping: {}";
                    message = String.format(message, alreadyExistingMapping);
                    throw new IResourceStore.ResourceAlreadyExistsException(message);
                }

                collection.insertOne(documentBuilder.toDocument(differBotMapping));
            } catch (IOException e) {
                throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }

        DifferBotMapping readDifferBotMapping(String botIntent)
                throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

            try {
                Document document = collection.find(new Document(BOT_INTENT_FIELD, botIntent)).first();
                if (document == null) {
                    String message = "No DifferBotMapping found with botIntent %s.";
                    message = String.format(message, botIntent);
                    throw new IResourceStore.ResourceNotFoundException(message);
                }

                return documentBuilder.build(document, DifferBotMapping.class);
            } catch (IOException e) {
                throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }

        List<DifferBotMapping> readAllDifferBotMappings() throws IResourceStore.ResourceStoreException {

            try {
                List<DifferBotMapping> ret = new LinkedList<>();

                var documents = collection.find();
                for (Document document : documents) {
                    ret.add(documentBuilder.build(document, DifferBotMapping.class));
                }

                return ret;
            } catch (IOException e) {
                throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }

        void addBotUserIdToDifferBotMapping(String intent, String botUserId)
                throws IResourceStore.ResourceStoreException {

            try {
                var botMappingDocument = findMappingByIntent(intent);
                if (botMappingDocument == null) {
                    String message = String.format("No DifferBotMapping found for intent %s", intent);
                    throw new IResourceStore.ResourceStoreException(message);
                }

                var botMappingContainingBotUserId = findMappingByBotUserIds(Collections.singletonList(botUserId));
                if (botMappingContainingBotUserId != null) {
                    String message = "BotUserId %s has already been defined in the following BotMapping (%s)";
                    message = String.format(message, botUserId, documentBuilder.toString(botMappingContainingBotUserId));
                    throw new IResourceStore.ResourceStoreException(message);
                }

                String id = botMappingDocument.get("_id").toString();
                DifferBotMapping differBotMapping = documentBuilder.build(botMappingDocument, DifferBotMapping.class);
                differBotMapping.getDifferBotUserIds().add(botUserId);
                collection.updateOne(new Document(ID_FIELD, new ObjectId(id)), documentBuilder.toDocument(differBotMapping));

            } catch (IOException e) {
                throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }

        private Document findMappingByIntent(String botIntent) {
            return collection.find(new Document("botIntent", botIntent)).first();
        }

        void deleteBotUserIdFromDifferBotMappings(String botUserId)
                throws IResourceStore.ResourceStoreException {

            Document botMappingDocument = findMappingByBotUserIds(List.of(botUserId));
            if (botMappingDocument != null) {
                try {
                    String id = botMappingDocument.get("_id").toString();
                    DifferBotMapping differBotMapping = documentBuilder.build(botMappingDocument, DifferBotMapping.class);
                    differBotMapping.getDifferBotUserIds().remove(botUserId);
                    collection.updateOne(
                            new Document(ID_FIELD, new ObjectId(id)),
                            documentBuilder.toDocument(differBotMapping));
                } catch (IOException e) {
                    throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
                }
            } else {
                String message = String.format("No DifferBotMapping found containing BotUserId %s", botUserId);
                throw new IResourceStore.ResourceStoreException(message);
            }
        }

        Document findMappingByBotUserIds(List<String> differBotUserIds) {
            return collection.find(
                    new Document(DIFFER_BOT_USER_IDS_FIELD,
                            new Document("$in", differBotUserIds))).first();
        }

        private void deleteDifferBotMapping(String botIntent) {
            collection.deleteOne(new Document("botIntent", botIntent));
        }
    }
}
