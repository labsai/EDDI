package ai.labs.memory;

import ai.labs.group.IGroupStore;
import ai.labs.memory.descriptor.IConversationDescriptorStore;
import ai.labs.memory.descriptor.model.ConversationDescriptor;
import ai.labs.permission.IPermissionStore;
import ai.labs.persistence.DescriptorStore;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.user.IUserStore;
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
    public ConversationDescriptorStore(MongoDatabase database, IPermissionStore permissionStore, IUserStore userStore, IGroupStore groupStore, IDocumentBuilder documentBuilder) {
        super(database, permissionStore, userStore, groupStore, documentBuilder, ConversationDescriptor.class);

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
