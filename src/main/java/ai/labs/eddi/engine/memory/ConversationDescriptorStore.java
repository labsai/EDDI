package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.mongo.DescriptorStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import javax.inject.Inject;

/**
 * @author ginccc
 */
public class ConversationDescriptorStore extends DescriptorStore<ConversationDescriptor> implements IConversationDescriptorStore {

    private final MongoCollection<Document> descriptorCollection;

    @Inject
    public ConversationDescriptorStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        super(database, documentBuilder, ConversationDescriptor.class);

        descriptorCollection = database.getCollection(COLLECTION_DESCRIPTORS);
    }

    @Override
    public void updateTimeStamp(String conversationId) {
        String resource = resourceUri + conversationId;
        descriptorCollection.findOneAndUpdate(
                new Document("resource", resource),
                new Document("$set", new Document(FIELD_LAST_MODIFIED, System.currentTimeMillis())));
    }
}
