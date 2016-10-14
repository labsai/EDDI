package io.sls.persistence.impl;

import com.mongodb.DB;
import com.mongodb.DBCollection;
import io.sls.group.IGroupStore;
import io.sls.permission.IPermissionStore;
import io.sls.persistence.IDescriptorStore;
import io.sls.persistence.IResourceFilter;
import io.sls.persistence.IResourceStore;
import io.sls.persistence.impl.mongo.ModifiableHistorizedResourceStore;
import io.sls.persistence.impl.mongo.MongoResourceStorage;
import io.sls.serialization.IDocumentBuilder;
import io.sls.user.IUserStore;
import io.sls.utilities.RuntimeUtilities;
import io.sls.utilities.StringUtilities;

import java.util.LinkedList;
import java.util.List;

/**
 * Copyright by Spoken Language System. All rights reserved.
 * User: jarisch
 * Date: 19.11.12
 * Time: 17:34
 */
public class DescriptorStore<T> implements IDescriptorStore<T> {
    private static final String COLLECTION_DESCRIPTORS = "descriptors";
    public static final String FIELD_RESOURCE = "resource";
    public static final String FIELD_NAME = "name";
    public static final String FIELD_DESCRIPTION = "description";
    public static final String FIELD_AUTHOR = "author";
    public static final String FIELD_LAST_MODIFIED = "lastModifiedOn";
    public static final String FIELD_DELETED = "deleted";

    private static final String collectionName = "descriptors";
    private ModifiableHistorizedResourceStore<T> descriptorResourceStore;

    private DBCollection descriptorCollection;
    private IResourceFilter<T> resourceFilter;

    public DescriptorStore(DB database, IPermissionStore permissionStore, IUserStore userStore, IGroupStore groupStore, IDocumentBuilder<T> documentBuilder) {
        RuntimeUtilities.checkNotNull(database, "database");
        RuntimeUtilities.checkNotNull(permissionStore, "permissionStore");

        descriptorCollection = database.getCollection(COLLECTION_DESCRIPTORS);
        MongoResourceStorage<T> resourceStorage = new MongoResourceStorage<T>(database, collectionName, documentBuilder);
        this.descriptorResourceStore = new ModifiableHistorizedResourceStore<T>(resourceStorage);
        this.resourceFilter = new ResourceFilter<T>(descriptorCollection, descriptorResourceStore, permissionStore, userStore, groupStore, documentBuilder);
    }

    @Override
    public List<T> readDescriptors(String type, String filter, Integer index, Integer limit, boolean includeDeleted) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        List<IResourceFilter.QueryFilter> queryFiltersRequired = new LinkedList<IResourceFilter.QueryFilter>();
        String filterURI = "resource://" + type + ".*";
        queryFiltersRequired.add(new IResourceFilter.QueryFilter(FIELD_RESOURCE, filterURI));
        queryFiltersRequired.add(new IResourceFilter.QueryFilter(FIELD_DELETED, includeDeleted));
        IResourceFilter.QueryFilters required = new IResourceFilter.QueryFilters(queryFiltersRequired);

        List<IResourceFilter.QueryFilter> queryFiltersOptional = new LinkedList<IResourceFilter.QueryFilter>();
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
