package ai.labs.eddi.datastore;

import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.utils.StringUtilities;

import java.util.LinkedList;
import java.util.List;


/**
 * Database-agnostic descriptor store. Uses {@link IResourceStorageFactory}
 * to obtain the underlying storage, and {@link IResourceStorage#findResources}
 * for filter/pagination queries.
 *
 * @author ginccc
 */
public class DescriptorStore<T> implements IDescriptorStore<T> {
    private static final String FIELD_RESOURCE = "resource";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_BOT_NAME = "agentName";
    private static final String FIELD_DESCRIPTION = "description";
    public static final String FIELD_LAST_MODIFIED = "lastModifiedOn";
    private static final String FIELD_DELETED = "deleted";
    private static final String FIELD_USER_ID = "userId";

    private final ModifiableHistorizedResourceStore<T> descriptorResourceStore;
    private final IResourceStorage<T> resourceStorage;

    public DescriptorStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder,
                           Class<T> documentType) {
        this.resourceStorage = storageFactory.create("descriptors", documentBuilder, documentType);
        this.descriptorResourceStore = new ModifiableHistorizedResourceStore<>(resourceStorage);
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
            queryFiltersOptional.add(new IResourceFilter.QueryFilter(FIELD_USER_ID, filter));
            queryFiltersOptional.add(new IResourceFilter.QueryFilter(FIELD_NAME, filter));
            queryFiltersOptional.add(new IResourceFilter.QueryFilter(FIELD_BOT_NAME, filter));
            queryFiltersOptional.add(new IResourceFilter.QueryFilter(FIELD_DESCRIPTION, filter));
            queryFiltersOptional.add(new IResourceFilter.QueryFilter(FIELD_RESOURCE, filter));
        }

        int effectiveLimit = (limit == null || limit < 1) ? 20 : limit;
        int skip = (index != null && index > 0) ? index * effectiveLimit : 0;

        IResourceFilter.QueryFilters[] allFilters;
        if (!queryFiltersOptional.isEmpty()) {
            IResourceFilter.QueryFilters optional = new IResourceFilter.QueryFilters(
                    IResourceFilter.QueryFilters.ConnectingType.OR, queryFiltersOptional);
            allFilters = new IResourceFilter.QueryFilters[]{required, optional};
        } else {
            allFilters = new IResourceFilter.QueryFilters[]{required};
        }

        // Use the storage-level findResources for database-agnostic querying
        List<IResourceStore.IResourceId> matchingIds =
                resourceStorage.findResources(allFilters, FIELD_LAST_MODIFIED, skip, effectiveLimit);

        // Read each matching resource
        List<T> ret = new LinkedList<>();
        for (IResourceStore.IResourceId resourceId : matchingIds) {
            ret.add(descriptorResourceStore.read(resourceId.getId(), resourceId.getVersion()));
        }
        return ret;
    }

    @Override
    public T readDescriptor(String resourceId, Integer version)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        return descriptorResourceStore.read(resourceId, version);
    }

    @Override
    public T readDescriptorWithHistory(String resourceId, Integer version)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        return descriptorResourceStore.readIncludingDeleted(resourceId, version);
    }

    @Override
    public Integer updateDescriptor(String resourceId, Integer version, T documentDescriptor)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException,
            IResourceStore.ResourceNotFoundException {
        return descriptorResourceStore.update(resourceId, version, documentDescriptor);
    }

    @Override
    public void setDescriptor(String resourceId, Integer version, T documentDescriptor)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        descriptorResourceStore.set(resourceId, version, documentDescriptor);
    }

    @Override
    public void createDescriptor(String resourceId, Integer version, T documentDescriptor)
            throws IResourceStore.ResourceStoreException {
        descriptorResourceStore.createNew(resourceId, version, documentDescriptor);
    }

    @Override
    public void deleteDescriptor(String resourceId, Integer version)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceModifiedException {
        descriptorResourceStore.delete(resourceId, version);
    }

    @Override
    public void deleteAllDescriptor(String resourceId) {
        descriptorResourceStore.deleteAllPermanently(resourceId);
    }

    public IResourceStore.IResourceId getCurrentResourceId(String id)
            throws IResourceStore.ResourceNotFoundException {
        return descriptorResourceStore.getCurrentResourceId(id);
    }

    @Override
    public List<T> findByOriginId(String originId)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        List<IResourceFilter.QueryFilter> queryFilters = new LinkedList<>();
        queryFilters.add(new IResourceFilter.QueryFilter("originId", originId));
        queryFilters.add(new IResourceFilter.QueryFilter(FIELD_DELETED, false));
        IResourceFilter.QueryFilters required = new IResourceFilter.QueryFilters(queryFilters);

        List<IResourceStore.IResourceId> matchingIds =
                resourceStorage.findResources(new IResourceFilter.QueryFilters[]{required}, FIELD_LAST_MODIFIED, 0, 10);

        List<T> ret = new LinkedList<>();
        for (IResourceStore.IResourceId resourceId : matchingIds) {
            ret.add(descriptorResourceStore.read(resourceId.getId(), resourceId.getVersion()));
        }
        return ret;
    }
}
