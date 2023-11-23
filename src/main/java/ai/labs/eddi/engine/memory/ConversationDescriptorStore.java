package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.mongo.DescriptorStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import com.mongodb.client.model.Filters;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.reactivex.rxjava3.core.Observable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.util.List;

import static ai.labs.eddi.datastore.mongo.DescriptorStore.COLLECTION_DESCRIPTORS;
import static ai.labs.eddi.datastore.mongo.DescriptorStore.FIELD_LAST_MODIFIED;

/**
 * @author ginccc
 */
@ApplicationScoped
public class ConversationDescriptorStore implements IConversationDescriptorStore {
    private final MongoCollection<Document> descriptorCollection;
    private final DescriptorStore<ConversationDescriptor> descriptorStore;

    @Inject
    public ConversationDescriptorStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        descriptorStore = new DescriptorStore<>(database, documentBuilder, ConversationDescriptor.class);

        descriptorCollection = database.getCollection(COLLECTION_DESCRIPTORS);
    }

    @Override
    public void updateTimeStamp(String conversationId) {
        String resource = resourceUri + conversationId;
        Observable.fromPublisher(descriptorCollection.findOneAndUpdate(
                Filters.eq("resource", resource),
                new Document("$set", new Document(FIELD_LAST_MODIFIED, System.currentTimeMillis())))).blockingFirst();
    }

    @Override
    public List<ConversationDescriptor> readDescriptors(String type, String filter, Integer index,
                                                        Integer limit, boolean includeDeleted)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        return descriptorStore.readDescriptors(type, filter, index, limit, includeDeleted);
    }

    @Override
    public ConversationDescriptor readDescriptor(String resourceId, Integer version)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        return descriptorStore.readDescriptor(resourceId, version);
    }

    @Override
    public ConversationDescriptor readDescriptorWithHistory(String resourceId, Integer version)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        return descriptorStore.readDescriptorWithHistory(resourceId, version);
    }

    @Override
    public Integer updateDescriptor(String resourceId, Integer version, ConversationDescriptor descriptor)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {

        return descriptorStore.updateDescriptor(resourceId, version, descriptor);
    }

    @Override
    public void setDescriptor(String resourceId, Integer version, ConversationDescriptor descriptor)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        descriptorStore.setDescriptor(resourceId, version, descriptor);
    }

    @Override
    public void createDescriptor(String resourceId, Integer version, ConversationDescriptor descriptor)
            throws IResourceStore.ResourceStoreException {

        descriptorStore.createDescriptor(resourceId, version, descriptor);
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return descriptorStore.getCurrentResourceId(id);
    }

    @Override
    public void deleteDescriptor(String resourceId, Integer version)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceModifiedException {

        descriptorStore.deleteDescriptor(resourceId, version);
    }

    @Override
    public void deleteAllDescriptor(String resourceId) {
        descriptorStore.deleteAllDescriptor(resourceId);
    }
}
