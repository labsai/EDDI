package ai.labs.persistence;

import ai.labs.group.IGroupStore;
import ai.labs.permission.IPermissionStore;
import ai.labs.persistence.mongo.ModifiableHistorizedResourceStore;
import ai.labs.persistence.mongo.MongoResourceStorage;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.user.IUserStore;
import ai.labs.utilities.RuntimeUtilities;
import ai.labs.utilities.StringUtilities;
import com.mongodb.DB;
import com.mongodb.DBCollection;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class DescriptorStore<T> implements IDescriptorStore<T> {
    private static final String COLLECTION_DESCRIPTORS = "descriptors";
    private static final String FIELD_RESOURCE = "resource";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_LAST_MODIFIED = "lastModifiedOn";
    private static final String FIELD_DELETED = "deleted";

    private static final String collectionName = "descriptors";
    private ModifiableHistorizedResourceStore<T> descriptorResourceStore;

    private IResourceFilter<T> resourceFilter;

    public DescriptorStore(DB database, IPermissionStore permissionStore, IUserStore userStore,
                           IGroupStore groupStore, IDocumentBuilder documentBuilder, Class<T> documentType) {
        RuntimeUtilities.checkNotNull(database, "database");
        RuntimeUtilities.checkNotNull(permissionStore, "permissionStore");

        DBCollection descriptorCollection = database.getCollection(COLLECTION_DESCRIPTORS);
        MongoResourceStorage<T> resourceStorage =
                new MongoResourceStorage<>(database, collectionName, documentBuilder, documentType);
        this.descriptorResourceStore = new ModifiableHistorizedResourceStore<>(resourceStorage);
        this.resourceFilter = new ResourceFilter<>(descriptorCollection, descriptorResourceStore,
                permissionStore, userStore, groupStore, documentBuilder, documentType);
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
            queryFiltersOptional.add(new IResourceFilter.QueryFilter(FIELD_DESCRIPTION, filter));
        }
        IResourceFilter.QueryFilters optional = new IResourceFilter.QueryFilters(IResourceFilter.QueryFilters.ConnectingType.OR, queryFiltersOptional);

        return resourceFilter.readResources(new IResourceFilter.QueryFilters[]{required, optional}, index, limit, FIELD_LAST_MODIFIED);
    }

    @Override
    public T readDescriptor(String resourceId, Integer version) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        return descriptorResourceStore.read(resourceId, version);
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
    public void createDescriptor(String resourceId, Integer version, T documentDescriptor) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        descriptorResourceStore.create(resourceId, version, documentDescriptor);
    }

    @Override
    public void deleteDescriptor(String resourceId, Integer version) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException, IResourceStore.ResourceModifiedException {
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
