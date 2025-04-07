package ai.labs.eddi.configs.botmanagement.mongo;

import ai.labs.eddi.configs.botmanagement.IUserConversationStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceAlreadyExistsException;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.model.UserConversation;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.reactivex.rxjava3.core.Observable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.io.IOException;
import java.util.NoSuchElementException;

/**
 * @author ginccc
 */

@ApplicationScoped
public class UserConversationStore implements IUserConversationStore {
    private static final String COLLECTION_USER_CONVERSATIONS = "userconversations";
    private static final String INTENT_FIELD = "intent";
    private static final String USER_ID_FIELD = "userId";
    private final MongoCollection<Document> collection;
    private final IDocumentBuilder documentBuilder;
    private final IJsonSerialization jsonSerialization;
    private final UserConversationResourceStore userConversationStore;


    @Inject
    public UserConversationStore(MongoDatabase database,
                                 IJsonSerialization jsonSerialization,
                                 IDocumentBuilder documentBuilder) {
        this.jsonSerialization = jsonSerialization;
        RuntimeUtilities.checkNotNull(database, "database");
        this.collection = database.getCollection(COLLECTION_USER_CONVERSATIONS);
        this.documentBuilder = documentBuilder;
        this.userConversationStore = new UserConversationResourceStore();
        Observable.fromPublisher(
                collection.createIndex(
                        Indexes.compoundIndex(
                                Indexes.ascending(INTENT_FIELD),
                                Indexes.ascending(USER_ID_FIELD)),
                        new IndexOptions().unique(true))
        ).blockingFirst();
    }

    @Override
    public UserConversation readUserConversation(String intent, String userId) throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(intent, INTENT_FIELD);
        RuntimeUtilities.checkNotNull(userId, USER_ID_FIELD);

        return userConversationStore.readUserConversation(intent, userId);
    }

    @Override
    public void createUserConversation(UserConversation userConversation)
            throws IResourceStore.ResourceStoreException, ResourceAlreadyExistsException {
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
                throws IResourceStore.ResourceStoreException {

            Document filter = new Document();
            filter.put(INTENT_FIELD, intent);
            filter.put(USER_ID_FIELD, userId);

            try {
                Document document = Observable.fromPublisher(collection.find(filter).first()).blockingFirst();
                return documentBuilder.build(document, UserConversation.class);
            } catch (NoSuchElementException e) {
                return null;
            } catch (IOException e) {
                throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }

        void createUserConversation(UserConversation userConversation)
                throws IResourceStore.ResourceStoreException, ResourceAlreadyExistsException {

            Document filter = new Document();
            filter.put(INTENT_FIELD, userConversation.getIntent());
            filter.put(USER_ID_FIELD, userConversation.getUserId());


            try {
                Observable.fromPublisher(collection.find(filter).first()).blockingFirst();

                // a user conversation with the given intent was found, so we throw an error
                String message = "UserConversation with intent=%s does already exist";
                message = String.format(message, userConversation.getIntent());
                throw new ResourceAlreadyExistsException(message);
            } catch (NoSuchElementException e) {
                //no user conversation with the given intent has been found, so we create a new one
                Observable.fromPublisher(collection.insertOne(createDocument(userConversation))).blockingFirst();
            }

        }

        void deleteUserConversation(String intent, String userId) {
            Observable.fromPublisher(collection.deleteOne(new Document(INTENT_FIELD, intent).append(USER_ID_FIELD, userId))).blockingFirst();
        }

        private Document createDocument(UserConversation userConversation)
                throws IResourceStore.ResourceStoreException {
            try {
                return jsonSerialization.deserialize(jsonSerialization.serialize(userConversation),
                        Document.class);
            } catch (IOException e) {
                throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
            }
        }
    }
}
