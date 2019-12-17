package ai.labs.persistence;

import ai.labs.group.IGroupStore;
import ai.labs.permission.IPermissionStore;
import ai.labs.persistence.mongo.ModifiableHistorizedResourceStore;
import ai.labs.persistence.mongo.MongoResourceStorage;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.user.IUserStore;
import ai.labs.utilities.RuntimeUtilities;
import ai.labs.utilities.StringUtilities;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;

import java.util.LinkedList;
import java.util.List;

import static ai.labs.persistence.IResourceStore.*;

/**
 * @author ginccc
 */
public class DescriptorStore<T> implements IDescriptorStore<T> {
    protected static final String COLLECTION_DESCRIPTORS = "descriptors";
    private static final String FIELD_RESOURCE = "resource";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_DESCRIPTION = "description";
    protected static final String FIELD_LAST_MODIFIED = "lastModifiedOn";
    private static final String FIELD_DELETED = "deleted";

    private static final String collectionName = "descriptors";
    private ModifiableHistorizedResourceStore<T> descriptorResourceStore;

    private IResourceFilter<T> resourceFilter;

    public DescriptorStore(MongoDatabase database, IPermissionStore permissionStore, IUserStore userStore,
                           IGroupStore groupStore, IDocumentBuilder documentBuilder, Class<T> documentType) {
        RuntimeUtilities.checkNotNull(database, "database");
        RuntimeUtilities.checkNotNull(permissionStore, "permissionStore");

        MongoCollection<Document> descriptorCollection = database.getCollection(COLLECTION_DESCRIPTORS);
        MongoResourceStorage<T> resourceStorage =
                new MongoResourceStorage<>(database, collectionName, documentBuilder, documentType);
        this.descriptorResourceStore = new ModifiableHistorizedResourceStore<>(resourceStorage);
        this.resourceFilter = new ResourceFilter<>(descriptorCollection, descriptorResourceStore,
                permissionStore, userStore, groupStore, documentBuilder, documentType);

        descriptorCollection.createIndex(Indexes.ascending(FIELD_RESOURCE), new IndexOptions().unique(true));
    }

    @Override
    public List<T> readDescriptors(String type, String filter, Integer index, Integer limit, boolean includeDeleted)
            throws ResourceStoreException, ResourceNotFoundException {
        List<IResourceFilter.QueryFilter> queryFiltersRequired = new LinkedList<>();
        String filterURI = "eddi://" + type + ".*";
        queryFiltersRequired.add(new IResourceFilter.QueryFilter(FIELD_RESOURCE, filterURI));
        queryFiltersRequired.add(new IResourceFilter.QueryFilter(FIELD_DELETED, includeDeleted));
        IResourceFilter.QueryFilters required = new IResourceFilter.QueryFilters(queryFiltersRequired);

        List<IResourceFilter.QueryFilter> queryFiltersOptional = new LinkedList<>();
        if (filter != null) {
            filter = StringUtilities.convertToSearchString(filter);
            queryFiltersOptional.add(new IResourceFilter.QueryFilter(FIELD_NAME, filter));
            queryFiltersOptional.add(new IResourceFilter.QueryFilter(FIELD_DESCRIPTION, filter));
        }
        IResourceFilter.QueryFilters optional = new IResourceFilter.QueryFilters(IResourceFilter.QueryFilters.ConnectingType.OR, queryFiltersOptional);

        return resourceFilter.readResources(new IResourceFilter.QueryFilters[]{required, optional}, index, limit, FIELD_LAST_MODIFIED);
    }

    @Override
    public T readDescriptor(String resourceId, Integer version) throws ResourceStoreException, ResourceNotFoundException {
        return descriptorResourceStore.read(resourceId, version);
    }

    @Override
    public T readDescriptorWithHistory(String resourceId, Integer version) throws ResourceStoreException, ResourceNotFoundException {
        return descriptorResourceStore.readIncludingDeleted(resourceId, version);
    }


    @Override
    public Integer updateDescriptor(String resourceId, Integer version, T documentDescriptor) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        return descriptorResourceStore.update(resourceId, version, documentDescriptor);
    }

    @Override
    public void setDescriptor(String resourceId, Integer version, T documentDescriptor) throws ResourceStoreException, ResourceNotFoundException {
        descriptorResourceStore.set(resourceId, version, documentDescriptor);
    }

    @Override
    public void createDescriptor(String resourceId, Integer version, T documentDescriptor) throws ResourceStoreException {
        descriptorResourceStore.create(resourceId, version, documentDescriptor);
    }

    @Override
    public void deleteDescriptor(String resourceId, Integer version) throws ResourceNotFoundException, ResourceModifiedException {
        descriptorResourceStore.delete(resourceId, version);
    }

    @Override
    public void deleteAllDescriptor(String resourceId) {
        descriptorResourceStore.deleteAllPermanently(resourceId);
    }


    public IResourceId getCurrentResourceId(String id) throws ResourceNotFoundException {
        return descriptorResourceStore.getCurrentResourceId(id);
    }
}
