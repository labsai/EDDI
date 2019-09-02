package ai.labs.channels.differ.storage.conversations;

import ai.labs.channels.differ.model.DifferConversationInfo;
import ai.labs.channels.differ.storage.IDifferConversationStore;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;

import javax.inject.Inject;
import java.util.LinkedList;
import java.util.List;

import static ai.labs.persistence.IResourceStore.ResourceAlreadyExistsException;
import static ai.labs.utilities.RuntimeUtilities.checkNotEmpty;
import static ai.labs.utilities.RuntimeUtilities.checkNotNull;

/**
 * @author ginccc
 */
@Slf4j
public class DifferConversationStore implements IDifferConversationStore {
    private static final String COLLECTION_DIFFER_CONVERSATIONS = "differconversations";
    private static final String CONVERSATION_ID_FIELD = "conversationId";
    private final MongoCollection<DifferConversationInfo> collection;
    private DifferConversationResourceStore userConversationStore;

    @Inject
    public DifferConversationStore(MongoDatabase database) {
        checkNotNull(database, "database");
        this.collection = database.getCollection(COLLECTION_DIFFER_CONVERSATIONS, DifferConversationInfo.class);
        this.userConversationStore = new DifferConversationResourceStore();
        collection.createIndex(Indexes.ascending(CONVERSATION_ID_FIELD), new IndexOptions().unique(true));
    }

    @Override
    public List<String> getAllDifferConversationIds() {
        return userConversationStore.getAllDifferConversationIds();
    }

    @Override
    public DifferConversationInfo readDifferConversation(String conversationId) {
        checkNotNull(conversationId, CONVERSATION_ID_FIELD);

        return userConversationStore.readDifferConversation(conversationId);
    }

    @Override
    public void createDifferConversation(DifferConversationInfo differConversationInfo) throws ResourceAlreadyExistsException {
        checkNotNull(differConversationInfo, "differConversationInfo");
        checkNotNull(differConversationInfo.getConversationId(), "differConversationInfo.conversationId");
        checkNotEmpty(differConversationInfo.getAllParticipantIds(), "differConversationInfo.allParticipantIds");
        checkNotEmpty(differConversationInfo.getBotParticipantIds(), "differConversationInfo.botParticipantIds");

        userConversationStore.createDifferConversation(differConversationInfo);
    }

    @Override
    public void deleteDifferConversation(String conversationId) {
        checkNotNull(conversationId, CONVERSATION_ID_FIELD);

        userConversationStore.deleteDifferConversation(conversationId);
    }

    private class DifferConversationResourceStore {
        DifferConversationInfo readDifferConversation(String conversationId) {
            return collection.find(new Document(CONVERSATION_ID_FIELD, conversationId)).first();
        }

        void createDifferConversation(DifferConversationInfo differConversationInfo) throws ResourceAlreadyExistsException {
            Document filter = new Document();
            filter.put(CONVERSATION_ID_FIELD, differConversationInfo.getConversationId());

            if (collection.find(filter).first() != null) {
                String message = "DifferConversationInfo with conversationId=%s does already exist";
                message = String.format(message, differConversationInfo.getConversationId());
                throw new ResourceAlreadyExistsException(message);
            }

            collection.insertOne(differConversationInfo);
        }

        void deleteDifferConversation(String conversationId) {
            collection.deleteOne(new Document(CONVERSATION_ID_FIELD, conversationId));
        }

        List<String> getAllDifferConversationIds() {
            List<String> ret = new LinkedList<>();

            var includeConversationIdField = Projections.include(CONVERSATION_ID_FIELD);
            var documents = collection.find().projection(includeConversationIdField);
            for (var conversationInfo : documents) {
                ret.add(conversationInfo.getConversationId());
            }

            return ret;
        }
    }
}

