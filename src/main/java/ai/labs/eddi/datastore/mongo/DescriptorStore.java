package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.utils.RuntimeUtilities;
import ai.labs.eddi.utils.StringUtilities;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.reactivex.rxjava3.core.Observable;
import org.bson.Document;

import java.util.LinkedList;
import java.util.List;


/**
 * @author ginccc
 */
public class DescriptorStore<T> implements IDescriptorStore<T> {
    public static final String COLLECTION_DESCRIPTORS = "descriptors";
    private static final String FIELD_RESOURCE = "resource";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_BOT_NAME = "botName";
    private static final String FIELD_DESCRIPTION = "description";
    public static final String FIELD_LAST_MODIFIED = "lastModifiedOn";
    private static final String FIELD_DELETED = "deleted";

    private static final String collectionName = "descriptors";
    private final ModifiableHistorizedResourceStore<T> descriptorResourceStore;

    private final IResourceFilter<T> resourceFilter;

    public DescriptorStore(MongoDatabase database, IDocumentBuilder documentBuilder, Class<T> documentType) {
        RuntimeUtilities.checkNotNull(database, "database");

        MongoCollection<Document> descriptorCollection = database.getCollection(COLLECTION_DESCRIPTORS);
        MongoResourceStorage<T> resourceStorage =
                new MongoResourceStorage<>(database, collectionName, documentBuilder, documentType);
        this.descriptorResourceStore = new ModifiableHistorizedResourceStore<>(resourceStorage);
        this.resourceFilter = new ResourceFilter<>(descriptorCollection, descriptorResourceStore);

        Observable.fromPublisher(descriptorCollection.createIndex(Indexes.ascending(FIELD_RESOURCE), new IndexOptions().unique(true))).blockingFirst();
        Observable.fromPublisher(descriptorCollection.createIndex(Indexes.ascending(FIELD_NAME), new IndexOptions().unique(false))).blockingFirst();
        Observable.fromPublisher(descriptorCollection.createIndex(Indexes.ascending(FIELD_BOT_NAME), new IndexOptions().unique(false))).blockingFirst();
        Observable.fromPublisher(descriptorCollection.createIndex(Indexes.ascending(FIELD_DESCRIPTION), new IndexOptions().unique(false))).blockingFirst();
        Observable.fromPublisher(descriptorCollection.createIndex(Indexes.ascending(FIELD_LAST_MODIFIED), new IndexOptions().unique(false))).blockingFirst();
        Observable.fromPublisher(descriptorCollection.createIndex(Indexes.ascending(FIELD_DELETED), new IndexOptions().unique(false))).blockingFirst();
    }

    @Override
    public List<T> readDescriptors(String type, String filter, Integer index, Integer limit, boolean includeDeleted)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        List<IResourceFilter.QueryFilter> queryFiltersRequired = new LinkedList<>();
        String filterURI = "eddi://" + type + ".*";
        queryFiltersRequired.add(new IResourceFilter.QueryFilter(FIELD_RESOURCE, filterURI));
        queryFiltersRequired.add(new IResourceFilter.QueryFilter(FIELD_DELETED, includeDeleted));
        IResourceFilter.QueryFilters required = new IResourceFilter.QueryFilters(queryFiltersRequired);

        List<IResourceFilter.QueryFilter> queryFiltersOptional = new LinkedList<>();
        if (filter != null) {
            filter = StringUtilities.convertToSearchString(filter);
            queryFiltersOptional.add(new IResourceFilter.QueryFilter(FIELD_NAME, filter));
            queryFiltersOptional.add(new IResourceFilter.QueryFilter(FIELD_BOT_NAME, filter));
            queryFiltersOptional.add(new IResourceFilter.QueryFilter(FIELD_DESCRIPTION, filter));
            queryFiltersOptional.add(new IResourceFilter.QueryFilter(FIELD_RESOURCE, filter));
        }
        if (!queryFiltersOptional.isEmpty()) {
            IResourceFilter.QueryFilters optional = new IResourceFilter.QueryFilters(IResourceFilter.QueryFilters.ConnectingType.OR, queryFiltersOptional);
            return resourceFilter.readResources(new IResourceFilter.QueryFilters[]{required, optional}, index, limit, FIELD_LAST_MODIFIED);
        } else {
            return resourceFilter.readResources(new IResourceFilter.QueryFilters[]{required}, index, limit, FIELD_LAST_MODIFIED);
        }

    }

    @Override
    public T readDescriptor(String resourceId, Integer version)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        return descriptorResourceStore.read(resourceId, version);
    }

    @Override
    public T readDescriptorWithHistory(String resourceId, Integer version) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        return descriptorResourceStore.readIncludingDeleted(resourceId, version);
    }


    @Override
    public Integer updateDescriptor(String resourceId, Integer version, T documentDescriptor) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        return descriptorResourceStore.update(resourceId, version, documentDescriptor);
    }

    @Override
    public void setDescriptor(String resourceId, Integer version, T documentDescriptor) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        descriptorResourceStore.set(resourceId, version, documentDescriptor);
    }

    @Override
    public void createDescriptor(String resourceId, Integer version, T documentDescriptor) throws IResourceStore.ResourceStoreException {
        descriptorResourceStore.createNew(resourceId, version, documentDescriptor);
    }

    @Override
    public void deleteDescriptor(String resourceId, Integer version) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceModifiedException {
        descriptorResourceStore.delete(resourceId, version);
    }

    @Override
    public void deleteAllDescriptor(String resourceId) {
        descriptorResourceStore.deleteAllPermanently(resourceId);
    }


    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return descriptorResourceStore.getCurrentResourceId(id);
    }
}
