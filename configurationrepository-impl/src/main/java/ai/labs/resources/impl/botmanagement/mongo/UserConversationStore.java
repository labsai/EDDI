package ai.labs.resources.impl.botmanagement.mongo;

import ai.labs.models.UserConversation;
import ai.labs.resources.rest.botmanagement.IUserConversationStore;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.utilities.RuntimeUtilities;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;

import javax.inject.Inject;
import java.io.IOException;

import static ai.labs.persistence.IResourceStore.*;

/**
 * @author ginccc
 */
@Slf4j
public class UserConversationStore implements IUserConversationStore {
    private static final String COLLECTION_USER_CONVERSATIONS = "userconversations";
    private static final String INTENT_FIELD = "intent";
    private static final String USER_ID_FIELD = "userId";
    private final MongoCollection<Document> collection;
    private final IDocumentBuilder documentBuilder;
    private final IJsonSerialization jsonSerialization;
    private UserConversationResourceStore userConversationStore;

    @Inject
    public UserConversationStore(MongoDatabase database,
                                 IJsonSerialization jsonSerialization,
                                 IDocumentBuilder documentBuilder) {
        this.jsonSerialization = jsonSerialization;
        RuntimeUtilities.checkNotNull(database, "database");
        this.collection = database.getCollection(COLLECTION_USER_CONVERSATIONS);
        this.documentBuilder = documentBuilder;
        this.userConversationStore = new UserConversationResourceStore();
        collection.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending(INTENT_FIELD),
                        Indexes.ascending(USER_ID_FIELD)),
                new IndexOptions().unique(true));
    }

    @Override
    public UserConversation readUserConversation(String intent, String userId)
            throws ResourceNotFoundException, ResourceStoreException {
        RuntimeUtilities.checkNotNull(intent, INTENT_FIELD);
        RuntimeUtilities.checkNotNull(userId, USER_ID_FIELD);

        return userConversationStore.readUserConversation(intent, userId);
    }

    @Override
    public void createUserConversation(UserConversation userConversation)
            throws ResourceAlreadyExistsException, ResourceStoreException {
        RuntimeUtilities.checkNotNull(userConversation, "userConversation");
        RuntimeUtilities.checkNotNull(userConversation.getIntent(), "userConversation.intent");
        RuntimeUtilities.checkNotNull(userConversation.getUserId(), "userConversation.userId");
        RuntimeUtilities.checkNotNull(userConversation.getEnvironment(), "userConversation.environment");
        RuntimeUtilities.checkNotNull(userConversation.getBotId(), "userConversation.botId");
        RuntimeUtilities.checkNotNull(userConversation.getConversationId(), "userConversation.conversationId");

        userConversationStore.createUserConversation(userConversation);
    }

    @Override
    public void deleteUserConversation(String intent, String userId) {
        RuntimeUtilities.checkNotNull(intent, INTENT_FIELD);
        RuntimeUtilities.checkNotNull(userId, USER_ID_FIELD);

        userConversationStore.deleteUserConversation(intent, userId);
    }

    private class UserConversationResourceStore {
        UserConversation readUserConversation(String intent, String userId)
                throws ResourceStoreException, ResourceNotFoundException {

            Document filter = new Document();
            filter.put(INTENT_FIELD, intent);
            filter.put(USER_ID_FIELD, userId);

            try {
                Document document = collection.find(filter).first();
                if (document != null) {
                    return documentBuilder.build(document, UserConversation.class);
                } else {
                    String message = "UserConversation with intent=%s and userId=%s does not exist";
                    message = String.format(message, intent, userId);
                    throw new ResourceNotFoundException(message);
                }
            } catch (IOException e) {
                throw new ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }

        void createUserConversation(UserConversation userConversation)
                throws ResourceStoreException, ResourceAlreadyExistsException {

            Document filter = new Document();
            filter.put(INTENT_FIELD, userConversation.getIntent());
            filter.put(USER_ID_FIELD, userConversation.getUserId());

            if (collection.find(filter).first() != null) {
                String message = "UserConversation with intent=%s does already exist";
                message = String.format(message, userConversation.getIntent());
                throw new ResourceAlreadyExistsException(message);
            }

            collection.insertOne(createDocument(userConversation));
        }

        void deleteUserConversation(String intent, String userId) {
            collection.deleteOne(new Document(INTENT_FIELD, intent).append(USER_ID_FIELD, userId));
        }

        private Document createDocument(UserConversation userConversation)
                throws ResourceStoreException {
            try {
                return jsonSerialization.deserialize(jsonSerialization.serialize(userConversation),
                        Document.class);
            } catch (IOException e) {
                throw new ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }
    }
}
